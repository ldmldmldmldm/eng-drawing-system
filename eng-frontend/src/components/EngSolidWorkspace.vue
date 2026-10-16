<script setup lang="ts">
/**
 * 工程图三维工作区：从 AiDrawingPage 抽出的最小可运行壳。
 * 向导 + STP 解析 + occt 实体视口 + 层次爆炸编辑。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import * as THREE from 'three'
import {
  scanStep,
  getEngStack,
  taskApi,
  type ConvertPreview,
  type EngComposeResult,
  type EngStackAssetSummary,
  type FactoryFace,
  type FactoryKnife,
  type OcctMesh,
  type OcctModule,
  type StepScanView,
  type StackExplodeExportBody,
  type TaskAnnotSnapshot
} from '@/api'
import { extractDraftingPlates } from '@/utils/stackExplodeEdges'
import StackExplodeEditor, {
  type StackCalloutSeed,
  type StackPlateInput
} from '@/components/StackExplodeEditor.vue'
import EngDrawingWizard, { type ComposeBoardPayload } from '@/components/EngDrawingWizard.vue'
import ComposeArtifactBoard from '@/components/ComposeArtifactBoard.vue'
import { LoadingOutlined, ZoomInOutlined, ZoomOutOutlined, DragOutlined, AimOutlined } from '@ant-design/icons-vue'

const props = defineProps<{
  taskId?: number | string
  taskTitle?: string
  /** DRAFT / IN_PROGRESS / COMPLETED；已完成回看时禁止再生成/再完成 */
  taskStatus?: string
  /** 任务当前步骤 */
  initialStep?: number
  stackAssetId?: string
  /** 任务已跳过②层次 */
  stackSkipped?: boolean
  /** 任务已跳过①抽取 */
  extractSkipped?: boolean
  stepFileName?: string
  hasStepFile?: boolean
  hasCustomerDwg?: boolean
}>()

const emit = defineEmits<{
  stepChange: [step: number]
  composed: [result: EngComposeResult]
  drawingNo: [drawingNo: string]
  back: []
  /** 层次资产已绑定到任务 */
  stackBound: [stackAssetId: string]
  /** 任务元数据被上传接口刷新 */
  taskPatch: [
    patch: {
      stepFileName?: string
      hasStepFile?: boolean
      hasCustomerDwg?: boolean
      stackSkipped?: boolean
      extractSkipped?: boolean
      stackAssetId?: string | null
    }
  ]
}>()

const MAX_STEP = 80 * 1024 * 1024
const UNLABELED = '未标注'
const STACK_Z_EPS = 1e-3
/** Z 展开滑条默认上限；实际上限随模型面宽动态上调（收窄，避免拖一点就过开） */
const EXPLODE_GAP_MAX_DEFAULT = 20
const EXPLODE_GAP_MAX_HARD = 40
/** 动态上限 ≈ 面宽比例 */
const EXPLODE_GAP_MAX_FACE_RATIO = 0.25
/** 推荐间隙：面宽比例（透视投影后片间距不足时 DXF 易叠） */
const EXPLODE_GAP_FACE_RATIO = 0.06
const EXPLODE_GAP_RECOMMEND_MIN = 1
/** 当前间隙低于推荐值的该比例时，进层次编辑自动加大 */
const EXPLODE_AUTO_BOOST_RATIO = 0.6
/** 滑条步进 */
const EXPLODE_GAP_STEP = 0.05
const STACK_VIEW_ELEV_RAD = (28 * Math.PI) / 180
const STACK_VIEW_AZIM_RAD = (45 * Math.PI) / 180
const SOLID_ROTATE_RAD_PER_PX = 0.005
const SOLID_PAN_FACTOR = 0.0012
const SOLID_ZOOM_WHEEL = 0.001
/** STP 常导出近白材质，浅底上看不见；按实体轮换对比色 */
const BODY_PALETTE = ['#3b82f6', '#14b8a6', '#f59e0b', '#ec4899', '#8b5cf6', '#64748b']

type FactoryLayer = {
  id: string
  color: string
  thickness: number
  meshCount: number
  partNo: string
  material: string
  knife: FactoryKnife | string
  halfCutRemain: string
  face: FactoryFace
  note: string
  faceNotes: Partial<Record<FactoryFace, string>>
  flatBox: null
  flatLayers: string[]
  flatEntities: string[]
  stepSolidRef: string
  noFlatContour: boolean
}

const stepFile = ref<File | null>(null)
const stepScanning = ref(false)
const stepResult = ref<StepScanView | null>(null)
const solidHost = ref<HTMLDivElement | null>(null)
const wireCanvas = ref<HTMLCanvasElement | null>(null)
const stackPreviewCanvas = ref<HTMLCanvasElement | null>(null)
const solidReady = ref(false)
const wireVisible = ref(false)
const lastSolidError = ref('')
const solidHint = ref('')
const solidHand = ref(false)
const explodeGap = ref(0)
const explodeGapMax = ref(EXPLODE_GAP_MAX_DEFAULT)
const factoryLayers = ref<FactoryLayer[]>([])
const factoryBodies = ref<{ id: string; layerId: string; color: string; thickness: number }[]>([])
const selectedLayerId = ref('')
const selectedBodyId = ref('')
const factoryListMode = ref<'layer' | 'body'>('body')

const stackExplodeOpen = ref(false)
const stackExplodeOpening = ref(false)
const stackExplodePlates = ref<StackPlateInput[]>([])
const stackExplodeInitialCallouts = ref<StackCalloutSeed[]>([])
/** ① 已确认的 2D 材料，供层次标注按序号预填 */
const stackExplodeMaterialSeeds = ref<
  { noteNo: string; material: string; color: string; thickness: string }[]
>([])
const engWizardRef = ref<InstanceType<typeof EngDrawingWizard> | null>(null)
const engWizardStep = ref(Number(props.initialStep || 1) || 1)
/** ① 抽取：左栏展示客户图纸预览（非三维） */
const extractPreview = ref<ConvertPreview | null>(null)
const extractBusy = ref(false)
/** ⑤ 产物总览数据 */
const composeBoard = ref<ComposeBoardPayload | null>(null)
const showDwgStage = computed(() => engWizardStep.value === 1)
const showSolidStage = computed(() => engWizardStep.value === 2)
const showComposeBoard = computed(() => engWizardStep.value === 5)
const DWG_ZOOM_MIN = 0.08
const DWG_ZOOM_MAX = 6
const dwgHand = ref(true)
const dwgScale = ref(1)
const dwgOffsetX = ref(0)
const dwgOffsetY = ref(0)
/** 预览内容原始宽高（PNG natural / SVG viewBox），供适应窗口 */
const dwgContentW = ref(0)
const dwgContentH = ref(0)
const dwgPageIndex = ref(0)
const dwgPanning = ref(false)
const dwgViewportRef = ref<HTMLDivElement | null>(null)
let dwgPanLastX = 0
let dwgPanLastY = 0
/** 用户是否手动缩放/拖动过 */
let dwgUserAdjusted = false

type DwgPageItem = { kind: 'png' | 'svg'; content: string }

/** 多页优先用 previewSvgs；单页优先 PNG（更清晰） */
const dwgPages = computed<DwgPageItem[]>(() => {
  const p = extractPreview.value
  if (!p) return []
  const svgs = (p.previewSvgs?.length ? p.previewSvgs : p.previewSvg ? [p.previewSvg] : []).filter(
    (s) => !!s && s.trim()
  )
  if (svgs.length > 1) {
    return svgs.map((s) => ({ kind: 'svg' as const, content: s }))
  }
  const b64 = p.previewPngBase64
  if (b64) {
    const src = b64.startsWith('data:') ? b64 : `data:image/png;base64,${b64}`
    return [{ kind: 'png' as const, content: src }]
  }
  if (svgs.length === 1) {
    return [{ kind: 'svg' as const, content: svgs[0] }]
  }
  return []
})
const dwgMultiPage = computed(() => dwgPages.value.length > 1)
const dwgCurrentPage = computed(() => dwgPages.value[dwgPageIndex.value] || null)
const hasDwgPreviewMedia = computed(() => dwgPages.value.length > 0)
/** 平移 + 缩放一律走 transform，手形拖动稳定可靠 */
const dwgTransformStyle = computed(() => ({
  transform: `translate(${dwgOffsetX.value}px, ${dwgOffsetY.value}px) scale(${dwgScale.value})`,
  transformOrigin: '0 0'
}))

function ensureDwgContentSize(): boolean {
  const size = readDwgContentSize()
  if (!size) return false
  dwgContentW.value = size.cw
  dwgContentH.value = size.ch
  return true
}

function onDwgImgLoad() {
  ensureDwgContentSize()
  if (!dwgUserAdjusted) scheduleFitDwgToViewport()
}

function resetDwgView() {
  dwgScale.value = 1
  dwgOffsetX.value = 0
  dwgOffsetY.value = 0
}

function readDwgContentSize(): { cw: number; ch: number } | null {
  const el = dwgViewportRef.value
  if (!el || !hasDwgPreviewMedia.value) return null
  const page = dwgCurrentPage.value
  if (page?.kind === 'png') {
    const img = el.querySelector('.stage-dwg-img') as HTMLImageElement | null
    if (!img) return null
    const cw = img.naturalWidth || 0
    const ch = img.naturalHeight || 0
    if (cw < 8 || ch < 8) return null
    return { cw, ch }
  }
  const svgHost = el.querySelector('.stage-dwg-svg') as HTMLElement | null
  const svg = svgHost?.querySelector('svg') as SVGSVGElement | null
  let cw = 0
  let ch = 0
  if (svg) {
    const vb = svg.viewBox?.baseVal
    cw = Number(vb?.width) || svg.clientWidth || svgHost?.scrollWidth || 0
    ch = Number(vb?.height) || svg.clientHeight || svgHost?.scrollHeight || 0
  } else {
    cw = svgHost?.scrollWidth || 0
    ch = svgHost?.scrollHeight || 0
  }
  if (cw < 8 || ch < 8) return null
  return { cw, ch }
}

/** 渲完/复位：整图缩进视口，锚点左上（不居中） */
function fitDwgToViewport() {
  const el = dwgViewportRef.value
  if (!el || !hasDwgPreviewMedia.value) return
  const vw = el.clientWidth
  const vh = el.clientHeight
  if (vw < 48 || vh < 48) return
  if (!ensureDwgContentSize()) return

  const cw = dwgContentW.value
  const ch = dwgContentH.value
  const pad = 8
  const next = clampDwgScale(Math.min((vw - pad) / cw, (vh - pad) / ch))
  dwgScale.value = next
  dwgOffsetX.value = pad / 2
  dwgOffsetY.value = pad / 2
  dwgUserAdjusted = false
}

function scheduleFitDwgToViewport() {
  const tryFit = (attempt: number) => {
    const el = dwgViewportRef.value
    if (!el || !hasDwgPreviewMedia.value) {
      if (attempt < 12) window.setTimeout(() => tryFit(attempt + 1), 50)
      return
    }
    if (el.clientWidth < 48 || el.clientHeight < 48) {
      if (attempt < 12) window.setTimeout(() => tryFit(attempt + 1), 50)
      return
    }
    const img = el.querySelector('.stage-dwg-img') as HTMLImageElement | null
    if (img && (!img.complete || !(img.naturalWidth > 0))) {
      const onLoad = () => {
        img.removeEventListener('load', onLoad)
        fitDwgToViewport()
      }
      img.addEventListener('load', onLoad)
      if (attempt < 12) window.setTimeout(() => tryFit(attempt + 1), 80)
      return
    }
    fitDwgToViewport()
  }
  void nextTick(() => {
    requestAnimationFrame(() => tryFit(0))
  })
}

function clampDwgScale(v: number) {
  return Math.min(DWG_ZOOM_MAX, Math.max(DWG_ZOOM_MIN, v))
}

function zoomDwgAt(factor: number, clientX?: number, clientY?: number) {
  const el = dwgViewportRef.value
  const prev = dwgScale.value
  const next = clampDwgScale(prev * factor)
  if (next === prev) return
  dwgUserAdjusted = true
  if (el && clientX != null && clientY != null) {
    const rect = el.getBoundingClientRect()
    const vx = clientX - rect.left
    const vy = clientY - rect.top
    const contentX = (vx - dwgOffsetX.value) / prev
    const contentY = (vy - dwgOffsetY.value) / prev
    dwgScale.value = next
    dwgOffsetX.value = vx - contentX * next
    dwgOffsetY.value = vy - contentY * next
  } else {
    dwgScale.value = next
  }
}

function zoomDwgIn() {
  const el = dwgViewportRef.value
  if (el) {
    const r = el.getBoundingClientRect()
    zoomDwgAt(1.2, r.left + r.width / 2, r.top + r.height / 2)
  } else {
    zoomDwgAt(1.2)
  }
}

function zoomDwgOut() {
  const el = dwgViewportRef.value
  if (el) {
    const r = el.getBoundingClientRect()
    zoomDwgAt(1 / 1.2, r.left + r.width / 2, r.top + r.height / 2)
  } else {
    zoomDwgAt(1 / 1.2)
  }
}

function selectDwgPage(idx: number) {
  if (idx < 0 || idx >= dwgPages.value.length) return
  dwgPageIndex.value = idx
  dwgUserAdjusted = false
  scheduleFitDwgToViewport()
}

function onDwgWheel(e: WheelEvent) {
  if (!hasDwgPreviewMedia.value) return
  e.preventDefault()
  // 滚轮缩放；放大后用手形拖动平移
  const factor = e.deltaY < 0 ? 1.12 : 1 / 1.12
  zoomDwgAt(factor, e.clientX, e.clientY)
}

function onDwgPointerUp(e: PointerEvent) {
  if (dwgCropMode.value) {
    onDwgCropPointerUp(e)
    return
  }
  if (!dwgPanning.value) return
  dwgPanning.value = false
  try {
    ;(e.currentTarget as HTMLElement).releasePointerCapture?.(e.pointerId)
  } catch {
    /* ignore */
  }
}

