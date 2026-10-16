<script setup lang="ts">
/**
 * 层次爆炸编辑（独立功能）：黑底白线 WYSIWYG，引线可拖，导出 DXF + PNG 预览。
 * 不依赖二维图；不改动原「打开标注 / 工厂图册」。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { LoadingOutlined } from '@ant-design/icons-vue'
import { exportStackExplodeDxf, type StackExplodeExportBody } from '@/api'

const UNLABELED = '未标注'

export interface StackPlateSeg {
  /** 等轴测投影线段 [x1,y1,x2,y2]，Y 向上 */
  x1: number
  y1: number
  x2: number
  y2: number
}

export interface StackPlateInput {
  bodyId: string
  thickness: number
  segments: StackPlateSeg[]
}

export interface StackElbow {
  x: number
  y: number
}

export interface StackCallout {
  bodyId: string
  calloutNo: string
  material: string
  color: string
  specialReq: string
  thickness: number
  anchorX: number
  anchorY: number
  textX: number
  textY: number
  /** 中间折点（不含锚点/文字端）；空则直线，可拖转向 */
  elbows: StackElbow[]
  /** 用户已放置/拖过引线；生成时优先用此坐标 */
  placed: boolean
}

/** 回填用：兼容旧存盘（仅 line1/line2）与新结构化字段 */
export type StackCalloutSeed = Partial<StackCallout> & {
  bodyId: string
  line1?: string
  line2?: string
}

const MAX_ELBOWS = 3

const props = defineProps<{
  plates: StackPlateInput[]
  drawingNo?: string
  /** 工程图向导：显示「保存层次图」并 emit saved */
  enableSave?: boolean
  /** 已保存旁注；按 bodyId 合并，打开已存层次时恢复 */
  initialCallouts?: StackCalloutSeed[]
  /** ① 已确认 2D 材料；标注序号命中时自动预填（可再改） */
  materialSeeds?: { noteNo: string; material: string; color: string; thickness: string }[]
  /** 工程图向导：异步落库；提供时保存中保持按钮 loading，成功后再关 */
  persistSave?: (payload: {
    drawingNo: string
    plates: StackExplodeExportBody['plates']
    callouts: StackExplodeExportBody['callouts']
    previewPngBase64: string
  }) => Promise<void>
}>()

const emit = defineEmits<{
  close: []
  saved: [
    payload: {
      drawingNo: string
      plates: StackExplodeExportBody['plates']
      callouts: StackExplodeExportBody['callouts']
      previewPngBase64: string
    },
  ]
}>()

export type StackExplodeSavePayload = {
  drawingNo: string
  plates: StackExplodeExportBody['plates']
  callouts: StackExplodeExportBody['callouts']
  previewPngBase64: string
}

const canvasRef = ref<HTMLCanvasElement | null>(null)
const callouts = ref<StackCallout[]>([])
const selectedId = ref('')
const calloutNoInputRef = ref<HTMLInputElement | null>(null)
const previewUrl = ref('')
const exporting = ref(false)
const saving = ref(false)
/** 手型：空白处拖动画布；关闭后点实体可拉引线 */
const handMode = ref(true)

/** 旁注属性剪贴板（不含锚点/折点/位置） */
type CalloutAttrs = {
  calloutNo: string
  material: string
  color: string
  specialReq: string
  thickness: number
}
const attrClipboard = ref<CalloutAttrs | null>(null)
const historyStack = ref<string[]>([])
const historyIndex = ref(-1)
const HISTORY_MAX = 50
let suppressHistory = false
/** 表单连续输入只入栈一次 */
let fieldHistoryPushed = false

const canUndo = computed(() => historyIndex.value > 0)
const canRedo = computed(() => historyIndex.value >= 0 && historyIndex.value < historyStack.value.length - 1)
const canPasteAttrs = computed(() => !!attrClipboard.value && !!selected.value)
/** 有可撤销编辑即视为未保存改动 */
const isDirty = computed(() => historyIndex.value > 0)

/** 画布：模型坐标 → 像素；pad/fitFactor 保证旁注不被裁切 */
let view = { scale: 1, ox: 40, oy: 40, pad: 64 }
const FIT_FACTOR = 0.92
let drag:
  | { kind: 'place'; bodyId: string; ax: number; ay: number }
  | { kind: 'text'; bodyId: string; ox: number; oy: number; tx: number; ty: number }
  | { kind: 'anchor'; bodyId: string; ox: number; oy: number; ax: number; ay: number }
  | { kind: 'elbow'; bodyId: string; index: number; ox: number; oy: number; ex: number; ey: number }
  | { kind: 'pan'; ox0: number; oy0: number; sx: number; sy: number }
  | null = null
/** 双击判定：短时间内两次 pointerdown */
let lastTap: { t: number; x: number; y: number; bodyId: string } | null = null
const TAP_MS = 380
const TAP_PX = 8
const ZOOM_MIN = 0.05
const ZOOM_MAX = 80
/** 滚动条厚度（与样式一致） */
const SB_SIZE = 10
/** 视口两侧额外可滚余量（即使适应屏幕也能挪一点） */
const SCROLL_SLACK = 0.45

/** 画布边缘滚动条（与 view.ox/oy 联动） */
const scrollUi = ref({
  rangeX: 1,
  rangeY: 1,
  left: 0,
  top: 0,
  thumbW: 48,
  thumbH: 48,
  thumbLeft: 0,
  thumbTop: 0,
})
let scrollMetrics = {
  oxAt0: 0,
  oyAt0: 0,
  rangeX: 1,
  rangeY: 1,
  trackW: 1,
  trackH: 1,
  thumbW: 48,
  thumbH: 48,
}
let sbDrag: {
  axis: 'x' | 'y'
  startClient: number
  startScroll: number
} | null = null

const selected = computed(() => callouts.value.find((c) => c.bodyId === selectedId.value) ?? null)

const modelBounds = computed(() => {
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const plate of props.plates) {
    for (const s of plate.segments) {
      minX = Math.min(minX, s.x1, s.x2)
      minY = Math.min(minY, s.y1, s.y2)
      maxX = Math.max(maxX, s.x1, s.x2)
      maxY = Math.max(maxY, s.y1, s.y2)
    }
  }
  if (!Number.isFinite(minX)) {
    return { minX: 0, minY: 0, maxX: 100, maxY: 100 }
  }
  return { minX, minY, maxX, maxY }
})

function parseLine1(line1?: string): { material: string; color: string; thickness?: number } {
  const raw = (line1 || '').trim()
  if (!raw) return { material: UNLABELED, color: UNLABELED }
  const m = raw.match(/^(.+?),\s*COLOR:\s*(.+?),\s*(.+?)\s*mm\s*THICK$/i)
  if (!m) return { material: UNLABELED, color: UNLABELED }
  const thick = Number(m[3])
  return {
    material: m[1].trim() || UNLABELED,
    color: m[2].trim() || UNLABELED,
    thickness: Number.isFinite(thick) ? thick : undefined
  }
}

function initCallouts() {
  const bounds = modelBounds.value
  const spanY = Math.max(1, bounds.maxY - bounds.minY)
  const right = bounds.maxX + Math.max(8, (bounds.maxX - bounds.minX) * 0.08)
  const n = props.plates.length
  const savedMap = new Map((props.initialCallouts || []).map((c) => [c.bodyId, c]))
  callouts.value = props.plates.map((plate, i) => {
    const anchor = rightmostPoint(plate.segments) ?? {
      x: (bounds.minX + bounds.maxX) / 2,
      y: bounds.minY + (spanY * (i + 0.5)) / Math.max(n, 1),
    }
    const textY = anchor.y
    const saved = savedMap.get(plate.bodyId)
    if (saved) {
      const parsed = parseLine1(saved.line1)
      const material = (saved.material || '').trim() || parsed.material
      const color = (saved.color || '').trim() || parsed.color
      const specialReq = (saved.specialReq || saved.line2 || '').trim()
      const thickness =
        saved.thickness != null && Number.isFinite(Number(saved.thickness))
          ? Number(saved.thickness)
          : parsed.thickness != null
            ? parsed.thickness
            : plate.thickness
      const hasGeom =
        Number.isFinite(saved.textX) &&
        Number.isFinite(saved.textY) &&
        (Number.isFinite(saved.anchorX) || Number.isFinite(saved.anchorY) || (saved.elbows?.length ?? 0) > 0)
      return {
        bodyId: plate.bodyId,
        calloutNo: String(saved.calloutNo || '').trim(),
        material,
        color,
        specialReq,
        thickness,
        // 锚点始终跟当前投影实体（Z 展开变化后旧锚点会错位）
        anchorX: anchor.x,
        anchorY: anchor.y,
        textX: Number.isFinite(saved.textX) ? Number(saved.textX) : right,
        textY: Number.isFinite(saved.textY) ? Number(saved.textY) : textY,
        elbows: Array.isArray(saved.elbows)
          ? saved.elbows.map((e) => ({ x: Number(e.x), y: Number(e.y) })).filter((e) => Number.isFinite(e.x) && Number.isFinite(e.y))
          : [],
        placed: saved.placed === true || hasGeom || !!String(saved.calloutNo || '').trim(),
      }
    }
    return {
      bodyId: plate.bodyId,
      calloutNo: '',
      material: UNLABELED,
      color: UNLABELED,
      specialReq: '',
      thickness: plate.thickness,
      anchorX: anchor.x,
      anchorY: anchor.y,
      textX: right,
      textY,
      elbows: [],
      placed: false,
    }
  })
  // 序号已有且材料为空时，按 2D 种子预填
  for (const c of callouts.value) {
    if (c.calloutNo) apply2dMaterialSeed(c, false)
  }
  selectedId.value = callouts.value[0]?.bodyId ?? ''
  autoLayoutUnplaced()
  seedHistory()
}

/** 规范化序号，与材料核对一致 */
function normalizeSeedNo(raw?: string | number | null): string {
  if (raw == null) return ''
  return String(raw)
    .trim()
    .replace(/^\[|\]$/g, '')
    .replace(/\s+/g, '')
}

function isBlankMat(raw?: string | null): boolean {
  const t = (raw || '').trim()
  return !t || t === UNLABELED
}

/** 旁注缺项（序号/材料/颜色）；用于列表标记与保存门禁提示 */
type CalloutGap = { bodyId: string; label: string; missing: string[] }

const saveBlockHint = ref('')

function calloutMissingFields(c: StackCallout): string[] {
  const missing: string[] = []
  if (!(c.calloutNo || '').trim()) missing.push('序号')
  if (isBlankMat(c.material)) missing.push('材料')
  if (isBlankMat(c.color)) missing.push('颜色')
  return missing
}

function findCalloutGaps(): CalloutGap[] {
  const gaps: CalloutGap[] = []
  for (const c of callouts.value) {
    const missing = calloutMissingFields(c)
    if (!missing.length) continue
    const label = (c.calloutNo || '').trim() || c.bodyId || '?'
    gaps.push({ bodyId: c.bodyId, label, missing })
  }
  return gaps
}

const incompleteCount = computed(() => findCalloutGaps().length)

function clearSaveBlockHintIfReady() {
  if (!saveBlockHint.value) return
  if (!findCalloutGaps().length) saveBlockHint.value = ''
}

/** 列表角标：缺序号/材料/颜色 */
function calloutGapTag(c: StackCallout): string {
  const missing = calloutMissingFields(c)
  return missing.length ? `缺${missing.join('/')}` : ''
}

function parseSeedThickness(raw?: string | null): number | undefined {
  if (raw == null || raw === '') return undefined
  const s = String(raw).trim().replace(/mm$/i, '').replace(/\s+/g, '')
  const n = Number(s)
  return Number.isFinite(n) ? n : undefined
}

/** 与右侧表单展示一致，避免 0.045 vs 0.0450001 误报冲突 */
function roundThicknessMm(n: number): number {
  return Math.round(n * 1000) / 1000
}

function thicknessMismatch(a: number, b: number): boolean {
  return roundThicknessMm(a) !== roundThicknessMm(b)
}

/** 实体对应的真实 3D 厚度（来自 plates，不被旁注编辑冲掉） */
function plateThicknessOf(bodyId: string): number | undefined {
  const p = props.plates.find((x) => x.bodyId === bodyId)
  if (!p || !Number.isFinite(p.thickness)) return undefined
  return p.thickness
}

