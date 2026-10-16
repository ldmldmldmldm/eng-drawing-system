import { driver, type DriveStep, type Config } from 'driver.js'
import 'driver.js/dist/driver.css'
import './theme.css'
import {
  readTourProgress,
  shouldAutoStartList,
  shouldResumeWizard,
  writeTourProgress
} from './progress'

export type TourPhase = 'list' | 'wizard' | 'full'

let activeDriver: ReturnType<typeof driver> | null = null
let starting = false

function destroyActive() {
  if (activeDriver) {
    try {
      activeDriver.destroy()
    } catch {
      // ignore
    }
    activeDriver = null
  }
}

function el(selector: string): Element | undefined {
  return document.querySelector(selector) || undefined
}

function waitFor(selector: string, timeoutMs = 4000): Promise<Element | null> {
  const hit = document.querySelector(selector)
  if (hit) return Promise.resolve(hit)
  return new Promise((resolve) => {
    const t0 = Date.now()
    const timer = window.setInterval(() => {
      const n = document.querySelector(selector)
      if (n || Date.now() - t0 > timeoutMs) {
        window.clearInterval(timer)
        resolve(n)
      }
    }, 120)
  })
}

function baseConfig(): Config {
  return {
    animate: true,
    overlayOpacity: 0.45,
    stagePadding: 6,
    stageRadius: 8,
    allowClose: true,
    overlayClickBehavior: 'close',
    smoothScroll: true,
    showProgress: true,
    progressText: '{{current}} / {{total}}',
    nextBtnText: '下一步',
    prevBtnText: '上一步',
    doneBtnText: '完成',
    popoverClass: 'eng-tour-popover'
  }
}

function listSteps(): DriveStep[] {
  const steps: DriveStep[] = [
    {
      element: el('[data-tour="menu-tasks"]') || el('[data-tour="page-tasks"]'),
      popover: {
        title: '欢迎使用工程图纸生成',
        description:
          '本系统以「任务」驱动：新建任务 → 进入工作台 → 按①抽取～⑤生成完成工程图。下面用高亮带你过一遍关键入口。',
        side: 'right',
        align: 'start'
      }
    },
    {
      element: el('[data-tour="btn-create-task"]'),
      popover: {
        title: '① 新建任务',
        description: '先点这里创建任务并填写标题。创建后会出现在下方列表中。',
        side: 'bottom',
        align: 'end'
      }
    },
    {
      element: el('[data-tour="filter-tasks"]'),
      popover: {
        title: '筛选任务',
        description: '可用关键词（标题）和状态筛选；任务多了时先查再进。',
        side: 'bottom',
        align: 'start'
      }
    }
  ]
  const enter = el('[data-tour="btn-enter-task"]')
  if (enter) {
    steps.push({
      element: enter,
      popover: {
        title: '② 进入工作台',
        description:
          '点「编辑」打开该任务的五步向导。引导将在工作台继续讲解①抽取～⑤生成。请点「编辑」任意任务以继续。',
        side: 'left',
        align: 'start'
      }
    })
  } else {
    steps.push({
      popover: {
        title: '② 进入工作台',
        description:
          '列表暂无任务时，请先「新建任务」，再在操作列点「编辑」。进入工作台后会自动续接五步流程说明。'
      }
    })
  }
  return steps
}