/** ① 框选识别：由右侧「截图识别」触发 */
const dwgCropMode = ref(false)
const dwgCropKind = ref<'note' | 'material'>('note')
const dwgCropDraft = ref<{ x0: number; y0: number; x1: number; y1: number } | null>(null)
const dwgCropPending = ref<{ x: number; y: number; w: number; h: number } | null>(null)
const dwgCropDrawing = ref(false)
let dwgHandBeforeCrop: boolean | null = null

const dwgCropRectStyle = computed(() => {
  const d = dwgCropDraft.value
  const p = dwgCropPending.value
  const box = d
    ? {
        x: Math.min(d.x0, d.x1),
        y: Math.min(d.y0, d.y1),
        w: Math.abs(d.x1 - d.x0),
        h: Math.abs(d.y1 - d.y0)
      }
    : p
  if (!box || box.w < 2 || box.h < 2) return null
  return {
    left: `${box.x}px`,
    top: `${box.y}px`,
    width: `${box.w}px`,
    height: `${box.h}px`
  }
})

function exitDwgCropMode(silent = false) {
  dwgCropMode.value = false
  dwgCropDraft.value = null
  dwgCropPending.value = null
  dwgCropDrawing.value = false
  if (dwgHandBeforeCrop != null) {
    dwgHand.value = dwgHandBeforeCrop
    dwgHandBeforeCrop = null
  }
  if (!silent) message.info('已取消框选')
}

function onRegionCaptureRequest(kind: 'note' | 'material') {
  if (!hasDwgPreviewMedia.value) {
    message.warning('左侧暂无图纸预览，请先上传 DWG 完成解析')
    return
  }
  if (extractBusy.value) {
    message.warning('图纸解析进行中，请稍候')
    return
  }
  if (dwgCropMode.value) {
    exitDwgCropMode(true)
  }
  dwgCropKind.value = kind
  dwgHandBeforeCrop = dwgHand.value
  dwgHand.value = false
  dwgCropMode.value = true
  dwgCropDraft.value = null
  dwgCropPending.value = null
  // 不改动左侧缩放/滚动：框选前保持用户当前预览状态
  message.info('请在左图拖拽框选区域，松手后点「识别」')
}

function onDwgCropKeydown(e: KeyboardEvent) {
  if (!dwgCropMode.value) return
  if (e.key === 'Escape') {
    e.preventDefault()
    exitDwgCropMode()
  }
}

watch(dwgCropMode, (on) => {
  if (on) window.addEventListener('keydown', onDwgCropKeydown)
  else window.removeEventListener('keydown', onDwgCropKeydown)
})

function onDwgCropPointerDown(e: PointerEvent) {
  if (!dwgCropMode.value || e.button !== 0) return
  const el = dwgViewportRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  dwgCropPending.value = null
  dwgCropDraft.value = { x0: x, y0: y, x1: x, y1: y }
  dwgCropDrawing.value = true
  el.setPointerCapture?.(e.pointerId)
}

function onDwgCropPointerMove(e: PointerEvent) {
  if (!dwgCropMode.value || !dwgCropDrawing.value || !dwgCropDraft.value) return
  const el = dwgViewportRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  dwgCropDraft.value = {
    ...dwgCropDraft.value,
    x1: e.clientX - rect.left,
    y1: e.clientY - rect.top
  }
}

function onDwgCropPointerUp(e: PointerEvent) {
  if (!dwgCropMode.value) return
  const el = e.currentTarget as HTMLElement
  try {
    el.releasePointerCapture?.(e.pointerId)
  } catch {
    /* ignore */
  }
  if (!dwgCropDrawing.value || !dwgCropDraft.value) return
  dwgCropDrawing.value = false
  const d = dwgCropDraft.value
  const x = Math.min(d.x0, d.x1)
  const y = Math.min(d.y0, d.y1)
  const w = Math.abs(d.x1 - d.x0)
  const h = Math.abs(d.y1 - d.y0)
  dwgCropDraft.value = null
  if (w < 16 || h < 16) {
    message.warning('选区过小，请重新框选')
    return
  }
  dwgCropPending.value = { x, y, w, h }
}

async function rasterizeSvgToCanvas(svgHtml: string): Promise<HTMLCanvasElement> {
  const wrap = document.createElement('div')
  wrap.innerHTML = svgHtml
  const svg = wrap.querySelector('svg')
  if (!svg) throw new Error('当前页 SVG 无效')
  const vb = svg.viewBox?.baseVal
  const w = Math.max(1, Math.round(vb?.width || svg.clientWidth || 1200))
  const h = Math.max(1, Math.round(vb?.height || svg.clientHeight || 800))
  svg.setAttribute('width', String(w))
  svg.setAttribute('height', String(h))
  const xml = new XMLSerializer().serializeToString(svg)
  const url = URL.createObjectURL(new Blob([xml], { type: 'image/svg+xml;charset=utf-8' }))
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const i = new Image()
      i.onload = () => resolve(i)
      i.onerror = () => reject(new Error('SVG 渲图失败'))
      i.src = url
    })
    const canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('无法创建画布')
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, w, h)
    ctx.drawImage(img, 0, 0, w, h)
    return canvas
  } finally {
    URL.revokeObjectURL(url)
  }
}

async function cropViewportRectToPng(box: { x: number; y: number; w: number; h: number }): Promise<Blob> {
  const page = dwgCurrentPage.value
  if (!page) throw new Error('无预览页')
  // 视口坐标 → 内容坐标（未缩放）
  const scale = dwgScale.value || 1
  const cx0 = (box.x - dwgOffsetX.value) / scale
  const cy0 = (box.y - dwgOffsetY.value) / scale
  const cx1 = (box.x + box.w - dwgOffsetX.value) / scale
  const cy1 = (box.y + box.h - dwgOffsetY.value) / scale

  let source: HTMLCanvasElement | HTMLImageElement
  let displayW: number
  let displayH: number
  let naturalW: number
  let naturalH: number

  if (page.kind === 'png') {
    const imgEl = dwgViewportRef.value?.querySelector('.stage-dwg-img') as HTMLImageElement | null
    if (!imgEl || !imgEl.naturalWidth) throw new Error('预览图未就绪')
    source = imgEl
    displayW = imgEl.offsetWidth || imgEl.naturalWidth
    displayH = imgEl.offsetHeight || imgEl.naturalHeight
    naturalW = imgEl.naturalWidth
    naturalH = imgEl.naturalHeight
  } else {
    const svgEl = dwgViewportRef.value?.querySelector('.stage-dwg-svg svg') as SVGSVGElement | null
    const canvas = await rasterizeSvgToCanvas(page.content)
    source = canvas
    displayW = svgEl?.clientWidth || canvas.width
    displayH = svgEl?.clientHeight || canvas.height
    naturalW = canvas.width
    naturalH = canvas.height
  }

  // 框选区域原样裁切到 PNG（不内缩边缘；像素对齐用 floor/ceil 包住整选区）
  const left = Math.min(cx0, cx1)
  const top = Math.min(cy0, cy1)
  const right = Math.max(cx0, cx1)
  const bottom = Math.max(cy0, cy1)
  const x = Math.max(0, Math.floor((left / displayW) * naturalW))
  const y = Math.max(0, Math.floor((top / displayH) * naturalH))
  const x1 = Math.min(naturalW, Math.ceil((right / displayW) * naturalW))
  const y1 = Math.min(naturalH, Math.ceil((bottom / displayH) * naturalH))
  const w = Math.max(1, x1 - x)
  const h = Math.max(1, y1 - y)
  if (w < 8 || h < 8) throw new Error('选区无效')

  const out = document.createElement('canvas')
  // 略放大便于 OCR（几何内容不变，不裁边）
  const outScale = Math.min(2, Math.max(1, 1600 / Math.max(w, h)))
  out.width = Math.round(w * outScale)
  out.height = Math.round(h * outScale)
  const ctx = out.getContext('2d')
  if (!ctx) throw new Error('无法裁剪')
  ctx.imageSmoothingEnabled = true
  ctx.filter = 'contrast(1.42) brightness(1.14)'
  ctx.drawImage(source, x, y, w, h, 0, 0, out.width, out.height)
  ctx.filter = 'none'
  const blob = await new Promise<Blob | null>((resolve) => out.toBlob((b) => resolve(b), 'image/png'))
  if (!blob) throw new Error('裁剪失败')
  return blob
}

async function cropViewportRectNormalized(
  box: { x: number; y: number; w: number; h: number }
): Promise<{ x: number; y: number; w: number; h: number }> {
  const page = dwgCurrentPage.value
  if (!page) throw new Error('无预览页')
  const scale = dwgScale.value || 1
  const cx0 = (box.x - dwgOffsetX.value) / scale
  const cy0 = (box.y - dwgOffsetY.value) / scale
  const cx1 = (box.x + box.w - dwgOffsetX.value) / scale
  const cy1 = (box.y + box.h - dwgOffsetY.value) / scale

  let displayW: number
  let displayH: number
  if (page.kind === 'png') {
    const imgEl = dwgViewportRef.value?.querySelector('.stage-dwg-img') as HTMLImageElement | null
    if (!imgEl || !imgEl.naturalWidth) throw new Error('预览图未就绪')
    displayW = imgEl.offsetWidth || imgEl.naturalWidth
    displayH = imgEl.offsetHeight || imgEl.naturalHeight
  } else {
    const svgEl = dwgViewportRef.value?.querySelector('.stage-dwg-svg svg') as SVGSVGElement | null
    displayW = svgEl?.clientWidth || 1
    displayH = svgEl?.clientHeight || 1
  }
  if (displayW < 8 || displayH < 8) throw new Error('预览尺寸无效')

  const left = Math.min(cx0, cx1)
  const top = Math.min(cy0, cy1)
  const right = Math.max(cx0, cx1)
  const bottom = Math.max(cy0, cy1)
  const x = Math.max(0, Math.min(1, left / displayW))
  const y = Math.max(0, Math.min(1, top / displayH))
  const x1 = Math.max(0, Math.min(1, right / displayW))
  const y1 = Math.max(0, Math.min(1, bottom / displayH))
  const w = Math.max(0.002, x1 - x)
  const h = Math.max(0.002, y1 - y)
  if (w * displayW < 8 || h * displayH < 8) throw new Error('选区无效')
  return { x, y, w, h }
}

async function confirmDwgCrop() {
  const box = dwgCropPending.value
  if (!box) {
    message.warning('请先框选区域')
    return
  }
  try {
    const kind = dwgCropKind.value
    const rect = await cropViewportRectNormalized(box)
    exitDwgCropMode(true)
    engWizardRef.value?.applyRegionCapture?.({ kind, rect })
  } catch (e) {
    message.error(e instanceof Error ? e.message : '裁剪失败')
  }
}

function onDwgPointerDown(e: PointerEvent) {
  if (dwgCropMode.value) {
    onDwgCropPointerDown(e)
    return
  }
  if (!dwgHand.value || !hasDwgPreviewMedia.value) return
  if (e.button !== 0) return
  e.preventDefault()
  dwgPanning.value = true
  dwgPanLastX = e.clientX
  dwgPanLastY = e.clientY
  ;(e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId)
}

function onDwgPointerMove(e: PointerEvent) {
  if (dwgCropMode.value) {
    onDwgCropPointerMove(e)
    return
  }
  if (!dwgPanning.value) return
  e.preventDefault()
  const dx = e.clientX - dwgPanLastX
  const dy = e.clientY - dwgPanLastY
  dwgPanLastX = e.clientX
  dwgPanLastY = e.clientY
  if (dx === 0 && dy === 0) return
  dwgOffsetX.value += dx
  dwgOffsetY.value += dy
  dwgUserAdjusted = true
}
/** 进入任务恢复中：抑制步骤误写回、弱化 toast */
const restoring = ref(false)

/** 恢复遮罩：已用时 + 轮播安抚文案（线上大文件较慢） */
const HYDRATE_TIPS = [
  '正在从服务器取回客户图纸与三维模型，文件较大时会稍慢一些',
  '加载完成后会回到您上次离开的步骤，请稍候',
  '网络较慢时可能需要一两分钟，请勿关闭或刷新页面',
  '恢复期间页面暂不可操作，完成后即可继续编辑'
] as const
const hydrateElapsedMs = ref(0)
const hydrateTipIndex = ref(0)
let hydratePerfAnchor = 0
let hydrateElapsedTimer = 0
let hydrateTipTimer = 0