/** 深色编辑器内弹窗（风格与层次爆炸编辑一致） */
type EdModalState = {
  title: string
  content: string
  okText: string
  cancelText: string | null
  okPrimary?: boolean
  onOk: () => void
  onCancel?: () => void
}
const edModal = ref<EdModalState | null>(null)
/** 改序号连弹确认时关掉上一张，避免改错实体 */
let seedApplySeq = 0

function destroyPendingThicknessModal() {
  edModal.value = null
}

function openEdModal(state: EdModalState) {
  edModal.value = state
}

function resolveEdModalOk() {
  const m = edModal.value
  edModal.value = null
  m?.onOk()
}

function resolveEdModalCancel() {
  const m = edModal.value
  edModal.value = null
  m?.onCancel?.()
}

function findMaterialSeed(noteNo: string) {
  const key = normalizeSeedNo(noteNo)
  if (!key) return null
  const list = props.materialSeeds || []
  return list.find((s) => normalizeSeedNo(s.noteNo) === key) || null
}

/**
 * 序号命中 2D 时带入材料/色。
 * force=true：改序号时覆盖；false：仅材料为空时预填（打开已存层次不冲掉人工值）。
 * 厚度：打开编辑器仅在与 3D 一致时写入；改序号由 apply2dSeedOnCalloutNoChange 确认后写入。
 */
function apply2dMaterialSeed(c: StackCallout, force: boolean): boolean {
  const seed = findMaterialSeed(c.calloutNo)
  if (!seed) return false
  if (!force && !isBlankMat(c.material)) return false
  const mat = (seed.material || '').trim()
  if (mat) c.material = mat
  const color = (seed.color || '').trim()
  if (color) c.color = color
  if (!force) {
    const th = parseSeedThickness(seed.thickness)
    const plateTh = plateThicknessOf(c.bodyId)
    if (th != null && plateTh != null && !thicknessMismatch(plateTh, th)) c.thickness = th
  }
  return true
}

function findCalloutByBodyId(bodyId: string): StackCallout | undefined {
  return callouts.value.find((x) => x.bodyId === bodyId)
}

/** 用户改序号：材料/色立即带入；2D 厚度与 3D 不一致须确认，禁止静默覆盖 3D */
function apply2dSeedOnCalloutNoChange(c: StackCallout) {
  const bodyId = c.bodyId
  const seq = ++seedApplySeq
  destroyPendingThicknessModal()
  if (!apply2dMaterialSeed(c, true)) {
    paint()
    return
  }
  const seed = findMaterialSeed(c.calloutNo)
  const th2d = parseSeedThickness(seed?.thickness)
  const target = findCalloutByBodyId(bodyId)
  const plateTh = plateThicknessOf(bodyId)
  if (!target || th2d == null || plateTh == null) {
    paint()
    return
  }
  if (!thicknessMismatch(plateTh, th2d)) {
    if (thicknessMismatch(target.thickness, th2d)) {
      target.thickness = th2d
    }
    paint()
    return
  }
  const keep3d = roundThicknessMm(plateTh)
  const from2d = roundThicknessMm(th2d)
  const noteNo = target.calloutNo || ''
  openEdModal({
    title: '厚度与 2D 材料表不一致',
    content: `当前 3D 厚度 ${keep3d} mm，2D 序号 ${noteNo} 为 ${from2d} mm。要用 2D 厚度替换吗？`,
    okText: '使用 2D 厚度',
    cancelText: '保留 3D 厚度',
    okPrimary: true,
    onOk: () => {
      if (seq !== seedApplySeq) return
      const row = findCalloutByBodyId(bodyId)
      if (!row) return
      pushHistory()
      row.thickness = th2d
      paint()
    },
    onCancel: () => {
      if (seq !== seedApplySeq) return
      const row = findCalloutByBodyId(bodyId)
      if (!row) return
      if (thicknessMismatch(row.thickness, plateTh)) {
        pushHistory()
        row.thickness = plateTh
        paint()
      }
    },
  })
  paint()
}

/** 手动改厚度：与 3D 不一致时确认 */
function confirmManualThicknessIfNeeded(bodyId: string, nextTh: number) {
  const plateTh = plateThicknessOf(bodyId)
  if (plateTh == null || !thicknessMismatch(plateTh, nextTh)) return
  const seq = ++seedApplySeq
  destroyPendingThicknessModal()
  const keep3d = roundThicknessMm(plateTh)
  const edited = roundThicknessMm(nextTh)
  openEdModal({
    title: '厚度与 3D 不一致',
    content: `当前 3D 厚度 ${keep3d} mm，您改为 ${edited} mm。确认使用新厚度吗？`,
    okText: '使用新厚度',
    cancelText: '恢复 3D 厚度',
    okPrimary: true,
    onOk: () => {
      /* 表单已写入 nextTh */
    },
    onCancel: () => {
      if (seq !== seedApplySeq) return
      const row = findCalloutByBodyId(bodyId)
      if (!row) return
      pushHistory()
      row.thickness = plateTh
      paint()
    },
  })
}

function extremePoint(
  segs: StackPlateSeg[],
  prefer: 'left' | 'right',
): { x: number; y: number } | null {
  let best: { x: number; y: number } | null = null
  for (const s of segs) {
    for (const p of [
      { x: s.x1, y: s.y1 },
      { x: s.x2, y: s.y2 },
    ]) {
      if (!best) {
        best = p
        continue
      }
      if (prefer === 'right' ? p.x > best.x : p.x < best.x) best = p
    }
  }
  return best
}

function rightmostPoint(segs: StackPlateSeg[]): { x: number; y: number } | null {
  return extremePoint(segs, 'right')
}

function plateCentroidX(segs: StackPlateSeg[]): number | null {
  let sx = 0
  let n = 0
  for (const s of segs) {
    sx += s.x1 + s.x2
    n += 2
  }
  return n ? sx / n : null
}

/** textX 表示序号方框左缘；左侧旁注时引线接到方框右中 */
function isLeftCallout(c: StackCallout): boolean {
  const b = modelBounds.value
  const midX = (b.minX + b.maxX) / 2
  return c.textX + boxWidthModel(c) / 2 < midX
}

function boxWidthModel(c: StackCallout): number {
  const no = (c.calloutNo || '').trim() || '?'
  const px = Math.max(18, no.length * 9 + 8)
  return px / Math.max(view.scale || 1, 0.01)
}

/** 按锚点 Y 拉开 textY，保序防重叠 */
function packCalloutYs(items: StackCallout[], spanY: number, bounds: { minY: number; maxY: number }) {
  if (!items.length) return
  items.sort((a, b) => a.anchorY - b.anchorY)
  const scale = Math.max(view.scale || 1, 0.01)
  const minGap = Math.max(22 / scale, spanY / Math.max(items.length * 2, 8), spanY * 0.012)
  const ys = items.map((c) => c.anchorY)
  for (let i = 1; i < ys.length; i++) {
    if (ys[i]! < ys[i - 1]! + minGap) {
      ys[i] = ys[i - 1]! + minGap
    }
  }
  const topLimit = bounds.maxY + spanY * 0.08
  const botLimit = bounds.minY - spanY * 0.08
  const last = ys[ys.length - 1]!
  if (last > topLimit) {
    const overflow = last - topLimit
    for (let i = 0; i < ys.length; i++) ys[i] = ys[i]! - overflow
  }
  if (ys[0]! < botLimit) {
    const shift = botLimit - ys[0]!
    for (let i = 0; i < ys.length; i++) ys[i] = ys[i]! + shift
  }
  if (ys[ys.length - 1]! > topLimit && ys.length > 1) {
    const lo = ys[0]!
    const hi = ys[ys.length - 1]!
    const range = Math.max(hi - lo, 1e-6)
    for (let i = 0; i < ys.length; i++) {
      ys[i] = botLimit + ((ys[i]! - lo) / range) * (topLimit - botLimit)
    }
  }
  items.forEach((c, i) => {
    c.textY = ys[i]!
  })
}

/**
 * 未手调旁注：按锚点分左右列（实体居中），同侧按高度同排 + 防重叠。
 * 等轴测叠层重心接近中线时，按数量交替分列，避免全挤一侧。
 */
function autoLayoutUnplaced() {
  const bounds = modelBounds.value
  const spanX = Math.max(1, bounds.maxX - bounds.minX)
  const spanY = Math.max(1, bounds.maxY - bounds.minY)
  const midX = (bounds.minX + bounds.maxX) / 2
  const margin = Math.max(10, spanX * 0.1)
  const right = bounds.maxX + margin
  const leftAttach = bounds.minX - margin
  const thresh = spanX * 0.08

  const unplaced: StackCallout[] = []
  for (const c of callouts.value) {
    if (c.placed) continue
    c.elbows = []
    unplaced.push(c)
  }
  if (!unplaced.length) return

  // 先按当前锚点 Y 排序，便于均衡分列
  unplaced.sort((a, b) => a.anchorY - b.anchorY)
  const leftCol: StackCallout[] = []
  const rightCol: StackCallout[] = []
  for (const c of unplaced) {
    const plate = props.plates.find((p) => p.bodyId === c.bodyId)
    const cx = plate ? plateCentroidX(plate.segments) : c.anchorX
    let side: 'left' | 'right'
    if (cx != null && cx < midX - thresh) side = 'left'
    else if (cx != null && cx > midX + thresh) side = 'right'
    else side = leftCol.length <= rightCol.length ? 'left' : 'right'

    const anchor = plate
      ? extremePoint(plate.segments, side) ?? { x: c.anchorX, y: c.anchorY }
      : { x: c.anchorX, y: c.anchorY }
    c.anchorX = anchor.x
    c.anchorY = anchor.y
    if (side === 'left') {
      // textX = 方框左缘；引线接到方框右缘 ≈ leftAttach
      c.textX = leftAttach - boxWidthModel(c)
      leftCol.push(c)
    } else {
      c.textX = right
      rightCol.push(c)
    }
  }

  packCalloutYs(leftCol, spanY, bounds)
  packCalloutYs(rightCol, spanY, bounds)
}

/** 引线终点：右侧接方框左中，左侧接方框右中 */
function leaderAttachPoint(c: StackCallout): { x: number; y: number } {
  if (isLeftCallout(c)) {
    return { x: c.textX + boxWidthModel(c), y: c.textY }
  }
  return { x: c.textX, y: c.textY }
}

/** 引线折线顶点：锚点 → 折点… → 方框内侧中点 */
function leaderPoints(c: StackCallout): { x: number; y: number }[] {
  const end = leaderAttachPoint(c)
  return [{ x: c.anchorX, y: c.anchorY }, ...(c.elbows || []), end]
}

function syncCanvasSize(): { ctx: CanvasRenderingContext2D; w: number; h: number } | null {
  const canvas = canvasRef.value
  if (!canvas) return null
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  const w = canvas.clientWidth
  const h = canvas.clientHeight
  canvas.width = Math.max(1, Math.floor(w * dpr))
  canvas.height = Math.max(1, Math.floor(h * dpr))
  const ctx = canvas.getContext('2d')
  if (!ctx) return null
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  return { ctx, w, h }
}

/** 用 measureText 估旁注块宽（模型坐标），避免左侧文字被裁切 */
function estimateTextWidth(c: StackCallout): number {
  const scale = Math.max(view.scale || 1, 0.01)
  const line1 = formatLine1(c)
  const line2 = (c.specialReq || '').trim()
  const no = (c.calloutNo || '').trim() || '?'
  const boxPx = Math.max(18, no.length * 9 + 8)
  let textPx = Math.max(line1.length, line2.length || 0) * 7.4
  const canvas = canvasRef.value
  const ctx = canvas?.getContext('2d')
  if (ctx) {
    ctx.font = '11px "Courier New", Consolas, monospace'
    textPx = Math.max(ctx.measureText(line1).width, line2 ? ctx.measureText(line2).width : 0)
  }
  // 方框 + 间距 + 文字 + 安全边
  return (boxPx + 10 + textPx + 16) / scale
}