function wizardSteps(): DriveStep[] {
  const stepOf = (sel: string, title: string, description: string, align: 'start' | 'center' | 'end' = 'start'): DriveStep => {
    const node = el(sel)
    const popover = { title, description, side: 'bottom' as const, align }
    return node ? { element: node, popover } : { popover }
  }
  const steps: DriveStep[] = [
    stepOf(
      '[data-tour="wizard-steps"]',
      '五步向导总览',
      '工作台按固定顺序推进：①抽取 → ②层次 → ③材料 → ④翻译 → ⑤生成。顶栏步骤可看进度；未满足门禁时后续步会禁用。',
      'center'
    ),
    stepOf(
      '[data-tour="wizard-step-1"]',
      '① 抽取并对图核对',
      '上传客户 DWG 先生成高清预览；优先用「截图抽取」框选 NOTES/材料区，不准再用「自动抽取」。请对照左图逐条确认后才能进层次。'
    ),
    stepOf(
      '[data-tour="wizard-step-2"]',
      '② 三维层次',
      '上传/解析 STP，在三维中标注板与旁注。旁注需填齐序号、材料、颜色后再保存层次图。'
    ),
    stepOf(
      '[data-tour="wizard-step-3"]',
      '③ 材料核对',
      '对照 3D 层次与 2D 抽取材料：一致项可直接采用，不一致请手工确认后回写层次。',
      'center'
    ),
    stepOf(
      '[data-tour="wizard-step-4"]',
      '④ 翻译审校',
      '审校 NOTE/材料中文译文（料号与标准号保持原文），逐条确认后再生成。',
      'center'
    ),
    stepOf(
      '[data-tour="wizard-step-5"]',
      '⑤ 生成工程图',
      '点「生成工程图」后服务端排队执行；可看已用时与过程日志。完成后可下载 DXF，也可回列表「预览下载」。',
      'end'
    )
  ]
  const help = el('[data-tour="btn-help-tour"]')
  steps.push({
    element: help,
    popover: {
      title: '随时可再看引导',
      description:
        '顶栏「帮助 → 新手引导」可重开本说明。管理员不会强制弹出；普通用户首次进入任务页会自动提示。',
      side: 'bottom',
      align: 'end'
    }
  })
  return steps
}

function runSteps(steps: DriveStep[], onFinish: (completed: boolean) => void) {
  destroyActive()
  let settled = false
  const settle = (completed: boolean) => {
    if (settled) return
    settled = true
    onFinish(completed)
  }

  const d = driver({
    ...baseConfig(),
    steps,
    onNextClick: (_element, _step, { driver: drv }) => {
      if (drv.isLastStep()) {
        settle(true)
        drv.destroy()
        return
      }
      drv.moveNext()
    },
    onCloseClick: (_element, _step, { driver: drv }) => {
      settle(false)
      drv.destroy()
    },
    onDestroyed: () => {
      activeDriver = null
      // 遮罩点击关闭等路径：未 settle 则视为跳过
      settle(false)
    }
  })
  activeDriver = d
  d.drive()
}

/** 任务列表段 */
export function startListTour(username: string) {
  if (starting) return
  starting = true
  try {
    const steps = listSteps()
    if (!steps.length) return
    runSteps(steps, (completed) => {
      if (completed) {
        writeTourProgress(username, {
          listDone: true,
          pendingWizard: true,
          skipped: false
        })
      } else {
        writeTourProgress(username, {
          listDone: true,
          skipped: true,
          pendingWizard: false
        })
      }
    })
  } finally {
    window.setTimeout(() => {
      starting = false
    }, 400)
  }
}

/** 工作台五步段 */
export async function startWizardTour(username: string) {
  if (starting) return
  starting = true
  try {
    await waitFor('[data-tour="wizard-steps"]', 6000)
    const steps = wizardSteps()
    runSteps(steps, (completed) => {
      if (completed) {
        writeTourProgress(username, {
          listDone: true,
          wizardDone: true,
          pendingWizard: false,
          skipped: false
        })
      } else {
        writeTourProgress(username, {
          wizardDone: true,
          pendingWizard: false,
          skipped: true
        })
      }
    })
  } finally {
    window.setTimeout(() => {
      starting = false
    }, 400)
  }
}

/** 帮助菜单：整段重开 */
export async function startFullTour(username: string) {
  writeTourProgress(username, {
    skipped: false,
    listDone: false,
    wizardDone: false,
    pendingWizard: false
  })
  const onWizard = !!document.querySelector('[data-tour="wizard-steps"]')
  if (onWizard) {
    writeTourProgress(username, { listDone: true, pendingWizard: true })
    await startWizardTour(username)
    return
  }
  startListTour(username)
}

/** 列表页挂载后：普通用户自动开 */
export function maybeAutoStartListTour(username: string, isAdmin: boolean) {
  if (!shouldAutoStartList(username, isAdmin)) return
  window.setTimeout(() => startListTour(username), 480)
}

/** 工作台挂载后：续接五步 */
export function maybeResumeWizardTour(username: string) {
  if (!shouldResumeWizard(username)) return
  window.setTimeout(() => {
    void startWizardTour(username)
  }, 600)
}

export function stopTour() {
  destroyActive()
}

export { readTourProgress, writeTourProgress }
