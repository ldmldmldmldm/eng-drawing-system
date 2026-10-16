<template>
  <div class="page-list">
    <a-card size="small" title="任务结果">
      <template #extra>
        <a-space :size="6">
          <a-button type="primary" size="small" @click="goBack">
            <LeftOutlined /> 返回任务列表
          </a-button>
          <a-button
            type="primary"
            size="small"
            :loading="editing"
            @click="onEdit"
          >
            编辑任务
          </a-button>
          <FullscreenExitFilterBtn />
        </a-space>
      </template>

      <a-spin :spinning="loading">
        <a-alert v-if="loadError" type="error" show-icon :message="loadError" style="margin-bottom: 12px" />
        <a-empty v-else-if="!task && !loading" description="暂无数据" />
        <template v-else-if="task">
          <a-alert
            v-if="task.status !== 'COMPLETED'"
            type="warning"
            show-icon
            message="任务尚未完成，无法预览结果"
            style="margin-bottom: 12px"
          >
            <template #action>
              <a-button size="small" type="primary" @click="goWorkspace">去工作台</a-button>
            </template>
          </a-alert>
          <div v-else class="result-body">
            <div class="result-meta">
              <div><strong>标题：</strong>{{ task.title }}</div>
              <div v-if="task.completedAt || task.updateTime">
                <strong>完成时间：</strong>{{ formatTime(task.completedAt || task.updateTime) }}
              </div>
            </div>
            <div class="result-toolbar">
              <div class="result-actions">
                <a-tooltip :title="hasDxf ? '下载工程图 DXF' : '产物中无 DXF，请回工作台重新生成'">
                  <span style="display: inline-block">
                    <a-button type="primary" size="small" :disabled="!hasDxf" :loading="savingDxf" @click="downloadDxf">
                      下载 DXF
                    </a-button>
                  </span>
                </a-tooltip>
                <a-tooltip :title="task.hasCustomerDwg ? '下载本任务上传的客户 DWG' : '未上传客户 DWG'">
                  <span style="display: inline-block">
                    <a-button
                      size="small"
                      :disabled="!task.hasCustomerDwg"
                      :loading="savingDwg"
                      @click="downloadUploadedDwg"
                    >
                      下载客户 DWG
                    </a-button>
                  </span>
                </a-tooltip>
                <a-tooltip :title="task.hasStepFile ? '下载本任务上传的三维 STP' : '未上传三维 STP'">
                  <span style="display: inline-block">
                    <a-button
                      size="small"
                      :disabled="!task.hasStepFile"
                      :loading="savingStep"
                      @click="downloadUploadedStep"
                    >
                      下载三维 STP
                    </a-button>
                  </span>
                </a-tooltip>
              </div>
              <div v-if="hasPreview" class="view-tools">
                <label class="view-hand" title="手形拖动画布">
                  <input v-model="viewHand" type="checkbox" />
                  <DragOutlined />
                  手形
                </label>
                <button type="button" class="view-tool-btn" title="放大" @click="zoomIn">
                  <ZoomInOutlined />
                </button>
                <button type="button" class="view-tool-btn" title="缩小" @click="zoomOut">
                  <ZoomOutOutlined />
                </button>
                <button type="button" class="view-tool-btn" title="适应居中" @click="resetView">
                  <AimOutlined />
                </button>
                <span class="view-zoom-lab" title="相对适应视口的比例">{{ viewZoomLabel }}</span>
              </div>
            </div>
            <div v-if="hasPreview" class="preview-box">
              <div
                ref="viewPortRef"
                class="preview-viewport"
                :class="{ 'is-hand': viewHand, 'is-panning': viewPanning }"
                @wheel.prevent="onViewWheel"
                @pointerdown="onViewPointerDown"
                @pointermove="onViewPointerMove"
                @pointerup="onViewPointerUp"
                @pointercancel="onViewPointerUp"
              >
                <div class="preview-canvas" :style="viewTransformStyle">
                  <img
                    v-if="previewSrc"
                    :src="previewSrc"
                    alt="工程图预览"
                    class="preview-img"
                    draggable="false"
                    @load="onMediaReady"
                  />
                  <div v-else-if="task.previewSvg" class="preview-svg" v-html="task.previewSvg" />
                </div>
              </div>
            </div>
            <div v-else class="preview-box preview-box--empty">
              <a-empty description="暂无预览图" />
            </div>
          </div>
        </template>
      </a-spin>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  AimOutlined,
  DragOutlined,
  LeftOutlined,
  ZoomInOutlined,
  ZoomOutOutlined
} from '@ant-design/icons-vue'
import { taskApi } from '@/api'
import type { TaskItem } from '@/api/types'
import { formatTime } from '@/utils/format'
import { engineeringDxfFileName } from '@/utils/engFileName'
import FullscreenExitFilterBtn from '@/components/FullscreenExitFilterBtn.vue'

const ZOOM_MIN = 0.05
const ZOOM_MAX = 12