function contentBounds(): { minX: number; minY: number; maxX: number; maxY: number } {
  const b = modelBounds.value
  let minX = b.minX
  let minY = b.minY
  let maxX = b.maxX
  let maxY = b.maxY
  for (const plate of props.plates) {
    for (const s of plate.segments) {
      minX = Math.min(minX, s.x1, s.x2)
      minY = Math.min(minY, s.y1, s.y2)
      maxX = Math.max(maxX, s.x1, s.x2)
      maxY = Math.max(maxY, s.y1, s.y2)
    }
  }
  for (const c of callouts.value) {
    const blockW = estimateTextWidth(c)
    const boxW = boxWidthModel(c)
    if (isLeftCallout(c)) {
      minX = Math.min(minX, c.anchorX, c.textX + boxW - blockW)
      maxX = Math.max(maxX, c.anchorX, c.textX + boxW)
    } else {
      minX = Math.min(minX, c.anchorX, c.textX)
      maxX = Math.max(maxX, c.anchorX, c.textX + blockW)
    }
    minY = Math.min(minY, c.anchorY, c.textY - 6)
    maxY = Math.max(maxY, c.anchorY, c.textY + 6)
    for (const e of c.elbows || []) {
      minX = Math.min(minX, e.x)
      maxX = Math.max(maxX, e.x)
      minY = Math.min(minY, e.y)
      maxY = Math.max(maxY, e.y)
    }
  }
  if (!Number.isFinite(minX)) {
    return { minX: 0, minY: 0, maxX: 100, maxY: 100 }
  }
  return { minX, minY, maxX, maxY }
}

/**
 * 将实体+旁注适配进画布（不在每次 paint 调用，以保留手型平移）。
 * reserveTable：为右下 3D 表与左上 2D 参考表预留像素区；预留有上限，避免中间爆炸图被挤没。
 */
function fitView(opts?: { reserveTable?: boolean; forExport?: boolean }) {
  const sized = syncCanvasSize()
  if (!sized) return
  const { ctx, w, h } = sized
  const pad = view.pad
  let leftPad = pad
  let rightPad = pad
  let topPad = pad
  let botPad = pad
  if (opts?.reserveTable) {
    const layout3d = measureMaterialTableLayout(ctx, w, h, !!opts.forExport)
    const layout2d = measure2dSeedTableLayout(ctx, w, h, !!opts.forExport)
    const gap = 12
    // 右下 3D 旁注表：预留不超过画布约 28%
    const rightCap = Math.max(120, w * 0.28)
    rightPad = Math.max(pad, Math.min(layout3d.margin + layout3d.tableW + gap, rightCap))
    botPad = Math.max(pad, Math.min(layout3d.tableH * 0.12, h * 0.06) + layout3d.hintReserve)
    // 左上 2D 材料参考表：预留跟真实表宽/高对齐，左不超过画布约 40%
    if (layout2d.tableW > 0) {
      const leftCap = Math.max(120, w * 0.4)
      leftPad = Math.max(pad, Math.min(layout2d.margin + layout2d.tableW + gap, leftCap))
      topPad = Math.max(pad, Math.min(layout2d.tableH + gap * 0.5, h * 0.42))
    }
  }
  // 旁注宽依赖 scale，迭代 2 次收敛
  for (let i = 0; i < 2; i++) {
    const b = contentBounds()
    const bw = Math.max(1e-6, b.maxX - b.minX)
    const bh = Math.max(1e-6, b.maxY - b.minY)
    const regionW = Math.max(1, w - leftPad - rightPad)
    const regionH = Math.max(1, h - topPad - botPad)
    const scale = Math.min(regionW / bw, regionH / bh) * FIT_FACTOR
    view.scale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, scale))
    view.ox = leftPad - b.minX * view.scale + (regionW - bw * view.scale) / 2
    view.oy = h - botPad + b.minY * view.scale - (regionH - bh * view.scale) / 2
  }
}

function fitToScreen() {
  fitView({ reserveTable: true, forExport: false })
  refreshScrollUi({ expandToView: true })
  paint()
}

function toScreen(x: number, y: number): { x: number; y: number } {
  return { x: view.ox + x * view.scale, y: view.oy - y * view.scale }
}

function toModel(sx: number, sy: number): { x: number; y: number } {
  return { x: (sx - view.ox) / view.scale, y: (view.oy - sy) / view.scale }
}

function formatLine1(c: StackCallout): string {
  const mat = (c.material || '').trim() || UNLABELED
  const col = (c.color || '').trim() || UNLABELED
  const thick = Number.isFinite(c.thickness) ? String(c.thickness) : UNLABELED
  return `${mat}, COLOR: ${col}, ${thick} mm THICK`
}

function formatLine2(c: StackCallout): string | null {
  const s = (c.specialReq || '').trim()
  return s ? s : null
}

function paint(opts?: { exportMode?: boolean; fit?: boolean }) {
  const exportMode = !!opts?.exportMode
  if (exportMode || opts?.fit) {
    // 导出/强制 fit：预留材料表，避免旁注与表重叠
    fitView({ reserveTable: true })
  } else {
    syncCanvasSize()
  }
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  const w = canvas.clientWidth
  const h = canvas.clientHeight
  ctx.fillStyle = '#000'
  ctx.fillRect(0, 0, w, h)
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'

  // 实体：导出一律白线；编辑时选中可黄高亮
  ctx.strokeStyle = '#ffffff'
  ctx.lineWidth = 1.1
  for (const plate of props.plates) {
    const on = !exportMode && plate.bodyId === selectedId.value
    ctx.strokeStyle = on ? '#fbbf24' : '#ffffff'
    ctx.lineWidth = on ? 1.6 : 1.1
    ctx.beginPath()
    for (const s of plate.segments) {
      const a = toScreen(s.x1, s.y1)
      const b = toScreen(s.x2, s.y2)
      ctx.moveTo(a.x, a.y)
      ctx.lineTo(b.x, b.y)
    }
    ctx.stroke()
  }

  // 引线 + 方框序号 + 旁注（字号略小，便于一屏看全）
  for (const c of callouts.value) {
    const on = !exportMode && c.bodyId === selectedId.value
    const left = isLeftCallout(c)
    const pts = leaderPoints(c).map((p) => toScreen(p.x, p.y))
    const t = pts[pts.length - 1]
    const no = (c.calloutNo || '').trim() || '?'
    const boxW = Math.max(18, no.length * 9 + 8)
    const boxH = 18
    const boxX = left ? t.x - boxW : t.x
    const boxY = t.y - boxH / 2

    ctx.strokeStyle = on ? '#fbbf24' : '#ffffff'
    ctx.fillStyle = '#ffffff'
    ctx.lineWidth = on ? 1.4 : 1
    ctx.beginPath()
    ctx.moveTo(pts[0].x, pts[0].y)
    for (let i = 1; i < pts.length; i++) {
      ctx.lineTo(pts[i].x, pts[i].y)
    }
    ctx.stroke()
    if (pts.length >= 2) {
      drawArrow(ctx, pts[0].x, pts[0].y, pts[1].x, pts[1].y)
    }
    if (on) {
      for (const e of c.elbows || []) {
        const p = toScreen(e.x, e.y)
        ctx.fillStyle = '#fbbf24'
        ctx.beginPath()
        ctx.arc(p.x, p.y, 4.5, 0, Math.PI * 2)
        ctx.fill()
        ctx.strokeStyle = '#000'
        ctx.lineWidth = 1
        ctx.stroke()
      }
      ctx.strokeStyle = '#fbbf24'
      ctx.fillStyle = '#ffffff'
      ctx.lineWidth = 1.4
    }

    ctx.strokeRect(boxX, boxY, boxW, boxH)
    ctx.font = 'bold 11px "Courier New", Consolas, monospace'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(no, boxX + boxW / 2, t.y)

    const line1 = formatLine1(c)
    const line2 = formatLine2(c)
    ctx.font = '11px "Courier New", Consolas, monospace'
    ctx.textBaseline = 'middle'
    if (left) {
      ctx.textAlign = 'right'
      const tx = boxX - 6
      if (line2) {
        ctx.fillText(line1, tx, t.y - 7)
        ctx.fillText(line2, tx, t.y + 7)
      } else {
        ctx.fillText(line1, tx, t.y)
      }
    } else {
      ctx.textAlign = 'left'
      const tx = boxX + boxW + 6
      if (line2) {
        ctx.fillText(line1, tx, t.y - 7)
        ctx.fillText(line2, tx, t.y + 7)
      } else {
        ctx.fillText(line1, tx, t.y)
      }
    }
  }

  // 左上角 2D 已确认材料参考表
  draw2dSeedTable(ctx, w, h, exportMode)
  // 右下角材料表（图二风格：表头在下，序号自下而上递增）
  drawMaterialTable(ctx, w, h, exportMode)

  if (!exportMode) {
    ctx.fillStyle = '#94a3b8'
    ctx.font = '11px sans-serif'
    ctx.textAlign = 'left'
    ctx.textBaseline = 'alphabetic'
    ctx.fillText(
      '左上 2D 材料对照 · 改序号自动带材料 · 厚度与 3D 不一致须确认 · Ctrl+C/V 属性 · Ctrl+Z/Y 撤销 · 边缘滚动条/滚轮平移 · Ctrl+滚轮缩放',
      12,
      h - 12,
    )
  }
  refreshScrollUi({ expandToView: true })
}

type MaterialTableRow = {
  no: string
  material: string
  thickness: string
  color: string
}

/** 旁注 → 材料表行；按序号数字升序（表底小、表顶大） */
function buildMaterialTableRows(): MaterialTableRow[] {
  const rows: MaterialTableRow[] = callouts.value.map((c) => {
    const thick = Number.isFinite(c.thickness) ? String(c.thickness) : UNLABELED
    return {
      no: (c.calloutNo || '').trim() || '?',
      material: ((c.material || '').trim() || UNLABELED).toUpperCase(),
      thickness: `${thick} mm`,
      color: ((c.color || '').trim() || UNLABELED).toUpperCase(),
    }
  })
  rows.sort((a, b) => {
    const na = Number(a.no)
    const nb = Number(b.no)
    const aNum = Number.isFinite(na)
    const bNum = Number.isFinite(nb)
    if (aNum && bNum) return na - nb
    if (aNum) return -1
    if (bNum) return 1
    return a.no.localeCompare(b.no, 'en')
  })
  return rows
}

type MaterialTableLayout = {
  rows: MaterialTableRow[]
  header: MaterialTableRow
  margin: number
  hintReserve: number
  padX: number
  rowH: number
  fontPx: number
  noW: number
  matW: number
  thickW: number
  colorW: number
  tableW: number
  tableH: number
  x0: number
  yTop: number
  yBottom: number
}

/** 测算材料表几何：供绘制与 fitView 预留共用；宽不超过画布约 36%，材料列截断 */
function measureMaterialTableLayout(
  ctx: CanvasRenderingContext2D,
  canvasW: number,
  canvasH: number,
  exportMode: boolean,
): MaterialTableLayout {
  const rows = buildMaterialTableRows()
  const header: MaterialTableRow = {
    no: 'NO.',
    material: 'MATERIAL NAME',
    thickness: 'THICKNESS',
    color: 'COLOR',
  }
  const margin = 10
  const hintReserve = exportMode ? 8 : 28
  const maxTableH = Math.max(80, canvasH * 0.5)
  const maxTableW = Math.max(140, Math.min(canvasW * 0.36, canvasW - margin * 2))
  const nRows = Math.max(1, rows.length + 1)
  const padX = 5

  let rowH = Math.min(20, Math.max(13, Math.floor(maxTableH / nRows)))
  let fontPx = rowH >= 18 ? 11 : rowH >= 15 ? 10 : 9

  const measureCols = (px: number) => {
    ctx.font = `${px}px "Courier New", Consolas, monospace`
    const measure = (s: string) => ctx.measureText(s).width
    let noW = measure(header.no)
    let matW = measure(header.material)
    let thickW = measure(header.thickness)
    let colorW = measure(header.color)
    for (const r of rows) {
      noW = Math.max(noW, measure(r.no))
      matW = Math.max(matW, measure(r.material))
      thickW = Math.max(thickW, measure(r.thickness))
      colorW = Math.max(colorW, measure(r.color))
    }
    noW = Math.ceil(noW + padX * 2)
    thickW = Math.ceil(thickW + padX * 2)
    colorW = Math.ceil(colorW + padX * 2)
    const other = noW + thickW + colorW
    matW = Math.min(Math.ceil(matW + padX * 2), Math.max(72, maxTableW - other))
    return { noW, matW, thickW, colorW, tableW: noW + matW + thickW + colorW }
  }

  let cols = measureCols(fontPx)
  while (cols.tableW > maxTableW && fontPx > 7) {
    fontPx -= 1
    rowH = Math.max(12, Math.min(rowH, fontPx + 7))
    cols = measureCols(fontPx)
  }
  let tableH = nRows * rowH
  if (tableH > maxTableH) {
    rowH = Math.max(12, Math.floor(maxTableH / nRows))
    fontPx = Math.min(fontPx, rowH >= 18 ? 11 : rowH >= 15 ? 10 : 9)
    cols = measureCols(fontPx)
    tableH = nRows * rowH
  }

  const { noW, matW, thickW, colorW, tableW } = cols
  const x0 = canvasW - margin - tableW
  const yBottom = canvasH - hintReserve
  const yTop = yBottom - tableH
  return {
    rows,
    header,
    margin,
    hintReserve,
    padX,
    rowH,
    fontPx,
    noW,
    matW,
    thickW,
    colorW,
    tableW,
    tableH,
    x0,
    yTop,
    yBottom,
  }
}

