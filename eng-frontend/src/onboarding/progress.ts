/** 新手引导进度（按工号落 localStorage） */

export type TourProgress = {
  /** 列表段已完成或跳过 */
  listDone: boolean
  /** 向导五步段已完成或跳过 */
  wizardDone: boolean
  /** 用户主动跳过（仍可从帮助重开） */
  skipped: boolean
  /** 列表段结束后等待进入工作台续接 */
  pendingWizard: boolean
  /** 管理员：不自动弹出 */
  adminMuteAuto: boolean
}

const PREFIX = 'eng-tour:'

function keyFor(username: string): string {
  return `${PREFIX}${username || 'anon'}`
}

export function defaultProgress(): TourProgress {
  return {
    listDone: false,
    wizardDone: false,
    skipped: false,
    pendingWizard: false,
    adminMuteAuto: false
  }
}

export function readTourProgress(username: string): TourProgress {
  try {
    const raw = localStorage.getItem(keyFor(username))
    if (!raw) return defaultProgress()
    const p = JSON.parse(raw) as Partial<TourProgress>
    return { ...defaultProgress(), ...p }
  } catch {
    return defaultProgress()
  }
}

export function writeTourProgress(username: string, patch: Partial<TourProgress>) {
  const next = { ...readTourProgress(username), ...patch }
  try {
    localStorage.setItem(keyFor(username), JSON.stringify(next))
  } catch {
    // ignore quota
  }
  return next
}

export function isTourFullyDone(p: TourProgress): boolean {
  return p.listDone && p.wizardDone
}

/** 普通用户是否应自动开列表引导 */
export function shouldAutoStartList(username: string, isAdmin: boolean): boolean {
  const p = readTourProgress(username)
  if (isAdmin) return false
  if (p.skipped || p.listDone) return false
  return true
}

/** 进入工作台后是否续接向导引导 */
export function shouldResumeWizard(username: string): boolean {
  const p = readTourProgress(username)
  if (p.wizardDone || p.skipped) return false
  return !!(p.pendingWizard && p.listDone)
}
