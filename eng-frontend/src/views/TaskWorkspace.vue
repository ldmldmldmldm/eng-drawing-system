<template>
  <div class="page-list task-workspace">
    <a-card size="small" class="ws-card">
      <template #title>
        <div class="ws-title-row">
          <a-button type="link" size="small" class="back-btn" @click="goBack">
            <LeftOutlined /> 返回任务列表
          </a-button>
          <span class="ws-title">{{ task?.title || '任务工作台' }}</span>
          <a-tag v-if="task" :color="statusColor(task.status)">{{ statusLabel(task.status) }}</a-tag>
        </div>
      </template>
      <template #extra>
        <a-space :size="6" wrap>
          <span class="ws-meta">步骤：{{ stepText }}</span>
          <a-button v-if="task?.status === 'COMPLETED'" type="primary" size="small" @click="goResult">
            查看结果
          </a-button>
          <FullscreenExitFilterBtn />
        </a-space>
      </template>

      <a-alert v-if="loadError" type="error" show-icon :message="loadError" style="margin-bottom: 8px" />
      <a-alert
        v-else-if="task?.status === 'COMPLETED'"
        type="info"
        show-icon
        message="任务已完成，当前为回看。若要改①～⑤并重新生成，请到结果页或列表点「编辑」。"
        style="margin-bottom: 8px"
      />
      <a-spin :spinning="loading" tip="正在加载任务…" class="ws-spin">
        <EngSolidWorkspace
          v-if="task"
          :task-id="task.id"
          :task-title="task.title"
          :task-status="task.status"
          :initial-step="task.currentStep || 1"
          :stack-asset-id="task.stackAssetId"
          :stack-skipped="!!task.stackSkipped"
          :extract-skipped="!!task.extractSkipped"
          :step-file-name="task.stepFileName"
          :has-step-file="!!task.hasStepFile"
          :has-customer-dwg="!!task.hasCustomerDwg"
          @step-change="onWizardStep"
          @composed="onComposed"
          @drawing-no="onDrawingNo"
          @stack-bound="onStackBound"
          @task-patch="onTaskPatch"
          @back="goBack"
        />
        <div v-else-if="loading" class="ws-loading-ph" aria-hidden="true" />
      </a-spin>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { LeftOutlined } from '@ant-design/icons-vue'
import { taskApi, type EngComposeResult } from '@/api'
import type { TaskItem, TaskStatus } from '@/api/types'
import EngSolidWorkspace from '@/components/EngSolidWorkspace.vue'
import FullscreenExitFilterBtn from '@/components/FullscreenExitFilterBtn.vue'
import { taskStepLabel } from '@/utils/taskStep'
import { maybeResumeWizardTour } from '@/onboarding'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const taskId = computed(() => String(route.params.id || ''))
const task = ref<TaskItem | null>(null)
const loading = ref(false)
const loadError = ref('')
const wizardStep = ref(1)

const stepText = computed(() =>
  taskStepLabel(task.value?.currentStep || wizardStep.value || 1, task.value?.status)
)

function statusLabel(s: TaskStatus | string) {
  if (s === 'DRAFT') return '草稿'
  if (s === 'IN_PROGRESS') return '进行中'
  if (s === 'COMPLETED') return '已完成'
  return s || '—'
}

function statusColor(s: TaskStatus | string) {
  if (s === 'DRAFT') return 'default'
  if (s === 'IN_PROGRESS') return 'processing'
  if (s === 'COMPLETED') return 'success'
  return 'default'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    // 已完成保持 COMPLETED；重开仅列表/结果页「编辑」显式触发
    task.value = await taskApi.get(taskId.value)
    wizardStep.value = Number(task.value?.currentStep || 1)
  } catch (e) {
    task.value = null
    loadError.value = e instanceof Error ? e.message : '加载任务失败'
  } finally {
    loading.value = false
  }
}

function goBack() {
  router.push('/tasks')
}

function goResult() {
  router.push(`/tasks/${taskId.value}/result`)
}