/**
 * 画布右下角材料表：黑底绿框绿字；表头贴底，数据行向上排。
 * 表宽受限；材料名过长截断显示。
 */
function drawMaterialTable(
  ctx: CanvasRenderingContext2D,
  canvasW: number,
  canvasH: number,
  exportMode: boolean,
) {
  const layout = measureMaterialTableLayout(ctx, canvasW, canvasH, exportMode)
  const {
    rows,
    header,
    padX,
    rowH,
    fontPx,
    noW,
    matW,
    thickW,
    colorW,
    tableW,
    tableH,
    x0,
    yTop,
    yBottom,
  } = layout
  const GREEN = '#00ff00'

  ctx.fillStyle = '#000000'
  ctx.fillRect(x0, yTop, tableW, tableH)
  ctx.strokeStyle = GREEN
  ctx.fillStyle = GREEN
  ctx.lineWidth = 1
  ctx.font = `${fontPx}px "Courier New", Consolas, monospace`
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'

  const colXs = [x0, x0 + noW, x0 + noW + matW, x0 + noW + matW + thickW, x0 + tableW]
  const colWs = [noW, matW, thickW, colorW]

  function clipText(text: string, maxW: number): string {
    if (ctx.measureText(text).width <= maxW) return text
    let s = text
    while (s.length > 1 && ctx.measureText(s + '…').width > maxW) {
      s = s.slice(0, -1)
    }
    return s + '…'
  }

  function drawCellRow(row: MaterialTableRow, y: number, boldNo: boolean) {
    const texts = [row.no, row.material, row.thickness, row.color]
    for (let i = 0; i < 4; i++) {
      const cx = colXs[i]!
      const cw = colWs[i]!
      ctx.strokeRect(cx, y, cw, rowH)
      ctx.font = `${boldNo && i === 0 ? 'bold ' : ''}${fontPx}px "Courier New", Consolas, monospace`
      const t = clipText(texts[i]!, cw - padX * 2)
      ctx.fillText(t, cx + padX, y + rowH / 2)
    }
  }

  let y = yBottom - rowH
  drawCellRow(header, y, true)
  for (const r of rows) {
    y -= rowH
    drawCellRow(r, y, false)
  }
  ctx.strokeRect(x0, yTop, tableW, tableH)
}

/** ① 已确认 2D 材料 → 左上参考表行 */
function build2dSeedTableRows(): MaterialTableRow[] {
  const seeds = props.materialSeeds || []
  const rows: MaterialTableRow[] = seeds
    .filter((s) => normalizeSeedNo(s.noteNo))
    .map((s) => {
      const thick = (s.thickness || '').trim() || UNLABELED
      return {
        no: normalizeSeedNo(s.noteNo),
        material: ((s.material || '').trim() || UNLABELED).toUpperCase(),
        thickness: /mm$/i.test(thick) || thick === UNLABELED ? thick : `${thick} mm`,
        color: ((s.color || '').trim() || UNLABELED).toUpperCase(),
      }
    })
  rows.sort((a, b) => {
    const na = Number(a.no)
    const nb = Number(b.no)
    const aNum = Number.isFinite(na)
    const bNum = Number.isFinite(nb)
    if (aNum && bNum) return na - nb
    if (aNum) return -1
    if (bNum) return 1
    return a.no.localeCompare(b.no, 'en')
  })
  return rows
}

type SeedTableLayout = MaterialTableLayout & {
  titleH: number
  /** 表头 + 数据行的材料列折行（完整展示，不截字） */
  matLines: string[][]
  /** 各行高度（含表头），与 matLines 对齐 */
  rowHeights: number[]
}

/** 按可用宽度折行（空格优先，超宽 token 按字符折），保证全文可见 */
function wrapCanvasText(measure: (s: string) => number, text: string, maxW: number): string[] {
  const t = (text || '').trim() || ' '
  if (maxW <= 1 || measure(t) <= maxW) return [t]
  const lines: string[] = []
  const tokens = t.split(/(\s+)/)
  let cur = ''
  const flush = () => {
    if (cur) {
      lines.push(cur)
      cur = ''
    }
  }
  for (const tok of tokens) {
    if (!tok) continue
    if (/^\s+$/.test(tok)) {
      if (cur && measure(cur + tok) > maxW) flush()
      else if (cur) cur += tok
      continue
    }
    if (measure(cur + tok) <= maxW) {
      cur += tok
      continue
    }
    flush()
    if (measure(tok) <= maxW) {
      cur = tok
      continue
    }
    let chunk = ''
    for (const ch of tok) {
      if (chunk && measure(chunk + ch) > maxW) {
        lines.push(chunk)
        chunk = ch
      } else {
        chunk += ch
      }
    }
    cur = chunk
  }
  flush()
  return lines.length ? lines : [t]
}

/** 测算左上 2D 参考表；无种子时 tableW=0。列宽按全文，超画布才缩小字号/材料列换行 */
function measure2dSeedTableLayout(
  ctx: CanvasRenderingContext2D,
  canvasW: number,
  canvasH: number,
  _exportMode: boolean,
): SeedTableLayout {
  const rows = build2dSeedTableRows()
  const header: MaterialTableRow = {
    no: 'NO.',
    material: '2D MATERIAL',
    thickness: 'THK',
    color: 'COLOR',
  }
  const margin = 10
  const titleH = 16
  const hintReserve = 0
  const empty: SeedTableLayout = {
    rows,
    header,
    margin,
    hintReserve,
    padX: 4,
    rowH: 0,
    fontPx: 9,
    noW: 0,
    matW: 0,
    thickW: 0,
    colorW: 0,
    tableW: 0,
    tableH: 0,
    x0: margin,
    yTop: margin,
    yBottom: margin,
    titleH,
    matLines: [],
    rowHeights: [],
  }
  if (!rows.length) return empty

  const maxTableH = Math.max(80, canvasH * 0.5 - titleH)
  const maxTableW = Math.max(160, canvasW - margin * 2)
  const padX = 4
  const lineGap = 2

  let fontPx = 10
  let lineH = 16

  const measureCols = (px: number) => {
    ctx.font = `${px}px "Courier New", Consolas, monospace`
    const measure = (s: string) => ctx.measureText(s).width
    let noW = measure(header.no)
    let matW = measure(header.material)
    let thickW = measure(header.thickness)
    let colorW = measure(header.color)
    for (const r of rows) {
      noW = Math.max(noW, measure(r.no))
      matW = Math.max(matW, measure(r.material))
      thickW = Math.max(thickW, measure(r.thickness))
      colorW = Math.max(colorW, measure(r.color))
    }
    noW = Math.ceil(noW + padX * 2)
    matW = Math.ceil(matW + padX * 2)
    thickW = Math.ceil(thickW + padX * 2)
    colorW = Math.ceil(colorW + padX * 2)
    return { noW, matW, thickW, colorW, tableW: noW + matW + thickW + colorW }
  }

  const applyWidthCap = (cols: ReturnType<typeof measureCols>, wrap: boolean) => {
    if (!wrap) return cols
    const other = cols.noW + cols.thickW + cols.colorW
    const matW = Math.max(80, maxTableW - other)
    return { ...cols, matW, tableW: other + matW }
  }

  const buildMatLines = (cols: ReturnType<typeof measureCols>, wrap: boolean): string[][] => {
    ctx.font = `${fontPx}px "Courier New", Consolas, monospace`
    const measure = (s: string) => ctx.measureText(s).width
    const inner = Math.max(8, cols.matW - padX * 2)
    const one = (s: string) => (wrap ? wrapCanvasText(measure, s, inner) : [s])
    return [one(header.material), ...rows.map((r) => one(r.material))]
  }

  const rowHeightsOf = (matLines: string[][], lh: number, px: number) =>
    matLines.map((ls) => Math.max(lh, ls.length * (px + lineGap) + 4))

  let cols = measureCols(fontPx)
  while (cols.tableW > maxTableW && fontPx > 7) {
    fontPx -= 1
    lineH = Math.max(12, fontPx + 6)
    cols = measureCols(fontPx)
  }
  let wrapMat = cols.tableW > maxTableW
  cols = applyWidthCap(cols, wrapMat)
  let matLines = buildMatLines(cols, wrapMat)
  let rowHeights = rowHeightsOf(matLines, lineH, fontPx)
  let innerH = rowHeights.reduce((a, b) => a + b, 0)

  while (innerH > maxTableH && fontPx > 7) {
    fontPx -= 1
    lineH = Math.max(11, fontPx + 6)
    cols = measureCols(fontPx)
    wrapMat = cols.tableW > maxTableW
    cols = applyWidthCap(cols, wrapMat)
    matLines = buildMatLines(cols, wrapMat)
    rowHeights = rowHeightsOf(matLines, lineH, fontPx)
    innerH = rowHeights.reduce((a, b) => a + b, 0)
  }

  const { noW, matW, thickW, colorW, tableW } = cols
  const x0 = margin
  const yTop = margin + titleH
  const yBottom = yTop + innerH
  return {
    rows,
    header,
    margin,
    hintReserve,
    padX,
    rowH: lineH,
    fontPx,
    noW,
    matW,
    thickW,
    colorW,
    tableW,
    tableH: innerH + titleH,
    x0,
    yTop,
    yBottom,
    titleH,
    matLines,
    rowHeights,
  }
}

/**
 * 画布左上角：① 已确认 2D 材料参考表（橙框，与右下绿表区分）
 * 列宽按内容；超画布时材料列换行，不截省略号。
 */