const route = useRoute()
const router = useRouter()
const taskId = computed(() => String(route.params.id || ''))
const task = ref<TaskItem | null>(null)
const loading = ref(false)
const loadError = ref('')
const savingDwg = ref(false)
const savingStep = ref(false)
const savingDxf = ref(false)
const editing = ref(false)

const viewHand = ref(true)
const viewScale = ref(1)
const viewOffsetX = ref(0)
const viewOffsetY = ref(0)
const viewPanning = ref(false)
const viewPortRef = ref<HTMLDivElement | null>(null)
const fitScale = ref(1)
let panLastX = 0
let panLastY = 0
let fitRetryTimer = 0

const previewSrc = computed(() => {
  const b64 = task.value?.previewPngBase64
  if (!b64) return ''
  return b64.startsWith('data:') ? b64 : `data:image/png;base64,${b64}`
})

const hasPreview = computed(() => !!(previewSrc.value || task.value?.previewSvg))

const viewTransformStyle = computed(() => ({
  transform: `translate(${viewOffsetX.value}px, ${viewOffsetY.value}px) scale(${viewScale.value})`,
  transformOrigin: '0 0'
}))

/** 相对「适应」的百分比，适应时为 100% */
const viewZoomLabel = computed(() => {
  const base = fitScale.value > 1e-6 ? fitScale.value : 1
  return `${Math.round((viewScale.value / base) * 100)}%`
})

function measureMediaSize(): { w: number; h: number } | null {
  const vp = viewPortRef.value
  if (!vp) return null
  const img = vp.querySelector('img.preview-img') as HTMLImageElement | null
  if (img && img.naturalWidth > 0 && img.naturalHeight > 0) {
    return { w: img.naturalWidth, h: img.naturalHeight }
  }
  const svg = vp.querySelector('.preview-svg svg') as SVGSVGElement | null
  if (svg) {
    try {
      const box = svg.getBBox()
      if (box.width > 0 && box.height > 0) return { w: box.width, h: box.height }
    } catch {
      /* ignore */
    }
    const aw = svg.width?.baseVal?.value || svg.clientWidth || svg.scrollWidth
    const ah = svg.height?.baseVal?.value || svg.clientHeight || svg.scrollHeight
    if (aw > 0 && ah > 0) return { w: aw, h: ah }
  }
  return null
}

function fitToView() {
  const vp = viewPortRef.value
  if (!vp) return
  const vw = vp.clientWidth
  const vh = vp.clientHeight
  if (vw < 8 || vh < 8) {
    scheduleFitToView(80)
    return
  }
  const size = measureMediaSize()
  if (!size) {
    scheduleFitToView(80)
    return
  }
  const pad = 0.92
  const s = Math.min(vw / size.w, vh / size.h) * pad
  const scale = Number.isFinite(s) && s > 0 ? s : 1
  fitScale.value = scale
  viewScale.value = scale
  viewOffsetX.value = (vw - size.w * scale) / 2
  viewOffsetY.value = (vh - size.h * scale) / 2
}

function scheduleFitToView(delayMs = 0) {
  if (fitRetryTimer) window.clearTimeout(fitRetryTimer)
  fitRetryTimer = window.setTimeout(() => {
    fitRetryTimer = 0
    void nextTick(() => fitToView())
  }, delayMs)
}

function resetView() {
  fitToView()
}

function onMediaReady() {
  scheduleFitToView(0)
}

function clampScale(v: number) {
  return Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, v))
}

function zoomAt(factor: number, clientX?: number, clientY?: number) {
  const el = viewPortRef.value
  const prev = viewScale.value
  const next = clampScale(prev * factor)
  if (next === prev) return
  if (el && clientX != null && clientY != null) {
    const rect = el.getBoundingClientRect()
    const vx = clientX - rect.left
    const vy = clientY - rect.top
    const contentX = (vx - viewOffsetX.value) / prev
    const contentY = (vy - viewOffsetY.value) / prev
    viewScale.value = next
    viewOffsetX.value = vx - contentX * next
    viewOffsetY.value = vy - contentY * next
  } else {
    viewScale.value = next
  }
}

function zoomIn() {
  zoomAt(1.25)
}

function zoomOut() {
  zoomAt(1 / 1.25)
}

function onViewWheel(e: WheelEvent) {
  if (!hasPreview.value) return
  e.preventDefault()
  zoomAt(e.deltaY < 0 ? 1.12 : 1 / 1.12, e.clientX, e.clientY)
}

function onViewPointerDown(e: PointerEvent) {
  if (!viewHand.value || !hasPreview.value) return
  if (e.button !== 0) return
  viewPanning.value = true
  panLastX = e.clientX
  panLastY = e.clientY
  ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
}

function onViewPointerMove(e: PointerEvent) {
  if (!viewPanning.value) return
  viewOffsetX.value += e.clientX - panLastX
  viewOffsetY.value += e.clientY - panLastY
  panLastX = e.clientX
  panLastY = e.clientY
}