function formatHydrateElapsed(ms: number): string {
  const sec = Math.max(0, Math.floor(ms / 1000))
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

const hydrateElapsedText = computed(() => formatHydrateElapsed(hydrateElapsedMs.value))
const hydrateTip = computed(() => HYDRATE_TIPS[hydrateTipIndex.value % HYDRATE_TIPS.length])
/** 未知总时长：用时越久越接近 92%，避免假固定百分比 */
const hydratePercent = computed(() => {
  const sec = hydrateElapsedMs.value / 1000
  return Math.min(92, Math.round(22 + 70 * (1 - Math.exp(-sec / 28))))
})

function stopHydrateUi() {
  if (hydrateElapsedTimer) {
    window.clearInterval(hydrateElapsedTimer)
    hydrateElapsedTimer = 0
  }
  if (hydrateTipTimer) {
    window.clearInterval(hydrateTipTimer)
    hydrateTipTimer = 0
  }
}

function startHydrateUi() {
  stopHydrateUi()
  hydratePerfAnchor = performance.now()
  hydrateElapsedMs.value = 0
  hydrateTipIndex.value = 0
  hydrateElapsedTimer = window.setInterval(() => {
    hydrateElapsedMs.value = Math.max(0, Math.floor(performance.now() - hydratePerfAnchor))
  }, 250)
  hydrateTipTimer = window.setInterval(() => {
    hydrateTipIndex.value += 1
  }, 4500)
}

watch(restoring, (on) => {
  if (on) startHydrateUi()
  else stopHydrateUi()
})

let solidFrame = 0
let solidRenderer: THREE.WebGLRenderer | null = null
let solidControls: SolidTurntable | null = null
let solidCamera: THREE.PerspectiveCamera | null = null
let solidResize: ResizeObserver | null = null
let solidMeshes: THREE.Mesh[] = []
let solidLookCenter: THREE.Vector3 | null = null
let solidViewSpan = 1
let solidStackAxis = 2
let stackPreviewTimer = 0

type SolidTurntable = {
  target: THREE.Vector3
  enabled: boolean
  update: () => void
  dispose: () => void
  addEventListener: (type: 'end', fn: () => void) => void
  removeEventListener: (type: 'end', fn: () => void) => void
}

/** 层次旁注是否填齐（与 StackExplodeEditor 保存门禁一致） */
function isStackCalloutReady(c: {
  calloutNo?: string
  material?: string
  color?: string
}) {
  const no = (c.calloutNo || '').trim()
  const mat = (c.material || '').trim()
  const col = (c.color || '').trim()
  return !!no && !!mat && mat !== UNLABELED && !!col && col !== UNLABELED
}

/** 仅②层次步骤展示工厂分层，避免①及之后抽屉叠着上一页控件 */
const showFactoryPanel = computed(
  () => engWizardStep.value === 2 && !!stepResult.value && factoryLayers.value.length > 0
)

const previewPickSelectedId = computed(() =>
  factoryListMode.value === 'body' ? selectedBodyId.value : selectedLayerId.value
)

const previewPickRows = computed(() => {
  if (factoryListMode.value === 'body') {
    return factoryBodies.value.map((row) => ({
      id: row.id,
      color: row.color,
      label: `${row.id} · ${row.layerId} · 厚 ${row.thickness}`
    }))
  }
  return factoryLayers.value.map((row) => ({
    id: row.id,
    color: row.color,
    label: `${row.id} · ${row.meshCount}件 · 厚 ${row.thickness}`
  }))
})

/** 最近一次层次旁注进度（保存/hydrate 回写）；无旁注时用实体数作 total 提示「未标注」 */
const stackCalloutProgress = ref<{ done: number; total: number }>({ done: 0, total: 0 })

function refreshStackCalloutProgress(
  callouts: { calloutNo?: string; material?: string; color?: string }[] | null | undefined,
  fallbackTotal?: number
) {
  const list = Array.isArray(callouts) ? callouts : []
  if (list.length) {
    let done = 0
    for (const c of list) {
      if (isStackCalloutReady(c)) done += 1
    }
    stackCalloutProgress.value = { done, total: list.length }
    return
  }
  const total = Math.max(0, Number(fallbackTotal) || factoryBodies.value.length || 0)
  stackCalloutProgress.value = { done: 0, total }
}

const annotProgress = computed(() => {
  const p = stackCalloutProgress.value
  if (p.total > 0) return p
  // 尚未打开过层次编辑：按实体数提示待标注
  const total = factoryBodies.value.length
  return { done: 0, total }
})

const annotComplete = computed(
  () => annotProgress.value.total > 0 && annotProgress.value.done >= annotProgress.value.total
)

function fact(value?: string | null) {
  const t = (value || '').trim()
  return t || UNLABELED
}

function formatLen(value: number) {
  return Number.isFinite(value) ? value.toFixed(2) : '—'
}

/** 近白/低饱和色改用调色板，保证浅底预览可见 */
function resolveMeshColor(mesh: OcctMesh, index: number): THREE.Color {
  const fallback = new THREE.Color(BODY_PALETTE[index % BODY_PALETTE.length])
  if (!mesh.color || mesh.color.length < 3) return fallback
  const color = new THREE.Color(mesh.color[0], mesh.color[1], mesh.color[2])
  const hsl = { h: 0, s: 0, l: 0 }
  color.getHSL(hsl)
  if (hsl.l > 0.82 || hsl.s < 0.12) return fallback
  return color
}

function resetFactory() {
  factoryLayers.value = []
  factoryBodies.value = []
  selectedLayerId.value = ''
  selectedBodyId.value = ''
  factoryListMode.value = 'body'
  explodeGap.value = 0
  solidMeshes = []
  stackExplodeOpen.value = false
  stackExplodePlates.value = []
}

function createSolidTurntable(camera: THREE.PerspectiveCamera, dom: HTMLElement): SolidTurntable {
  const target = new THREE.Vector3()
  const offset = new THREE.Vector3()
  const panDelta = new THREE.Vector3()
  const camRight = new THREE.Vector3()
  const camUp = new THREE.Vector3()
  const camForward = new THREE.Vector3()
  const qPitch = new THREE.Quaternion()
  const qYaw = new THREE.Quaternion()
  const qDelta = new THREE.Quaternion()
  const endListeners = new Set<() => void>()
  let enabled = true
  let dragging: 'rotate' | 'pan' | 'dolly' | null = null
  let lastX = 0
  let lastY = 0
  let activePointer: number | null = null

  function rotateBy(dx: number, dy: number) {
    camera.updateMatrixWorld(true)
    camera.matrixWorld.extractBasis(camRight, camUp, camForward)
    qPitch.setFromAxisAngle(camRight, -dy * SOLID_ROTATE_RAD_PER_PX)
    qYaw.setFromAxisAngle(camUp, -dx * SOLID_ROTATE_RAD_PER_PX)
    qDelta.copy(qYaw).multiply(qPitch)
    offset.copy(camera.position).sub(target)
    offset.applyQuaternion(qDelta)
    camera.position.copy(target).add(offset)
    camera.quaternion.premultiply(qDelta)
    camera.up.set(0, 1, 0).applyQuaternion(camera.quaternion).normalize()
  }

  function panBy(dx: number, dy: number) {
    const dist = Math.max(camera.position.distanceTo(target), 1)
    camera.updateMatrixWorld(true)
    camera.matrixWorld.extractBasis(camRight, camUp, camForward)
    panDelta
      .copy(camRight)
      .multiplyScalar(-dx * dist * SOLID_PAN_FACTOR)
      .addScaledVector(camUp, dy * dist * SOLID_PAN_FACTOR)
    camera.position.add(panDelta)
    target.add(panDelta)
  }

  function dollyBy(deltaY: number) {
    offset.copy(camera.position).sub(target)
    const factor = Math.exp(deltaY * SOLID_ZOOM_WHEEL)
    offset.multiplyScalar(factor)
    if (offset.length() < 1e-4) return
    camera.position.copy(target).add(offset)
  }

  function modeForButton(button: number): 'rotate' | 'pan' | 'dolly' | null {
    if (button === 1) return 'dolly'
    if (solidHand.value) {
      if (button === 0) return 'pan'
      if (button === 2) return 'rotate'
      return null
    }
    if (button === 0) return 'rotate'
    if (button === 2) return 'pan'
    return null
  }

  function onPointerDown(event: PointerEvent) {
    if (!enabled) return
    const mode = modeForButton(event.button)
    if (!mode) return
    event.preventDefault()
    dragging = mode
    activePointer = event.pointerId
    lastX = event.clientX
    lastY = event.clientY
    dom.setPointerCapture(event.pointerId)
  }

  function onPointerMove(event: PointerEvent) {
    if (!enabled || dragging == null || event.pointerId !== activePointer) return
    const dx = event.clientX - lastX
    const dy = event.clientY - lastY
    lastX = event.clientX
    lastY = event.clientY
    if (dragging === 'rotate') rotateBy(dx, dy)
    else if (dragging === 'pan') panBy(dx, dy)
    else dollyBy(dy)
  }

  function endDrag(event: PointerEvent) {
    if (event.pointerId !== activePointer) return
    const wasDragging = dragging != null
    dragging = null
    activePointer = null
    try {
      dom.releasePointerCapture(event.pointerId)
    } catch {
      /* ignore */
    }
    if (wasDragging) {
      for (const fn of endListeners) fn()
    }
  }

  function onWheel(event: WheelEvent) {
    if (!enabled) return
    event.preventDefault()
    dollyBy(event.deltaY)
    for (const fn of endListeners) fn()
  }

  function onContextMenu(event: Event) {
    event.preventDefault()
  }

  dom.addEventListener('pointerdown', onPointerDown)
  dom.addEventListener('pointermove', onPointerMove)
  dom.addEventListener('pointerup', endDrag)
  dom.addEventListener('pointercancel', endDrag)
  dom.addEventListener('wheel', onWheel, { passive: false })
  dom.addEventListener('contextmenu', onContextMenu)

  return {
    target,
    get enabled() {
      return enabled
    },
    set enabled(v: boolean) {
      enabled = v
    },
    update() {},
    dispose() {
      dom.removeEventListener('pointerdown', onPointerDown)
      dom.removeEventListener('pointermove', onPointerMove)
      dom.removeEventListener('pointerup', endDrag)
      dom.removeEventListener('pointercancel', endDrag)
      dom.removeEventListener('wheel', onWheel)
      dom.removeEventListener('contextmenu', onContextMenu)
      endListeners.clear()
      dragging = null
      activePointer = null
    },
    addEventListener(type, fn) {
      if (type === 'end') endListeners.add(fn)
    },
    removeEventListener(type, fn) {
      if (type === 'end') endListeners.delete(fn)
    }
  }
}

function stackViewPosition(center: THREE.Vector3, dist: number): THREE.Vector3 {
  const ce = Math.cos(STACK_VIEW_ELEV_RAD)
  const se = Math.sin(STACK_VIEW_ELEV_RAD)
  const ca = Math.cos(STACK_VIEW_AZIM_RAD)
  const sa = Math.sin(STACK_VIEW_AZIM_RAD)
  return new THREE.Vector3(
    center.x + dist * ce * ca,
    center.y + dist * ce * sa,
    center.z + dist * se
  )
}

function followSolidBounds() {
  if (!solidMeshes.length || !solidControls || !solidCamera) return
  const box = new THREE.Box3()
  for (const mesh of solidMeshes) box.expandByObject(mesh)
  if (box.isEmpty()) return
  const center = box.getCenter(new THREE.Vector3())
  const prev = solidLookCenter
  if (prev) {
    const dx = center.x - prev.x
    const dy = center.y - prev.y
    const dz = center.z - prev.z
    solidControls.target.x += dx
    solidControls.target.y += dy
    solidControls.target.z += dz
    solidCamera.position.x += dx
    solidCamera.position.y += dy
    solidCamera.position.z += dz
  } else {
    solidControls.target.copy(center)
  }
  solidLookCenter = center
  solidControls.update()
}

function clusterAssemblyLayers(meshes: THREE.Mesh[]): THREE.Mesh[][] {
  const n = meshes.length
  if (!n) return []
  const parent = Array.from({ length: n }, (_, i) => i)
  const find = (i: number): number => {
    let x = i
    while (parent[x] !== x) {
      parent[x] = parent[parent[x]]
      x = parent[x]
    }
    return x
  }
  const unite = (a: number, b: number) => {
    const ra = find(a)
    const rb = find(b)
    if (ra !== rb) parent[ra] = rb
  }
  for (let i = 0; i < n; i++) {
    const zi0 = Number(meshes[i].userData.zMin)
    const zi1 = Number(meshes[i].userData.zMax)
    for (let j = i + 1; j < n; j++) {
      const zj0 = Number(meshes[j].userData.zMin)
      const zj1 = Number(meshes[j].userData.zMax)
      const sameBottom = Math.abs(zi0 - zj0) <= STACK_Z_EPS
      const overlap = zi0 < zj1 - STACK_Z_EPS && zj0 < zi1 - STACK_Z_EPS
      if (sameBottom || overlap) unite(i, j)
    }
  }
  const groups = new Map<number, THREE.Mesh[]>()
  for (let i = 0; i < n; i++) {
    const root = find(i)
    const list = groups.get(root) ?? []
    list.push(meshes[i])
    groups.set(root, list)
  }
  return [...groups.values()].sort((a, b) => {
    const za = Math.min(...a.map((m) => Number(m.userData.zMin)))
    const zb = Math.min(...b.map((m) => Number(m.userData.zMin)))
    return za - zb
  })
}

/** 叠层面宽（垂直于展开轴），用几何 bbox，不受当前展开位移影响 */
function measureStackFaceSize(): number {
  if (!solidMeshes.length) return 10
  const axis = solidStackAxis
  let minA = Infinity
  let maxA = -Infinity
  let minB = Infinity
  let maxB = -Infinity
  const aAxis = (axis + 1) % 3
  const bAxis = (axis + 2) % 3
  for (const mesh of solidMeshes) {
    const geom = mesh.geometry as THREE.BufferGeometry
    if (!geom.boundingBox) geom.computeBoundingBox()
    const b = geom.boundingBox
    if (!b) continue
    minA = Math.min(minA, b.min.getComponent(aAxis))
    maxA = Math.max(maxA, b.max.getComponent(aAxis))
    minB = Math.min(minB, b.min.getComponent(bAxis))
    maxB = Math.max(maxB, b.max.getComponent(bAxis))
  }
  if (!(minA < maxA) || !(minB < maxB)) return 10
  return Math.max(maxA - minA, maxB - minB, 1)
}

function refreshExplodeGapMax() {
  const face = measureStackFaceSize()
  const dyn = Number((face * EXPLODE_GAP_MAX_FACE_RATIO).toFixed(2))
  explodeGapMax.value = Math.min(EXPLODE_GAP_MAX_HARD, Math.max(EXPLODE_GAP_MAX_DEFAULT, dyn))
}

/** 推荐层间距：约面宽 6%，保证透视投影后片与片能分开 */
function recommendedExplodeGap(): number {
  const face = measureStackFaceSize()
  refreshExplodeGapMax()
  const raw = face * EXPLODE_GAP_FACE_RATIO
  return Math.min(explodeGapMax.value, Math.max(EXPLODE_GAP_RECOMMEND_MIN, Number(raw.toFixed(2))))
}

/** 按原始 zMin（容差内）收成共面桶，再按 zMin 升序 */
function clusterMeshesByZMin(meshes: THREE.Mesh[]): THREE.Mesh[][] {
  const sorted = [...meshes].sort((a, b) => Number(a.userData.zMin) - Number(b.userData.zMin))
  const buckets: THREE.Mesh[][] = []
  for (const mesh of sorted) {
    const z = Number(mesh.userData.zMin)
    const last = buckets[buckets.length - 1]
    if (last && Math.abs(Number(last[0].userData.zMin) - z) <= STACK_Z_EPS) {
      last.push(mesh)
    } else {
      buckets.push([mesh])
    }
  }
  return buckets
}

/**
 * 进层次爆炸编辑前：间隙明显偏小则自动加大，避免 DXF 片叠成一团。
 * @returns 是否自动加大过
 */
function ensureExplodeGapForStackEdit(): boolean {
  const rec = recommendedExplodeGap()
  const cur = Math.max(0, Number(explodeGap.value) || 0)
  if (cur >= rec * EXPLODE_AUTO_BOOST_RATIO) return false
  explodeGap.value = rec
  return true
}

/**
 * Z 展开：仅沿叠层轴平移，XY/旋转/缩放不变。
 * 按料层 = 同 layerId 同偏移；按实体 = 同 zMin（容差）共面，再按不同 zMin 平面整档拉开。
 */
function applyExplode() {
  const maxGap = explodeGapMax.value
  const gap = Math.min(maxGap, Math.max(0, Number(explodeGap.value) || 0))
  if (explodeGap.value !== gap) explodeGap.value = gap
  const axis = solidStackAxis
  for (const mesh of solidMeshes) mesh.position.set(0, 0, 0)
  if (gap <= 0 || !solidMeshes.length) {
    followSolidBounds()
    scheduleStackExplodePreview()
    return
  }
  if (factoryListMode.value === 'body') {
    const planes = clusterMeshesByZMin(solidMeshes)
    planes.forEach((members, index) => {
      const offset = index * gap
      for (const mesh of members) mesh.position.setComponent(axis, offset)
    })
  } else if (factoryLayers.value.length) {
    const ranked = factoryLayers.value
      .map((row) => {
        const members = solidMeshes.filter((mesh) => mesh.userData.layerId === row.id)
        if (!members.length) return null
        const zMin = Math.min(...members.map((m) => Number(m.userData.zMin)))
        return { members, zMin }
      })
      .filter((row): row is { members: THREE.Mesh[]; zMin: number } => !!row)
      .sort((a, b) => a.zMin - b.zMin)
    ranked.forEach((layer, index) => {
      const offset = index * gap
      for (const mesh of layer.members) mesh.position.setComponent(axis, offset)
    })
  }
  followSolidBounds()
  scheduleStackExplodePreview()
}

function layerEdgeColor(layerId: string): THREE.Color {
  const idx = Math.max(0, factoryLayers.value.findIndex((row) => row.id === layerId))
  const hue = (idx * 47) % 360
  return new THREE.Color(`hsl(${hue}, 72%, 38%)`)
}

function applySelectionHighlight() {
  const bodyMode = factoryListMode.value === 'body'
  const hasPick = bodyMode ? !!selectedBodyId.value : !!selectedLayerId.value
  const pickedLayerId = bodyMode
    ? (factoryBodies.value.find((b) => b.id === selectedBodyId.value)?.layerId ?? selectedLayerId.value)
    : selectedLayerId.value
  for (const mesh of solidMeshes) {
    const mat = mesh.material as THREE.MeshPhysicalMaterial
    const layerId = String(mesh.userData.layerId ?? '')
    const isPick = bodyMode
      ? mesh.userData.bodyId === selectedBodyId.value
      : mesh.userData.layerId === selectedLayerId.value
    const sameLayer = bodyMode && hasPick && layerId === pickedLayerId
    const on = !hasPick ? true : bodyMode ? isPick || sameLayer : isPick
    mat.transparent = true
    if (!hasPick) {
      mat.opacity = 1
      mat.emissive = new THREE.Color(0x000000)
      mat.emissiveIntensity = 0
    } else if (bodyMode && isPick) {
      mat.opacity = 1
      mat.emissive = new THREE.Color(0xf97316)
      mat.emissiveIntensity = 0.62
    } else if (bodyMode && sameLayer) {
      mat.opacity = 0.78
      mat.emissive = new THREE.Color(0xfbbf24)
      mat.emissiveIntensity = 0.22
    } else if (on) {
      mat.opacity = 1
      mat.emissive = new THREE.Color(0xf97316)
      mat.emissiveIntensity = 0.45
    } else {
      mat.opacity = bodyMode ? 0.1 : 0.22
      mat.emissive = new THREE.Color(0x000000)
      mat.emissiveIntensity = 0
    }
    mat.needsUpdate = true
    for (const child of mesh.children) {
      if (!(child instanceof THREE.LineSegments)) continue
      const lineMat = child.material as THREE.LineBasicMaterial
      if (bodyMode && isPick) {
        lineMat.color = new THREE.Color(0xea580c)
        lineMat.opacity = 0.95
      } else if (bodyMode && sameLayer) {
        lineMat.color = new THREE.Color(0xd97706)
        lineMat.opacity = 0.8
      } else if (!hasPick && bodyMode) {
        lineMat.color = layerEdgeColor(layerId)
        lineMat.opacity = 0.72
      } else {
        lineMat.color = new THREE.Color(0x1e293b)
        lineMat.opacity = on ? 0.68 : 0.06
      }
      lineMat.needsUpdate = true
    }
  }
}

function disposeSolid(opts?: { deferHeavy?: boolean }) {
  const deferHeavy = !!opts?.deferHeavy
  solidMeshes = []
  solidLookCenter = null
  solidViewSpan = 1
  if (stackPreviewTimer) {
    window.clearTimeout(stackPreviewTimer)
    stackPreviewTimer = 0
  }
  if (solidFrame) {
    cancelAnimationFrame(solidFrame)
    solidFrame = 0
  }
  solidResize?.disconnect()
  solidResize = null
  solidControls?.dispose()
  solidControls = null
  solidCamera = null
  const renderer = solidRenderer
  solidRenderer = null
  if (renderer) {
    // 立刻摘掉画布，路由可先走；WebGL dispose 可延后
    try {
      renderer.domElement.remove()
    } catch {
      /* ignore */
    }
    const runDispose = () => {
      try {
        renderer.dispose()
      } catch {
        /* ignore */
      }
    }
    if (deferHeavy) {
      const ric = (window as Window & {
        requestIdleCallback?: (cb: () => void, opts?: { timeout: number }) => number
      }).requestIdleCallback
      if (typeof ric === 'function') {
        ric(runDispose, { timeout: 2000 })
      } else {
        window.setTimeout(runDispose, 0)
      }
    } else {
      runDispose()
    }
  }
  const host = solidHost.value
  if (host) {
    host.style.minWidth = ''
    host.style.minHeight = ''
  }
  solidReady.value = false
  wireVisible.value = false
  void nextTick(paintStackExplodePreview)
}

/** 后端线框兜底：OCCT 网格失败时仍可看见轮廓 */
function paintWire() {
  const canvas = wireCanvas.value
  const edges = stepResult.value?.edges
  if (!canvas || !edges?.length) return false
  const parent = canvas.parentElement
  const w = Math.max(320, parent?.clientWidth || 640)
  const h = Math.max(280, parent?.clientHeight || 360)
  if (canvas.width !== w || canvas.height !== h) {
    canvas.width = w
    canvas.height = h
  }
  const ctx = canvas.getContext('2d')
  if (!ctx) return false
  ctx.clearRect(0, 0, w, h)
  ctx.fillStyle = '#f8fafc'
  ctx.fillRect(0, 0, w, h)
  const yaw = Math.PI / 5
  const pitch = Math.PI / 7
  const project = (x: number, y: number, z: number): [number, number] => {
    const cy = Math.cos(yaw)
    const sy = Math.sin(yaw)
    const x1 = x * cy + z * sy
    const z1 = -x * sy + z * cy
    const cx = Math.cos(pitch)
    const sx = Math.sin(pitch)
    return [x1, y * cx - z1 * sx]
  }
  const flat: number[] = []
  for (const edge of edges) {
    if (!edge || edge.length < 6) continue
    const a = project(edge[0], edge[1], edge[2])
    const b = project(edge[3], edge[4], edge[5])
    flat.push(a[0], a[1], b[0], b[1])
  }
  if (!flat.length) return false
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (let i = 0; i < flat.length; i += 2) {
    minX = Math.min(minX, flat[i])
    maxX = Math.max(maxX, flat[i])
    minY = Math.min(minY, flat[i + 1])
    maxY = Math.max(maxY, flat[i + 1])
  }
  const span = Math.max(maxX - minX, maxY - minY, 1)
  const scale = (Math.min(w, h) - 28) / span
  const cx = (minX + maxX) / 2
  const cy = (minY + maxY) / 2
  ctx.strokeStyle = '#1e293b'
  ctx.lineWidth = 1
  ctx.beginPath()
  for (let i = 0; i < flat.length; i += 4) {
    ctx.moveTo(w / 2 + (flat[i] - cx) * scale, h / 2 - (flat[i + 1] - cy) * scale)
    ctx.lineTo(w / 2 + (flat[i + 2] - cx) * scale, h / 2 - (flat[i + 3] - cy) * scale)
  }
  ctx.stroke()
  wireVisible.value = true
  return true
}

function resizeSolid() {
  const host = solidHost.value
  if (!host || !solidRenderer || !solidCamera) return
  const width = host.clientWidth
  const height = host.clientHeight
  if (width < 8 || height < 8) return
  solidRenderer.setSize(width, height)
  solidCamera.aspect = width / height
  solidCamera.updateProjectionMatrix()
}

/** 先显示 host，再等 flex 给出真实宽高；超时则用兜底尺寸，避免整页挂掉 */
async function waitForHostSize(maxFrames = 90): Promise<{ width: number; height: number }> {
  for (let i = 0; i < maxFrames; i++) {
    const host = solidHost.value
    if (host && host.clientWidth >= 8 && host.clientHeight >= 8) {
      return { width: host.clientWidth, height: host.clientHeight }
    }
    await new Promise<void>((r) => requestAnimationFrame(() => r()))
  }
  const host = solidHost.value
  const parent = host?.parentElement
  const width = Math.max(host?.clientWidth || 0, parent?.clientWidth || 0, 640)
  const height = Math.max(host?.clientHeight || 0, parent?.clientHeight || 0, 360)
  return { width, height }
}

function scheduleSolidResize() {
  resizeSolid()
  requestAnimationFrame(() => {
    resizeSolid()
    requestAnimationFrame(() => resizeSolid())
  })
  window.setTimeout(() => resizeSolid(), 50)
  window.setTimeout(() => resizeSolid(), 200)
}

async function showSolid(file: File) {
  try {
    // 与 drawing-reader-ai 完全一致（依赖 Vite 预构建 CJS→ESM）
    const occtimportjs = (await import('occt-import-js')).default as (
      opts: { locateFile: (name: string) => string }
    ) => Promise<OcctModule>
    if (typeof occtimportjs !== 'function') {
      // 兼容偶发的双重 default
      const alt = (occtimportjs as unknown as { default?: unknown })?.default
      if (typeof alt !== 'function') {
        throw new Error('occt-import-js 未正确预构建，请停掉前端后执行：npx vite --force')
      }
      return await showSolidWithFactory(alt as typeof occtimportjs, file)
    }
    return await showSolidWithFactory(occtimportjs, file)
  } catch (e) {
    disposeSolid()
    lastSolidError.value = e instanceof Error ? e.message : '实体预览失败'
    console.error('[eng-solid] showSolid failed', e)
    return false
  }
}

async function showSolidWithFactory(
  occtimportjs: (opts: { locateFile: (name: string) => string }) => Promise<OcctModule>,
  file: File
) {
  const wasmUrl = (await import('occt-import-js/dist/occt-import-js.wasm?url')).default as string
  const occt = await occtimportjs({
    locateFile: (name: string) => (name.endsWith('.wasm') ? wasmUrl : name)
  })
  if (!occt?.ReadStepFile) {
    throw new Error('OCCT WASM 未就绪')
  }
  const result = occt.ReadStepFile(new Uint8Array(await file.arrayBuffer()), {
    linearUnit: 'millimeter',
    linearDeflectionType: 'bounding_box_ratio',
    linearDeflection: 0.002,
    angularDeflection: 0.5
  })
  if (!result?.success || !result.meshes?.length) {
    throw new Error('STEP 实体网格为空')
  }
  // 先亮起 host，才能量到非 0 尺寸（与 drawing-reader-ai 一致）
  wireVisible.value = false
  solidReady.value = true
  await nextTick()
  const size = await waitForHostSize()
  const shown = mountSolid(result.meshes, size.width, size.height)
  if (!shown) {
    disposeSolid()
    throw new Error('三维视口未就绪')
  }
  scheduleSolidResize()
  return true
}

function mountSolid(meshes: OcctMesh[], forceW?: number, forceH?: number) {
  const host = solidHost.value
  if (!host) return false
  const width = Math.max(forceW || 0, host.clientWidth, 8)
  const height = Math.max(forceH || 0, host.clientHeight, 8)
  if (width < 8 || height < 8) return false
  // 若布局仍塌缩，先写死 host 最小尺寸，保证 WebGL 画布可见
  if (host.clientWidth < 8 || host.clientHeight < 8) {
    host.style.minWidth = `${width}px`
    host.style.minHeight = `${height}px`
  }
  solidMeshes = []
  if (solidFrame) {
    cancelAnimationFrame(solidFrame)
    solidFrame = 0
  }
  solidControls?.dispose()
  solidControls = null
  solidRenderer?.dispose()
  solidRenderer?.domElement.remove()
  solidRenderer = null
  // 不透明清屏，避免 alpha 合成导致「已就绪但一片白」
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
  renderer.setSize(width, height)
  renderer.setClearColor(0xe8eef6, 1)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  renderer.toneMappingExposure = 1.12
  host.appendChild(renderer.domElement)
  const scene = new THREE.Scene()
  const group = new THREE.Group()
  meshes.forEach((mesh, meshIndex) => {
    const geometry = new THREE.BufferGeometry()
    geometry.setAttribute('position', new THREE.Float32BufferAttribute(mesh.attributes.position.array, 3))
    if (mesh.attributes.normal) {
      geometry.setAttribute('normal', new THREE.Float32BufferAttribute(mesh.attributes.normal.array, 3))
    } else {
      geometry.computeVertexNormals()
    }
    geometry.setIndex(Array.from(mesh.index.array))
    const color = resolveMeshColor(mesh, meshIndex)
    const item = new THREE.Mesh(
      geometry,
      new THREE.MeshPhysicalMaterial({
        color,
        metalness: 0.05,
        roughness: 0.32,
        clearcoat: 0.4,
        clearcoatRoughness: 0.25,
        reflectivity: 0.4,
        flatShading: false,
        side: THREE.DoubleSide
      })
    )
    geometry.computeBoundingBox()
    item.userData.color = `#${color.getHexString().toUpperCase()}`
    item.userData.centroid = geometry.boundingBox?.getCenter(new THREE.Vector3()) ?? new THREE.Vector3()
    const edgeGeom = new THREE.EdgesGeometry(geometry, 18)
    const edge = new THREE.LineSegments(
      edgeGeom,
      new THREE.LineBasicMaterial({
        color: 0x1e293b,
        transparent: true,
        opacity: 0.68,
        depthWrite: false
      })
    )
    edge.renderOrder = 2
    item.add(edge)
    group.add(item)
    solidMeshes.push(item)
  })
  const preBox = new THREE.Box3()
  for (const mesh of solidMeshes) preBox.expandByObject(mesh)
  const preSize = preBox.getSize(new THREE.Vector3())
  solidStackAxis = preSize.z <= preSize.x && preSize.z <= preSize.y ? 2 : preSize.y <= preSize.x ? 1 : 0
  const axis = solidStackAxis
  for (const mesh of solidMeshes) {
    const box = (mesh.geometry as THREE.BufferGeometry).boundingBox
    const zMin = box ? box.min.getComponent(axis) : 0
    const zMax = box ? box.max.getComponent(axis) : 0
    const thick = Math.max(0, zMax - zMin)
    mesh.userData.zMin = zMin
    mesh.userData.zMax = zMax
    mesh.userData.thickness = Number(thick.toFixed(3))
  }
  refreshExplodeGapMax()
  if (explodeGap.value > explodeGapMax.value) explodeGap.value = explodeGapMax.value
  const ranked = clusterAssemblyLayers(solidMeshes)
  factoryLayers.value = ranked.map((members, index) => {
    const id = `L${index + 1}`
    const layerZMin = Math.min(...members.map((m) => Number(m.userData.zMin)))
    const thicknesses = members.map((m) => Number(m.userData.thickness))
    const thickness = Math.max(...thicknesses)
    members.forEach((mesh) => {
      mesh.userData.layerId = id
      mesh.userData.stackOrder = index
      mesh.userData.layerZMin = layerZMin
    })
    return {
      id,
      color: String(members[0].userData.color),
      thickness,
      meshCount: members.length,
      partNo: '',
      material: UNLABELED,
      knife: 'CUT',
      halfCutRemain: '',
      face: '未指定',
      note: '',
      faceNotes: {},
      flatBox: null,
      flatLayers: [],
      flatEntities: [],
      stepSolidRef: '',
      noFlatContour: false
    }
  })
  factoryBodies.value = solidMeshes.map((mesh, index) => {
    const solidIds = stepResult.value?.solidEntityIds ?? []
    const ref = solidIds.length === solidMeshes.length ? String(solidIds[index] || '') : ''
    const id = ref ? `S${ref.replace(/^#/, '')}` : `B${index + 1}`
    mesh.userData.bodyId = id
    mesh.userData.stepSolidRef = ref
    return {
      id,
      layerId: String(mesh.userData.layerId),
      color: String(mesh.userData.color),
      thickness: Number(mesh.userData.thickness)
    }
  })
  selectedLayerId.value = factoryLayers.value[0]?.id ?? ''
  selectedBodyId.value = ''
  factoryListMode.value = 'body'
  explodeGap.value = 0
  applySelectionHighlight()
  scene.add(group)
  const box = new THREE.Box3().setFromObject(group)
  const size = box.getSize(new THREE.Vector3())
  const center = box.getCenter(new THREE.Vector3())
  solidLookCenter = center.clone()
  const span = Math.max(size.x, size.y, size.z, 1)
  solidViewSpan = span
  scene.add(new THREE.HemisphereLight(0xf8fafc, 0x94a3b8, 0.42))
  const keyLight = new THREE.DirectionalLight(0xfff4e8, 1.2)
  keyLight.position.set(center.x + span * 0.95, center.y + span * 1.25, center.z + span * 0.65)
  keyLight.target.position.copy(center)
  scene.add(keyLight)
  scene.add(keyLight.target)
  const fillLight = new THREE.DirectionalLight(0xdbe7f5, 0.5)
  fillLight.position.set(center.x - span * 1.15, center.y + span * 0.4, center.z - span * 0.45)
  scene.add(fillLight)
  const rimLight = new THREE.DirectionalLight(0xffffff, 0.62)
  rimLight.position.set(center.x - span * 0.15, center.y + span * 0.15, center.z + span * 1.35)
  scene.add(rimLight)
  const camera = new THREE.PerspectiveCamera(40, width / height, span / 200, span * 30)
  camera.up.set(0, 0, 1)
  camera.position.copy(stackViewPosition(center, span * 2.2))
  camera.lookAt(center)
  const controls = createSolidTurntable(camera, renderer.domElement)
  controls.target.copy(center)
  controls.addEventListener('end', scheduleStackExplodePreview)
  solidControls = controls
  solidRenderer = renderer
  solidCamera = camera
  solidResize?.disconnect()
  solidResize = new ResizeObserver(() => {
    resizeSolid()
    scheduleStackExplodePreview()
  })
  solidResize.observe(host)
  // 同时观察外层，flex 高度晚到时也能触发 resize
  const stageEl = host.parentElement
  if (stageEl) solidResize.observe(stageEl)
  resizeSolid()
  const loop = () => {
    solidFrame = requestAnimationFrame(loop)
    solidControls?.update()
    renderer.render(scene, camera)
  }
  loop()
  void nextTick(scheduleStackExplodePreview)
  return true
}

function acceptStepFile(file: File) {
  if (!/\.(stp|step)$/i.test(file.name)) {
    message.error('三维图纸请上传 stp 或 step')
    return false
  }
  if (file.size > MAX_STEP) {
    message.error('三维图纸超过 80MB')
    return false
  }
  stepFile.value = file
  stepResult.value = null
  resetFactory()
  disposeSolid()
  return true
}

async function runStep(opts?: { silent?: boolean }) {
  if (!stepFile.value) {
    message.warning('请先选择三维图纸')
    return
  }
  const silent = !!opts?.silent
  stepScanning.value = true
  solidHint.value = silent ? '正在恢复三维…' : '正在读取三维图纸'
  lastSolidError.value = ''
  disposeSolid()
  stepResult.value = null
  try {
    stepResult.value = await scanStep(stepFile.value)
    if (stepResult.value?.drawingNo) {
      emit('drawingNo', stepResult.value.drawingNo)
    }
    solidHint.value = silent ? '正在恢复实体预览…' : '正在生成实体预览'
    const shown = await showSolid(stepFile.value)
    if (shown) {
      const n = factoryBodies.value.length || stepResult.value.solidCount || 0
      solidHint.value = '已就绪 · 左键旋转 · 右键平移 · 滚轮缩放 · 可用 Z 展开'
      if (!silent) {
        message.success(n > 0 ? `三维已解析（${n} 实体），可用 Z 展开保存层次` : '三维已解析')
      }
      void nextTick(() => {
        scheduleSolidResize()
        scheduleStackExplodePreview()
      })
    } else {
      await nextTick()
      const wired = paintWire()
      solidHint.value = wired
        ? `线框预览（实体网格失败${lastSolidError.value ? '：' + lastSolidError.value : ''}）· 可重新解析`
        : lastSolidError.value || '实体预览失败，可重新解析'
      if (!silent) {
        if (wired) {
          message.warning('实体网格未生成，已显示线框；层次爆炸需实体网格，请重试解析')
        } else {
          message.error(lastSolidError.value || '三维实体预览失败')
        }
      }
    }
  } catch (e) {
    stepResult.value = null
    if (!silent) {
      message.error(e instanceof Error ? e.message : '三维图纸读取失败')
    } else {
      solidHint.value = e instanceof Error ? e.message : '三维恢复失败，请重新导入 STP'
    }
  } finally {
    stepScanning.value = false
  }
}

async function onEngPickStep(file: File) {
  if (!acceptStepFile(file)) return
  if (props.taskId) {
    try {
      const patched = await taskApi.uploadStep(props.taskId, file)
      emit('taskPatch', {
        stepFileName: patched.stepFileName,
        hasStepFile: patched.hasStepFile
      })
      if (!patched.hasStepFile) {
        stepFile.value = null
        message.error('STP 未落到任务目录，请重试上传后再保存层次')
        return
      }
    } catch {
      // 任务场景：上传失败则不继续本机解析，避免「层次勾了、重进无模型」
      stepFile.value = null
      message.error('STP 未能保存到任务，请检查网络后重试上传')
      return
    }
  }
  void runStep()
}

async function onDwgPicked(file: File) {
  if (!props.taskId) return
  try {
    const patched = await taskApi.uploadDwg(props.taskId, file)
    emit('taskPatch', { hasCustomerDwg: patched.hasCustomerDwg })
  } catch {
    message.warning('DWG 已用于本机合成，但未成功保存到任务（重进可能丢失）')
  }
}

function onStackBound(stackAssetId: string) {
  emit('stackBound', stackAssetId)
}

function onStackSkipChange(skipped: boolean) {
  emit('taskPatch', {
    stackSkipped: skipped,
    stackAssetId: skipped ? null : undefined
  })
}

function onExtractSkipChange(skipped: boolean) {
  emit('taskPatch', { extractSkipped: skipped })
}

function focusEngSolid() {
  void nextTick(() => scheduleSolidResize())
}

function onEngWizardStepChange(n: number) {
  engWizardStep.value = n
  // 恢复过程中不向上冒泡，避免把任务 currentStep 冲掉
  if (restoring.value) return
  emit('stepChange', n)
  // ①↔② 分栏切换后重算三维尺寸，避免左栏留白/画布错位
  void nextTick(() => scheduleSolidResize())
  if (n === 1) focusEngSolid()
}

function dwgPreviewMediaKey(p: ConvertPreview | null): string {
  if (!p) return ''
  const png = p.previewPngBase64 || ''
  const svg = p.previewSvg || ''
  const svgs = p.previewSvgs || []
  // 用长度 + 头尾指纹，避免整图 base64 比较过重
  const pngFp = png ? `${png.length}:${png.slice(0, 48)}:${png.slice(-24)}` : ''
  const svgFp = svg ? `${svg.length}:${svg.slice(0, 32)}` : ''
  return `${pngFp}|${svgFp}|${svgs.length}|${svgs.map((s) => s?.length || 0).join(',')}`
}

let lastDwgPreviewMediaKey = ''

function onExtractPreview(p: ConvertPreview | null) {
  const key = dwgPreviewMediaKey(p)
  const mediaChanged = key !== lastDwgPreviewMediaKey
  extractPreview.value = p
  // 仅预览图/SVG 真变了才适应窗口；sheetReady/写入 NOTE 不冲掉缩放平移
  if (!mediaChanged) return
  lastDwgPreviewMediaKey = key
  dwgPageIndex.value = 0
  dwgUserAdjusted = false
  scheduleFitDwgToViewport()
}

function onExtractBusy(busy: boolean) {
  extractBusy.value = busy
}

function onComposeBoard(payload: ComposeBoardPayload | null) {
  composeBoard.value = payload
}

function onComposeBoardGoStep(n: 1 | 2 | 3 | 4) {
  engWizardRef.value?.goStep?.(n)
}

/** 进入进行中任务：恢复 STP 三维 + 层次资产 + 客户 DWG + 步骤 */
async function hydrateFromTask() {
  if (!props.taskId) {
    restoring.value = false
    emit('stepChange', engWizardStep.value)
    return
  }
  const needRestore = !!(
    props.hasStepFile ||
    props.stackAssetId ||
    props.hasCustomerDwg ||
    props.stackSkipped ||
    props.extractSkipped
  )
  if (!needRestore) {
    // 无文件可恢复但仍可能停在⑤：补拉终稿并放开 hold
    const initStep = Number(props.initialStep || engWizardStep.value || 1) || 1
    if (initStep >= 5 || engWizardStep.value >= 5) {
      await engWizardRef.value?.restoreProgress({ step: initStep })
    }
    restoring.value = false
    emit('stepChange', engWizardStep.value)
    return
  }
  restoring.value = true
  solidHint.value = '正在为您恢复任务，请稍候…'
  try {
    let stackSummary: EngStackAssetSummary | null = null
    if (props.stackAssetId) {
      try {
        const asset = await getEngStack(props.stackAssetId)
        stackSummary = {
          id: asset.id,
          drawingNo: asset.drawingNo,
          title: asset.title,
          savedAt: asset.savedAt,
          plateCount: asset.plateCount,
          calloutCount: asset.calloutCount
        }
        refreshStackCalloutProgress(asset.job?.callouts, asset.plateCount)
      } catch {
        message.warning('已保存层次读取失败，可从「最近」重新选用')
      }
    }

    let dwg: File | null = null
    if (props.hasCustomerDwg) {
      try {
        dwg = await taskApi.downloadDwg(props.taskId)
      } catch {
        message.warning('客户 DWG 恢复失败，请重新上传')
      }
    }

    let annot: TaskAnnotSnapshot | null = null
    if (dwg) {
      try {
        annot = (await taskApi.getAnnot(props.taskId)) || null
      } catch {
        annot = null
      }
    }

    if (props.hasStepFile) {
      try {
        const file = await taskApi.downloadStep(props.taskId, props.stepFileName)
        if (acceptStepFile(file)) {
          await runStep({ silent: true })
        }
      } catch {
        message.warning('三维模型恢复失败，请重新导入 STP')
      }
    } else if (props.stackAssetId) {
      // 层次有、STP 无：明确提示，避免误以为「编辑页没加载」
      solidHint.value = '层次已恢复，请重新导入 STP 以预览三维'
      message.warning('层次已恢复，但任务未保存三维模型，请重新导入 STP')
    }

    await nextTick()
    await engWizardRef.value?.restoreProgress({
      step: props.initialStep,
      stack: stackSummary,
      dwgFile: dwg,
      annot,
    })
    await nextTick()
    // step 已由向导 restoreProgress → stepChange 写入 engWizardStep；勿用 initialStep 覆盖
    emit('stepChange', engWizardStep.value)
    if (solidReady.value || stepFile.value) {
      solidHint.value = solidReady.value
        ? '已恢复 · 左键旋转 · 右键平移 · 滚轮缩放 · 可用 Z 展开'
        : solidHint.value
      if (!annot) {
        message.success('已恢复任务进度')
      }
    } else if (stackSummary && !props.hasStepFile) {
      solidHint.value = '层次已恢复，请重新导入 STP 以预览三维'
    }
  } finally {
    restoring.value = false
    void nextTick(() => {
      scheduleSolidResize()
      scheduleStackExplodePreview()
    })
  }
}

function buildStackExplodePlatesFromView(): StackPlateInput[] {
  if (!solidCamera || !solidMeshes.length) return []
  for (const mesh of solidMeshes) mesh.updateWorldMatrix(true, false)
  return extractDraftingPlates(solidMeshes, solidCamera).map((p) => ({
    bodyId: p.bodyId,
    thickness: p.thickness,
    segments: p.segments
  }))
}

function scheduleStackExplodePreview() {
  if (stackPreviewTimer) window.clearTimeout(stackPreviewTimer)
  stackPreviewTimer = window.setTimeout(() => {
    stackPreviewTimer = 0
    paintStackExplodePreview()
  }, 60)
}

function paintStackExplodePreview() {
  const canvas = stackPreviewCanvas.value
  if (!canvas) return
  const cssW = Math.max(160, canvas.clientWidth || 260)
  const cssH = Math.max(72, canvas.clientHeight || 88)
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  canvas.width = Math.floor(cssW * dpr)
  canvas.height = Math.floor(cssH * dpr)
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.fillStyle = '#000000'
  ctx.fillRect(0, 0, cssW, cssH)

  if (!solidReady.value || !solidCamera || !solidMeshes.length) {
    ctx.fillStyle = '#64748b'
    ctx.font = '12px sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText('解析三维后显示层次爆炸预览', cssW / 2, cssH / 2)
    return
  }

  solidControls?.update()
  solidCamera.updateMatrixWorld(true)
  solidCamera.updateProjectionMatrix()
  const plates = buildStackExplodePlatesFromView()
  if (!plates.length) {
    ctx.fillStyle = '#64748b'
    ctx.font = '12px sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText('暂无边线可预览', cssW / 2, cssH / 2)
    return
  }

  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const plate of plates) {
    for (const s of plate.segments) {
      minX = Math.min(minX, s.x1, s.x2)
      minY = Math.min(minY, s.y1, s.y2)
      maxX = Math.max(maxX, s.x1, s.x2)
      maxY = Math.max(maxY, s.y1, s.y2)
    }
  }
  if (!Number.isFinite(minX) || maxX - minX < 1e-9 || maxY - minY < 1e-9) {
    ctx.fillStyle = '#64748b'
    ctx.font = '12px sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText('暂无边线可预览', cssW / 2, cssH / 2)
    return
  }

  const pad = 12
  const bw = Math.max(1e-6, maxX - minX)
  const bh = Math.max(1e-6, maxY - minY)
  const fit = Math.min((cssW - pad * 2) / bw, (cssH - pad * 2) / bh)
  const ox = pad - minX * fit
  const oy = cssH - pad + minY * fit
  const toS = (x: number, y: number) => ({ x: ox + x * fit, y: oy - y * fit })

  ctx.strokeStyle = '#ffffff'
  ctx.lineWidth = 1.15
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  for (const plate of plates) {
    ctx.beginPath()
    for (const s of plate.segments) {
      const a = toS(s.x1, s.y1)
      const b = toS(s.x2, s.y2)
      ctx.moveTo(a.x, a.y)
      ctx.lineTo(b.x, b.y)
    }
    ctx.stroke()
  }

  ctx.fillStyle = '#94a3b8'
  ctx.font = '10px sans-serif'
  ctx.textAlign = 'left'
  ctx.textBaseline = 'alphabetic'
  ctx.fillText('预览 · 与三维同投影 · 点击编辑', 6, cssH - 6)
}