function draw2dSeedTable(
  ctx: CanvasRenderingContext2D,
  canvasW: number,
  canvasH: number,
  exportMode: boolean,
) {
  const layout = measure2dSeedTableLayout(ctx, canvasW, canvasH, exportMode)
  if (!layout.tableW) return
  const {
    rows,
    header,
    padX,
    fontPx,
    noW,
    matW,
    thickW,
    tableW,
    x0,
    yTop,
    titleH,
    margin,
    matLines,
    rowHeights,
  } = layout
  const ORANGE = '#f5a623'
  const innerH = rowHeights.reduce((a, b) => a + b, 0)

  ctx.fillStyle = ORANGE
  ctx.font = `bold ${Math.max(10, fontPx)}px sans-serif`
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'
  ctx.fillText('2D 材料（已确认）', x0, margin + titleH / 2)

  const tableTop = yTop
  ctx.fillStyle = '#000000'
  ctx.fillRect(x0, tableTop, tableW, innerH)
  ctx.strokeStyle = ORANGE
  ctx.fillStyle = ORANGE
  ctx.lineWidth = 1
  ctx.font = `${fontPx}px "Courier New", Consolas, monospace`
  ctx.textAlign = 'left'
  ctx.textBaseline = 'middle'

  const colXs = [x0, x0 + noW, x0 + noW + matW, x0 + noW + matW + thickW, x0 + tableW]
  const colWs = [noW, matW, thickW, layout.colorW]
  const allRows: MaterialTableRow[] = [header, ...rows]
  const lineGap = 2

  function drawCellRow(row: MaterialTableRow, y: number, rh: number, matRow: string[], boldNo: boolean) {
    const texts = [row.no, row.material, row.thickness, row.color]
    for (let i = 0; i < 4; i++) {
      const cx = colXs[i]!
      const cw = colWs[i]!
      ctx.strokeRect(cx, y, cw, rh)
      ctx.font = `${boldNo && i === 0 ? 'bold ' : ''}${fontPx}px "Courier New", Consolas, monospace`
      if (i === 1) {
        const blockH = matRow.length * (fontPx + lineGap)
        let ly = y + (rh - blockH) / 2 + fontPx / 2
        for (const line of matRow) {
          ctx.fillText(line, cx + padX, ly)
          ly += fontPx + lineGap
        }
      } else {
        ctx.fillText(texts[i]!, cx + padX, y + rh / 2)
      }
    }
  }

  let y = tableTop
  for (let i = 0; i < allRows.length; i++) {
    const rh = rowHeights[i] || layout.rowH
    drawCellRow(allRows[i]!, y, rh, matLines[i] || [allRows[i]!.material], i === 0)
    y += rh
  }
  ctx.strokeRect(x0, tableTop, tableW, innerH)
}

function drawArrow(ctx: CanvasRenderingContext2D, ax: number, ay: number, bx: number, by: number) {
  // 箭头画在锚点侧（指向实体）
  const dx = ax - bx
  const dy = ay - by
  const len = Math.hypot(dx, dy) || 1
  const ux = dx / len
  const uy = dy / len
  const s = 7
  ctx.beginPath()
  ctx.moveTo(ax, ay)
  ctx.lineTo(ax - ux * s - uy * s * 0.45, ay - uy * s + ux * s * 0.45)
  ctx.moveTo(ax, ay)
  ctx.lineTo(ax - ux * s + uy * s * 0.45, ay - uy * s - ux * s * 0.45)
  ctx.stroke()
}

function hitPlate(mx: number, my: number): string | null {
  const thresh = 6 / Math.max(view.scale, 0.01)
  let best: { id: string; d: number } | null = null
  for (const plate of props.plates) {
    for (const s of plate.segments) {
      const d = distToSeg(mx, my, s.x1, s.y1, s.x2, s.y2)
      if (d <= thresh && (!best || d < best.d)) best = { id: plate.bodyId, d }
    }
  }
  return best?.id ?? null
}

function distToSeg(px: number, py: number, x1: number, y1: number, x2: number, y2: number): number {
  const dx = x2 - x1
  const dy = y2 - y1
  const len2 = dx * dx + dy * dy || 1
  let t = ((px - x1) * dx + (py - y1) * dy) / len2
  t = Math.max(0, Math.min(1, t))
  return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))
}

function hitHandle(
  sx: number,
  sy: number,
): { kind: 'text' | 'anchor' | 'elbow'; bodyId: string; index?: number } | null {
  for (const c of callouts.value) {
    // 折点优先
    for (let i = 0; i < (c.elbows || []).length; i++) {
      const e = c.elbows[i]
      const p = toScreen(e.x, e.y)
      if (Math.hypot(sx - p.x, sy - p.y) <= 9) {
        return { kind: 'elbow', bodyId: c.bodyId, index: i }
      }
    }
    const t = toScreen(c.textX, c.textY)
    const no = (c.calloutNo || '').trim() || '?'
    const boxW = Math.max(18, no.length * 9 + 8)
    const left = isLeftCallout(c)
    const blockPx = estimateTextWidth(c) * Math.max(view.scale, 0.01)
    const hitL = left ? t.x + boxW - blockPx - 4 : t.x - 4
    const hitR = left ? t.x + boxW + 8 : t.x + blockPx + 8
    if (sx >= hitL && sx <= hitR && Math.abs(sy - t.y) <= 18) {
      return { kind: 'text', bodyId: c.bodyId }
    }
    const a = toScreen(c.anchorX, c.anchorY)
    if (Math.hypot(sx - a.x, sy - a.y) <= 10) {
      return { kind: 'anchor', bodyId: c.bodyId }
    }
  }
  return null
}

/** 命中引线某段，返回 callout 与段索引（插入折点用） */
function hitLeaderSeg(
  mx: number,
  my: number,
): { bodyId: string; segIndex: number; x: number; y: number } | null {
  const thresh = 7 / Math.max(view.scale, 0.01)
  let best: { bodyId: string; segIndex: number; x: number; y: number; d: number } | null = null
  for (const c of callouts.value) {
    const pts = leaderPoints(c)
    for (let i = 0; i < pts.length - 1; i++) {
      const a = pts[i]
      const b = pts[i + 1]
      const proj = projectOnSeg(mx, my, a.x, a.y, b.x, b.y)
      if (proj.d <= thresh && (!best || proj.d < best.d)) {
        best = { bodyId: c.bodyId, segIndex: i, x: proj.x, y: proj.y, d: proj.d }
      }
    }
  }
  return best
}

function projectOnSeg(
  px: number,
  py: number,
  x1: number,
  y1: number,
  x2: number,
  y2: number,
): { x: number; y: number; d: number; t: number } {
  const dx = x2 - x1
  const dy = y2 - y1
  const len2 = dx * dx + dy * dy || 1
  let t = ((px - x1) * dx + (py - y1) * dy) / len2
  t = Math.max(0, Math.min(1, t))
  const x = x1 + t * dx
  const y = y1 + t * dy
  return { x, y, t, d: Math.hypot(px - x, py - y) }
}

function onPointerDown(ev: PointerEvent) {
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const sx = ev.clientX - rect.left
  const sy = ev.clientY - rect.top
  const m = toModel(sx, sy)
  const now = Date.now()

  // 中键或手型模式空白处：平移画布
  const wantPan = ev.button === 1 || (ev.button === 0 && handMode.value)
  if (wantPan && ev.button === 1) {
    ev.preventDefault()
  }

  // 双击：折点删除 / 线段加折点（手型下也可用）
  if (
    ev.button === 0
    && lastTap
    && now - lastTap.t <= TAP_MS
    && Math.hypot(sx - lastTap.x, sy - lastTap.y) <= TAP_PX
  ) {
    lastTap = null
    const elbowHit = hitHandle(sx, sy)
    // 双击删折点也入历史
    if (elbowHit?.kind === 'elbow' && elbowHit.index != null) {
      const c = callouts.value.find((row) => row.bodyId === elbowHit.bodyId)
      if (c) {
        pushHistory()
        c.elbows.splice(elbowHit.index, 1)
        c.placed = true
        selectedId.value = c.bodyId
        paint()
      }
      return
    }
    const seg = hitLeaderSeg(m.x, m.y)
    if (seg) {
      insertElbow(seg.bodyId, seg.segIndex, seg.x, seg.y)
      return
    }
  }
  if (ev.button === 0) {
    lastTap = { t: now, x: sx, y: sy, bodyId: '' }
  }

  // 优先命中手柄（手型开启时仍可调旁注）
  if (ev.button === 0) {
    const handle = hitHandle(sx, sy)
    if (handle) {
      selectedId.value = handle.bodyId
      const c = callouts.value.find((row) => row.bodyId === handle.bodyId)
      if (!c) return
      pushHistory()
      if (handle.kind === 'text') {
        drag = { kind: 'text', bodyId: c.bodyId, ox: sx, oy: sy, tx: c.textX, ty: c.textY }
      } else if (handle.kind === 'anchor') {
        drag = { kind: 'anchor', bodyId: c.bodyId, ox: sx, oy: sy, ax: c.anchorX, ay: c.anchorY }
      } else if (handle.kind === 'elbow' && handle.index != null) {
        const e = c.elbows[handle.index]
        drag = {
          kind: 'elbow',
          bodyId: c.bodyId,
          index: handle.index,
          ox: sx,
          oy: sy,
          ex: e.x,
          ey: e.y,
        }
      }
      canvas.setPointerCapture(ev.pointerId)
      paint()
      return
    }
  }

  if (wantPan) {
    drag = { kind: 'pan', ox0: view.ox, oy0: view.oy, sx, sy }
    canvas.setPointerCapture(ev.pointerId)
    return
  }

  if (ev.button !== 0) return

  // 点在引线上：选中，便于再双击加折点
  const onLeader = hitLeaderSeg(m.x, m.y)
  if (onLeader) {
    selectedId.value = onLeader.bodyId
    paint()
  }

  const bodyId = hitPlate(m.x, m.y)
  if (bodyId) {
    selectedId.value = bodyId
    const plate = props.plates.find((p) => p.bodyId === bodyId)
    const anchor = plate ? rightmostPoint(plate.segments) : { x: m.x, y: m.y }
    const c = callouts.value.find((row) => row.bodyId === bodyId)
    pushHistory()
    if (c && anchor) {
      c.anchorX = anchor.x
      c.anchorY = anchor.y
      c.elbows = []
    }
    drag = { kind: 'place', bodyId, ax: anchor?.x ?? m.x, ay: anchor?.y ?? m.y }
    canvas.setPointerCapture(ev.pointerId)
    paint()
  }
}

function insertElbow(bodyId: string, segIndex: number, x: number, y: number) {
  const c = callouts.value.find((row) => row.bodyId === bodyId)
  if (!c) return
  if ((c.elbows || []).length >= MAX_ELBOWS) {
    message.warning(`单条引线最多 ${MAX_ELBOWS} 个折点`)
    return
  }
  pushHistory()
  c.elbows.splice(segIndex, 0, { x, y })
  c.placed = true
  selectedId.value = bodyId
  message.success('已加折点，可拖动转向；双击折点可删除', 2)
  paint()
}

function clearElbows() {
  const c = selected.value
  if (!c) return
  pushHistory()
  c.elbows = []
  c.placed = true
  paint()
}

function addOrthoElbow() {
  const c = selected.value
  if (!c) return
  if ((c.elbows || []).length >= MAX_ELBOWS) {
    message.warning(`单条引线最多 ${MAX_ELBOWS} 个折点`)
    return
  }
  pushHistory()
  c.elbows.push({ x: leaderAttachPoint(c).x, y: c.anchorY })
  c.placed = true
  paint()
}

function onPointerMove(ev: PointerEvent) {
  if (!drag) return
  const canvas = canvasRef.value
  if (!canvas) return
  const rect = canvas.getBoundingClientRect()
  const sx = ev.clientX - rect.left
  const sy = ev.clientY - rect.top
  if (drag.kind === 'pan') {
    view.ox = drag.ox0 + (sx - drag.sx)
    view.oy = drag.oy0 + (sy - drag.sy)
    paint()
    return
  }
  const m = toModel(sx, sy)
  const c = callouts.value.find((row) => row.bodyId === drag!.bodyId)
  if (!c) return
  if (drag.kind === 'place') {
    c.textX = m.x
    c.textY = m.y
    c.anchorX = drag.ax
    c.anchorY = drag.ay
    c.placed = true
  } else if (drag.kind === 'text') {
    const dm = toModel(sx, sy)
    const om = toModel(drag.ox, drag.oy)
    c.textX = drag.tx + (dm.x - om.x)
    c.textY = drag.ty + (dm.y - om.y)
    c.placed = true
  } else if (drag.kind === 'anchor') {
    const dm = toModel(sx, sy)
    const om = toModel(drag.ox, drag.oy)
    c.anchorX = drag.ax + (dm.x - om.x)
    c.anchorY = drag.ay + (dm.y - om.y)
    c.placed = true
  } else if (drag.kind === 'elbow') {
    const dm = toModel(sx, sy)
    const om = toModel(drag.ox, drag.oy)
    const e = c.elbows[drag.index]
    if (e) {
      e.x = drag.ex + (dm.x - om.x)
      e.y = drag.ey + (dm.y - om.y)
      c.placed = true
    }
  }
  paint()
}

function onPointerUp() {
  drag = null
  sbDrag = null
  paint()
}