async function onWizardStep(n: number) {
  wizardStep.value = n
  if (!task.value || task.value.status === 'COMPLETED') return
  const prev = Number(task.value.currentStep || 1)
  // 回看①～④不降低已达步骤，避免已完成再编辑后顶栏丢⑤
  if (n <= prev && task.value.status !== 'DRAFT') return
  try {
    const patch: { currentStep: number; status?: TaskStatus } = { currentStep: n }
    if (task.value.status === 'DRAFT') patch.status = 'IN_PROGRESS'
    task.value = await taskApi.update(task.value.id, patch)
  } catch {
    // 非阻断
  }
}

async function onDrawingNo(drawingNo: string) {
  if (!task.value || !drawingNo) return
  if (task.value.drawingNo === drawingNo) return
  try {
    task.value = await taskApi.update(task.value.id, { drawingNo })
  } catch {
    // ignore
  }
}

async function onStackBound(stackAssetId: string) {
  if (!task.value || !stackAssetId) return
  try {
    // 步骤由向导 stepChange 推进（保存层次后进③），此处只绑定资产；绑定即取消跳过
    task.value = await taskApi.update(task.value.id, {
      stackAssetId,
      stackSkipped: false,
      status: 'IN_PROGRESS'
    })
  } catch {
    message.warning('层次已保存，但任务绑定失败（重进可能需从「最近」选用）')
  }
}

function onTaskPatch(patch: {
  stepFileName?: string
  hasStepFile?: boolean
  hasCustomerDwg?: boolean
  stackSkipped?: boolean
  extractSkipped?: boolean
  stackAssetId?: string | null
}) {
  if (!task.value) return
  if (patch.stepFileName !== undefined) task.value.stepFileName = patch.stepFileName
  if (patch.hasStepFile !== undefined) task.value.hasStepFile = patch.hasStepFile
  if (patch.hasCustomerDwg !== undefined) task.value.hasCustomerDwg = patch.hasCustomerDwg
  if (patch.stackSkipped !== undefined) task.value.stackSkipped = patch.stackSkipped
  if (patch.extractSkipped !== undefined) task.value.extractSkipped = patch.extractSkipped
  if (patch.stackAssetId !== undefined) {
    task.value.stackAssetId = patch.stackAssetId || undefined
  }
  if (task.value.status === 'DRAFT') task.value.status = 'IN_PROGRESS'
}

async function onComposed(result: EngComposeResult) {
  if (!task.value) return
  try {
    // 合成若带了存盘名 customer.dwg 推出来的图号，保留任务已有图号（通常来自 STP）
    const drawingNo =
      result.drawingNo && result.drawingNo.toLowerCase() !== 'customer'
        ? result.drawingNo
        : task.value.drawingNo || result.drawingNo
    await taskApi.complete(task.value.id, {
      drawingNo,
      previewPngBase64: result.previewPngBase64,
      previewSvg: result.previewSvg,
      dxfFileName: result.dxfFileName,
      stackAssetId: result.stackAssetId,
      message: result.message
    })
    message.success('任务已完成')
    await router.replace(`/tasks/${task.value.id}/result`)
  } catch (e) {
    message.error(
      e instanceof Error
        ? `${e.message}。工程图已生成，可先下载核对；修好后请在⑤再次点「完成任务并进入结果页」`
        : '完成任务失败。工程图已生成，请在⑤重试完成'
    )
  }
}

onMounted(async () => {
  await load()
  if (task.value) {
    maybeResumeWizardTour(userStore.username || '')
  }
})
</script>

<style scoped>
.task-workspace {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ws-card {
  border-radius: 10px;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ws-card :deep(.ant-card-body) {
  padding: 8px 10px 10px;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ws-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.back-btn {
  padding-inline: 0;
  height: 24px;
}
.ws-title {
  font-weight: 700;
  color: var(--ui-text-strong, #000);
}
.ws-meta {
  font-size: 12px;
  color: #64748b;
}
/* Spin 必须把高度传到子组件，否则左栏三维撑不满 */
.ws-spin {
  flex: 1;
  min-height: 420px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.ws-loading-ph {
  flex: 1;
  min-height: 420px;
}
.ws-spin :deep(.ant-spin-nested-loading),
.ws-spin :deep(.ant-spin-container) {
  flex: 1;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