function setAnnotMode(mode: 'layer' | 'body') {
  factoryListMode.value = mode
  selectedLayerId.value = ''
  selectedBodyId.value = ''
  applySelectionHighlight()
  applyExplode()
  // 列表行数变化可能触发布局重算，补一次三维尺寸
  void nextTick(() => scheduleSolidResize())
}

function selectFactoryLayer(id: string) {
  selectedLayerId.value = id
  selectedBodyId.value = ''
  applySelectionHighlight()
}

function selectFactoryBody(id: string) {
  selectedBodyId.value = id
  const body = factoryBodies.value.find((row) => row.id === id)
  if (body) selectedLayerId.value = body.layerId
  applySelectionHighlight()
}

function pickFactoryPreview(id: string) {
  if (factoryListMode.value === 'body') selectFactoryBody(id)
  else selectFactoryLayer(id)
}

/** 本仓标注在层次爆炸编辑器内完成（无二维对照页） */
function openAnnotPage() {
  void openStackExplodeEditor()
}

async function openStackExplodeEditor() {
  if (stackExplodeOpening.value || stackExplodeOpen.value) return
  if (!solidReady.value || !solidMeshes.length) {
    message.warning('请先解析三维并生成实体预览')
    return
  }
  if (!solidCamera) {
    message.warning('三维相机未就绪，请稍候再试')
    return
  }
  stackExplodeOpening.value = true
  try {
    const boosted = ensureExplodeGapForStackEdit()
    applyExplode()
    if (boosted) {
      message.info(`已自动加大 Z 展开至 ${explodeGap.value.toFixed(2)}，便于层次图拉开`)
    }
    solidControls?.update()
    solidCamera.updateMatrixWorld(true)
    solidCamera.updateProjectionMatrix()
    const plates = buildStackExplodePlatesFromView()
    if (!plates.length) {
      message.warning('未读出实体边线，无法编辑层次爆炸')
      return
    }
    stackExplodePlates.value = plates
    // 回读已保存旁注（序号/材料/颜色等），避免重开编辑器丢信息
    stackExplodeInitialCallouts.value = []
    const stackId = engWizardRef.value?.getStackAssetId?.() || ''
    if (stackId) {
      try {
        const asset = await getEngStack(stackId)
        const saved = asset.job?.callouts
        if (Array.isArray(saved) && saved.length) {
          stackExplodeInitialCallouts.value = saved.map((c) => ({ ...c, bodyId: String(c.bodyId || '') }))
          refreshStackCalloutProgress(stackExplodeInitialCallouts.value)
        }
      } catch {
        // 无资产或读失败时仍可编辑当前投影
      }
    }
    // 2D 材料种子：序号命中时自动预填
    stackExplodeMaterialSeeds.value = engWizardRef.value?.getMaterialSeeds?.() || []
    stackExplodeOpen.value = true
  } finally {
    stackExplodeOpening.value = false
  }
}