function onViewPointerUp(e: PointerEvent) {
  if (!viewPanning.value) return
  viewPanning.value = false
  try {
    ;(e.currentTarget as HTMLElement).releasePointerCapture?.(e.pointerId)
  } catch {
    /* ignore */
  }
}

function downloadBase64(name: string, base64: string, mime: string) {
  const bin = atob(base64)
  const bytes = new Uint8Array(bin.length)
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
  const url = URL.createObjectURL(new Blob([bytes], { type: mime }))
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}

const hasDxf = computed(() => !!(task.value?.hasDxf || task.value?.dxfBase64))

async function downloadDxf() {
  if (!task.value || !hasDxf.value || savingDxf.value) return
  const b64 = task.value.dxfBase64
  if (b64) {
    downloadBase64(
      task.value.dxfFileName ||
        engineeringDxfFileName(task.value.drawingNo, task.value.completedAt || task.value.updateTime),
      b64,
      'application/dxf'
    )
    return
  }
  savingDxf.value = true
  try {
    await taskApi.saveEngineeringDxf(
      task.value.id,
      task.value.dxfFileName ||
        engineeringDxfFileName(task.value.drawingNo, task.value.completedAt || task.value.updateTime)
    )
    message.success('工程图 DXF 已开始下载')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '工程图下载失败')
  } finally {
    savingDxf.value = false
  }
}

async function downloadUploadedDwg() {
  if (!task.value?.hasCustomerDwg || savingDwg.value) return
  savingDwg.value = true
  try {
    await taskApi.saveDwg(task.value.id)
    message.success('客户 DWG 已开始下载')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '客户 DWG 下载失败')
  } finally {
    savingDwg.value = false
  }
}

async function downloadUploadedStep() {
  if (!task.value?.hasStepFile || savingStep.value) return
  savingStep.value = true
  try {
    await taskApi.saveStep(task.value.id, task.value.stepFileName)
    message.success('三维 STP 已开始下载')
  } catch (e) {
    message.error(e instanceof Error ? e.message : '三维 STP 下载失败')
  } finally {
    savingStep.value = false
  }
}

function goBack() {
  router.push('/tasks')
}

function goWorkspace() {
  router.push(`/tasks/${taskId.value}`)
}

/** 已完成：确认后重开为进行中再进向导，可改①～⑤ */
function onEdit() {
  if (!task.value) return
  const id = task.value.id
  if (task.value.status !== 'COMPLETED') {
    void router.replace(`/tasks/${id}`)
    return
  }
  Modal.confirm({
    title: '重新编辑已完成任务？',
    content:
      '重新编辑后，任务会变为「进行中」，当前成品图将暂时无法预览/下载；在①～⑤修改并再次生成完成后才会更新产物。是否继续？',
    okText: '继续编辑',
    cancelText: '取消',
    centered: true,
    onOk: async () => {
      editing.value = true
      try {
        await taskApi.update(id, { status: 'IN_PROGRESS' })
        await router.replace(`/tasks/${id}`)
      } finally {
        editing.value = false
      }
    }
  })
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    try {
      task.value = await taskApi.result(taskId.value)
    } catch {
      task.value = await taskApi.get(taskId.value)
    }
  } catch (e) {
    task.value = null
    loadError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

watch(hasPreview, (ok) => {
  if (ok) scheduleFitToView(40)
})

watch(previewSrc, () => {
  if (hasPreview.value) scheduleFitToView(40)
})

onMounted(() => {
  void load()
})

onBeforeUnmount(() => {
  if (fitRetryTimer) window.clearTimeout(fitRetryTimer)
})
</script>

<style scoped>
.result-body {
  width: 100%;
}

.result-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 13px;
  color: #334155;
}

.result-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 12px;
}

.result-actions {
  display: flex;
  gap: 8px;
}

.view-tools {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.view-hand {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #334155;
  cursor: pointer;
  user-select: none;
}

.view-tool-btn {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--ui-border, #d0d5dd);
  border-radius: 4px;
  background: #fff;
  color: #334155;
  cursor: pointer;
}

.view-tool-btn:hover {
  border-color: #1677ff;
  color: #1677ff;
}

.view-zoom-lab {
  font-size: 12px;
  font-weight: 700;
  color: #64748b;
  min-width: 40px;
}

.preview-box {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #0b1220;
  height: min(70vh, 720px);
  min-height: 360px;
  overflow: hidden;
}

.preview-box--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f8fafc;
}

.preview-viewport {
  width: 100%;
  height: 100%;
  position: relative;
  overflow: hidden;
  cursor: default;
  touch-action: none;
}

.preview-viewport.is-hand {
  cursor: grab;
}

.preview-viewport.is-panning {
  cursor: grabbing;
}

.preview-canvas {
  position: absolute;
  left: 0;
  top: 0;
  will-change: transform;
}

.preview-img {
  display: block;
  max-width: none;
  user-select: none;
  pointer-events: none;
}

.preview-svg {
  display: block;
  background: #fff;
  pointer-events: none;
  user-select: none;
}

.preview-svg :deep(svg) {
  display: block;
  max-width: none;
}
</style>