/** 刷新滚动条；手型/滚轮可自由平移，滑块超出时贴边显示 */
function refreshScrollUi(opts?: { expandToView?: boolean }) {
  const canvas = canvasRef.value
  if (!canvas) return
  const vw = canvas.clientWidth
  const vh = canvas.clientHeight
  if (vw < 8 || vh < 8) return
  const b = contentBounds()
  const scale = Math.max(view.scale || 1, 0.01)
  const cw = Math.max(1, (b.maxX - b.minX) * scale)
  const ch = Math.max(1, (b.maxY - b.minY) * scale)
  const slackX = vw * SCROLL_SLACK
  const slackY = vh * SCROLL_SLACK
  const minRangeX = vw * 0.5
  const minRangeY = vh * 0.5
  let rangeX = Math.max(minRangeX, cw + 2 * slackX - vw)
  let rangeY = Math.max(minRangeY, ch + 2 * slackY - vh)
  // scroll=0：内容偏左/上；增大 scroll → 看向右/下
  let oxAt0 = slackX - b.minX * scale
  let oyAt0 = slackY + b.maxY * scale
  let left = oxAt0 - view.ox
  let top = view.oy - oyAt0
  // 适应屏幕后坐标系可能与 fit 预留不一致：外扩以包含当前视图，避免首次平移跳变
  if (opts?.expandToView) {
    if (left < 0) {
      rangeX += -left
      oxAt0 = view.ox
      left = 0
    } else if (left > rangeX) {
      rangeX = left
    }
    if (top < 0) {
      rangeY += -top
      oyAt0 = view.oy
      top = 0
    } else if (top > rangeY) {
      rangeY = top
    }
  } else {
    left = Math.max(0, Math.min(rangeX, left))
    top = Math.max(0, Math.min(rangeY, top))
  }
  const trackW = Math.max(1, vw - SB_SIZE)
  const trackH = Math.max(1, vh - SB_SIZE)
  const thumbW = Math.max(28, (vw / (vw + rangeX)) * trackW)
  const thumbH = Math.max(28, (vh / (vh + rangeY)) * trackH)
  scrollMetrics = { oxAt0, oyAt0, rangeX, rangeY, trackW, trackH, thumbW, thumbH }
  const thumbLeft = rangeX <= 0 ? 0 : (left / rangeX) * (trackW - thumbW)
  const thumbTop = rangeY <= 0 ? 0 : (top / rangeY) * (trackH - thumbH)
  scrollUi.value = {
    rangeX,
    rangeY,
    left,
    top,
    thumbW,
    thumbH,
    thumbLeft,
    thumbTop,
  }
}

/** 滚动条拖动时用：把相机限制在当前可滚范围 */
function clampViewToScrollRange() {
  refreshScrollUi({ expandToView: true })
  view.ox = Math.min(scrollMetrics.oxAt0, Math.max(scrollMetrics.oxAt0 - scrollMetrics.rangeX, view.ox))
  view.oy = Math.max(scrollMetrics.oyAt0, Math.min(scrollMetrics.oyAt0 + scrollMetrics.rangeY, view.oy))
}

function applyScrollLeft(left: number) {
  const L = Math.max(0, Math.min(scrollMetrics.rangeX, left))
  view.ox = scrollMetrics.oxAt0 - L
  paint()
}

function applyScrollTop(top: number) {
  const T = Math.max(0, Math.min(scrollMetrics.rangeY, top))
  view.oy = scrollMetrics.oyAt0 + T
  paint()
}

function onSbThumbDown(axis: 'x' | 'y', ev: PointerEvent) {
  ev.preventDefault()
  ev.stopPropagation()
  const el = ev.currentTarget as HTMLElement
  el.setPointerCapture(ev.pointerId)
  refreshScrollUi({ expandToView: true })
  sbDrag = {
    axis,
    startClient: axis === 'x' ? ev.clientX : ev.clientY,
    startScroll: axis === 'x' ? scrollUi.value.left : scrollUi.value.top,
  }
}

function onSbTrackDown(axis: 'x' | 'y', ev: PointerEvent) {
  ev.preventDefault()
  ev.stopPropagation()
  refreshScrollUi({ expandToView: true })
  const track = ev.currentTarget as HTMLElement
  const rect = track.getBoundingClientRect()
  if (axis === 'x') {
    const x = ev.clientX - rect.left
    const L =
      ((x - scrollMetrics.thumbW / 2) / Math.max(1, scrollMetrics.trackW - scrollMetrics.thumbW)) *
      scrollMetrics.rangeX
    applyScrollLeft(L)
  } else {
    const y = ev.clientY - rect.top
    const T =
      ((y - scrollMetrics.thumbH / 2) / Math.max(1, scrollMetrics.trackH - scrollMetrics.thumbH)) *
      scrollMetrics.rangeY
    applyScrollTop(T)
  }
}

function onSbPointerMove(ev: PointerEvent) {
  if (!sbDrag) return
  if (sbDrag.axis === 'x') {
    const track = Math.max(1, scrollMetrics.trackW - scrollMetrics.thumbW)
    const delta = ev.clientX - sbDrag.startClient
    applyScrollLeft(sbDrag.startScroll + (delta / track) * scrollMetrics.rangeX)
  } else {
    const track = Math.max(1, scrollMetrics.trackH - scrollMetrics.thumbH)
    const delta = ev.clientY - sbDrag.startClient
    applyScrollTop(sbDrag.startScroll + (delta / track) * scrollMetrics.rangeY)
  }
}

function onSbPointerUp() {
  sbDrag = null
}

/**
 * 滚轮/触控板：默认平移（上下左右）；Ctrl/Cmd+滚轮：相对光标缩放。
 * deltaMode 统一成像素，避免行/页模式一次跳太远。
 */
function onWheel(ev: WheelEvent) {
  const canvas = canvasRef.value
  if (!canvas) return
  ev.preventDefault()
  const zoom = ev.ctrlKey || ev.metaKey
  let dx = ev.deltaX
  let dy = ev.deltaY
  if (ev.deltaMode === 1) {
    dx *= 16
    dy *= 16
  } else if (ev.deltaMode === 2) {
    dx *= canvas.clientWidth
    dy *= canvas.clientHeight
  }
  if (zoom) {
    const rect = canvas.getBoundingClientRect()
    const sx = ev.clientX - rect.left
    const sy = ev.clientY - rect.top
    const before = toModel(sx, sy)
    const factor = dy > 0 ? 0.9 : 1.1
    view.scale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, view.scale * factor))
    view.ox = sx - before.x * view.scale
    view.oy = sy + before.y * view.scale
  } else {
    // 触控板/滚轮：内容随手指方向移动（与常见画布一致）
    view.ox -= dx
    view.oy -= dy
  }
  paint()
}

/** 导出/预览：临时 fit 后执行，再恢复当前视图 */
function withFittedView(fn: () => void) {
  const snap = { scale: view.scale, ox: view.ox, oy: view.oy }
  fitView({ reserveTable: true })
  paint({ exportMode: true })
  try {
    fn()
  } finally {
    view.scale = snap.scale
    view.ox = snap.ox
    view.oy = snap.oy
    paint()
  }
}

function selectRow(id: string) {
  selectedId.value = id
  fieldHistoryPushed = false
  paint()
}

/** 双击列表行：选中并聚焦序号输入，方便直接填号 */
async function selectRowAndFocusNo(id: string) {
  selectRow(id)
  await nextTick()
  const el = calloutNoInputRef.value
  if (!el) return
  el.focus()
  el.select()
  document
    .querySelector(`.stack-ed-row.on`)
    ?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}

/** 跳到第一条缺项并聚焦序号 */
async function jumpFirstIncomplete() {
  const gaps = findCalloutGaps()
  if (!gaps.length) {
    message.success('旁注已填齐')
    return
  }
  await selectRowAndFocusNo(gaps[0].bodyId)
}

/** 序号框 Enter：跳到下一条未填齐并聚焦序号 */
async function focusNextIncompleteNo() {
  const gaps = findCalloutGaps()
  if (!gaps.length) {
    message.success('旁注已填齐，可保存层次图')
    return
  }
  const ids = callouts.value.map((c) => c.bodyId)
  const curPos = ids.indexOf(selectedId.value)
  const after = gaps.find((g) => ids.indexOf(g.bodyId) > curPos)
  const next = after || gaps.find((g) => g.bodyId !== selectedId.value) || gaps[0]
  await selectRowAndFocusNo(next.bodyId)
}

/** 关闭：有未保存改动时二次确认 */
function requestClose() {
  if (saving.value || exporting.value) return
  if (!isDirty.value) {
    emit('close')
    return
  }
  openEdModal({
    title: '有未保存的标注改动',
    content: '关闭后本次修改将丢失。确定关闭？',
    okText: '仍要关闭',
    cancelText: '继续编辑',
    okPrimary: true,
    onOk: () => emit('close'),
  })
}

function seedHistory() {
  historyStack.value = [JSON.stringify(callouts.value)]
  historyIndex.value = 0
}

function pushHistory() {
  if (suppressHistory) return
  const snap = JSON.stringify(callouts.value)
  const cur = historyStack.value[historyIndex.value]
  if (cur === snap) return
  const next = historyStack.value.slice(0, historyIndex.value + 1)
  next.push(snap)
  while (next.length > HISTORY_MAX) next.shift()
  historyStack.value = next
  historyIndex.value = next.length - 1
}

function applyHistoryAt(index: number) {
  const snap = historyStack.value[index]
  if (snap == null) return
  suppressHistory = true
  try {
    callouts.value = JSON.parse(snap) as StackCallout[]
    if (!callouts.value.some((c) => c.bodyId === selectedId.value)) {
      selectedId.value = callouts.value[0]?.bodyId ?? ''
    }
  } finally {
    suppressHistory = false
  }
  paint()
}

function undo() {
  if (!canUndo.value) {
    message.info('没有可撤销的操作')
    return
  }
  historyIndex.value -= 1
  applyHistoryAt(historyIndex.value)
}

function redo() {
  if (!canRedo.value) {
    message.info('没有可重做的操作')
    return
  }
  historyIndex.value += 1
  applyHistoryAt(historyIndex.value)
}

function copyAttrs() {
  const c = selected.value
  if (!c) {
    message.warning('请先选中旁注')
    return
  }
  attrClipboard.value = {
    calloutNo: c.calloutNo,
    material: c.material,
    color: c.color,
    specialReq: c.specialReq,
    thickness: c.thickness,
  }
  message.success(`已复制属性（序号 ${c.calloutNo || '?'}）`)
}

function pasteAttrs() {
  const c = selected.value
  const clip = attrClipboard.value
  if (!c) {
    message.warning('请先选中要粘贴到的旁注')
    return
  }
  if (!clip) {
    message.warning('剪贴板为空，请先复制属性')
    return
  }
  pushHistory()
  c.calloutNo = clip.calloutNo
  c.material = clip.material
  c.color = clip.color
  c.specialReq = clip.specialReq
  c.thickness = clip.thickness
  paint()
  // 厚度与 3D / 2D 不一致时弹窗确认（不冲掉已粘贴的材料/颜色）
  const bodyId = c.bodyId
  const plateTh = plateThicknessOf(bodyId)
  const th2d = parseSeedThickness(findMaterialSeed(c.calloutNo)?.thickness)
  if (plateTh != null && th2d != null && thicknessMismatch(plateTh, th2d)) {
    const seq = ++seedApplySeq
    destroyPendingThicknessModal()
    const keep3d = roundThicknessMm(plateTh)
    const from2d = roundThicknessMm(th2d)
    const noteNo = c.calloutNo || ''
    openEdModal({
      title: '厚度与 2D 材料表不一致',
      content: `当前 3D 厚度 ${keep3d} mm，2D 序号 ${noteNo} 为 ${from2d} mm。要用 2D 厚度替换吗？`,
      okText: '使用 2D 厚度',
      cancelText: '保留 3D 厚度',
      okPrimary: true,
      onOk: () => {
        if (seq !== seedApplySeq) return
        const row = findCalloutByBodyId(bodyId)
        if (!row) return
        pushHistory()
        row.thickness = th2d
        paint()
      },
      onCancel: () => {
        if (seq !== seedApplySeq) return
        const row = findCalloutByBodyId(bodyId)
        if (!row) return
        pushHistory()
        row.thickness = plateTh
        paint()
      },
    })
  } else {
    confirmManualThicknessIfNeeded(bodyId, c.thickness)
  }
  message.success('已粘贴属性（位置不变）')
}

function isTypingTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

function onKeyDown(ev: KeyboardEvent) {
  const mod = ev.metaKey || ev.ctrlKey
  if (!mod) return
  const key = ev.key.toLowerCase()
  const typing = isTypingTarget(ev.target)

  if (key === 'z' && !ev.shiftKey) {
    ev.preventDefault()
    undo()
    return
  }
  if ((key === 'z' && ev.shiftKey) || key === 'y') {
    ev.preventDefault()
    redo()
    return
  }
  if (key === 'c') {
    // 输入框内有划选时走系统复制
    if (typing) {
      const input = ev.target as HTMLInputElement
      if (
        typeof input.selectionStart === 'number'
        && typeof input.selectionEnd === 'number'
        && input.selectionStart !== input.selectionEnd
      ) {
        return
      }
    }
    ev.preventDefault()
    copyAttrs()
    return
  }
  if (key === 'v') {
    // 输入框内粘贴走系统，避免冲掉正在编辑的字段
    if (typing) return
    ev.preventDefault()
    pasteAttrs()
  }
}

function patchSelected(field: keyof StackCallout, value: string | number) {
  const c = selected.value
  if (!c) return
  if (!fieldHistoryPushed) {
    pushHistory()
    fieldHistoryPushed = true
  }
  ;(c as Record<string, unknown>)[field] = value
  paint()
}

function onFieldFocus() {
  fieldHistoryPushed = false
}

function onFieldInput(field: 'calloutNo' | 'material' | 'color' | 'specialReq' | 'thickness', ev: Event) {
  const el = ev.target as HTMLInputElement
  if (field === 'thickness') {
    const n = Number(el.value)
    patchSelected('thickness', Number.isFinite(n) ? n : 0)
    return
  }
  patchSelected(field, el.value)
  // 改序号：命中 2D 则带材料/色；厚度不一致弹窗确认
  if (field === 'calloutNo') {
    const c = selected.value
    if (c) apply2dSeedOnCalloutNoChange(c)
  }
  clearSaveBlockHintIfReady()
}

function onThicknessBlur(ev: Event) {
  const c = selected.value
  if (!c) return
  const el = ev.target as HTMLInputElement
  const n = Number(el.value)
  const next = Number.isFinite(n) ? n : 0
  confirmManualThicknessIfNeeded(c.bodyId, next)
}

function downloadBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}

function refreshPreviewPng() {
  const canvas = canvasRef.value
  if (!canvas) return
  withFittedView(() => {
    previewUrl.value = canvas.toDataURL('image/png')
  })
}

function downloadPng() {
  const canvas = canvasRef.value
  if (!canvas) return
  const snap = { scale: view.scale, ox: view.ox, oy: view.oy }
  fitView({ reserveTable: true })
  paint({ exportMode: true })
  canvas.toBlob((blob) => {
    view.scale = snap.scale
    view.ox = snap.ox
    view.oy = snap.oy
    paint()
    if (!blob) {
      message.error('PNG 预览失败')
      return
    }
    const stem = props.drawingNo || 'stack-explode'
    downloadBlob(blob, `${stem}-层次爆炸预览.png`)
  })
}

async function downloadDxf() {
  exporting.value = true
  try {
    autoLayoutUnplaced()
    fitToScreen()
    const result = await exportStackExplodeDxf({
      drawingNo: props.drawingNo || 'stack-explode',
      plates: props.plates.map((p) => ({
        bodyId: p.bodyId,
        thickness: p.thickness,
        segments: p.segments,
      })),
      callouts: callouts.value.map((c) => ({
        bodyId: c.bodyId,
        calloutNo: c.calloutNo,
        anchorX: c.anchorX,
        anchorY: c.anchorY,
        textX: c.textX,
        textY: c.textY,
        elbows: c.elbows || [],
        line1: formatLine1(c),
        line2: formatLine2(c) || '',
        material: c.material,
        color: c.color,
        thickness: c.thickness,
      })),
    })
    const bin = atob(result.dxfBase64)
    const bytes = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
    downloadBlob(
      new Blob([bytes], { type: 'application/dxf' }),
      result.fileName || `${props.drawingNo || 'stack-explode'}-层次爆炸.dxf`,
    )
    refreshPreviewPng()
    const tip = result.warnings?.length ? `（${result.warnings[0]}）` : ''
    message.success((result.message || '已导出 DXF') + tip)
  } catch (e) {
    message.error(e instanceof Error ? e.message : 'DXF 导出失败')
  } finally {
    exporting.value = false
  }
}

function buildExportPayload() {
  autoLayoutUnplaced()
  fitToScreen()
  refreshPreviewPng()
  return {
    drawingNo: props.drawingNo || 'stack-explode',
    plates: props.plates.map((p) => ({
      bodyId: p.bodyId,
      thickness: p.thickness,
      segments: p.segments,
    })),
    callouts: callouts.value.map((c) => ({
      bodyId: c.bodyId,
      calloutNo: c.calloutNo,
      anchorX: c.anchorX,
      anchorY: c.anchorY,
      textX: c.textX,
      textY: c.textY,
      elbows: c.elbows || [],
      line1: formatLine1(c),
      line2: formatLine2(c) || '',
      material: c.material,
      color: c.color,
      specialReq: c.specialReq,
      thickness: c.thickness,
      placed: c.placed,
    })),
    previewPngBase64: previewUrl.value || '',
  }
}

/** 序号/材料/颜色必填，禁止把「未标注」绿表写入层次 DXF */
function validateCalloutsReady(): string | null {
  const gaps = findCalloutGaps()
  if (!gaps.length) return null
  const preview = gaps
    .slice(0, 5)
    .map((g) => `${g.label}（缺${g.missing.join('/')}）`)
    .join('；')
  const more = gaps.length > 5 ? `等共 ${gaps.length} 条` : ''
  return `还有 ${gaps.length} 条旁注未填齐：${preview}${more}。请在右侧实体表单补全（改序号可命中左上 2D 材料；厚度不一致须确认）后再保存`
}

async function saveForEng() {
  if (saving.value) return
  const gaps = findCalloutGaps()
  if (gaps.length) {
    const tip = validateCalloutsReady() || '旁注未填齐，无法保存'
    saveBlockHint.value = tip
    await selectRowAndFocusNo(gaps[0].bodyId)
    // 全屏编辑器内用深色弹窗，避免 Ant 默认浅色浮层风格不一致
    openEdModal({
      title: '无法保存层次图',
      content: tip,
      okText: '去补全',
      cancelText: null,
      okPrimary: true,
      onOk: () => {},
    })
    return
  }
  saveBlockHint.value = ''
  saving.value = true
  try {
    const payload = buildExportPayload()
    if (props.persistSave) {
      await props.persistSave(payload)
    } else {
      emit('saved', payload)
    }
  } catch (e) {
    // persistSave 内 onStackSaved 已 toast；避免重复弹窗
    if (e instanceof Error && e.message === 'STACK_SAVE_FAILED') return
    message.error(e instanceof Error ? e.message : '层次保存失败')
  } finally {
    saving.value = false
  }
}

function onResize() {
  fitToScreen()
}

function relayoutAndFit() {
  initCallouts()
  nextTick(() => {
    fitView({ reserveTable: true })
    autoLayoutUnplaced()
    fitToScreen()
    refreshPreviewPng()
  })
}

watch(
  () => props.plates,
  () => {
    relayoutAndFit()
  },
  { deep: true },
)

watch(
  () => props.initialCallouts,
  () => {
    relayoutAndFit()
  },
  { deep: true },
)

watch(
  () => props.materialSeeds,
  () => {
    fitToScreen()
  },
  { deep: true },
)

onMounted(() => {
  initCallouts()
  nextTick(() => {
    fitView({ reserveTable: true })
    autoLayoutUnplaced()
    fitToScreen()
    seedHistory()
    refreshPreviewPng()
  })
  window.addEventListener('resize', onResize)
  window.addEventListener('keydown', onKeyDown)
})

onBeforeUnmount(() => {
  destroyPendingThicknessModal()
  window.removeEventListener('resize', onResize)
  window.removeEventListener('keydown', onKeyDown)
})
</script>