function closeStackExplodeEditor() {
  stackExplodeOpen.value = false
  stackExplodeInitialCallouts.value = []
  stackExplodeMaterialSeeds.value = []
  scheduleStackExplodePreview()
}

/** 编辑器内「保存层次图」：落库成功后再关闭，失败则留在编辑器 */
async function persistStackExplodeSave(payload: {
  drawingNo: string
  plates: StackExplodeExportBody['plates']
  callouts: StackExplodeExportBody['callouts']
  previewPngBase64: string
}) {
  refreshStackCalloutProgress(payload.callouts, payload.plates?.length)
  const ok = await engWizardRef.value?.onStackSaved(payload)
  if (!ok) {
    // 失败提示已由 onStackSaved 弹出
    throw new Error('STACK_SAVE_FAILED')
  }
  stackExplodeOpen.value = false
  stackExplodeInitialCallouts.value = []
  stackExplodeMaterialSeeds.value = []
  scheduleStackExplodePreview()
}

function onComposed(result: EngComposeResult) {
  emit('composed', result)
}

watch(explodeGap, () => {
  applyExplode()
})

watch(showFactoryPanel, (on) => {
  if (on) {
    void nextTick(() => {
      scheduleSolidResize()
      scheduleStackExplodePreview()
    })
  }
})

/** 离开图纸/产物总览回到三维时重算尺寸，避免画布错位 */
watch([showDwgStage, showComposeBoard], ([dwg, board]) => {
  if (!dwg && !board) {
    void nextTick(() => scheduleSolidResize())
  }
  if (dwg && hasDwgPreviewMedia.value) {
    scheduleFitDwgToViewport()
  }
})

/** 解析结束后视口才稳定；用户已手动缩放/平移则不再强制适应 */
watch(extractBusy, (busy, wasBusy) => {
  if (wasBusy && !busy && hasDwgPreviewMedia.value && showDwgStage.value && !dwgUserAdjusted) {
    scheduleFitDwgToViewport()
  }
})

onMounted(() => {
  // 有待恢复资产时立刻亮遮罩，避免 nextTick 前闪出空上传态
  const needRestore =
    !!props.taskId &&
    !!(
      props.hasStepFile ||
      props.stackAssetId ||
      props.hasCustomerDwg ||
      props.stackSkipped ||
      props.extractSkipped
    )
  if (needRestore) {
    restoring.value = true
    solidHint.value = '正在为您恢复任务，请稍候…'
  }
  void nextTick(async () => {
    resizeSolid()
    await hydrateFromTask()
  })
})

onBeforeUnmount(() => {
  stopHydrateUi()
  window.removeEventListener('keydown', onDwgCropKeydown)
  if (stackPreviewTimer) {
    window.clearTimeout(stackPreviewTimer)
    stackPreviewTimer = 0
  }
  // 离开工作台：软卸载三维，不挡「返回任务列表」跳转
  disposeSolid({ deferHeavy: true })
})
</script>