<template>
  <div class="stack-ed">
    <header class="stack-ed-head">
      <div>
        <h2>层次爆炸编辑</h2>
        <p>
          左上橙表为①已确认 2D 材料；右下绿表随旁注生成。改序号命中 2D 时自动带材料，厚度与
          3D 不一致时需确认。Ctrl+Z 撤销；边缘滚动条或滚轮/触控板平移，Ctrl+滚轮缩放；右侧表单改字。
        </p>
      </div>
      <div class="stack-ed-actions">
        <label class="hand-toggle" title="开启后拖空白处平移画布；仍可拖方框/锚点/折点；滚轮也可平移">
          <input v-model="handMode" type="checkbox" />
          手型平移
        </label>
        <button type="button" class="btn" :disabled="!selected" title="Ctrl/Cmd+C" @click="copyAttrs">复制属性</button>
        <button type="button" class="btn" :disabled="!canPasteAttrs" title="Ctrl/Cmd+V" @click="pasteAttrs">粘贴属性</button>
        <button type="button" class="btn" :disabled="!canUndo" title="Ctrl/Cmd+Z" @click="undo">撤销</button>
        <button type="button" class="btn" :disabled="!canRedo" title="Ctrl/Cmd+Y" @click="redo">重做</button>
        <button type="button" class="btn" :disabled="exporting || saving" @click="fitToScreen">适应屏幕</button>
        <button type="button" class="btn" :disabled="exporting || saving" @click="downloadPng">下载 PNG 预览</button>
        <button type="button" class="btn primary" :disabled="exporting || saving" @click="downloadDxf">
          <LoadingOutlined v-if="exporting" spin />
          <span>{{ exporting ? '导出中…' : '生成层次爆炸 DXF' }}</span>
        </button>
        <button
          v-if="enableSave"
          type="button"
          class="btn primary"
          :disabled="exporting || saving || !plates.length"
          @click="saveForEng"
        >
          <LoadingOutlined v-if="saving" spin />
          <span>{{ saving ? '保存中…' : '保存层次图' }}</span>
        </button>
        <button type="button" class="btn" :disabled="exporting || saving" @click="requestClose">关闭</button>
      </div>
    </header>
    <p v-if="saveBlockHint" class="stack-ed-alert" role="alert">{{ saveBlockHint }}</p>
    <div class="stack-ed-body">
      <div class="stack-ed-stage">
        <div class="stack-ed-canvas-wrap">
          <canvas
            ref="canvasRef"
            class="stack-ed-canvas"
            :class="{ 'is-hand': handMode }"
            @pointerdown="onPointerDown"
            @pointermove="onPointerMove"
            @pointerup="onPointerUp"
            @pointercancel="onPointerUp"
            @wheel.prevent="onWheel"
          />
          <div
            class="stack-ed-sb stack-ed-sb-v"
            title="上下滚动画布"
            @pointerdown="onSbTrackDown('y', $event)"
          >
            <div
              class="stack-ed-sb-thumb"
              :style="{ height: scrollUi.thumbH + 'px', top: scrollUi.thumbTop + 'px' }"
              @pointerdown="onSbThumbDown('y', $event)"
              @pointermove="onSbPointerMove"
              @pointerup="onSbPointerUp"
              @pointercancel="onSbPointerUp"
            />
          </div>
          <div
            class="stack-ed-sb stack-ed-sb-h"
            title="左右滚动画布"
            @pointerdown="onSbTrackDown('x', $event)"
          >
            <div
              class="stack-ed-sb-thumb"
              :style="{ width: scrollUi.thumbW + 'px', left: scrollUi.thumbLeft + 'px' }"
              @pointerdown="onSbThumbDown('x', $event)"
              @pointermove="onSbPointerMove"
              @pointerup="onSbPointerUp"
              @pointercancel="onSbPointerUp"
            />
          </div>
          <div class="stack-ed-sb-corner" aria-hidden="true" />
        </div>
      </div>
      <aside class="stack-ed-side">
        <div class="stack-ed-list">
          <p class="stack-ed-list-title">
            <span>实体列表（{{ callouts.length }}）</span>
            <button
              v-if="incompleteCount"
              type="button"
              class="stack-ed-gap-jump"
              title="跳到第一条缺项并聚焦序号"
              @click="jumpFirstIncomplete"
            >
              缺 {{ incompleteCount }} 条
            </button>
          </p>
          <button
            v-for="c in callouts"
            :key="c.bodyId"
            type="button"
            class="stack-ed-row"
            :class="{
              on: c.bodyId === selectedId,
              'is-incomplete': !!calloutGapTag(c),
            }"
            @click="selectRow(c.bodyId)"
            @dblclick="selectRowAndFocusNo(c.bodyId)"
          >
            <strong>{{ c.calloutNo || '?' }}</strong>
            <span>{{ c.bodyId }} · 厚 {{ c.thickness }}</span>
            <em v-if="calloutGapTag(c)" class="gap">{{ calloutGapTag(c) }}</em>
            <em v-else-if="c.placed">已调</em>
          </button>
        </div>
        <div v-if="selected" class="stack-ed-form">
          <label>序号（方框）
            <input
              ref="calloutNoInputRef"
              :value="selected.calloutNo"
              placeholder="如 12"
              @focus="onFieldFocus"
              @input="onFieldInput('calloutNo', $event)"
              @keydown.enter.prevent="focusNextIncompleteNo"
            />
          </label>
          <label>材料
            <input
              :value="selected.material"
              placeholder="未标注"
              @focus="onFieldFocus"
              @input="onFieldInput('material', $event)"
            />
          </label>
          <label>颜色
            <input
              :value="selected.color"
              placeholder="未标注"
              @focus="onFieldFocus"
              @input="onFieldInput('color', $event)"
            />
          </label>
          <label>特殊要求（另起一行；空则不画）
            <textarea
              :value="selected.specialReq"
              placeholder="可空"
              rows="2"
              @focus="onFieldFocus"
              @input="onFieldInput('specialReq', $event)"
            />
          </label>
          <label>厚度 (mm)
            <input
              :value="selected.thickness"
              type="number"
              step="0.001"
              min="0"
              @focus="onFieldFocus"
              @input="onFieldInput('thickness', $event)"
              @blur="onThicknessBlur"
            />
          </label>
          <div class="elbow-actions">
            <button type="button" class="btn" @click="addOrthoElbow">加直角折点</button>
            <button type="button" class="btn" :disabled="!selected.elbows?.length" @click="clearElbows">清除折点</button>
          </div>
          <p class="hint">
            双击列表行填序号 · Enter 下一条缺项 · Ctrl+C/V 属性 · Ctrl+Z/Y 撤销 · 折点
            {{ selected.elbows?.length || 0 }}/{{ MAX_ELBOWS }}
          </p>
        </div>
        <div v-if="previewUrl" class="stack-ed-preview">
          <p>PNG 预览</p>
          <img :src="previewUrl" alt="层次爆炸预览" />
        </div>
      </aside>
    </div>

    <!-- 深色确认弹窗：与层次爆炸编辑整体风格一致，遮罩期间不可操作底层 -->
    <div
      v-if="edModal"
      class="stack-ed-modal-mask"
      role="alertdialog"
      aria-modal="true"
      :aria-label="edModal.title"
      @keydown.esc.prevent="edModal.cancelText ? resolveEdModalCancel() : resolveEdModalOk()"
    >
      <div class="stack-ed-modal">
        <div class="stack-ed-modal-badge" aria-hidden="true">!</div>
        <h3 class="stack-ed-modal-title">{{ edModal.title }}</h3>
        <p class="stack-ed-modal-body">{{ edModal.content }}</p>
        <div class="stack-ed-modal-actions">
          <button
            v-if="edModal.cancelText"
            type="button"
            class="btn"
            @click="resolveEdModalCancel"
          >
            {{ edModal.cancelText }}
          </button>
          <button
            type="button"
            class="btn"
            :class="{ primary: edModal.okPrimary !== false }"
            @click="resolveEdModalOk"
          >
            {{ edModal.okText }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.stack-ed {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: flex;
  flex-direction: column;
  background: #0b1220;
  color: #e2e8f0;
}
.stack-ed-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  padding: 12px 16px;
  border-bottom: 1px solid #1e293b;
  background: #111827;
}
.stack-ed-head h2 {
  margin: 0 0 4px;
  font-size: 16px;
  color: #f8fafc;
}
.stack-ed-head p {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
  max-width: 52rem;
  line-height: 1.5;
}
.stack-ed-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  flex-shrink: 0;
}
.stack-ed-alert {
  margin: 0;
  padding: 8px 16px;
  border-bottom: 1px solid #b45309;
  background: #431407;
  color: #fdba74;
  font-size: 12px;
  line-height: 1.45;
  flex-shrink: 0;
}
.hand-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #cbd5e1;
  user-select: none;
  white-space: nowrap;
}
.hand-toggle input {
  margin: 0;
}
.btn {
  border: 1px solid #334155;
  background: #1e293b;
  color: #e2e8f0;
  border-radius: 6px;
  padding: 6px 12px;
  cursor: pointer;
  font-size: 13px;
}
.btn:hover {
  border-color: #64748b;
}
.btn.primary {
  background: #ea580c;
  border-color: #ea580c;
  color: #fff;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.stack-ed-modal-mask {
  position: absolute;
  inset: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(2, 6, 23, 0.72);
  backdrop-filter: blur(2px);
}
.stack-ed-modal {
  width: min(420px, 100%);
  padding: 22px 22px 18px;
  border-radius: 10px;
  border: 1px solid #334155;
  background: #111827;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.45);
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
}
.stack-ed-modal-badge {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  font-weight: 800;
  line-height: 1;
  color: #0b1220;
  background: #f5a623;
  align-self: flex-start;
}
.stack-ed-modal-title {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  line-height: 22px;
  color: #f8fafc;
}
.stack-ed-modal-body {
  margin: 0;
  font-size: 13px;
  line-height: 20px;
  color: #cbd5e1;
  white-space: pre-wrap;
  word-break: break-word;
}
.stack-ed-modal-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 6px;
}
.stack-ed-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  /* 锁死可视高度，避免侧栏按内容撑高后底部被视口裁切 */
  overflow: hidden;
}
.stack-ed-stage {
  min-width: 0;
  min-height: 0;
  padding: 8px;
  display: flex;
  flex-direction: column;
}
.stack-ed-canvas-wrap {
  position: relative;
  flex: 1 1 0%;
  min-width: 0;
  min-height: 0;
  width: 100%;
  height: 100%;
}
.stack-ed-canvas {
  width: 100%;
  height: 100%;
  display: block;
  border: 1px solid #1e293b;
  border-radius: 8px;
  cursor: crosshair;
  touch-action: none;
  background: #000;
}
.stack-ed-canvas.is-hand {
  cursor: grab;
}
.stack-ed-canvas.is-hand:active {
  cursor: grabbing;
}
/* 画布右缘竖条、底缘横条（与标注区边缘对齐） */
.stack-ed-sb {
  position: absolute;
  z-index: 3;
  background: rgba(15, 23, 42, 0.92);
  box-sizing: border-box;
}
.stack-ed-sb-v {
  top: 1px;
  right: 1px;
  bottom: 11px;
  width: 10px;
  border-left: 1px solid #334155;
  border-radius: 0 7px 0 0;
}
.stack-ed-sb-h {
  left: 1px;
  right: 11px;
  bottom: 1px;
  height: 10px;
  border-top: 1px solid #334155;
  border-radius: 0 0 0 7px;
}
.stack-ed-sb-corner {
  position: absolute;
  right: 1px;
  bottom: 1px;
  width: 10px;
  height: 10px;
  z-index: 3;
  background: rgba(15, 23, 42, 0.92);
  border-radius: 0 0 7px 0;
  border-left: 1px solid #334155;
  border-top: 1px solid #334155;
  box-sizing: border-box;
}
.stack-ed-sb-thumb {
  position: absolute;
  background: #64748b;
  border-radius: 4px;
  cursor: grab;
  touch-action: none;
}
.stack-ed-sb-thumb:hover {
  background: #94a3b8;
}
.stack-ed-sb-thumb:active {
  cursor: grabbing;
  background: #cbd5e1;
}
.stack-ed-sb-v .stack-ed-sb-thumb {
  left: 1px;
  width: 8px;
}
.stack-ed-sb-h .stack-ed-sb-thumb {
  top: 1px;
  height: 8px;
}
.stack-ed-side {
  border-left: 1px solid #1e293b;
  display: flex;
  flex-direction: column;
  min-height: 0;
  height: 100%;
  max-height: 100%;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  background: #0f172a;
  -webkit-overflow-scrolling: touch;
}
.stack-ed-list {
  flex: 0 0 auto;
  /* 不限高、不内嵌滚动：全部实体一次列出，由侧栏整体滚动，避免误以为条目缺失 */
  overflow: visible;
  padding: 6px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.stack-ed-list-title {
  margin: 0 0 2px;
  padding: 0 2px;
  font-size: 11px;
  line-height: 16px;
  color: #94a3b8;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.stack-ed-gap-jump {
  flex: 0 0 auto;
  border: 1px solid #b45309;
  background: #431407;
  color: #fdba74;
  border-radius: 4px;
  padding: 0 6px;
  font-size: 11px;
  line-height: 18px;
  font-weight: 650;
  cursor: pointer;
}
.stack-ed-gap-jump:hover {
  border-color: #f59e0b;
  color: #fde68a;
}
.stack-ed-row {
  display: grid;
  grid-template-columns: 2rem 1fr auto;
  gap: 4px;
  align-items: center;
  text-align: left;
  border: 1px solid #1e293b;
  background: #111827;
  color: #cbd5e1;
  border-radius: 4px;
  padding: 3px 6px;
  cursor: pointer;
  font-size: 12px;
  line-height: 16px;
}
.stack-ed-row.on {
  border-color: #ea580c;
  background: #1c1917;
}
.stack-ed-row.is-incomplete {
  border-color: #b45309;
}
.stack-ed-row.is-incomplete strong {
  color: #f87171;
}
.stack-ed-row em {
  font-style: normal;
  color: #86efac;
  font-size: 11px;
}
.stack-ed-row em.gap {
  color: #fbbf24;
}
.stack-ed-form {
  flex: 0 0 auto;
  padding: 6px 8px 8px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  border-top: 1px solid #1e293b;
}
.stack-ed-form label {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11px;
  line-height: 16px;
  color: #94a3b8;
}
.stack-ed-form input,
.stack-ed-form textarea {
  border: 1px solid #334155;
  background: #020617;
  color: #f1f5f9;
  border-radius: 4px;
  padding: 3px 6px;
  font-size: 12px;
  line-height: 18px;
  box-sizing: border-box;
  width: 100%;
  font-family: inherit;
  resize: vertical;
}
.stack-ed-form input {
  height: 26px;
  resize: none;
}
.stack-ed-form textarea {
  min-height: 44px;
  height: auto;
}
.stack-ed-form .hint {
  margin: 0;
  font-size: 11px;
  color: #64748b;
  word-break: break-all;
  line-height: 1.35;
}
.elbow-actions {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
.elbow-actions .btn {
  padding: 3px 8px;
  font-size: 12px;
  line-height: 18px;
}
.stack-ed-preview {
  flex: 0 0 auto;
  margin-top: 0;
  padding: 6px 8px 10px;
  border-top: 1px solid #1e293b;
}
.stack-ed-preview p {
  margin: 0 0 4px;
  font-size: 11px;
  line-height: 16px;
  color: #94a3b8;
}
.stack-ed-preview img {
  display: block;
  width: 100%;
  max-height: 160px;
  object-fit: contain;
  border: 1px solid #1e293b;
  border-radius: 4px;
  background: #000;
}
@media (max-width: 900px) {
  .stack-ed-body {
    grid-template-columns: 1fr;
    grid-template-rows: 1fr auto;
  }
  .stack-ed-side {
    border-left: none;
    border-top: 1px solid #1e293b;
    max-height: 45vh;
    height: auto;
  }
}
</style>