<template>
  <div
    class="eng-ws"
    :class="{
      'is-split': engWizardStep !== 2,
      'is-panel-full': engWizardStep === 3 || engWizardStep === 4,
      'is-compose': engWizardStep === 5
    }"
  >
    <div v-if="restoring" class="eng-ws-hydrate" role="status" aria-live="polite">
      <div class="eng-ws-hydrate-card">
        <div class="eng-ws-hydrate-ico" aria-hidden="true">
          <LoadingOutlined spin class="eng-ws-hydrate-spin" />
        </div>
        <strong class="eng-ws-hydrate-title">正在为您恢复任务…</strong>
        <p class="eng-ws-hydrate-desc">客户图纸、三维模型与审校结果加载中</p>
        <div class="eng-ws-hydrate-meta">
          <span class="eng-ws-hydrate-elapsed" title="已用时">已用时 {{ hydrateElapsedText }}</span>
        </div>
        <a-progress
          :percent="hydratePercent"
          status="active"
          :show-info="false"
          size="small"
          class="eng-ws-hydrate-bar"
        />
        <p class="eng-ws-hydrate-tip">{{ hydrateTip }}</p>
        <p class="eng-ws-hydrate-note">请耐心等待，勿关闭或刷新页面</p>
      </div>
    </div>
    <EngDrawingWizard
      ref="engWizardRef"
      embedded
      :task-id="taskId"
      :task-title="taskTitle"
      :task-status="taskStatus"
      :initial-step="initialStep"
      :stack-skipped="!!stackSkipped"
      :extract-skipped="!!extractSkipped"
      :solid-ready="solidReady"
      :body-count="factoryBodies.length"
      :drawing-no="stepResult?.drawingNo"
      :step-file-name="stepFile?.name || stepFileName"
      :step-persisted="!!hasStepFile"
      :has-step-file="!!hasStepFile"
      :has-customer-dwg="!!hasCustomerDwg"
      :step-scanning="stepScanning || restoring"
      :stack-editor-opening="stackExplodeOpening"
      :hydrating="restoring"
      @pick-step="onEngPickStep"
      @need-parse="() => runStep()"
      @open-stack-editor="openStackExplodeEditor"
      @focus-solid="focusEngSolid"
      @step-change="onEngWizardStepChange"
      @composed="onComposed"
      @stack-bound="onStackBound"
      @stack-skip-change="onStackSkipChange"
      @extract-skip-change="onExtractSkipChange"
      @dwg-picked="onDwgPicked"
      @extract-preview="onExtractPreview"
      @extract-busy="onExtractBusy"
      @region-capture-request="onRegionCaptureRequest"
      @compose-board="onComposeBoard"
      @back="emit('back')"
      @close="() => undefined"
    />

    <div class="eng-ws-body">
      <section v-show="engWizardStep !== 3 && engWizardStep !== 4" class="eng-stage">
        <!-- ①图纸 / ②三维 / ⑤产物总览；③④全宽侧栏隐藏本区；solid-host 在①②⑤保留挂载 -->
        <div class="stage-body">
          <div class="stage-main">
            <ComposeArtifactBoard
              v-if="showComposeBoard && composeBoard"
              :dwg-file-name="composeBoard.dwgFileName"
              :drawing-no="composeBoard.drawingNo"
              :preview-png-base64="composeBoard.previewPngBase64"
              :preview-svg="composeBoard.previewSvg"
              :preview-svgs="composeBoard.previewSvgs"
              :material-crops="composeBoard.materialCrops"
              :notes="composeBoard.notes"
              :materials="composeBoard.materials"
              :stack="composeBoard.stack"
              :stack-skipped="!!composeBoard.stackSkipped"
              :stack-preview-png-base64="composeBoard.stackPreviewPngBase64"
              :stack-preview-prefetched="composeBoard.stackPreviewPrefetched"
              :material-summary="composeBoard.materialSummary"
              :material-work-rows="composeBoard.materialWorkRows"
              :materials-confirmed="composeBoard.materialsConfirmed"
              :notes-confirmed="composeBoard.notesConfirmed"
              :result="composeBoard.result"
              @go-step="onComposeBoardGoStep"
            />
            <div v-else-if="showComposeBoard" class="stage-empty stage-empty--inline">
              {{ restoring ? '正在恢复终稿…' : '正在整理前序产物…' }}
            </div>

            <div v-show="showSolidStage" class="stage-bar">
              <span class="stage-lab">三维预览</span>
              <span class="stage-hint">{{ solidHint || (stepFile ? fact(stepResult?.envelope) : '请用上方工具条选择 STP') }}</span>
              <label class="hand-toggle">
                <input v-model="solidHand" type="checkbox" />
                手形平移
              </label>
              <label
                class="explode-ctrl"
                :title="
                  factoryListMode === 'body'
                    ? '按实体：同 zMin 共面，再按不同 zMin 平面拉开（XY/旋转不变）'
                    : '按料层：同层整层拉开'
                "
              >
                Z 展开·{{ factoryListMode === 'body' ? '实体' : '料层' }}
                <input
                  v-model.number="explodeGap"
                  type="range"
                  min="0"
                  :max="explodeGapMax"
                  :step="EXPLODE_GAP_STEP"
                />
                <span>{{ explodeGap.toFixed(2) }}</span>
              </label>
            </div>
            <div v-show="showDwgStage" class="stage-bar">
              <span class="stage-lab">客户图纸预览</span>
              <span class="stage-hint">
                <template v-if="extractBusy && !hasDwgPreviewMedia">正在解析，请稍候…</template>
                <template v-else-if="extractBusy && hasDwgPreviewMedia">正在处理，左侧可继续看图…</template>
                <template v-else-if="extractPreview">
                  {{ extractPreview.fileName || 'customer.dwg' }}
                  <template v-if="extractPreview.drawingNo"> · {{ extractPreview.drawingNo }}</template>
                  <template v-if="dwgMultiPage">
                    · {{ dwgPageIndex + 1 }}/{{ dwgPages.length }} 页
                  </template>
                </template>
                <template v-else>上传客户 DWG 后在此预览图纸</template>
              </span>
              <div v-if="hasDwgPreviewMedia && !extractBusy" class="dwg-tools">
                <label class="hand-toggle" title="手形拖动画布">
                  <input v-model="dwgHand" type="checkbox" :disabled="dwgCropMode" />
                  <DragOutlined />
                  手形
                </label>
                <button type="button" class="dwg-tool-btn" title="放大（或 Ctrl+滚轮）" @click="zoomDwgIn">
                  <ZoomInOutlined />
                </button>
                <button type="button" class="dwg-tool-btn" title="缩小（或 Ctrl+滚轮）" @click="zoomDwgOut">
                  <ZoomOutOutlined />
                </button>
                <button type="button" class="dwg-tool-btn" title="适应窗口（左上对齐）" @click="fitDwgToViewport">
                  <AimOutlined />
                </button>
                <span class="dwg-zoom-lab">{{ Math.round(dwgScale * 100) }}%</span>
              </div>
              <div v-if="dwgCropMode" class="dwg-crop-banner">
                <span>框选{{ dwgCropKind === 'material' ? '材料' : 'NOTE' }} · 拖选后点「识别」</span>
                <button type="button" class="dwg-crop-act" :disabled="!dwgCropPending" @click="confirmDwgCrop">
                  识别
                </button>
                <button type="button" class="dwg-crop-act ghost" @click="exitDwgCropMode()">取消</button>
              </div>
            </div>
            <!-- 三维 host：仅②可见；①⑤用 is-parked 隐藏但保持挂载 -->
            <div
              v-show="showSolidStage || showDwgStage || showComposeBoard"
              class="stage-solid"
              :class="{ 'is-parked': !showSolidStage }"
            >
              <div ref="solidHost" class="solid-host" :class="{ 'is-hand': solidHand, 'is-idle': !solidReady }" />
              <canvas
                v-show="wireVisible && !solidReady && showSolidStage"
                ref="wireCanvas"
                class="wire-canvas"
              />
              <div v-if="showSolidStage && !solidReady && !wireVisible" class="stage-empty">
                <template v-if="stepScanning || restoring">
                  <LoadingOutlined spin class="stage-empty-spin" />
                  <span class="stage-empty-busy">三维模型加载中，请稍候…</span>
                  <a-progress
                    class="stage-empty-bar"
                    :percent="72"
                    status="active"
                    :show-info="false"
                    size="small"
                  />
                </template>
                <template v-else>
                  {{
                    stepFile
                      ? lastSolidError || '等待实体预览'
                      : stackAssetId && !hasStepFile
                        ? '层次已恢复，请重新导入 STP 以预览三维'
                        : '选择 STP / STEP 后在此预览三维'
                  }}
                </template>
              </div>
            </div>
            <div v-show="showDwgStage" class="stage-dwg">
              <div v-if="restoring" class="stage-empty stage-empty--inline">
                <LoadingOutlined spin class="stage-empty-spin" />
                <span class="stage-empty-busy">客户图纸加载中，请稍候…</span>
                <a-progress
                  class="stage-empty-bar"
                  :percent="62"
                  status="active"
                  :show-info="false"
                  size="small"
                />
              </div>
              <div v-else-if="extractBusy && !hasDwgPreviewMedia" class="stage-empty stage-empty--inline">
                <LoadingOutlined spin class="stage-empty-spin" />
                <span class="stage-empty-busy">正在解析客户图纸…</span>
                <a-progress
                  class="stage-empty-bar"
                  :percent="68"
                  status="active"
                  :show-info="false"
                  size="small"
                />
              </div>
              <div v-else-if="hasDwgPreviewMedia" class="stage-dwg-body">
                <aside v-if="dwgMultiPage" class="dwg-page-rail" aria-label="图纸分页">
                  <button
                    v-for="(pg, idx) in dwgPages"
                    :key="'pg-' + idx"
                    type="button"
                    class="dwg-page-thumb"
                    :class="{ on: idx === dwgPageIndex }"
                    :title="`第 ${idx + 1} 页`"
                    @click="selectDwgPage(idx)"
                  >
                    <span class="dwg-page-num">{{ idx + 1 }}</span>
                    <div v-if="pg.kind === 'svg'" class="dwg-page-svg" v-html="pg.content" />
                    <img v-else class="dwg-page-img" :src="pg.content" alt="" />
                  </button>
                </aside>
                <div class="stage-dwg-scroll-host">
                  <div
                    ref="dwgViewportRef"
                    class="stage-dwg-view"
                    :class="{
                      'is-hand': dwgHand && !dwgCropMode,
                      'is-panning': dwgPanning,
                      'is-crop': dwgCropMode
                    }"
                    @wheel.prevent="onDwgWheel"
                    @pointerdown="onDwgPointerDown"
                    @pointermove="onDwgPointerMove"
                    @pointerup="onDwgPointerUp"
                    @pointercancel="onDwgPointerUp"
                  >
                    <div class="stage-dwg-canvas" :style="dwgTransformStyle">
                      <img
                        v-if="dwgCurrentPage?.kind === 'png'"
                        class="stage-dwg-img"
                        :src="dwgCurrentPage.content"
                        alt="客户图纸预览"
                        draggable="false"
                        @load="onDwgImgLoad"
                      />
                      <div
                        v-else-if="dwgCurrentPage?.kind === 'svg'"
                        class="stage-dwg-svg"
                        v-html="dwgCurrentPage.content"
                      />
                    </div>
                  </div>
                  <div v-if="dwgCropRectStyle" class="dwg-crop-rect" :style="dwgCropRectStyle" />
                </div>
              </div>
              <div v-else class="stage-empty stage-empty--inline">
                <template v-if="extractPreview">
                  已抽取 NOTE / 材料；本次无图纸预览图，可点右侧「重新解析」持久化预览
                </template>
                <template v-else>请在右侧上传客户 DWG；上传并预览后，图纸将显示在此</template>
              </div>
            </div>
            <p v-if="stepResult && showSolidStage" class="stage-meta">
              {{ stepResult.drawingNo || '—' }} ·
              {{ factoryBodies.length || stepResult.solidCount || 0 }} 实体 ·
              {{ formatLen(solidViewSpan) }} span · {{ fact(stepResult.lengthUnit) }}
            </p>
            <p v-else-if="showDwgStage && extractPreview && !extractBusy" class="stage-meta">
              NOTE {{ extractPreview.notes?.length || 0 }} 条 ·
              材料 {{ extractPreview.materials?.length || 0 }} 行
              <template v-if="dwgMultiPage"> · {{ dwgPages.length }} 页</template>
              <template v-if="extractPreview.message"> · {{ extractPreview.message }}</template>
            </p>
          </div>

          <aside v-if="showFactoryPanel" class="eng-panel">
            <div class="factory-box">
              <div class="factory-head">
                <div class="eng-title">
                  <strong>工厂分层</strong>
                </div>
                <div class="factory-modes">
                  <button
                    type="button"
                    class="mode-btn"
                    :class="{ on: factoryListMode === 'layer' }"
                    @click="setAnnotMode('layer')"
                  >
                    按料层
                  </button>
                  <button
                    type="button"
                    class="mode-btn"
                    :class="{ on: factoryListMode === 'body' }"
                    @click="setAnnotMode('body')"
                  >
                    按实体
                  </button>
                  <span class="annot-progress"
                    >旁注 {{ annotProgress.done }}/{{ annotProgress.total }}</span
                  >
                </div>
              </div>
              <div class="factory-scroll">
                <div class="factory-list">
                  <button
                    v-for="row in previewPickRows"
                    :key="row.id"
                    type="button"
                    class="factory-row"
                    :class="{ on: row.id === previewPickSelectedId }"
                    @click="pickFactoryPreview(row.id)"
                  >
                    <i class="swatch" :style="{ background: row.color }" />
                    <span>{{ row.label }}</span>
                  </button>
                </div>
              </div>
              <div class="factory-foot">
                <a-button
                  type="primary"
                  size="small"
                  block
                  :disabled="!solidReady || stackExplodeOpening"
                  :loading="stackExplodeOpening"
                  @click="openAnnotPage"
                >
                  {{ stackExplodeOpening ? '打开中…' : '打开标注' }}
                </a-button>
                <button
                  type="button"
                  class="stack-preview-wrap"
                  :disabled="!solidReady || !factoryBodies.length || stackExplodeOpening"
                  title="点击进入层次爆炸编辑"
                  @click="openStackExplodeEditor"
                >
                  <canvas ref="stackPreviewCanvas" class="stack-preview-canvas" />
                </button>
                <p class="eng-note">
                  旁注进度 {{ annotProgress.done }}/{{ annotProgress.total
                  }}{{ annotComplete ? ' · 已填齐' : ' · 打开标注补全序号/材料/颜色' }}
                </p>
                <p class="eng-note">与左侧三维同投影，点击编辑。</p>
              </div>
            </div>
          </aside>
        </div>
      </section>

      <!-- 向导①③④⑤ 操作栏挂载点（Teleport）；②层次为顶栏工具条；保留高度链，禁止塌成 0 -->
      <aside
        id="eng-wizard-side"
        class="eng-side-host"
        :class="{
          wide: engWizardStep === 1,
          full: engWizardStep === 3 || engWizardStep === 4
        }"
        v-show="engWizardStep !== 2"
        aria-hidden="false"
      />
    </div>

    <StackExplodeEditor
      v-if="stackExplodeOpen"
      :plates="stackExplodePlates"
      :initial-callouts="stackExplodeInitialCallouts"
      :material-seeds="stackExplodeMaterialSeeds"
      :drawing-no="stepResult?.drawingNo"
      :enable-save="true"
      :persist-save="persistStackExplodeSave"
      @close="closeStackExplodeEditor"
    />
  </div>
</template>

<style scoped>
.eng-ws {
  flex: 1 1 0%;
  min-height: calc(100vh - 220px);
  height: 100%;
  max-height: 100%;
  display: flex;
  flex-direction: column;
  position: relative;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 10px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
/* 进入/编辑恢复中：全工作台遮罩卡片，避免空上传态被当成故障 */
.eng-ws-hydrate {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  background: linear-gradient(160deg, rgba(248, 250, 252, 0.92) 0%, rgba(230, 244, 255, 0.9) 100%);
  backdrop-filter: blur(2px);
  text-align: center;
  pointer-events: all;
}
.eng-ws-hydrate-card {
  width: min(420px, 92vw);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 28px 24px 22px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #e2e8f0;
  box-shadow:
    0 1px 2px rgba(15, 23, 42, 0.04),
    0 12px 28px rgba(15, 23, 42, 0.08);
}
.eng-ws-hydrate-ico {
  width: 52px;
  height: 52px;
  margin-bottom: 4px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #e6f4ff;
  border: 1px solid #91caff;
}
.eng-ws-hydrate-spin {
  font-size: 24px;
  color: var(--jy-primary, #1677ff);
}
.eng-ws-hydrate-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
  color: var(--ui-text-strong, #000);
}
.eng-ws-hydrate-desc {
  margin: 0;
  font-size: 13px;
  line-height: 20px;
  color: #475569;
}
.eng-ws-hydrate-meta {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 2px;
}
.eng-ws-hydrate-elapsed {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 6px;
  background: #f0f5ff;
  border: 1px solid #d6e4ff;
  font-size: 12px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
  color: var(--jy-primary, #1677ff);
}
.eng-ws-hydrate-bar {
  width: 100%;
  max-width: 280px;
  margin-top: 6px;
}
.eng-ws-hydrate-bar :deep(.ant-progress-inner) {
  background: #e8eef5;
}
.eng-ws-hydrate-bar :deep(.ant-progress-bg) {
  background: linear-gradient(90deg, #69b1ff 0%, #1677ff 100%);
}
.eng-ws-hydrate-tip {
  margin: 6px 0 0;
  max-width: 340px;
  min-height: 36px;
  font-size: 12px;
  line-height: 18px;
  color: #64748b;
  transition: opacity 0.25s ease;
}
.eng-ws-hydrate-note {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  font-weight: 600;
  color: #475569;
}
/* ①③④⑤：顶栏 + 下方左右分栏；②层次无侧栏；min-height 兜底避免列表短时整区塌矮 */
.eng-ws-body {
  flex: 1 1 0%;
  min-height: calc(100vh - 280px);
  max-height: 100%;
  display: flex;
  flex-direction: row;
  align-items: stretch;
  overflow: hidden;
}
.eng-side-host {
  flex: 0 0 400px;
  width: 400px;
  min-width: 320px;
  max-width: 46%;
  height: 100%;
  /* 视口兜底：父级高度推算失败时也不让侧栏被内容无限撑开 */
  max-height: calc(100vh - 200px);
  min-height: 0;
  align-self: stretch;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  margin: 4px 6px 6px 0;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 8px;
  background: #fff;
}
.eng-side-host.wide {
  /* ① 抽取：右侧略加宽，材料表列尽量单行；左侧仍保留足够预览 */
  flex: 0 0 min(460px, 40%);
  width: min(460px, 40%);
  max-width: 46%;
}
/* ③材料 / ④翻译：右栏吃满，左预览区隐藏（solid-host 仍在 DOM） */
.eng-side-host.full {
  flex: 1 1 auto;
  width: auto;
  max-width: none;
  min-width: 0;
  margin-left: 6px;
}
.eng-ws.is-panel-full .eng-side-host {
  margin-right: 6px;
}
.eng-side-host:empty {
  /* Teleport 未挂上前也占位，避免闪一下塌缩 */
  min-height: 280px;
}
.eng-stage {
  flex: 1 1 0%;
  min-width: 0;
  /* 下限：420px 与视口剩余高度取大，按料层/按实体列表行数不同也不塌矮 */
  min-height: max(420px, calc(100vh - 280px));
  align-self: stretch;
  display: flex;
  flex-direction: column;
  margin: 4px 6px 6px;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 8px;
  background: #f3f5f9;
  overflow: hidden;
}
.eng-ws.is-split .eng-stage {
  margin-right: 4px;
}
/* ⑤ 产物总览：禁止用 100vh min-height 撑破父级，否则内层永远拿不到确定高度、滚不动 */
.eng-ws.is-compose .eng-ws-body {
  min-height: 0;
}
.eng-ws.is-compose .eng-stage {
  min-height: 0;
  max-height: 100%;
}
.eng-ws.is-compose .stage-main {
  position: relative;
}
.stage-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-bottom: 1px solid var(--jy-border, #d0d5dd);
  background: #fff;
  flex-shrink: 0;
}
.stage-lab {
  font-size: 13px;
  font-weight: 600;
  color: var(--jy-text-strong, #0f172a);
  line-height: 22px;
}
.stage-hint {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  line-height: 20px;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.hand-toggle,
.explode-ctrl {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  line-height: 20px;
  color: #1f2937;
  white-space: nowrap;
}
.explode-ctrl input[type='range'] {
  width: 90px;
}
.stage-body {
  flex: 1 1 0%;
  min-height: 0;
  display: flex;
  flex-direction: row;
  align-items: stretch;
  overflow: hidden;
}
.stage-main {
  flex: 1 1 0%;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #fff;
}
.stage-solid {
  flex: 1 1 0%;
  min-height: 280px;
  position: relative;
  background: #e8eef6;
  /* 三维只画在预览框内，禁止 canvas 视觉溢出 */
  overflow: hidden;
}
/* ①⑤：三维 host 驻留 DOM 但不占高度，避免产物板/图纸区被挤扁 */
.stage-solid.is-parked {
  position: absolute;
  width: 0;
  height: 0;
  min-height: 0;
  flex: 0 0 0;
  overflow: hidden;
  visibility: hidden;
  pointer-events: none;
}
/* ① 客户图纸：保留与三维同级的高度下限，避免切步塌缩 */
.stage-dwg {
  flex: 1 1 0%;
  min-height: 280px;
  position: relative;
  display: flex;
  flex-direction: column;
  background: #0b1220;
  overflow: hidden;
}
.stage-dwg-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
  overflow: hidden;
}
.dwg-page-rail {
  flex: 0 0 72px;
  width: 72px;
  overflow-y: auto;
  overflow-x: hidden;
  border-right: 1px solid rgba(255, 255, 255, 0.12);
  background: #111827;
  padding: 6px 4px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.dwg-page-thumb {
  position: relative;
  width: 100%;
  aspect-ratio: 3 / 4;
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 4px;
  background: #fff;
  padding: 0;
  overflow: hidden;
  cursor: pointer;
}
.dwg-page-thumb.on {
  border-color: var(--jy-primary, #1677ff);
  box-shadow: 0 0 0 1px var(--jy-primary, #1677ff);
}
.dwg-page-num {
  position: absolute;
  top: 2px;
  left: 2px;
  z-index: 1;
  min-width: 16px;
  height: 16px;
  padding: 0 3px;
  border-radius: 3px;
  background: rgba(15, 23, 42, 0.75);
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}
.dwg-page-svg,
.dwg-page-img {
  width: 100%;
  height: 100%;
  pointer-events: none;
}
.dwg-page-svg :deep(svg) {
  width: 100%;
  height: 100%;
  display: block;
}
.dwg-page-img {
  object-fit: contain;
  display: block;
}
.stage-dwg-scroll-host {
  flex: 1;
  min-width: 0;
  min-height: 0;
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.stage-dwg-view {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  position: relative;
  touch-action: none;
  user-select: none;
}
.stage-dwg-view.is-hand {
  cursor: grab;
}
.stage-dwg-view.is-hand.is-panning {
  cursor: grabbing;
}
.stage-dwg-view.is-crop {
  cursor: crosshair;
}
.dwg-crop-rect {
  position: absolute;
  border: 2px dashed var(--jy-primary, #1677ff);
  background: rgba(22, 119, 255, 0.12);
  pointer-events: none;
  z-index: 3;
  box-sizing: border-box;
}
.dwg-crop-banner {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: 8px;
  padding: 2px 8px;
  border-radius: 4px;
  background: #e6f4ff;
  border: 1px solid #91caff;
  font-size: 12px;
  color: #0958d9;
  flex-shrink: 0;
}
.dwg-crop-act {
  height: 22px;
  padding: 0 8px;
  border-radius: 4px;
  border: 1px solid var(--jy-primary, #1677ff);
  background: var(--jy-primary, #1677ff);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}
.dwg-crop-act:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.dwg-crop-act.ghost {
  background: #fff;
  color: #0958d9;
}
.stage-dwg-canvas {
  position: absolute;
  left: 0;
  top: 0;
  will-change: transform;
  line-height: 0;
}
.stage-dwg-img {
  display: block;
  max-width: none;
  height: auto;
  background: #050505;
  pointer-events: none;
  image-rendering: auto;
  filter: contrast(1.28) brightness(1.1);
}
.stage-dwg-svg {
  color: #e2e8f0;
  pointer-events: none;
}
.stage-dwg-svg :deep(svg) {
  max-width: none;
  height: auto;
  display: block;
  background: #fff;
}
.dwg-tools {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}
.dwg-tool-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 4px;
  background: #fff;
  color: #334155;
  cursor: pointer;
  font-size: 13px;
  padding: 0;
}
.dwg-tool-btn:hover {
  border-color: var(--jy-primary, #1677ff);
  color: var(--jy-primary, #1677ff);
}
.dwg-zoom-lab {
  font-size: 11px;
  color: #64748b;
  min-width: 36px;
  text-align: right;
  line-height: 22px;
}
.stage-empty--inline {
  position: relative;
  inset: auto;
  flex: 1;
  min-height: 200px;
  pointer-events: none;
}
.solid-host,
.wire-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  z-index: 1;
  background: radial-gradient(ellipse at 42% 32%, #f5f8fc 0%, #e4ebf3 52%, #d5dee8 100%);
}
.solid-host.is-idle {
  visibility: hidden;
  pointer-events: none;
}
.wire-canvas {
  z-index: 2;
  display: block;
  cursor: grab;
  touch-action: none;
}
.solid-host.is-hand {
  cursor: grab;
}
.solid-host.is-hand:active {
  cursor: grabbing;
}
.solid-host :deep(canvas) {
  display: block;
  width: 100% !important;
  height: 100% !important;
}
.stage-empty {
  position: absolute;
  inset: 0;
  z-index: 3;
  margin: 0;
  padding: 16px 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  text-align: center;
  color: #64748b;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.6;
  letter-spacing: 0.02em;
  white-space: normal;
  overflow: visible;
  pointer-events: none;
  box-sizing: border-box;
}
.stage-empty-spin {
  font-size: 26px;
  color: var(--jy-primary, #1677ff);
}
.stage-empty-busy {
  color: #334155;
  font-weight: 600;
  font-size: 14px;
  letter-spacing: 0.02em;
}
.stage-empty-bar {
  width: min(260px, 68%);
}
.stage-empty-bar :deep(.ant-progress-inner) {
  background: #e8eef6;
  border-radius: 999px;
}
.stage-empty-bar :deep(.ant-progress-bg) {
  border-radius: 999px;
}
/* 客户图纸暗底：高对比白字 + 亮蓝进度，避免看不清 */
.stage-dwg .stage-empty {
  color: #f8fafc;
  gap: 12px;
}
.stage-dwg .stage-empty-spin {
  color: #91caff;
  font-size: 28px;
  filter: drop-shadow(0 0 6px rgba(145, 193, 255, 0.35));
}
.stage-dwg .stage-empty-busy {
  color: #ffffff;
  font-weight: 700;
  font-size: 15px;
  letter-spacing: 0.03em;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.45);
}
.stage-dwg .stage-empty-bar :deep(.ant-progress-inner) {
  background: rgba(255, 255, 255, 0.28);
}
.stage-dwg .stage-empty-bar :deep(.ant-progress-bg) {
  background: #91caff !important;
}
.stage-meta {
  margin: 0;
  padding: 3px 8px;
  font-size: 11px;
  line-height: 18px;
  color: #64748b;
  border-top: 1px solid var(--jy-border, #d0d5dd);
  background: #fff;
  flex-shrink: 0;
}

.eng-panel {
  flex: 0 0 260px;
  width: 260px;
  align-self: stretch;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-left: 1px solid var(--jy-border, #d0d5dd);
  background: #fff;
  padding: 6px 6px 8px;
}
.factory-box {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.factory-head {
  flex-shrink: 0;
  padding-bottom: 2px;
}
.eng-title {
  display: flex;
  align-items: center;
  margin-bottom: 4px;
}
.eng-title strong {
  font-size: 12px;
  line-height: 18px;
  color: var(--jy-text-strong, #0f172a);
}
.eng-note {
  margin: 2px 0 0;
  color: #94a3b8;
  font-size: 10px;
  line-height: 1.3;
  font-weight: 400;
}
.factory-modes {
  display: flex;
  align-items: center;
  gap: 3px;
  margin: 0 0 4px;
  flex-wrap: nowrap;
}
.mode-btn {
  height: 22px;
  padding: 0 6px;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 4px;
  background: #fff;
  color: #1f2937;
  font-size: 11px;
  line-height: 16px;
  cursor: pointer;
}
.mode-btn.on {
  border-color: var(--jy-primary, #1677ff);
  background: var(--jy-primary, #1677ff);
  color: #fff;
  font-weight: 600;
}
.mode-btn:hover:not(.on) {
  border-color: var(--jy-primary, #1677ff);
  color: var(--jy-primary, #1677ff);
}
.annot-progress {
  margin-left: auto;
  font-size: 11px;
  line-height: 16px;
  color: #64748b;
  font-variant-numeric: tabular-nums;
}
/* 列表单独滚动；打开标注 + 预览钉底，按料层/按实体高度一致 */
.factory-scroll {
  flex: 1 1 0%;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 2px;
}
.factory-list {
  margin: 0;
}
.factory-foot {
  flex-shrink: 0;
  padding-top: 4px;
}
.factory-row {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  margin: 0 0 2px;
  padding: 2px 5px;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 3px;
  background: #fff;
  color: var(--jy-text-strong, #0f172a);
  font-size: 11px;
  line-height: 14px;
  text-align: left;
  cursor: pointer;
}
.factory-row:hover {
  border-color: #91caff;
  background: #e6f4ff;
}
.factory-row.on {
  border-color: var(--jy-primary, #1677ff);
  background: #e6f4ff;
}
.swatch {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  flex-shrink: 0;
  border: 1px solid rgba(15, 23, 42, 0.12);
}
.stack-preview-wrap {
  display: block;
  width: 100%;
  margin-top: 4px;
  padding: 0;
  border: 1px solid #1e293b;
  border-radius: 4px;
  overflow: hidden;
  background: #000;
  cursor: pointer;
}
.stack-preview-wrap:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.stack-preview-wrap:not(:disabled):hover {
  border-color: var(--jy-primary, #1677ff);
}
.stack-preview-canvas {
  display: block;
  width: 100%;
  height: 88px;
  background: #000;
}
.eng-panel :deep(.ant-btn) {
  height: 24px;
  font-size: 12px;
  padding: 0 8px;
}
</style>
