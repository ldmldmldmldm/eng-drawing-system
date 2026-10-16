<script setup lang="ts">
/**
 * 工程图生成向导：①上传预览→选截图/自动抽取 → ②三维层次(预填) → ③材料核对 → ④翻译审校 → ⑤生成
 * ③④确认前不可生成；爆炸编辑器逻辑不在此组件。
 */
import { computed, inject, nextTick, onBeforeUnmount, onMounted, ref, watch, type Ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  composeEngDrawing,
  convertPreview,
  convertTranslate,
  convertRegionExtract,
  convertRegionExtractByRect,
  getComposeStatus,
  getEngStack,
  getEngStackPreview,
  listEngStacks,
  saveEngStack,
  startComposeAsync,
  startExtractAsync,
  getExtractStatus,
  cancelExtract,
  taskApi,
  type ConvertMaterialRow,
  type ConvertNoteLine,
  type ConvertPreview,
  type EngComposeJobView,
  type EngExtractJobView,
  type ExtractJobMode,
  type EngComposeResult,
  type EngStackAssetSummary,
  type RegionExtractResult,
  type StackExplodeExportBody,
  type TaskAnnotSnapshot,
} from '@/api'
import {
  CheckOutlined,
  CloseOutlined,
  CloudUploadOutlined,
  DeleteOutlined,
  DownloadOutlined,
  ExclamationCircleOutlined,
  FileOutlined,
  FolderOpenOutlined,
  LoadingOutlined,
  PlusOutlined,
  QuestionCircleOutlined,
  ReloadOutlined,
  SaveOutlined,
  ScanOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons-vue'
import {
  applyAdopt2d,
  applyAdopt3d,
  buildMaterialCompare,
  buildMaterialWorkRows,
  calloutsFromStack,
  formatSideTriple,
  markManual,
  mergeMaterialWorkRows,
  normalizeNoteNo,
  unresolvedWorkRows,
  workRowsToMaterials,
  type MaterialWorkRow,
  type StackCalloutSide,
} from '@/utils/materialCompare'
import { engineeringDxfFileName } from '@/utils/engFileName'

const UNLABELED = '未标注'

const props = defineProps<{
  solidReady: boolean
  bodyCount?: number
  drawingNo?: string
  stepFileName?: string
  stepScanning?: boolean
  embedded?: boolean
  taskTitle?: string
  initialStep?: number
  stepPersisted?: boolean
  /** 绑定任务时持久化/回显审校快照 */
  taskId?: number | string
  /** DRAFT / IN_PROGRESS / COMPLETED；已完成仅回看 */
  taskStatus?: string
  /** 正在打开层次爆炸编辑器（回读资产等） */
  stackEditorOpening?: boolean
  /** 进入任务恢复 DWG/STP/annot 中 */
  hydrating?: boolean
  /** 任务已跳过②三维层次 */
  stackSkipped?: boolean
  /** 任务已跳过①抽取核对 */
  extractSkipped?: boolean
  /** 任务目录是否已落盘客户 DWG */
  hasCustomerDwg?: boolean
  /** 任务目录是否已落盘 STP */
  hasStepFile?: boolean
}>()

const emit = defineEmits<{
  close: []
  back: []
  pickStep: [file: File]
  openStackEditor: []
  needParse: []
  focusSolid: []
  stepChange: [step: number]
  composed: [result: EngComposeResult]
  stackBound: [stackAssetId: string]
  /** 跳过/取消跳过层次后同步任务表 */
  stackSkipChange: [skipped: boolean]
  /** 跳过/取消跳过①抽取后同步任务表 */
  extractSkipChange: [skipped: boolean]
  dwgPicked: [file: File]
  /** ② 抽取预览（含 PNG/SVG）；供左栏图纸视口 */
  extractPreview: [preview: ConvertPreview | null]
  extractBusy: [busy: boolean]
  /** ① 请求左图进入框选识别 */
  regionCaptureRequest: [kind: 'note' | 'material']
  /** ⑤ 左栏产物总览数据 */
  composeBoard: [payload: ComposeBoardPayload | null]
}>()

/** ⑤ 左栏产物板数据（与 ComposeArtifactBoard props 对齐） */
export interface ComposeBoardPayload {
  dwgFileName: string
  drawingNo: string
  previewPngBase64?: string
  previewSvg?: string
  previewSvgs?: string[]
  materialCrops: string[]
  notes: ConvertNoteLine[]
  materials: ConvertMaterialRow[]
  stack: EngStackAssetSummary | null
  /** 任务已跳过②三维层次（无 STP） */
  stackSkipped?: boolean
  /** 进⑤前预拉的层次 PNG（空串=已拉但无文件） */
  stackPreviewPngBase64?: string
  /** true 时产物板勿再请求 /stack/{id}/preview */
  stackPreviewPrefetched?: boolean
  materialSummary: {
    total: number
    match: number
    conflict: number
    missing3d: number
    missing2d: number
    fieldDiff: number
    unresolved: number
  }
  materialWorkRows: MaterialWorkRow[]
  materialsConfirmed: boolean
  notesConfirmed: boolean
  result: EngComposeResult | null
}

type WizardStep = 1 | 2 | 3 | 4 | 5

const HELP_TIP =
  '五步：①抽取并对图核对 → ②三维层次 → ③材料核对 → ④翻译审校 → ⑤生成工程图'

/** 用户可见提示：弱化 OCR/ODA 等内部词 */
function humanizeUserTip(raw: string): string {
  return (raw || '')
    .replace(/OCR\s*\+\s*模型/g, '文字识别')
    .replace(/OCR/g, '识别原文')
    .replace(/ODA\s*转\s*DXF/g, '转换图纸格式')
    .replace(/ODA/g, '图纸转换')
    .replace(/勿盲信/g, '请人工核对')
}

/** 过程日志：去掉 jobId / ms= / ODA·OCR 等技术词，保留时间与通俗说明 */
function humanizeProcessLog(raw: string): string {
  let s = String(raw || '')
  s = s
    .replace(/\s*mode=\S+/gi, '')
    .replace(/\s*jobId=\S+/gi, '')
    .replace(/\s*bytes=\d+/gi, '')
    .replace(/\s*ms=\d+/gi, '')
    .replace(/\s*totalMs=\d+/gi, '')
    .replace(/\s*legendMs=\d+/gi, '')
    .replace(/\s*boxedMs=\d+/gi, '')
    .replace(/\s*wallMs=\d+/gi, '')
    .replace(/\s*renderMs=\d+/gi, '')
    .replace(/ODA\s*\/\s*渲图\s*\/\s*OCR/gi, '转换图纸、生成预览并识别文字')
    .replace(/ODA\s*\/\s*高清渲图（跳过\s*OCR）/gi, '转换图纸并生成预览')
    .replace(/ODA\s*\/\s*渲图\s*\/\s*OCR\s*完成/gi, '图纸预览与文字识别完成')
    .replace(/ODA\s*\/\s*渲图完成/gi, '图纸预览已生成')
    .replace(/开始\s*ODA\s*转\s*DXF/gi, '正在转换图纸格式')
    .replace(/ODA\s*完成[^，,]*/gi, '图纸格式转换完成')
    .replace(/开始渲图/gi, '正在生成预览图')
    .replace(/开始\s*OCR[^\]]*/gi, '正在识别图上文字')
    .replace(/开始\s*DXF\s*明细抽取/gi, '正在读取图纸结构')
    .replace(/渲图\/OCR\s*段完成[^\]]*/gi, '图纸预览与文字识别完成')
    .replace(/补转\s*DXF/gi, '补全图纸数据')
    .replace(/明细不足，二次\s*ODA/gi, '图纸信息不完整，正在再次转换')
    .replace(/规则抽取\s*NOTE\s*\/\s*材料/gi, '整理说明与材料')
    .replace(/矢量\s*\+\s*OCR\s*规则抽取/gi, '正在从图纸中整理说明与材料')
    .replace(/视觉识别说明区与方框材料/gi, '核对说明区与材料框')
    .replace(/legend\s*与方框读框并行（按需）/gi, '正在智能核对说明区与材料框')
    .replace(/NOTE\/材料中文留待翻译步/gi, '说明与材料已整理，中文翻译留待后续步骤')
    .replace(/抽取核心完成，正在落盘/gi, '抽取完成，正在保存结果')
    .replace(/抽取任务已提交/gi, '已提交抽取，等待处理')
    .replace(/图纸预览任务已提交/gi, '已提交图纸预览，等待处理')
    .replace(/合成任务已提交/gi, '已提交工程图生成，等待处理')
    .replace(/已获得执行[权槽][^\]]*/gi, '已开始处理')
    .replace(/已获得执行槽[^\]]*/gi, '已开始处理')
    .replace(/合成核心完成[^\]]*/gi, '工程图已生成，正在保存预览')
    .replace(/打包预览产物/gi, '保存预览结果')
    .replace(/读取源\s*DXF\s*\/\s*ODA/gi, '读取图纸数据')
    .replace(/检查①源\s*DXF\s*缓存/gi, '正在检查已转换的图纸缓存')
    .replace(/ODA\s*转换\s*DWG/gi, '转换图纸')
    .replace(/无源\s*DXF\s*缓存，开始\s*ODA\s*转换/gi, '未找到缓存，正在转换图纸格式')
    .replace(/源\s*DXF\s*已落盘/gi, '图纸已转换')
    .replace(/复用源\s*DXF\s*缓存/gi, '复用已有图纸数据')
    .replace(/开始叠层次并生成工程图/gi, '正在叠加三维层次并生成工程图')
    .replace(/合成完成/gi, '工程图生成完成')
    .replace(/用户停止抽取/gi, '已停止抽取')
    .replace(/抽取超时未完成（可能服务重启或排队卡住），请重新解析/gi, '处理超时未完成，请重新解析')
    .replace(/合成超时未完成（可能服务重启或排队卡住），请重新生成/gi, '生成超时未完成，请重新生成')
  return humanizeUserTip(s).replace(/[ \t]{2,}/g, ' ').trimEnd()
}

function humanizeProcessLogs(lines: string[]): string[] {
  return (lines || []).map((line) => humanizeProcessLog(line))
}

/** annot.version≥2：步序语义为 ①抽取/②层次；旧版为 ①层次/②抽取 */
const WIZARD_FLOW_VERSION = 2

const step = ref<WizardStep>(clampStep(props.initialStep))
/** 已到达过的最高步（完成任务 currentStep=5）；顶栏可回看编辑 */
const reachedStep = ref<WizardStep>(clampStep(props.initialStep))
const saving = ref(false)
const composing = ref(false)
const extracting = ref(false)
/** ① 停止抽取进行中 */
const extractCanceling = ref(false)
/** 抽取已用时（跨页用 sessionStorage 记起点，未抽完不归零） */
const EXTRACT_START_KEY = 'eng-extract-start:'
const COMPOSE_START_KEY = 'eng-compose-start:'
const extractElapsedMs = ref(0)
let extractStartAtMem: number | null = null
let extractPerfAnchor: number | null = null
let extractElapsedAtAnchor = 0
let extractTickTimer: number | null = null
const extractElapsedText = computed(() => formatElapsed(extractElapsedMs.value))
const extractLogs = ref<string[]>([])
const extractJobStage = ref('')
const extractJobPercent = ref(0)
let extractPollTimer: number | null = null
let extractResumeTried = false
/** ⑤ 生成已用时（以服务端 elapsedMs/startedAt 为准，本地单调递增续跳） */
const composeElapsedMs = ref(0)
let composeStartAtMem: number | null = null
/** performance.now 锚点 + 当时已用时，避免轮询反复重置、时钟偏差卡 00:00 */
let composePerfAnchor: number | null = null
let composeElapsedAtAnchor = 0
let composeTickTimer: number | null = null
const composeElapsedText = computed(() => formatElapsed(composeElapsedMs.value))
/** ⑤ 服务端合成过程日志（异步任务） */
const composeLogs = ref<string[]>([])
const composeJobStage = ref('')
const composeJobPercent = ref(0)
let composePollTimer: number | null = null
let composeResumeTried = false
const stackAsset = ref<EngStackAssetSummary | null>(null)
/** 无 3D 时跳过②；与任务表 stack_skipped 对齐 */
const stackSkipped = ref(!!props.stackSkipped)
watch(
  () => props.stackSkipped,
  (v) => {
    stackSkipped.value = !!v
  }
)
const extractSkipped = ref(!!props.extractSkipped)
watch(
  () => props.extractSkipped,
  (v) => {
    extractSkipped.value = !!v
  }
)
const recentStacks = ref<EngStackAssetSummary[]>([])
const dwgFile = ref<File | null>(null)
const result = ref<EngComposeResult | null>(null)
const dwgDragging = ref(false)

const preview = ref<ConvertPreview | null>(null)
const editNotes = ref<ConvertNoteLine[]>([])
const editMaterials = ref<ConvertMaterialRow[]>([])
/** 抽取/恢复时的材料基线，供「重置」 */
const materialsBaseline = ref<ConvertMaterialRow[]>([])
const stackCallouts = ref<StackCalloutSide[]>([])
const materialWorkRows = ref<MaterialWorkRow[]>([])
const materialFilter = ref<'all' | 'conflict' | 'missing_3d' | 'missing_2d' | 'field_diff' | 'manual'>(
  'all'
)
const materialsConfirmed = ref(false)
const notesConfirmed = ref(false)
/** ① 抽完后 NOTE / 材料 / 裁剪 分类切换 */
const extractTab = ref<'note' | 'material' | 'crop'>('note')
/** ④ NOTE / 材料 Tab */
const translateTab = ref<'note' | 'material'>('note')
/** ① 逐条人工确认 NOTE（与翻译 status 无关） */
const noteVerified = ref<boolean[]>([])
/** ① 逐条人工确认材料 */
const materialVerified = ref<boolean[]>([])
/** ④ 逐条人工确认译文（与来源 status 无关） */
const noteTranslateVerified = ref<boolean[]>([])
/** ④ 逐条人工确认材料中文/2D 字段 */
const materialReviewVerified = ref<boolean[]>([])
/** ① 空 NOTE / 空材料须显式确认后才放行 */
const emptyNotesAcked = ref(false)
const emptyMaterialsAcked = ref(false)
/** ④ 正在重译的行（-1 表示无） */
const retranNoteIdx = ref(-1)
const retranMatIdx = ref(-1)
/** ④ 一键翻译进度 */
const batchTranslateBusy = ref(false)
const batchTranslateDone = ref(0)
const batchTranslateTotal = ref(0)
const batchTranslateKind = ref<'note' | 'material' | ''>('')

const batchTranslateLabel = computed(() => {
  if (!batchTranslateBusy.value || !batchTranslateTotal.value) return ''
  return `${batchTranslateDone.value}/${batchTranslateTotal.value}`
})

function noteHasZh(n: ConvertNoteLine | null | undefined): boolean {
  return !!(n?.translated || '').trim()
}

/** 原文是否已是中文（汉字占内容字比例够高，避免误伤纯英文型号） */
function textLooksChinese(raw: string): boolean {
  const s = (raw || '').trim()
  if (!s) return false
  const cjkMatches = s.match(/[\u3400-\u9FFF\uF900-\uFAFF]/g)
  const cjkCount = cjkMatches ? cjkMatches.reduce((n, part) => n + part.length, 0) : 0
  if (cjkCount < 1) return false
  const contentMatches = s.match(/[A-Za-z0-9\u3400-\u9FFF\uF900-\uFAFF]/g)
  const contentCount = contentMatches ? contentMatches.length : 0
  if (contentCount < 1) return false
  return cjkCount / contentCount >= 0.5
}

/** 料号/标准号/纯数字等无需汉译，保持原文即可确认 */
function textKeepAsOriginal(raw: string): boolean {
  const s = (raw || '').trim()
  if (!s) return false
  // 纯数字或数字分段（如 111、0.01、12-34）
  if (/^[\d]+([./\-_][\d]+)*$/.test(s)) return true
  // 含数字的短型号/料号（如 A5052、FR-4），无空格
  if (!/\s/.test(s) && /\d/.test(s) && /^[A-Za-z0-9.\-_/]+$/.test(s) && s.length <= 24) {
    return true
  }
  return false
}

/** 有可审校的中文名：中文名不能为空；中英相同且原文非中文、也非料号数字 → 未译 */
function materialHasZh(m: ConvertMaterialRow | null | undefined): boolean {
  const zh = (m?.materialZh || '').trim()
  const en = (m?.materialEn || '').trim()
  if (!zh) return false
  if (en && zh === en && !textLooksChinese(en) && !textKeepAsOriginal(en)) return false
  return true
}

/** 有原文且尚无真中文 → 须先译再确认 */
function materialNeedsTranslate(m: ConvertMaterialRow | null | undefined): boolean {
  return !!(m?.materialEn || '').trim() && !materialHasZh(m)
}

/** 原文已是中文或料号/数字：中文名为空时自动填入原文（仍须点确认；真正英文须翻译） */
function fillMaterialZhKeepOriginal() {
  for (const m of editMaterials.value) {
    const zh = (m.materialZh || '').trim()
    const en = (m.materialEn || '').trim()
    if (zh || !en) continue
    if (textLooksChinese(en) || textKeepAsOriginal(en)) {
      m.materialZh = en
    }
  }
}

/** 清掉「中文名=英文原文」的污染（原文已是中文或料号数字的同字不视为污染） */
function scrubMaterialZhEnCopies() {
  fillMaterialZhKeepOriginal()
  for (const m of editMaterials.value) {
    const zh = (m.materialZh || '').trim()
    const en = (m.materialEn || '').trim()
    if (zh && en && zh === en && !textLooksChinese(en) && !textKeepAsOriginal(en)) {
      m.materialZh = ''
    }
  }
  scrubMaterialReviewVerifiedForUntranslated()
}

/** 未译行不得保持④确认态（hydrate / scrub 后纠偏） */
function scrubMaterialReviewVerifiedForUntranslated() {
  const n = editMaterials.value.length
  if (!n) return
  const next = materialReviewVerified.value.slice()
  while (next.length < n) next.push(false)
  let changed = false
  for (let i = 0; i < n; i += 1) {
    if (next[i] && materialNeedsTranslate(editMaterials.value[i])) {
      next[i] = false
      changed = true
    }
  }
  if (changed) materialReviewVerified.value = next
}

/** ① 截图识别结果弹窗 */
type RegionPickRow = {
  key: string
  checked: boolean
  /** append | overwrite（仅同号已存在时可选） */
  mode: 'append' | 'overwrite'
  noteNo: string
  original: string
  materialEn: string
  thickness: string
  color: string
  existIdx: number
  existVerified: boolean
}
const regionModalOpen = ref(false)
const regionRecognizing = ref(false)
const regionKind = ref<'note' | 'material'>('note')
const regionOcrText = ref('')
const regionWarnings = ref<string[]>([])
const regionPickRows = ref<RegionPickRow[]>([])
/** 有同号已存在时才显示「写入」列（追加/覆盖） */
const regionHasWriteModeChoice = computed(() =>
  regionPickRows.value.some((r) => r.existIdx >= 0)
)

/** 与 MainLayout 内容区全屏联动（截图抽取仅提示，不自动进入） */
type ContentFsApi = {
  active: Ref<boolean>
  enter?: () => void
  exit: () => void
}
const contentFs = inject<ContentFsApi | null>('jyContentFullscreen', null)

function startRegionCapture() {
  if (!preview.value || extracting.value) {
    message.warning('请先上传客户 DWG 并完成图纸预览')
    return
  }
  if (extractTab.value === 'crop') {
    message.warning('请先切换到 NOTE 或材料 Tab，再截图抽取')
    return
  }
  // 不自动全屏：细字看不清时由用户点顶栏全屏
  if (!contentFs?.active?.value) {
    message.info('细字看不清时，可先点顶栏「全屏」再框选')
  }
  const kind: 'note' | 'material' = extractTab.value === 'material' ? 'material' : 'note'
  void nextTick(() => {
    emit('regionCaptureRequest', kind)
  })
}

function findExistNoteIdx(noteNo: string): number {
  const key = normalizeNoteNo(noteNo)
  if (!key) return -1
  return editNotes.value.findIndex((n) => normalizeNoteNo(n.noteNo) === key)
}

function findExistMaterialIdx(noteNo: string): number {
  const key = normalizeNoteNo(noteNo)
  if (!key) return -1
  return editMaterials.value.findIndex((m) => normalizeNoteNo(m.noteNo || m.seq) === key)
}

/** 左图框选确认后由工作区回调（优先传归一化坐标，服务端裁高清底图） */
async function applyRegionCapture(payload: {
  kind: 'note' | 'material'
  rect?: { x: number; y: number; w: number; h: number }
  blob?: Blob
}) {
  if (!payload) return
  if (regionRecognizing.value) return
  regionRecognizing.value = true
  try {
    let result: RegionExtractResult
    const taskId = props.taskId
    if (payload.rect && taskId != null && taskId !== '') {
      result = await convertRegionExtractByRect(payload.kind, taskId, payload.rect)
    } else if (payload.blob) {
      result = await convertRegionExtract(payload.blob, payload.kind, taskId)
    } else {
      message.warning('选区无效')
      return
    }
    regionKind.value = result.kind === 'material' ? 'material' : 'note'
    regionOcrText.value = (result.ocrText || '').trim()
    regionWarnings.value = result.warnings || []
    const picks: RegionPickRow[] = []
    if (regionKind.value === 'material') {
      const rows = result.materials || []
      rows.forEach((m, i) => {
        const noteNo = String(m.noteNo || m.seq || i + 1).trim()
        const existIdx = findExistMaterialIdx(noteNo)
        picks.push({
          key: `m-${i}-${noteNo}`,
          checked: true,
          mode: existIdx >= 0 ? 'overwrite' : 'append',
          noteNo,
          original: '',
          materialEn: (m.materialEn || '').trim(),
          thickness: (m.thickness || '').trim() || UNLABELED,
          color: (m.color || '').trim() || UNLABELED,
          existIdx,
          existVerified: existIdx >= 0 ? !!materialVerified.value[existIdx] : false,
        })
      })
    } else {
      const rows = result.notes || []
      rows.forEach((n, i) => {
        const noteNo = String(n.noteNo || i + 1).trim()
        const existIdx = findExistNoteIdx(noteNo)
        picks.push({
          key: `n-${i}-${noteNo}`,
          checked: true,
          mode: existIdx >= 0 ? 'overwrite' : 'append',
          noteNo,
          original: (n.original || '').trim(),
          materialEn: '',
          thickness: '',
          color: '',
          existIdx,
          existVerified: existIdx >= 0 ? !!noteVerified.value[existIdx] : false,
        })
      })
    }
    regionPickRows.value = picks
    // 后端未预填时：前端按 OCR 序号拆多行；禁止整段糊成 1 条
    if (!picks.length && regionOcrText.value && regionKind.value === 'note') {
      const splitRows = splitOcrTextToNoteRows(regionOcrText.value)
      if (splitRows.length >= 2) {
        regionPickRows.value = splitRows.map((r, i) => {
          const existIdx = findExistNoteIdx(r.noteNo)
          return {
            key: `n-ocr-${i}-${r.noteNo}`,
            checked: true,
            mode: existIdx >= 0 ? ('overwrite' as const) : ('append' as const),
            noteNo: r.noteNo,
            original: r.original,
            materialEn: '',
            thickness: '',
            color: '',
            existIdx,
            existVerified: existIdx >= 0 ? !!noteVerified.value[existIdx] : false,
          }
        })
        if (!regionWarnings.value.some((w) => w.includes('OCR') || w.includes('识别原文'))) {
          regionWarnings.value = [
            ...regionWarnings.value,
            `未能自动抽出 NOTE，已按识别原文拆成 ${splitRows.length} 条，请核对`,
          ]
        }
        message.info(`已按识别原文拆成 ${splitRows.length} 条，请核对序号与原文后再写入`)
      } else {
        regionPickRows.value = []
        if (!regionWarnings.value.some((w) => w.includes('OCR') || w.includes('手工'))) {
          regionWarnings.value = [
            ...regionWarnings.value,
            '未能按序号拆成多条，请展开识别原文对照后点「新增」手工落表，勿整段粘成一条',
          ]
        }
        message.warning('未能拆成多条 NOTE，请对照识别原文手工填写')
      }
    } else if (!picks.length && !regionOcrText.value) {
      message.warning('未识别到可用文字，请换一块区域重试')
      return
    } else if (!picks.length) {
      message.warning('未抽出结构化行，可对照识别原文手工填写')
    } else if (
      regionWarnings.value.some(
        (w) =>
          w.includes('OCR') ||
          w.includes('预填') ||
          w.includes('拆条') ||
          w.includes('超时') ||
          w.includes('失败')
      )
    ) {
      const tip =
        regionWarnings.value.find(
          (w) =>
            w.includes('OCR') ||
            w.includes('预填') ||
            w.includes('拆条') ||
            w.includes('超时')
        ) || '已按识别原文预填，请核对后再写入'
      message.warning(humanizeUserTip(tip))
    }
    // 后端已出条但仍疑似一大坨：再按原文强制拆
    if (
      regionKind.value === 'note' &&
      regionPickRows.value.length === 1 &&
      String(regionPickRows.value[0]?.original || '').length >= 200
    ) {
      const glued = regionPickRows.value[0]!
      const splitRows = splitOcrTextToNoteRows(glued.original || regionOcrText.value)
      if (splitRows.length >= 2) {
        regionPickRows.value = splitRows.map((r, i) => {
          const existIdx = findExistNoteIdx(r.noteNo)
          return {
            key: `n-split-${i}-${r.noteNo}`,
            checked: true,
            mode: existIdx >= 0 ? ('overwrite' as const) : ('append' as const),
            noteNo: r.noteNo,
            original: r.original,
            materialEn: '',
            thickness: '',
            color: '',
            existIdx,
            existVerified: existIdx >= 0 ? !!noteVerified.value[existIdx] : false,
          }
        })
        message.info(`检测到粘连长文，已拆成 ${splitRows.length} 条，请核对`)
      }
    }
    regionModalOpen.value = true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '截图抽取失败')
  } finally {
    regionRecognizing.value = false
  }
}

/**
 * 前端 OCR 拆条：按 1. / 2) / [11] / 无句点「2 GEOMETRIC」切开。
 * 拆不出多条返回 []（禁止整段糊成 1 条）。
 */
function splitOcrTextToNoteRows(ocr: string): { noteNo: string; original: string }[] {
  let blob = String(ocr || '')
    .replace(/\r/g, '\n')
    .replace(/。/g, '.')
    .trim()
  if (!blob) return []
  blob = blob
    .replace(/(?<=[A-Za-z0-9.])(?=[1-9]\d{0,2}\s*[.)、．](?!\d))/g, '\n')
    .replace(/(?<=[A-Za-z.,;:])\s+(?=[1-9]\d{0,2}\s+[A-Za-z\[])/g, '\n')
    .replace(/(?<!\n)\s*(?=\[\s*[1-9]\d{0,2}\s*\])/g, '\n')
  const flat = blob.replace(/\n+/g, ' ').replace(/[ \t]{2,}/g, ' ').trim()
  const re =
    /(?:\[\s*([1-9]\d{0,2})\s*\]|([1-9]\d{0,2})\s*[.)、．](?!\d)|(?<![\d.A-Za-z])([1-9]\d{0,2})(?=\s+[A-Za-z\[]))/gi
  const hits: { start: number; no: string }[] = []
  let m: RegExpExecArray | null
  while ((m = re.exec(flat))) {
    const no = m[1] || m[2] || m[3]
    if (!no) continue
    hits.push({ start: m.index, no })
  }
  if (hits.length < 2) {
    // 按行：行首序号
    const lineHead =
      /^(?:\[([1-9]\d{0,2})\]|([1-9]\d{0,2})(?:\s*[.)、．]|\s+(?=[A-Za-z\[])))\s*(.*)$/i
    const rows: { noteNo: string; original: string }[] = []
    let curNo = ''
    let curBody: string[] = []
    for (const raw of blob.split(/\n+/)) {
      const line = raw.trim()
      if (!line) continue
      const hm = lineHead.exec(line)
      if (hm) {
        if (curNo && curBody.join(' ').trim().length >= 4) {
          const body = curBody.join(' ').replace(/\s+/g, ' ').trim()
          rows.push({ noteNo: curNo, original: `${curNo}. ${body}` })
        }
        curNo = hm[1] || hm[2] || ''
        curBody = [(hm[3] || '').trim()].filter(Boolean)
      } else if (curNo) {
        curBody.push(line)
      }
    }
    if (curNo && curBody.join(' ').trim().length >= 4) {
      const body = curBody.join(' ').replace(/\s+/g, ' ').trim()
      rows.push({ noteNo: curNo, original: `${curNo}. ${body}` })
    }
    return rows.length >= 2 ? rows : []
  }
  const rows: { noteNo: string; original: string }[] = []
  for (let i = 0; i < hits.length; i++) {
    const start = hits[i]!.start
    const end = i + 1 < hits.length ? hits[i + 1]!.start : flat.length
    const chunk = flat.slice(start, end).trim()
    const body = chunk
      .replace(/^(?:\[\s*\d+\s*\]|\d+\s*[.)、．]?)\s*/, '')
      .replace(/\s+/g, ' ')
      .trim()
    if (body.length < 4) continue
    const no = hits[i]!.no
    rows.push({ noteNo: no, original: `${no}. ${body}` })
  }
  // 去重同号，保留较长
  const byNo = new Map<string, { noteNo: string; original: string }>()
  for (const r of rows) {
    const prev = byNo.get(r.noteNo)
    if (!prev || prev.original.length < r.original.length) byNo.set(r.noteNo, r)
  }
  return [...byNo.values()]
}

function closeRegionModal() {
  regionModalOpen.value = false
  regionPickRows.value = []
  regionOcrText.value = ''
  regionWarnings.value = []
}

/** 原文框按内容撑开行数，避免框内再滚动 */
function regionBodyRows(text: string): number {
  const t = String(text || '')
  if (!t.trim()) return 4
  const parts = t.split(/\r?\n/)
  let rows = 0
  for (const line of parts) {
    rows += Math.max(1, Math.ceil(Math.max(line.length, 1) / 68))
  }
  return Math.min(48, Math.max(4, rows))
}

/** 弹窗内改序号：刷新追加/覆盖态 */
function onRegionNoteNoChange(row: RegionPickRow) {
  const raw = String(row.noteNo || '').replace(/\D/g, '')
  row.noteNo = raw || row.noteNo
  if (regionKind.value === 'material') {
    const existIdx = findExistMaterialIdx(row.noteNo)
    row.existIdx = existIdx
    row.existVerified = existIdx >= 0 ? !!materialVerified.value[existIdx] : false
    row.mode = existIdx >= 0 ? 'overwrite' : 'append'
    return
  }
  const existIdx = findExistNoteIdx(row.noteNo)
  row.existIdx = existIdx
  row.existVerified = existIdx >= 0 ? !!noteVerified.value[existIdx] : false
  row.mode = existIdx >= 0 ? 'overwrite' : 'append'
}

/** 弹窗确定：校验失败时 reject，避免 a-modal 误关 */
function commitRegionPicks() {
  const selected = regionPickRows.value.filter((r) => r.checked)
  if (!selected.length) {
    message.warning('请至少勾选一行')
    return Promise.reject()
  }
  const overwriteVerified = selected.filter((r) => r.mode === 'overwrite' && r.existVerified)
  const doCommit = () => {
    let appended = 0
    let overwritten = 0
    if (regionKind.value === 'material') {
      extractTab.value = 'material'
      for (const row of selected) {
        if (row.mode === 'overwrite' && row.existIdx >= 0 && row.existIdx < editMaterials.value.length) {
          const cur = editMaterials.value[row.existIdx]!
          cur.noteNo = row.noteNo
          cur.materialEn = row.materialEn
          cur.thickness = row.thickness
          cur.color = row.color
          cur.source = 'region_capture'
          const flags = materialVerified.value.slice()
          flags[row.existIdx] = false
          materialVerified.value = flags
          const rev = materialReviewVerified.value.slice()
          if (row.existIdx < rev.length) {
            rev[row.existIdx] = false
            materialReviewVerified.value = rev
          }
          overwritten += 1
        } else {
          const seqNum = Number(row.noteNo)
          editMaterials.value.push({
            seq: Number.isFinite(seqNum) ? seqNum : editMaterials.value.length + 1,
            noteNo: row.noteNo,
            materialEn: row.materialEn,
            materialZh: '',
            thickness: row.thickness,
            source: 'region_capture',
            role: '',
            color: row.color,
          })
          materialVerified.value = [...materialVerified.value, false]
          materialReviewVerified.value = [...materialReviewVerified.value, false]
          appended += 1
        }
      }
      markMaterialsEditedInExtract()
    } else {
      extractTab.value = 'note'
      for (const row of selected) {
        if (row.mode === 'overwrite' && row.existIdx >= 0 && row.existIdx < editNotes.value.length) {
          const cur = editNotes.value[row.existIdx]!
          cur.noteNo = row.noteNo
          cur.original = row.original
          cur.translated = ''
          cur.status = 'untranslated'
          const flags = noteVerified.value.slice()
          flags[row.existIdx] = false
          noteVerified.value = flags
          const rev = noteTranslateVerified.value.slice()
          if (row.existIdx < rev.length) {
            rev[row.existIdx] = false
            noteTranslateVerified.value = rev
          }
          overwritten += 1
        } else {
          editNotes.value.push({
            noteNo: row.noteNo,
            original: row.original,
            translated: '',
            status: 'untranslated',
          })
          noteVerified.value = [...noteVerified.value, false]
          noteTranslateVerified.value = [...noteTranslateVerified.value, false]
          appended += 1
        }
      }
      emptyNotesAcked.value = false
      notesConfirmed.value = false
    }
    sortExtractNotesByNo({ persist: false })
    sortExtractMaterialsByNo({ persist: false })
    void persistAnnot()
    closeRegionModal()
    clearSheetReadyFlag()
    const parts = []
    if (appended) parts.push(`追加 ${appended}`)
    if (overwritten) parts.push(`覆盖 ${overwritten}`)
    message.success(`截图抽取已写入（${parts.join('，')}），请逐条确认`)
    void nextTick(() => autosizeAllNoteTextareas())
  }
  if (overwriteVerified.length) {
    return new Promise<void>((resolve, reject) => {
      Modal.confirm({
        title: '覆盖已确认行？',
        content: `有 ${overwriteVerified.length} 行同号且已确认，覆盖后须重新对图确认。`,
        okText: '仍要覆盖',
        okType: 'danger',
        cancelText: '取消',
        centered: true,
        onOk: () => {
          doCommit()
          resolve()
        },
        onCancel: () => reject(),
      })
    })
  }
  doCommit()
}

const notesVerifiedCount = computed(() => noteVerified.value.filter(Boolean).length)
const allNotesVerified = computed(() => {
  const n = editNotes.value.length
  // 空列表须显式确认「无 NOTE」，避免误放行进②
  if (!n) return emptyNotesAcked.value
  if (noteVerified.value.length < n) return false
  for (let i = 0; i < n; i += 1) {
    if (!noteVerified.value[i]) return false
  }
  return true
})
const materialsVerifiedCount = computed(() => materialVerified.value.filter(Boolean).length)
const allMaterialsVerified = computed(() => {
  const n = editMaterials.value.length
  if (!n) return emptyMaterialsAcked.value
  if (materialVerified.value.length < n) return false
  for (let i = 0; i < n; i += 1) {
    if (!materialVerified.value[i]) return false
  }
  return true
})
/** ① 抽取核对完成：NOTE + 材料都确认 */
const extractFullyVerified = computed(() => allNotesVerified.value && allMaterialsVerified.value)

/** ① 底栏：分阶段文案（NOTE → 材料 → 层次） */
const extractFootLabel = computed(() => {
  if (!allNotesVerified.value) {
    const total = editNotes.value.length
    if (!total) return '请勾选「确认本图无 NOTE」后继续'
    return `请先确认全部 NOTE（${notesVerifiedCount.value}/${total}）`
  }
  if (!allMaterialsVerified.value) {
    const total = editMaterials.value.length
    const prog = total
      ? `（材料 ${materialsVerifiedCount.value}/${total}）`
      : ''
    if (extractTab.value !== 'material') {
      return `NOTE 核对完成，进入材料核对${prog}`
    }
    if (!total) return '请勾选「确认本图无材料行」后进入层次'
    return `请确认全部材料（${materialsVerifiedCount.value}/${total}）`
  }
  return '核对完成，进入层次标注'
})

/** ① 底栏可点：NOTE 齐可进材料；双齐可进层次 */
const extractFootEnabled = computed(() => {
  if (!preview.value) return false
  if (!allNotesVerified.value) return false
  if (!allMaterialsVerified.value) return extractTab.value !== 'material'
  return true
})

const notesTranslateVerifiedCount = computed(
  () => noteTranslateVerified.value.filter(Boolean).length
)
const allNotesTranslateVerified = computed(() => {
  const n = editNotes.value.length
  // ④ 无 NOTE：与①空表确认对齐；未走过①空确认时仍允许（材料-only 图纸）
  if (!n) return emptyNotesAcked.value || materialsConfirmed.value
  if (noteTranslateVerified.value.length < n) return false
  for (let i = 0; i < n; i += 1) {
    if (!noteTranslateVerified.value[i]) return false
  }
  return true
})
const materialsReviewVerifiedCount = computed(
  () => materialReviewVerified.value.filter(Boolean).length
)
const allMaterialsReviewVerified = computed(() => {
  const n = editMaterials.value.length
  if (!n) return emptyMaterialsAcked.value || materialsConfirmed.value
  if (materialReviewVerified.value.length < n) return false
  for (let i = 0; i < n; i += 1) {
    if (!materialReviewVerified.value[i]) return false
  }
  return true
})

/** 对齐确认态数组长度；缺省用 fill；多出截断 */
function alignVerifiedFlags(
  src: boolean[] | undefined | null,
  len: number,
  fill = false
): boolean[] {
  const out = Array.from({ length: len }, () => fill)
  if (!src?.length || len <= 0) return out
  const n = Math.min(src.length, len)
  for (let i = 0; i < n; i += 1) out[i] = !!src[i]
  return out
}

function resetNoteVerified(len = editNotes.value.length) {
  noteVerified.value = alignVerifiedFlags(null, len, false)
}

function resetMaterialVerified(len = editMaterials.value.length) {
  materialVerified.value = alignVerifiedFlags(null, len, false)
}

function resetNoteTranslateVerified(len = editNotes.value.length) {
  noteTranslateVerified.value = alignVerifiedFlags(null, len, false)
}

function resetMaterialReviewVerified(len = editMaterials.value.length) {
  materialReviewVerified.value = alignVerifiedFlags(null, len, false)
}

function toggleNoteVerified(idx: number) {
  const n = editNotes.value.length
  const next = noteVerified.value.slice()
  while (next.length < n) next.push(false)
  next[idx] = !next[idx]
  noteVerified.value = next
  void persistAnnot()
}

function toggleMaterialVerified(idx: number) {
  const n = editMaterials.value.length
  const next = materialVerified.value.slice()
  while (next.length < n) next.push(false)
  next[idx] = !next[idx]
  materialVerified.value = next
  void persistAnnot()
}

function toggleNoteTranslateVerified(idx: number) {
  const n = editNotes.value.length
  const next = noteTranslateVerified.value.slice()
  while (next.length < n) next.push(false)
  next[idx] = !next[idx]
  noteTranslateVerified.value = next
  notesConfirmed.value = false
  void persistAnnot()
}

function toggleMaterialReviewVerified(idx: number) {
  const n = editMaterials.value.length
  const next = materialReviewVerified.value.slice()
  while (next.length < n) next.push(false)
  // 未译不可确认；取消确认仍允许
  if (!next[idx] && materialNeedsTranslate(editMaterials.value[idx])) {
    message.warning('请先翻译中文名后再确认')
    return
  }
  next[idx] = !next[idx]
  materialReviewVerified.value = next
  notesConfirmed.value = false
  void persistAnnot()
}

/** 一键确认：二次弹窗防误触 */
function confirmAllWithDialog(opts: { title: string; content: string; onOk: () => void }) {
  Modal.confirm({
    title: opts.title,
    content: opts.content,
    okText: '确认全部',
    cancelText: '取消',
    centered: true,
    onOk: opts.onOk,
  })
}

/** ① 一键确认全部 NOTE */
function confirmAllExtractNotes() {
  const n = editNotes.value.length
  if (!n || allNotesVerified.value) return
  const pending = n - notesVerifiedCount.value
  confirmAllWithDialog({
    title: '一键确认全部 NOTE？',
    content: `将对图确认全部 ${n} 条 NOTE（其中未确认 ${pending} 条）。请确认已对照左侧图纸核对，避免误触。`,
    onOk: () => {
      noteVerified.value = Array.from({ length: n }, () => true)
      void persistAnnot()
    },
  })
}

/** ① 一键确认全部材料 */
function confirmAllExtractMaterials() {
  const n = editMaterials.value.length
  if (!n || allMaterialsVerified.value) return
  const pending = n - materialsVerifiedCount.value
  confirmAllWithDialog({
    title: '一键确认全部材料？',
    content: `将对图确认全部 ${n} 行材料（其中未确认 ${pending} 行）。请确认已对照左侧图纸核对，避免误触。`,
    onOk: () => {
      materialVerified.value = Array.from({ length: n }, () => true)
      void persistAnnot()
    },
  })
}

/** ④ 一键确认全部 NOTE 译文 */
function confirmAllTranslateNotes() {
  const n = editNotes.value.length
  if (!n || allNotesTranslateVerified.value) return
  const pending = n - notesTranslateVerifiedCount.value
  confirmAllWithDialog({
    title: '一键确认全部 NOTE 译文？',
    content: `将确认全部 ${n} 条 NOTE 译文（其中未确认 ${pending} 条）。请确认译文已审校，避免误触。`,
    onOk: () => {
      noteTranslateVerified.value = Array.from({ length: n }, () => true)
      notesConfirmed.value = false
      void persistAnnot()
    },
  })
}

/** ④ 一键确认全部材料审校 */
function confirmAllTranslateMaterials() {
  const n = editMaterials.value.length
  if (!n || allMaterialsReviewVerified.value) return
  const needTranslate = editMaterials.value.filter((m) => materialNeedsTranslate(m)).length
  if (needTranslate > 0) {
    message.warning(`仍有 ${needTranslate} 行未译，请先一键/逐条翻译后再确认`)
    void scrollFirstUnverified('material-untranslated')
    return
  }
  const pending = n - materialsReviewVerifiedCount.value
  confirmAllWithDialog({
    title: '一键确认全部材料？',
    content: `将确认全部 ${n} 行材料审校（其中未确认 ${pending} 行）。请确认字段与译文已审校，避免误触。`,
    onOk: () => {
      materialReviewVerified.value = Array.from({ length: n }, () => true)
      notesConfirmed.value = false
      void persistAnnot()
    },
  })
}

function onFinishExtract() {
  if (!preview.value) {
    message.warning('请先完成图纸抽取')
    return
  }
  if (!editNotes.value.length && !emptyNotesAcked.value) {
    message.warning('未抽到 NOTE：请勾选「确认本图无 NOTE」后再继续')
    extractTab.value = 'note'
    return
  }
  if (!allNotesVerified.value) {
    message.warning(
      `请先确认全部 NOTE（已确认 ${notesVerifiedCount.value}/${editNotes.value.length}）`
    )
    extractTab.value = 'note'
    return
  }
  // NOTE 已齐、材料未齐：切到材料 Tab（友好引导，不弹窗）
  if (!allMaterialsVerified.value) {
    if (!editMaterials.value.length && !emptyMaterialsAcked.value) {
      message.warning('未抽到材料：请勾选「确认本图无材料行」后再进入层次')
    }
    extractTab.value = 'material'
    return
  }
  freezeMaterialsBaseline()
  if (extractSkipped.value) {
    extractSkipped.value = false
    emit('extractSkipChange', false)
    if (props.taskId != null && props.taskId !== '') {
      void taskApi.update(props.taskId, { extractSkipped: false }).catch(() => undefined)
    }
  }
  goStep(2)
}

function ackEmptyNotes() {
  emptyNotesAcked.value = !emptyNotesAcked.value
  void persistAnnot()
}

function ackEmptyMaterials() {
  emptyMaterialsAcked.value = !emptyMaterialsAcked.value
  void persistAnnot()
}

/** 从现有序号推下一号（取最大数字 +1；无数字则用条数 +1） */
function nextExtractSerial(values: Array<string | number | undefined | null>): string {
  let max = 0
  let found = false
  for (const raw of values) {
    const m = String(raw ?? '')
      .trim()
      .match(/(\d+)/)
    if (!m) continue
    const n = Number(m[1])
    if (!Number.isFinite(n)) continue
    found = true
    if (n > max) max = n
  }
  return String(found ? max + 1 : values.length + 1)
}

/** 序号数字键：无数字垫到最后，稳定排序 */
function noteNoSortKey(raw: unknown): number {
  const m = String(raw ?? '')
    .trim()
    .match(/(\d+)/)
  if (!m) return Number.POSITIVE_INFINITY
  const n = Number(m[1])
  return Number.isFinite(n) ? n : Number.POSITIVE_INFINITY
}

function reorderByIndices<T>(arr: T[], order: number[]): T[] {
  return order.map((i) => arr[i]!)
}

/** ① NOTE 按序号升序；确认/译文确认态随行移动 */
function sortExtractNotesByNo(opts?: { persist?: boolean }): boolean {
  const rows = editNotes.value
  if (rows.length <= 1) return false
  const order = rows
    .map((row, i) => ({ i, k: noteNoSortKey(row.noteNo) }))
    .sort((a, b) => a.k - b.k || a.i - b.i)
    .map((o) => o.i)
  if (order.every((i, idx) => i === idx)) return false
  editNotes.value = reorderByIndices(rows, order)
  noteVerified.value = reorderByIndices(
    alignVerifiedFlags(noteVerified.value, order.length, false),
    order
  )
  noteTranslateVerified.value = reorderByIndices(
    alignVerifiedFlags(noteTranslateVerified.value, order.length, false),
    order
  )
  if (opts?.persist !== false) void persistAnnot()
  return true
}

/** ① 材料按序号升序；确认/④审校态随行移动 */
function sortExtractMaterialsByNo(opts?: { persist?: boolean }): boolean {
  const rows = editMaterials.value
  if (rows.length <= 1) return false
  const order = rows
    .map((row, i) => ({ i, k: noteNoSortKey(row.noteNo || row.seq) }))
    .sort((a, b) => a.k - b.k || a.i - b.i)
    .map((o) => o.i)
  if (order.every((i, idx) => i === idx)) return false
  editMaterials.value = reorderByIndices(rows, order)
  materialVerified.value = reorderByIndices(
    alignVerifiedFlags(materialVerified.value, order.length, false),
    order
  )
  materialReviewVerified.value = reorderByIndices(
    alignVerifiedFlags(materialReviewVerified.value, order.length, false),
    order
  )
  if (opts?.persist !== false) void persistAnnot()
  return true
}

function onNoteNoBlur() {
  if (sortExtractNotesByNo({ persist: false })) void persistAnnot()
}

function onMaterialNoteNoBlur() {
  if (sortExtractMaterialsByNo({ persist: false })) {
    materialsBaseline.value = cloneMaterials(editMaterials.value)
    void persistAnnot()
  }
}

function spliceVerifiedFlag(flags: boolean[], idx: number): boolean[] {
  const next = flags.slice()
  if (idx >= 0 && idx < next.length) next.splice(idx, 1)
  return next
}

/** ① 手动补一条 NOTE（抽漏时） */
function addExtractNote() {
  const noteNo = nextExtractSerial(editNotes.value.map((n) => n.noteNo))
  editNotes.value.push({
    noteNo,
    original: '',
    translated: '',
    status: 'untranslated',
  })
  noteVerified.value = [...noteVerified.value, false]
  noteTranslateVerified.value = [...noteTranslateVerified.value, false]
  emptyNotesAcked.value = false
  notesConfirmed.value = false
  extractTab.value = 'note'
  clearSheetReadyFlag()
  sortExtractNotesByNo({ persist: false })
  void persistAnnot()
  void nextTick(() => autosizeAllNoteTextareas())
  message.info(`已新增 NOTE ${noteNo}，请对照左图填写`, 2)
}

/** ① 删掉多余/并错的 NOTE */
function removeExtractNote(idx: number) {
  const row = editNotes.value[idx]
  if (!row) return
  const label = (row.noteNo || String(idx + 1)).trim() || String(idx + 1)
  const hasBody = !!(row.original || '').trim() || !!(row.translated || '').trim()
  const doRemove = () => {
    editNotes.value.splice(idx, 1)
    noteVerified.value = spliceVerifiedFlag(noteVerified.value, idx)
    noteTranslateVerified.value = spliceVerifiedFlag(noteTranslateVerified.value, idx)
    notesConfirmed.value = false
    void persistAnnot()
    message.info(`已删除 NOTE ${label}`, 1.5)
  }
  if (!hasBody) {
    doRemove()
    return
  }
  Modal.confirm({
    title: `删除 NOTE ${label}？`,
    content: '删除后需重新核对条数；已填原文不会保留。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    centered: true,
    onOk: doRemove,
  })
}

function onNoteNoInput(idx: number, ev: Event) {
  const row = editNotes.value[idx]
  if (!row) return
  row.noteNo = (ev.target as HTMLInputElement).value
  notesConfirmed.value = false
  if (noteVerified.value[idx]) {
    const next = noteVerified.value.slice()
    next[idx] = false
    noteVerified.value = next
    void persistAnnot()
  }
}

/** ① 材料增删后：基线与③门禁作废，须重确认 */
function markMaterialsEditedInExtract() {
  emptyMaterialsAcked.value = false
  notesConfirmed.value = false
  if (materialsConfirmed.value) materialsConfirmed.value = false
  materialsBaseline.value = cloneMaterials(editMaterials.value)
  if (materialWorkRows.value.length) refreshMaterialWork(true)
}

function addExtractMaterial() {
  const noteNo = nextExtractSerial(editMaterials.value.map((m) => m.noteNo || m.seq))
  const seqNum = Number(noteNo)
  editMaterials.value.push({
    seq: Number.isFinite(seqNum) ? seqNum : editMaterials.value.length + 1,
    noteNo,
    materialEn: '',
    materialZh: '',
    thickness: '',
    color: '',
    source: 'manual',
  })
  materialVerified.value = [...materialVerified.value, false]
  materialReviewVerified.value = [...materialReviewVerified.value, false]
  markMaterialsEditedInExtract()
  extractTab.value = 'material'
  clearSheetReadyFlag()
  sortExtractMaterialsByNo({ persist: false })
  void persistAnnot()
  message.info(`已新增材料 ${noteNo}，请对照左图填写`, 2)
}

function removeExtractMaterial(idx: number) {
  const row = editMaterials.value[idx]
  if (!row) return
  const label = String(row.noteNo || row.seq || idx + 1).trim() || String(idx + 1)
  const hasBody = !!(
    (row.materialEn || '').trim() ||
    (row.materialZh || '').trim() ||
    (row.thickness || '').trim() ||
    (row.color || '').trim()
  )
  const doRemove = () => {
    editMaterials.value.splice(idx, 1)
    materialVerified.value = spliceVerifiedFlag(materialVerified.value, idx)
    materialReviewVerified.value = spliceVerifiedFlag(materialReviewVerified.value, idx)
    markMaterialsEditedInExtract()
    void persistAnnot()
    message.info(`已删除材料 ${label}`, 1.5)
  }
  if (!hasBody) {
    doRemove()
    return
  }
  Modal.confirm({
    title: `删除材料 ${label}？`,
    content: '删除后须重新逐条确认；③ 材料核对基线将按当前表重算。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    centered: true,
    onOk: doRemove,
  })
}

function onMaterialNoteNoInput(idx: number, ev: Event) {
  const row = editMaterials.value[idx]
  if (!row) return
  const v = (ev.target as HTMLInputElement).value
  row.noteNo = v
  const n = Number(String(v).trim().match(/(\d+)/)?.[1] || '')
  if (Number.isFinite(n)) row.seq = n
  // 与改字段一致：① 改序号使③基线失效，本行需重确认
  notesConfirmed.value = false
  let changed = false
  if (materialsConfirmed.value) {
    materialsConfirmed.value = false
    changed = true
  }
  if (materialVerified.value[idx]) {
    const next = materialVerified.value.slice()
    next[idx] = false
    materialVerified.value = next
    changed = true
  }
  if (changed) void persistAnnot()
}
/** ① 侧栏滚动区：按视口剩余高度锁定（勿用量到的面板高度——面板常被内容撑开） */
const extractBodyRef = ref<HTMLElement | null>(null)
let extractScrollRo: ResizeObserver | null = null

function lockExtractScrollHeight() {
  if (step.value !== 1 || !preview.value || extracting.value) return
  const body = extractBodyRef.value
  if (!body) return

  const host = document.getElementById('eng-wizard-side')
  const panel = body.closest('.eng-side-panel, .eng-drawer') as HTMLElement | null
  const head = panel?.querySelector('.eng-drawer-head') as HTMLElement | null
  const headH = head?.getBoundingClientRect().height || 64

  // 以宿主角点到视口底的剩余空间为准，与内容撑开无关
  const topAnchor = host?.getBoundingClientRect().top ?? body.getBoundingClientRect().top
  const bottomGap = 10
  const hostH = Math.max(200, Math.floor(window.innerHeight - topAnchor - bottomGap))
  const bodyH = Math.max(160, hostH - Math.ceil(headH))

  if (host) {
    host.style.height = `${hostH}px`
    host.style.maxHeight = `${hostH}px`
    host.style.minHeight = '0'
    host.style.overflow = 'hidden'
  }
  if (panel) {
    panel.style.height = '100%'
    panel.style.maxHeight = '100%'
    panel.style.overflow = 'hidden'
  }

  body.style.flex = 'none'
  body.style.height = `${bodyH}px`
  body.style.maxHeight = `${bodyH}px`
  body.style.minHeight = '0'
  body.style.overflowX = 'hidden'
  body.style.overflowY = 'scroll'
}

function scheduleLockExtractScroll() {
  requestAnimationFrame(() => {
    requestAnimationFrame(() => lockExtractScrollHeight())
  })
}

/** 长耗时无真实进度时：阶段文案轮播 + 缓涨进度条，避免用户以为卡住 */
type BusyKind = 'scan' | 'save' | 'material' | 'sheet' | 'extract' | 'compose'
const BUSY_STAGES: Record<BusyKind, string[]> = {
  scan: ['读取 STP…', '解析三维实体…', '构建预览网格…', '整理实体列表…'],
  save: ['整理层次数据…', '写入层次资产…', '绑定任务…'],
  material: ['采用结果落表…', '回写层次 DXF…', '更新材料对照…'],
  sheet: ['上传客户 DWG…', '转换图纸格式…', '渲染高清预览…', '整理预览结果…'],
  extract: ['上传客户 DWG…', '解析图纸结构…', '抽取 NOTE…', '抽取材料列表…', '整理预览结果…'],
  compose: ['准备合成参数…', '叠加层次与 DWG…', '生成工程图…', '打包预览产物…'],
}
const busyKind = ref<BusyKind | null>(null)
const busyStageIdx = ref(0)
const busyPercent = ref(0)
let busyTimer: number | null = null
/** 当前异步任务模式：图纸预览 vs 全量抽取 */
const extractJobMode = ref<ExtractJobMode | null>(null)

const showExtractChooser = computed(
  () => !!preview.value?.sheetReady && !extracting.value
)

const busyLabel = computed(() => {
  if (busyKind.value === 'compose' && composeJobStage.value) {
    return composeJobStage.value
  }
  if ((busyKind.value === 'extract' || busyKind.value === 'sheet') && extractJobStage.value) {
    return extractJobStage.value
  }
  const kind = busyKind.value
  if (!kind) return ''
  const stages = BUSY_STAGES[kind]
  return stages[Math.min(busyStageIdx.value, stages.length - 1)] || ''
})

const composeDisplayPercent = computed(() => {
  if (composeJobPercent.value > 0) return Math.min(99, composeJobPercent.value)
  return busyPercent.value
})

const extractDisplayPercent = computed(() => {
  if (extractJobPercent.value > 0) return Math.min(99, extractJobPercent.value)
  return busyPercent.value
})

function startBusy(kind: BusyKind) {
  stopBusyTimer()
  busyKind.value = kind
  busyStageIdx.value = 0
  busyPercent.value = 12
  const stages = BUSY_STAGES[kind]
  busyTimer = window.setInterval(() => {
    if (busyStageIdx.value < stages.length - 1) {
      busyStageIdx.value += 1
    }
    if (busyPercent.value < 92) {
      busyPercent.value = Math.min(92, busyPercent.value + 5 + Math.floor(Math.random() * 8))
    }
  }, 1400)
}

function stopBusyTimer() {
  if (busyTimer != null) {
    window.clearInterval(busyTimer)
    busyTimer = null
  }
}

function stopBusy() {
  stopBusyTimer()
  busyKind.value = null
  busyStageIdx.value = 0
  busyPercent.value = 0
}

function extractStartStorageKey(): string | null {
  if (props.taskId == null || props.taskId === '') return null
  return `${EXTRACT_START_KEY}${props.taskId}`
}

function composeStartStorageKey(): string | null {
  if (props.taskId == null || props.taskId === '') return null
  return `${COMPOSE_START_KEY}${props.taskId}`
}

function formatElapsed(ms: number): string {
  const sec = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  if (h > 0) return `${h}:${mm}:${ss}`
  return `${mm}:${ss}`
}

function readExtractStartAt(): number | null {
  const key = extractStartStorageKey()
  if (!key) return extractStartAtMem
  try {
    const raw = sessionStorage.getItem(key)
    const n = raw ? Number(raw) : NaN
    if (Number.isFinite(n) && n > 0) return n
  } catch {
    // ignore
  }
  return extractStartAtMem
}

function writeExtractStartAt(ts: number) {
  extractStartAtMem = ts
  const key = extractStartStorageKey()
  if (!key) return
  try {
    sessionStorage.setItem(key, String(ts))
  } catch {
    // ignore quota
  }
}

function clearExtractStartAt() {
  extractStartAtMem = null
  extractPerfAnchor = null
  extractElapsedAtAnchor = 0
  extractElapsedMs.value = 0
  const key = extractStartStorageKey()
  if (!key) return
  try {
    sessionStorage.removeItem(key)
  } catch {
    // ignore
  }
}

function stopExtractElapsedTick() {
  if (extractTickTimer != null) {
    window.clearInterval(extractTickTimer)
    extractTickTimer = null
  }
}

function syncExtractElapsedFromJob(job?: EngExtractJobView | null) {
  let serverMs = 0
  if (job) {
    const rawElapsed = Number(job.elapsedMs)
    if (Number.isFinite(rawElapsed) && rawElapsed > 0) {
      serverMs = Math.floor(rawElapsed)
    } else {
      const started = Number(job.startedAt)
      if (Number.isFinite(started) && started > 0) {
        const delta = Date.now() - started
        if (delta > 0) serverMs = Math.floor(delta)
        writeExtractStartAt(started)
      }
    }
    const started = Number(job.startedAt)
    if (Number.isFinite(started) && started > 0) {
      writeExtractStartAt(started)
    }
  }
  const localMs =
    extractPerfAnchor != null
      ? extractElapsedAtAnchor + Math.max(0, Math.floor(performance.now() - extractPerfAnchor))
      : extractElapsedMs.value
  const next = Math.max(serverMs, localMs, extractElapsedMs.value)
  extractPerfAnchor = performance.now()
  extractElapsedAtAnchor = next
  extractElapsedMs.value = next
}

function beginExtractTimer() {
  if (extractPerfAnchor == null) {
    let startAt = readExtractStartAt()
    if (startAt == null) {
      startAt = Date.now()
      writeExtractStartAt(startAt)
    } else {
      extractStartAtMem = startAt
    }
    const fromStart = Math.max(0, Date.now() - startAt)
    extractPerfAnchor = performance.now()
    extractElapsedAtAnchor = Math.max(fromStart, extractElapsedMs.value)
    extractElapsedMs.value = extractElapsedAtAnchor
  }
  stopExtractElapsedTick()
  const tick = () => {
    if (extractPerfAnchor == null) return
    extractElapsedMs.value =
      extractElapsedAtAnchor + Math.max(0, Math.floor(performance.now() - extractPerfAnchor))
  }
  tick()
  extractTickTimer = window.setInterval(tick, 250)
}

function readComposeStartAt(): number | null {
  const key = composeStartStorageKey()
  if (!key) return composeStartAtMem
  try {
    const raw = sessionStorage.getItem(key)
    const n = raw ? Number(raw) : NaN
    if (Number.isFinite(n) && n > 0) return n
  } catch {
    // ignore
  }
  return composeStartAtMem
}

function writeComposeStartAt(ts: number) {
  composeStartAtMem = ts
  const key = composeStartStorageKey()
  if (!key) return
  try {
    sessionStorage.setItem(key, String(ts))
  } catch {
    // ignore quota
  }
}

function clearComposeStartAt() {
  composeStartAtMem = null
  composePerfAnchor = null
  composeElapsedAtAnchor = 0
  composeElapsedMs.value = 0
  const key = composeStartStorageKey()
  if (!key) return
  try {
    sessionStorage.removeItem(key)
  } catch {
    // ignore
  }
}

function stopComposeElapsedTick() {
  if (composeTickTimer != null) {
    window.clearInterval(composeTickTimer)
    composeTickTimer = null
  }
}

/** 用服务端已用时或 startedAt 校准锚点（只抬高、不回拨，避免卡在 00:00） */
function syncComposeElapsedFromJob(job?: EngComposeJobView | null) {
  let serverMs = 0
  if (job) {
    const rawElapsed = Number(job.elapsedMs)
    if (Number.isFinite(rawElapsed) && rawElapsed > 0) {
      serverMs = Math.floor(rawElapsed)
    } else {
      const started = Number(job.startedAt)
      if (Number.isFinite(started) && started > 0) {
        const delta = Date.now() - started
        if (delta > 0) serverMs = Math.floor(delta)
        writeComposeStartAt(started)
      }
    }
    const started = Number(job.startedAt)
    if (Number.isFinite(started) && started > 0) {
      writeComposeStartAt(started)
    }
  }
  const localMs =
    composePerfAnchor != null
      ? composeElapsedAtAnchor + Math.max(0, Math.floor(performance.now() - composePerfAnchor))
      : composeElapsedMs.value
  const next = Math.max(serverMs, localMs, composeElapsedMs.value)
  composePerfAnchor = performance.now()
  composeElapsedAtAnchor = next
  composeElapsedMs.value = next
}

/** ⑤ 生成计时：本地按 performance 单调走秒；轮询用服务端 elapsedMs 校准 */
function beginComposeTimer() {
  if (composePerfAnchor == null) {
    let startAt = readComposeStartAt()
    if (startAt == null) {
      startAt = Date.now()
      writeComposeStartAt(startAt)
    } else {
      composeStartAtMem = startAt
    }
    const fromStart = Math.max(0, Date.now() - startAt)
    composePerfAnchor = performance.now()
    composeElapsedAtAnchor = Math.max(fromStart, composeElapsedMs.value)
    composeElapsedMs.value = composeElapsedAtAnchor
  }
  stopComposeElapsedTick()
  const tick = () => {
    if (composePerfAnchor == null) return
    composeElapsedMs.value =
      composeElapsedAtAnchor + Math.max(0, Math.floor(performance.now() - composePerfAnchor))
  }
  tick()
  composeTickTimer = window.setInterval(tick, 250)
}

function clampStep(n?: number): WizardStep {
  const v = Number(n || 1)
  if (v === 2) return 2
  if (v === 3) return 3
  if (v === 4) return 4
  if (v === 5) return 5
  return 1
}

function markReached(n: number) {
  const s = clampStep(n)
  if (s > reachedStep.value) reachedStep.value = s
}

/**
 * 旧 annot.version&lt;2：①层次/②抽取 → 新：①抽取/②层次（仅互换 1↔2）
 * 无 annot 时：step=2 且已有 DWG 视为旧「抽取中」，映射到新①
 */
function migrateLegacyStep(
  n?: number,
  opts?: { flowVersion?: number; hasStack?: boolean; hasDwg?: boolean }
): WizardStep {
  const v = Number(n || 1)
  if (!(v >= 1 && v <= 5)) return 1
  const stepN = clampStep(v)
  const fv = opts?.flowVersion
  if (fv != null && fv >= WIZARD_FLOW_VERSION) return stepN
  if (fv != null && fv < WIZARD_FLOW_VERSION) {
    if (stepN === 1) return 2
    if (stepN === 2) return 1
    return stepN
  }
  // 无快照：旧任务常停在②抽取（必有 DWG）；新任务进②层次前必已写 version=2 的 annot
  if (stepN === 2 && opts?.hasDwg) return 1
  return stepN
}

/** 本会话或任务已落盘的客户 DWG / STP（跳过门禁用） */
const hasDwgSource = computed(() => !!dwgFile.value || !!props.hasCustomerDwg)
const hasStepSource = computed(
  () => !!props.stepFileName || !!props.hasStepFile || !!props.stepPersisted
)

/** ①→②：抽取核对完成，或显式跳过抽取（跳过后可先到②再传 STP） */
const canStep2 = computed(() => {
  if (extractSkipped.value) return true
  if (!preview.value || !dwgFile.value) return false
  if (step.value !== 1) return true
  return extractFullyVerified.value
})
/** ②→③：已保存层次或跳过层次；且至少有 DWG / STP / 层次资产之一 */
const canStep3 = computed(() => {
  if (!stackAsset.value && !stackSkipped.value) return false
  if (!hasDwgSource.value && !hasStepSource.value && !stackAsset.value) return false
  if (extractSkipped.value) return true
  return !!dwgFile.value && !!preview.value
})
const canStep4 = computed(() => {
  if (!stackAsset.value && !stackSkipped.value) return false
  if (!hasDwgSource.value && !hasStepSource.value && !stackAsset.value) return false
  if (!extractSkipped.value && (!dwgFile.value || !preview.value)) return false
  return materialsConfirmed.value
})
const canStep5 = computed(() => canStep4.value && notesConfirmed.value)
/** 已完成任务：工作台仅回看，禁止再生成/再点完成（须先「编辑」重开） */
const taskCompleted = computed(
  () => String(props.taskStatus || '').toUpperCase() === 'COMPLETED'
)
/** ⑤ 合成：客户 DWG 与三维层次至少保留一个 */
const hasDwgForCompose = computed(() => !!dwgFile.value || !!props.hasCustomerDwg)
const hasStackForCompose = computed(() => !!stackAsset.value?.id)
const canComposeSources = computed(
  () => hasDwgForCompose.value || hasStackForCompose.value
)

/** 顶栏：已达步骤可回看；推进未达步仍走 canStep*（生成 CTA 不变） */
const canVisitStep2 = computed(() => {
  if (extractSkipped.value) return reachedStep.value >= 2 || canStep2.value
  if (!preview.value || !dwgFile.value) return false
  return reachedStep.value >= 2 || canStep2.value
})
const canVisitStep3 = computed(() => canStep3.value || reachedStep.value >= 3)
const canVisitStep4 = computed(() => {
  if (!canStep3.value && reachedStep.value < 3) return false
  return reachedStep.value >= 4 || materialsConfirmed.value
})
const canVisitStep5 = computed(() => {
  if (!canStep3.value && reachedStep.value < 3) return false
  return reachedStep.value >= 5 || canStep5.value
})

/**
 * 顶栏禁用提示：按①→④返回最早未满足门禁，避免悬停③④⑤时跳号到②。
 * upTo = 想进入的步（检查该步之前的条件）。
 */
function earliestBlockTip(upTo: 2 | 3 | 4 | 5): string {
  if (!extractSkipped.value) {
    if (!preview.value || !dwgFile.value) return '请先完成①抽取并对图核对'
    if (!extractFullyVerified.value) return '请先在①逐条确认全部 NOTE 与材料（空表请勾选确认）'
  }
  if (upTo >= 3) {
    if (!stackAsset.value && !stackSkipped.value) {
      return '请先在②保存三维层次，或点击「跳过层次」'
    }
    if (!hasDwgSource.value && !hasStepSource.value && !stackAsset.value) {
      return '请先上传客户 DWG 或三维 STP（二维与三维至少保留一个）'
    }
  }
  if (upTo >= 4 && !materialsConfirmed.value) return '请先在③确认材料核对'
  if (upTo >= 5 && !notesConfirmed.value) return '请先在④确认译文与材料审校'
  return ''
}

/** 顶栏步骤禁用原因（可感知） */
const step2BlockTip = computed(() => (canVisitStep2.value ? '' : earliestBlockTip(2)))
const step3BlockTip = computed(() => (canVisitStep3.value ? '' : earliestBlockTip(3)))
const step4BlockTip = computed(() => (canVisitStep4.value ? '' : earliestBlockTip(4)))
const step5BlockTip = computed(() => (canVisitStep5.value ? '' : earliestBlockTip(5)))

/** 合成结果是否「层次未嵌入」等半成功 */
function isComposePartial(r: EngComposeResult | null | undefined): boolean {
  if (!r) return false
  const msg = `${r.message || ''} ${(r.warnings || []).join(' ')}`
  return /未能嵌入|没有放进|无法打开|无图元|导入失败|量界失败/.test(msg)
}

function humanizeComposeMessage(raw: string): string {
  const t = (raw || '').trim()
  if (!t) return t
  return t
    .replace(/三维层次未能嵌入爆炸槽/g, '三维层次图没有放进工程图预留区')
    .replace(/未能嵌入爆炸槽/g, '没有放进工程图预留区')
    .replace(/爆炸槽/g, '层次预留区')
    .replace(/层次 DXF/g, '层次图文件')
}

const composePartial = computed(() => isComposePartial(result.value))
const pendingFinalize = ref(false)
const finalizing = ref(false)
const bodyHint = computed(() => props.bodyCount ?? 0)
const hasStepFile = computed(() => !!props.stepFileName)
const canBindStack = computed(() => !props.embedded || !!props.stepPersisted)
/** ② 层次：仅顶栏工具条，无侧栏 */
const chromeOnly = computed(() => step.value === 2)
/** ① 侧栏加宽；③④ 由工作台 full 吃满 */
const drawerWide = computed(() => step.value === 1)

const compareHasConflict = computed(() =>
  materialWorkRows.value.some((r) => r.status !== 'match')
)
const unresolvedMaterialCount = computed(() => unresolvedWorkRows(materialWorkRows.value).length)
const materialSummary = computed(() => {
  const rows = materialWorkRows.value
  let match = 0
  let missing3d = 0
  let missing2d = 0
  let fieldDiff = 0
  let manual = 0
  for (const r of rows) {
    if (r.status === 'match') match += 1
    else if (r.status === 'missing_3d') missing3d += 1
    else if (r.status === 'missing_2d') missing2d += 1
    else if (r.status === 'field_diff') fieldDiff += 1
    if (r.disposition === 'manual') manual += 1
  }
  return {
    total: rows.length,
    match,
    conflict: rows.length - match,
    missing3d,
    missing2d,
    fieldDiff,
    manual,
    unresolved: unresolvedMaterialCount.value
  }
})
const filteredMaterialRows = computed(() => {
  const f = materialFilter.value
  return materialWorkRows.value.filter((r) => {
    if (f === 'all') return true
    if (f === 'conflict') return r.status !== 'match'
    if (f === 'manual') return r.disposition === 'manual'
    return r.status === f
  })
})
/** NOTE 状态未译 + 材料有原文无真中文 */
const untranslatedCount = computed(() => {
  const notes = editNotes.value.filter((n) => n.status === 'untranslated').length
  const mats = editMaterials.value.filter((m) => materialNeedsTranslate(m)).length
  return notes + mats
})

/** 进⑤整理终稿/预览期间暂不推产物板，避免先闪总览再跳终稿 */
const composeBoardHold = ref(false)

/** 进⑤前缓存的层次预览（id 对齐才视为有效） */
const stackPreviewCache = ref<{ id: string; png: string } | null>(null)
/** 正在整理预览、尚未切到⑤ */
const composeEntering = ref(false)
const STACK_PREVIEW_WAIT_MS = 20000

function clearStackPreviewCache() {
  stackPreviewCache.value = null
}

/** ⑤ 左栏产物总览：随审校数据变化推给工作台 */
const composeBoardPayload = computed<ComposeBoardPayload>(() => {
  const p = preview.value
  const s = materialSummary.value
  const stackId = stackAsset.value?.id || ''
  const cache = stackPreviewCache.value
  const prefetched = !!(stackId && cache && cache.id === stackId)
  return {
    dwgFileName: dwgFile.value?.name || p?.fileName || '',
    drawingNo: p?.drawingNo || props.drawingNo || stackAsset.value?.drawingNo || '',
    previewPngBase64: p?.previewPngBase64,
    previewSvg: p?.previewSvg,
    previewSvgs: p?.previewSvgs,
    materialCrops: (p?.materialCrops || []).filter((c) => !!c && String(c).trim()),
    notes: editNotes.value.map((n) => ({ ...n })),
    materials: editMaterials.value.map((m) => ({ ...m })),
    stack: stackAsset.value,
    stackSkipped: stackSkipped.value && !stackAsset.value,
    stackPreviewPngBase64: prefetched ? cache!.png : undefined,
    stackPreviewPrefetched: prefetched,
    materialSummary: {
      total: s.total,
      match: s.match,
      conflict: s.conflict,
      missing3d: s.missing3d,
      missing2d: s.missing2d,
      fieldDiff: s.fieldDiff,
      unresolved: s.unresolved
    },
    materialWorkRows: materialWorkRows.value.map((r) => ({ ...r, adopted: { ...r.adopted } })),
    materialsConfirmed: materialsConfirmed.value,
    notesConfirmed: notesConfirmed.value,
    result: result.value
  }
})

watch(
  [step, composeBoardPayload, composeBoardHold],
  () => {
    if (step.value === 5 && !composeBoardHold.value) emit('composeBoard', composeBoardPayload.value)
    else emit('composeBoard', null)
  },
  { immediate: true, deep: true }
)

function cloneMaterials(list: ConvertMaterialRow[]): ConvertMaterialRow[] {
  return (list || []).map((m) => ({ ...m }))
}

function refreshMaterialWork(reset = false) {
  // 对照始终用 materialsBaseline（① 确认后的真 2D），禁止用 editMaterials（含采用结果会污染状态）
  const baseMats = materialsBaseline.value.length
    ? materialsBaseline.value
    : editMaterials.value
  if (reset || !materialWorkRows.value.length) {
    materialWorkRows.value = buildMaterialWorkRows(stackCallouts.value, baseMats)
  } else {
    materialWorkRows.value = mergeMaterialWorkRows(
      buildMaterialCompare(stackCallouts.value, baseMats),
      materialWorkRows.value
    )
  }
  if (materialsConfirmed.value) settleConfirmedWorkRows()
}

/** ① 核对完成：冻结当前材料为 2D 对照基线（③ 只比这份，不被采用结果改写） */
function freezeMaterialsBaseline() {
  materialsBaseline.value = cloneMaterials(editMaterials.value)
}

/** 已确认材料：采用结果跟快照，字段差不再卡待确认 */
function settleConfirmedWorkRows() {
  const byNo = new Map(
    editMaterials.value.map((m) => [String(m.noteNo || m.seq), m] as const)
  )
  for (const row of materialWorkRows.value) {
    const m = byNo.get(row.noteNo)
    if (m) {
      row.adopted = {
        material: (m.materialEn || m.materialZh || '').trim(),
        color: (m.color || '').trim(),
        thickness: (m.thickness || '').trim()
      }
    }
    if (row.disposition === 'pending') row.disposition = 'adopt_2d'
  }
}

function syncEditMaterialsFromWork() {
  const prevByNo = new Map(
    editMaterials.value.map((m) => [String(m.noteNo || m.seq), m] as const)
  )
  const next = workRowsToMaterials(materialWorkRows.value)
  // ③再同步时保留④已译真中文，避免被空的 dwg.materialZh 冲掉
  for (const m of next) {
    const prev = prevByNo.get(String(m.noteNo || m.seq))
    if (!prev) continue
    if (!(m.materialZh || '').trim() && materialHasZh(prev)) {
      m.materialZh = (prev.materialZh || '').trim()
    }
  }
  editMaterials.value = next
  scrubMaterialZhEnCopies()
}

/** ③ 采用/处置 → annot 快照 */
function snapshotMaterialWork(): NonNullable<TaskAnnotSnapshot['materialWorkSnapshot']> {
  return materialWorkRows.value.map((r) => ({
    noteNo: r.noteNo,
    disposition: r.disposition,
    adopted: {
      material: r.adopted?.material || '',
      color: r.adopted?.color || '',
      thickness: r.adopted?.thickness || '',
    },
  }))
}

/** 用 annot 快照盖回当前工作行（按序号） */
function applyMaterialWorkSnapshot(
  snaps: TaskAnnotSnapshot['materialWorkSnapshot'] | null | undefined
) {
  if (!snaps?.length || !materialWorkRows.value.length) return
  const byNo = new Map(
    snaps.map((s) => [normalizeNoteNo(s.noteNo), s] as const).filter(([k]) => !!k)
  )
  for (const row of materialWorkRows.value) {
    const s = byNo.get(normalizeNoteNo(row.noteNo))
    if (!s) continue
    if (s.disposition) row.disposition = s.disposition
    if (s.adopted) {
      row.adopted = {
        material: s.adopted.material || '',
        color: s.adopted.color || '',
        thickness: s.adopted.thickness || '',
      }
    }
  }
}

/** 离开③前：把采用结果写入材料表，便于落盘 */
function flushMaterialWorkIfNeeded(fromStep: WizardStep, toStep: WizardStep) {
  if (fromStep !== 3 || toStep === 3) return
  if (!materialWorkRows.value.length) return
  syncEditMaterialsFromWork()
}

function isBlankLabel(raw?: string | null): boolean {
  const t = (raw || '').trim()
  return !t || t === UNLABELED
}

function formatStackLine1(
  material: string,
  color: string,
  thickness: number | string | undefined | null
): string {
  const mat = (material || '').trim() || UNLABELED
  const col = (color || '').trim() || UNLABELED
  const thick =
    thickness != null && String(thickness).trim() !== '' && Number.isFinite(Number(thickness))
      ? String(Number(thickness))
      : UNLABELED
  return `${mat}, COLOR: ${col}, ${thick} mm THICK`
}

function parseAdoptedThickness(raw?: string | null): number | null {
  const s = (raw || '').trim().replace(/mm$/i, '').trim()
  if (!s || s === UNLABELED) return null
  const n = Number(s)
  return Number.isFinite(n) ? n : null
}

/** 层次旁注未填齐序号/材料/颜色的条目 */
function unlabeledCalloutLabels(
  callouts: StackExplodeExportBody['callouts'] | null | undefined
): string[] {
  if (!callouts?.length) return ['（无旁注）']
  const bad: string[] = []
  for (const c of callouts) {
    if (
      !String(c.calloutNo || '').trim() ||
      isBlankLabel(c.material) ||
      isBlankLabel(c.color)
    ) {
      bad.push(String(c.calloutNo || c.bodyId || '?'))
    }
  }
  return bad
}

/**
 * ③ 确认后：把采用结果回写到层次 callouts，并重新 saveEngStack 生成绿表 DXF。
 * 仅按序号匹配；缺序号的旁注无法回写，会报错提示回②补全。
 */
async function rewriteStackFromAdopted(): Promise<void> {
  const id = stackAsset.value?.id
  if (!id) {
    throw new Error('请先保存层次')
  }
  const asset = await getEngStack(id)
  const plates = asset.job?.plates
  const callouts = asset.job?.callouts
  if (!plates?.length) {
    throw new Error('层次资产缺少板件，无法回写材料')
  }
  if (!callouts?.length) {
    throw new Error('层次资产缺少旁注，请回②标注序号与材料后重新保存')
  }

  const byNo = new Map(
    materialWorkRows.value.map((r) => [normalizeNoteNo(r.noteNo), r.adopted] as const)
  )
  const updated: StackExplodeExportBody['callouts'] = callouts.map((c) => {
    const no = normalizeNoteNo(c.calloutNo)
    const adopted = no ? byNo.get(no) : undefined
    if (!adopted) return { ...c }
    const material = (adopted.material || '').trim()
    const color = (adopted.color || '').trim()
    const th = parseAdoptedThickness(adopted.thickness)
    const thickness = th != null ? th : c.thickness
    return {
      ...c,
      material,
      color,
      thickness,
      line1: formatStackLine1(material, color, thickness),
      line2: c.line2 || c.specialReq || '',
    }
  })

  const bad = unlabeledCalloutLabels(updated)
  if (bad.length) {
    const preview = bad.slice(0, 6).join('、')
    const more = bad.length > 6 ? '…' : ''
    throw new Error(
      `还有 ${bad.length} 条层次旁注缺少序号/材料/颜色（${preview}${more}）。请回②为每层填写序号（命中左上 2D 可自动带材料）后重新保存层次`
    )
  }

  const prevId = id
  const saved = await saveEngStack({
    drawingNo: asset.drawingNo || props.drawingNo || 'stack-explode',
    title: asset.title || `${props.drawingNo || '层次'}-层次图`,
    plates,
    callouts: updated,
  })
  stackAsset.value = saved
  stackCallouts.value = calloutsFromStack(updated)
  emit('stackBound', saved.id)
  if (saved.id !== prevId) {
    message.info('材料已写回层次图（已保存为新版本）')
  }
}

function onMaterialAdoptedInput(
  row: MaterialWorkRow,
  field: 'material' | 'color' | 'thickness',
  ev: Event
) {
  const el = ev.target as HTMLInputElement | HTMLTextAreaElement
  row.adopted[field] = el.value
  markManual(row)
  materialsConfirmed.value = false
}

function adoptRow3d(row: MaterialWorkRow) {
  if (!applyAdopt3d(row)) {
    message.warning('该行无 3D 旁注，无法采用')
    return
  }
  materialsConfirmed.value = false
}

function adoptRow2d(row: MaterialWorkRow) {
  if (!applyAdopt2d(row)) {
    message.warning('该行无 2D 材料，无法采用')
    return
  }
  materialsConfirmed.value = false
}

function batchAdopt2d() {
  let n = 0
  for (const row of materialWorkRows.value) {
    if (applyAdopt2d(row)) n += 1
  }
  materialsConfirmed.value = false
  message.success(n ? `已对 ${n} 行采用 2D` : '没有可采用 2D 的行')
}

function batchAdopt3d() {
  let n = 0
  for (const row of materialWorkRows.value) {
    if (applyAdopt3d(row)) n += 1
  }
  materialsConfirmed.value = false
  message.success(n ? `已对 ${n} 行采用 3D` : '没有可采用 3D 的行')
}

function resetMaterialWork() {
  if (!materialsBaseline.value.length) {
    message.warning('无抽取基线可重置')
    return
  }
  editMaterials.value = cloneMaterials(materialsBaseline.value)
  materialWorkRows.value = buildMaterialWorkRows(stackCallouts.value, materialsBaseline.value)
  materialsConfirmed.value = false
  message.success('已重置为抽取结果')
}

function saveMaterialDraft() {
  if (!preview.value && !extractSkipped.value) {
    message.warning('请先完成图纸抽取，或跳过抽取')
    return
  }
  const pending = unresolvedWorkRows(materialWorkRows.value)
  if (pending.length) {
    message.warning(`仍有 ${pending.length} 行字段差待确认，草稿已按当前采用结果保存`)
  }
  syncEditMaterialsFromWork()
  void persistAnnot({ wizardStep: 3, materialsConfirmed: false })
  message.success('材料草稿已保存')
}

function dispositionLabel(d: string) {
  if (d === 'pending') return '待确认'
  if (d === 'adopt_3d') return '采用3D'
  if (d === 'adopt_2d') return '采用2D'
  if (d === 'manual') return '手工'
  return d
}

function ensureStepPersisted(action: string): boolean {
  if (canBindStack.value) return true
  message.warning(`请先将 STP 成功保存到任务后再${action}（否则重进会丢失三维）`)
  return false
}

const statusChip = computed(() => {
  if (props.stepScanning) return { tone: 'busy', text: '解析中' }
  if (props.solidReady) {
    const n = bodyHint.value ? `${bodyHint.value} 实体` : '已解析'
    return { tone: 'ok', text: n }
  }
  if (hasStepFile.value) return { tone: 'warn', text: '待解析' }
  return { tone: 'muted', text: '未选模型' }
})

const fileChip = computed(() => {
  const name = props.stepFileName
  if (!name) return ''
  return name.length > 28 ? `${name.slice(0, 12)}…${name.slice(-12)}` : name
})

const drawerTitle = computed(() => {
  if (step.value === 1) return '抽取并对图核对'
  if (step.value === 3) return '材料核对'
  if (step.value === 4) return '翻译再审'
  return '生成工程图'
})

async function refreshStacks() {
  try {
    recentStacks.value = await listEngStacks()
  } catch {
    recentStacks.value = []
  }
}

onMounted(() => {
  void refreshStacks()
  emit('stepChange', step.value)
  if (step.value === 2) emit('focusSolid')
  if (props.stepScanning) startBusy('scan')
  window.addEventListener('resize', scheduleLockExtractScroll)
  extractScrollRo = new ResizeObserver(() => scheduleLockExtractScroll())
  const wsBody = document.querySelector('.eng-ws-body')
  if (wsBody) extractScrollRo.observe(wsBody)
  const host = document.getElementById('eng-wizard-side')
  if (host) extractScrollRo.observe(host)
  scheduleLockExtractScroll()
  // 有 taskId：终稿回填交给 hydrate→restoreProgress，避免先推空总览
  if (props.taskId && step.value === 5) {
    composeBoardHold.value = true
  } else {
    void resumeComposeIfNeeded()
  }
  void resumeExtractIfNeeded()
})

onBeforeUnmount(() => {
  stopBusy()
  stopExtractElapsedTick()
  stopComposeElapsedTick()
  stopComposePoll()
  stopExtractPoll()
  window.removeEventListener('resize', scheduleLockExtractScroll)
  extractScrollRo?.disconnect()
  extractScrollRo = null
  if (stepSwitchTimer != null) {
    window.clearTimeout(stepSwitchTimer)
    stepSwitchTimer = null
  }
  composeEntering.value = false
  composeBoardHold.value = false
  clearStackPreviewCache()
})

watch(
  [step, preview, extracting, editNotes],
  () => {
    scheduleLockExtractScroll()
  },
  { flush: 'post' }
)

watch(
  () => props.stepScanning,
  (on) => {
    if (on) {
      startBusy('scan')
      return
    }
    if (busyKind.value === 'scan') stopBusy()
  }
)

watch(
  preview,
  (p) => {
    emit('extractPreview', p)
  },
  { immediate: true }
)

watch(extracting, (busy) => {
  emit('extractBusy', busy)
})

watch(extractTab, (tab) => {
  if (tab === 'note') autosizeAllNoteTextareas()
})

watch(translateTab, (tab) => {
  if (tab === 'note') autosizeAllNoteTextareas()
})

watch(step, (s) => {
  if (s === 1 && extractTab.value === 'note' && editNotes.value.length) {
    autosizeAllNoteTextareas()
  }
  if (s === 4) {
    translateTab.value = 'note'
    // 返回④保留逐条确认；仅「确认材料」推进时才会 reset（见 confirmMaterials）
    scrubMaterialZhEnCopies()
    autosizeAllNoteTextareas()
  }
  if (s === 5) {
    void resumeComposeIfNeeded()
  }
  if (s === 1) {
    void resumeExtractIfNeeded()
  }
})

async function loadStackCallouts(stackId: string) {
  const snapBefore = snapshotMaterialWork()
  try {
    const asset = await getEngStack(stackId)
    stackCallouts.value = calloutsFromStack(asset.job?.callouts)
  } catch {
    stackCallouts.value = []
    message.warning('层次旁注读取失败，材料核对仅显示 2D 侧')
  }
  refreshMaterialWork(false)
  // 重建对照后盖回采用/处置，避免返回③丢失
  if (snapBefore.length) applyMaterialWorkSnapshot(snapBefore)
  if (materialsConfirmed.value) settleConfirmedWorkRows()
}

function applyPreview(p: ConvertPreview) {
  preview.value = p
  editNotes.value = (p.notes || []).map((n) => ({ ...n }))
  editMaterials.value = (p.materials || []).map((m) => ({ ...m }))
  sortExtractNotesByNo({ persist: false })
  sortExtractMaterialsByNo({ persist: false })
  materialsBaseline.value = cloneMaterials(editMaterials.value)
  materialsConfirmed.value = false
  notesConfirmed.value = false
  emptyNotesAcked.value = false
  emptyMaterialsAcked.value = false
  resetNoteVerified(editNotes.value.length)
  resetMaterialVerified(editMaterials.value.length)
  resetNoteTranslateVerified(editNotes.value.length)
  resetMaterialReviewVerified(editMaterials.value.length)
  extractTab.value = 'note'
  translateTab.value = 'note'
  result.value = null
  refreshMaterialWork(true)
  autosizeAllNoteTextareas()
}

/** 截图/手工写入后离开「仅预览」态 */
function clearSheetReadyFlag() {
  const p = preview.value
  if (!p?.sheetReady) return
  preview.value = { ...p, sheetReady: false, message: p.message || '已开始核对 NOTE / 材料' }
}

/** 从落库快照回显：保留确认态与预览图，不强制再抽 */
function applyRestoredAnnot(a: TaskAnnotSnapshot) {
  const notes = (a.notes || []).map((n) => ({ ...n }))
  const materials = (a.materials || []).map((m) => ({ ...m }))
  preview.value = {
    fileName: a.fileName || dwgFile.value?.name || 'customer.dwg',
    drawingNo: a.drawingNo || '',
    message: a.message || '已恢复上次审校结果',
    warnings: a.warnings || [],
    notes,
    materials,
    sheetLayout: a.sheetLayout || undefined,
    previewPngBase64: a.previewPngBase64 || undefined,
    previewSvg: a.previewSvg || undefined,
    previewSvgs: a.previewSvgs?.length ? [...a.previewSvgs] : undefined,
    materialCrops: a.materialCrops?.length ? [...a.materialCrops] : undefined,
    sheetReady: !!a.sheetReady && notes.length === 0 && materials.length === 0,
  }
  editNotes.value = notes
  editMaterials.value = materials
  scrubMaterialZhEnCopies()
  // 真 2D 基线与采用结果分离；旧快照无 materialsBaseline 时只能用 materials（可能已被采用结果污染）
  materialsBaseline.value = cloneMaterials(
    a.materialsBaseline?.length ? a.materialsBaseline : materials
  )
  materialsConfirmed.value = !!a.materialsConfirmed
  notesConfirmed.value = !!a.notesConfirmed
  // 旧快照无逐条字段：已越过①则①全确认；notesConfirmed 则④全确认
  const stepHint = a.wizardStep != null ? Number(a.wizardStep) : 0
  const extractDone = stepHint >= 2 || !!a.materialsConfirmed || !!a.notesConfirmed
  const translateDone = !!a.notesConfirmed
  noteVerified.value = alignVerifiedFlags(
    a.noteVerified,
    notes.length,
    extractDone && !a.noteVerified?.length
  )
  materialVerified.value = alignVerifiedFlags(
    a.materialVerified,
    materials.length,
    extractDone && !a.materialVerified?.length
  )
  noteTranslateVerified.value = alignVerifiedFlags(
    a.noteTranslateVerified,
    notes.length,
    translateDone && !a.noteTranslateVerified?.length
  )
  materialReviewVerified.value = alignVerifiedFlags(
    a.materialReviewVerified,
    materials.length,
    translateDone && !a.materialReviewVerified?.length
  )
  scrubMaterialReviewVerifiedForUntranslated()
  sortExtractNotesByNo({ persist: false })
  sortExtractMaterialsByNo({ persist: false })
  // 空表确认：有显式字段用字段；旧快照已越过①则视为已确认空表
  emptyNotesAcked.value =
    notes.length === 0 ? !!a.emptyNotesAcked || extractDone : false
  emptyMaterialsAcked.value =
    materials.length === 0 ? !!a.emptyMaterialsAcked || extractDone : false
  extractTab.value = 'note'
  translateTab.value = 'note'
  result.value = null
  // hydrate 晚于 onMounted 的 resume；清终稿后允许再拉 compose-result
  composeResumeTried = false
  refreshMaterialWork(true)
  applyMaterialWorkSnapshot(a.materialWorkSnapshot)
  if (materialsConfirmed.value) settleConfirmedWorkRows()
  autosizeAllNoteTextareas()
}

const annotPersistFailed = ref(false)

/** 将当前审校态写入任务目录（非阻断）；预览图由后端落独立文件。跳过①无 preview 时仍须落盘确认态。 */
async function persistAnnot(extra?: Partial<TaskAnnotSnapshot>) {
  if (!props.taskId) return
  if (!preview.value && !extractSkipped.value) return
  const p = preview.value
  const body: TaskAnnotSnapshot = {
    version: WIZARD_FLOW_VERSION,
    fileName: p?.fileName || dwgFile.value?.name || '',
    drawingNo:
      p?.drawingNo || props.drawingNo || stackAsset.value?.drawingNo || '',
    message: p?.message || '',
    warnings: p?.warnings || [],
    notes: editNotes.value,
    materials: editMaterials.value,
    materialsBaseline: materialsBaseline.value.map((m) => ({ ...m })),
    sheetLayout: (p?.sheetLayout as Record<string, unknown>) ?? null,
    materialsConfirmed: materialsConfirmed.value,
    notesConfirmed: notesConfirmed.value,
    noteVerified: noteVerified.value.slice(),
    materialVerified: materialVerified.value.slice(),
    noteTranslateVerified: noteTranslateVerified.value.slice(),
    materialReviewVerified: materialReviewVerified.value.slice(),
    emptyNotesAcked: emptyNotesAcked.value,
    emptyMaterialsAcked: emptyMaterialsAcked.value,
    materialWorkSnapshot: snapshotMaterialWork(),
    wizardStep: step.value,
    previewPngBase64: p?.previewPngBase64 || undefined,
    previewSvg: p?.previewSvg || undefined,
    previewSvgs: p?.previewSvgs?.length ? p?.previewSvgs : undefined,
    materialCrops: p?.materialCrops?.length ? p?.materialCrops : undefined,
    sheetReady: !!p?.sheetReady,
    ...extra,
  }
  try {
    await taskApi.saveAnnot(props.taskId, body)
    annotPersistFailed.value = false
  } catch (e) {
    annotPersistFailed.value = true
    message.error(e instanceof Error ? e.message : '审校快照保存失败，请稍后重试')
  }
}

async function retryPersistAnnot() {
  await persistAnnot()
  if (!annotPersistFailed.value) {
    message.success('审校快照已保存')
  }
}

async function runExtractJob(file: File, mode: ExtractJobMode) {
  extracting.value = true
  extractJobMode.value = mode
  clearExtractStartAt()
  writeExtractStartAt(Date.now())
  beginExtractTimer()
  startBusy(mode === 'previewOnly' ? 'sheet' : 'extract')
  extractLogs.value = []
  extractJobStage.value = mode === 'previewOnly' ? '提交图纸解析…' : '提交抽取任务…'
  extractJobPercent.value = 5
  try {
    if (props.taskId != null && props.taskId !== '') {
      const job = await startExtractAsync(file, props.taskId, mode)
      applyExtractJobView(job)
      beginExtractTimer()
      const terminal = await handleExtractTerminal(job)
      if (terminal === 'running') {
        startExtractPolling()
      }
      return
    }
    const p = await convertPreview(file, props.taskId)
    await finishExtractSuccess(p)
    endExtractUi(true)
  } catch (e) {
    if (!(mode === 'extract' && preview.value?.previewPngBase64)) {
      preview.value = null
    }
    endExtractUi(false)
    message.error(
      e instanceof Error
        ? e.message
        : mode === 'previewOnly'
          ? '图纸预览失败'
          : '图纸抽取失败'
    )
  }
}

/** 上传后：仅解析高清预览 */
async function runSheetPreview(file: File) {
  await runExtractJob(file, 'previewOnly')
}

/** 用户点「自动抽取」：全量 NOTE/材料 */
async function runExtract(file: File) {
  await runExtractJob(file, 'extract')
}

function applyExtractJobView(job: EngExtractJobView) {
  extractJobStage.value = humanizeProcessLog(job.stage || '')
  extractJobPercent.value = Number(job.percent || 0)
  extractLogs.value = Array.isArray(job.logs) ? humanizeProcessLogs(job.logs) : []
  if (job.mode === 'previewOnly' || job.mode === 'extract') {
    extractJobMode.value = job.mode
  }
  syncExtractElapsedFromJob(job)
}

function beginExtractUiFromJob(job: EngExtractJobView) {
  extracting.value = true
  const mode: ExtractJobMode = job.mode === 'previewOnly' ? 'previewOnly' : 'extract'
  extractJobMode.value = mode
  startBusy(mode === 'previewOnly' ? 'sheet' : 'extract')
  if (job.startedAt && Number(job.startedAt) > 0) {
    writeExtractStartAt(Number(job.startedAt))
  } else if (!readExtractStartAt()) {
    writeExtractStartAt(Date.now())
  }
  applyExtractJobView(job)
  beginExtractTimer()
}

async function finishSheetPreviewSuccess(p: ConvertPreview) {
  applyPreview({ ...p, sheetReady: true })
  message.success(p.message || '高清预览已就绪，请优先用截图抽取，或改用自动抽取')
  step.value = 1
  emit('stepChange', 1)
  await persistAnnot({
    wizardStep: 1,
    materialsConfirmed: false,
    notesConfirmed: false,
    sheetReady: true,
  })
}

async function finishExtractSuccess(p: ConvertPreview) {
  applyPreview({ ...p, sheetReady: false })
  if (stackAsset.value?.id) {
    await loadStackCallouts(stackAsset.value.id)
  }
  message.success(
    p.message || `已抽取 NOTE ${p.notes?.length || 0} 条、材料 ${p.materials?.length || 0} 行`
  )
  step.value = 1
  emit('stepChange', 1)
  await persistAnnot({
    wizardStep: 1,
    materialsConfirmed: false,
    notesConfirmed: false,
    sheetReady: false,
  })
}

function endExtractUi(ok: boolean) {
  extracting.value = false
  extractCanceling.value = false
  stopExtractElapsedTick()
  stopExtractPoll()
  if (ok) clearExtractStartAt()
  stopBusy()
  extractJobStage.value = ''
  extractJobPercent.value = 0
  if (ok) extractJobMode.value = null
}

/** ① 手动停止解析/抽取 */
async function stopExtractJob() {
  const taskId = props.taskId
  if (taskId == null || taskId === '' || !extracting.value || extractCanceling.value) return
  extractCanceling.value = true
  try {
    const job = await cancelExtract(taskId)
    const terminal = await handleExtractTerminal(job)
    if (terminal === 'running') {
      // 已请求取消，继续轮询直至 CANCELLED
      startExtractPolling()
    }
  } catch (e) {
    message.error(e instanceof Error ? e.message : '停止失败，请重试')
    extractCanceling.value = false
  }
}

function stopExtractPoll() {
  if (extractPollTimer != null) {
    window.clearInterval(extractPollTimer)
    extractPollTimer = null
  }
}

function startExtractPolling() {
  stopExtractPoll()
  const taskId = props.taskId
  if (taskId == null || taskId === '') return
  extractPollTimer = window.setInterval(() => {
    void pollExtractOnce()
  }, 2000)
}

async function pollExtractOnce() {
  const taskId = props.taskId
  if (taskId == null || taskId === '') return
  try {
    const job = await getExtractStatus(taskId)
    const terminal = await handleExtractTerminal(job)
    if (terminal !== 'running') {
      stopExtractPoll()
    }
  } catch {
    // 轮询失败不打断
  }
}

async function handleExtractTerminal(job: EngExtractJobView): Promise<'success' | 'failed' | 'running'> {
  applyExtractJobView(job)
  const st = String(job.status || '').toUpperCase()
  if (st === 'RUNNING' || st === 'QUEUED') {
    return 'running'
  }
  if (st === 'SUCCESS') {
    if (!job.result) {
      endExtractUi(false)
      message.error(
        extractJobMode.value === 'previewOnly'
          ? '预览已完成但结果丢失，请重新渲图'
          : '抽取已完成但结果丢失，请重新解析'
      )
      return 'failed'
    }
    try {
      const sheetOnly = job.mode === 'previewOnly' || job.result.sheetReady === true
      if (sheetOnly) {
        await finishSheetPreviewSuccess(job.result)
      } else {
        await finishExtractSuccess(job.result)
      }
      endExtractUi(true)
      return 'success'
    } catch (e) {
      endExtractUi(false)
      message.error(e instanceof Error ? e.message : '结果处理失败')
      return 'failed'
    }
  }
  if (st === 'FAILED') {
    endExtractUi(false)
    message.error(
      job.message ||
        (extractJobMode.value === 'previewOnly' ? '图纸预览失败' : '图纸抽取失败')
    )
    return 'failed'
  }
  if (st === 'CANCELLED') {
    endExtractUi(false)
    clearExtractStartAt()
    message.info(job.message || '已停止')
    return 'failed'
  }
  return 'running'
}

async function resumeExtractIfNeeded() {
  if (extractResumeTried && extracting.value) return
  const taskId = props.taskId
  if (taskId == null || taskId === '') return
  if (step.value !== 1) return
  extractResumeTried = true
  try {
    const job = await getExtractStatus(taskId)
    const st = String(job.status || '').toUpperCase()
    if (st === 'RUNNING' || st === 'QUEUED') {
      beginExtractUiFromJob(job)
      startExtractPolling()
      return
    }
    if (st === 'SUCCESS' && job.result && !preview.value) {
      beginExtractUiFromJob(job)
      await handleExtractTerminal(job)
    }
  } catch {
    // 忽略
  }
}

/** 从任务详情恢复层次 / DWG / 审校快照 / 步骤 */
async function restoreProgress(payload: {
  step?: number
  stack?: EngStackAssetSummary | null
  dwgFile?: File | null
  annot?: TaskAnnotSnapshot | null
}) {
  if (props.stackSkipped) {
    stackSkipped.value = true
  }
  if (props.extractSkipped) {
    extractSkipped.value = true
  }
  if (payload.stack) {
    stackAsset.value = payload.stack
    stackSkipped.value = false
  }
  if (payload.dwgFile) {
    dwgFile.value = payload.dwgFile
  }
  markReached(payload.step)
  const annot = payload.annot
  const flowOpts = {
    flowVersion: annot?.version,
    hasStack: !!stackAsset.value || stackSkipped.value,
    hasDwg: !!dwgFile.value,
  }
  let target = migrateLegacyStep(payload.step, flowOpts)

  const hasAnnot =
    !!annot &&
    ((Array.isArray(annot.notes) && annot.notes.length > 0) ||
      (Array.isArray(annot.materials) && annot.materials.length > 0) ||
      !!annot.previewPngBase64 ||
      !!annot.sheetReady)

  // 门禁：②起建议有抽取结果；无 DWG 且未跳过①时不能越过①；无层次且未跳过则不能越过②
  if (target >= 2 && !hasAnnot && !preview.value && !dwgFile.value && !extractSkipped.value) {
    target = 1
  } else if (target >= 3 && !stackAsset.value && !stackSkipped.value) {
    target =
      hasAnnot || preview.value || dwgFile.value || extractSkipped.value
        ? preview.value || hasAnnot || extractSkipped.value
          ? 2
          : 1
        : 1
  }

  // 有审校快照：直接回显，跳过 convert/preview
  if (hasAnnot && dwgFile.value) {
    clearExtractStartAt()
    applyRestoredAnnot(annot!)
    if (annot!.notesConfirmed) markReached(5)
    else if (annot!.materialsConfirmed) markReached(4)
    if (annot!.wizardStep != null) {
      target = migrateLegacyStep(annot!.wizardStep, flowOpts)
      markReached(target)
    }
    // 未达过后续步时按确认态钳制；已完成/已达⑤则允许停在原步
    if (reachedStep.value < 5) {
      if (!stackAsset.value && !stackSkipped.value && target > 2) target = 2
      if (!materialsConfirmed.value && target > 3) target = 3
      if (materialsConfirmed.value && !notesConfirmed.value && target > 4) target = 4
    } else if (!stackAsset.value && !stackSkipped.value && target > 2) {
      target = 2
    }
    if (target < 1) target = 1
    // 进⑤：先 hold 产物板，拉齐终稿/层次预览后再放开，避免闪总览
    if (target === 5) composeBoardHold.value = true
    step.value = target as WizardStep
    markReached(target)
    emit('stepChange', target)
    if (target === 2) emit('focusSolid')
    if (stackAsset.value?.id && target >= 3) {
      void loadStackCallouts(stackAsset.value.id)
    }
    message.success('已恢复上次审校结果')
    if (target === 5) {
      try {
        await Promise.all([ensureStackPreviewCached(), resumeComposeIfNeeded()])
      } finally {
        composeBoardHold.value = false
      }
    } else {
      // 放开 onMounted 可能设下的 hold
      composeBoardHold.value = false
    }
    return
  }

  // 有 DWG 但无预览：自动再解析高清图（不立刻全量抽取）
  if (target >= 1 && dwgFile.value && !preview.value) {
    composeBoardHold.value = false
    void runSheetPreview(dwgFile.value)
    return
  }
  if (target === 5) composeBoardHold.value = true
  step.value = target
  markReached(target)
  emit('stepChange', target)
  if (target === 2) emit('focusSolid')
  if (stackAsset.value?.id && target >= 3) {
    void loadStackCallouts(stackAsset.value.id)
  }
  if (target === 5) {
    try {
      await Promise.all([ensureStackPreviewCached(), resumeComposeIfNeeded()])
    } finally {
      composeBoardHold.value = false
    }
  } else {
    composeBoardHold.value = false
  }
}

const STEP_SWITCH_LABELS: Record<WizardStep, string> = {
  1: '①抽取并对图核对',
  2: '②三维层次',
  3: '③材料核对',
  4: '④翻译审校',
  5: '⑤生成工程图',
}

/** 切步短提示：避免布局闪空被当成故障 */
const stepSwitching = ref(false)
const stepSwitchTip = ref('')
let stepSwitchTimer: number | null = null

/** 内容未就绪：压制顶栏勾选/「已跳过」与⑤摘要「已确认」，避免加载中误判完成 */
const stageContentPending = computed(
  () =>
    !!props.hydrating ||
    composeEntering.value ||
    composeBoardHold.value ||
    stepSwitching.value
)

function beginStepSwitchTip(n: WizardStep) {
  if (props.hydrating) return
  if (stepSwitchTimer != null) {
    window.clearTimeout(stepSwitchTimer)
    stepSwitchTimer = null
  }
  stepSwitchTip.value = `正在切换到${STEP_SWITCH_LABELS[n]}…`
  stepSwitching.value = true
  stepSwitchTimer = window.setTimeout(() => {
    stepSwitching.value = false
    stepSwitchTip.value = ''
    stepSwitchTimer = null
  }, 420)
}

function beginComposeWaitTip() {
  if (props.hydrating) return
  if (stepSwitchTimer != null) {
    window.clearTimeout(stepSwitchTimer)
    stepSwitchTimer = null
  }
  stepSwitchTip.value = '正在整理前序产物预览，请稍候…'
  stepSwitching.value = true
}

function endComposeWaitTip() {
  if (stepSwitchTimer != null) {
    window.clearTimeout(stepSwitchTimer)
    stepSwitchTimer = null
  }
  stepSwitching.value = false
  stepSwitchTip.value = ''
}

/** 进⑤前预拉层次 PNG；超时/失败不阻断进入 */
async function ensureStackPreviewCached() {
  const id = stackAsset.value?.id || ''
  if (!id) {
    clearStackPreviewCache()
    return
  }
  if (stackPreviewCache.value?.id === id) return
  try {
    const r = await Promise.race([
      getEngStackPreview(id),
      new Promise<never>((_, reject) => {
        window.setTimeout(() => reject(new Error('timeout')), STACK_PREVIEW_WAIT_MS)
      }),
    ])
    stackPreviewCache.value = { id, png: r?.previewPngBase64 || '' }
  } catch {
    // 超时/失败：不写空成功缓存，进⑤后由产物板再拉并显示加载态
    clearStackPreviewCache()
  }
}

/** 预拉完成后再切⑤，避免总览闪「无预览图」；有终稿则先拉齐再推产物板 */
async function enterComposeStep() {
  if (composeEntering.value) return
  if (step.value === 5) return
  const id = stackAsset.value?.id || ''
  const cached = !!(id && stackPreviewCache.value?.id === id)
  composeEntering.value = true
  composeBoardHold.value = true
  if (!cached) beginComposeWaitTip()
  try {
    await ensureStackPreviewCached()
    if (step.value === 5) return
    step.value = 5
    markReached(5)
    emit('stepChange', 5)
    void persistAnnot({ wizardStep: 5 })
    // 已有 SUCCESS 终稿时先回填，再放开产物板 → 直接终稿页
    await resumeComposeIfNeeded()
  } finally {
    composeEntering.value = false
    composeBoardHold.value = false
    endComposeWaitTip()
  }
}

function goStep(n: WizardStep) {
  // 回退到更早步骤：不设前进门禁（避免③→② 等因缺少内存 DWG/预览而静默无反应）
  const goingBack = n < step.value
  const fromStep = step.value
  flushMaterialWorkIfNeeded(fromStep, n)
  if (goingBack) {
    if (n !== step.value) beginStepSwitchTip(n)
    if (n === 3) {
      refreshMaterialWork(false)
      // 同会话已有工作行；若刚恢复可再盖快照（persist 里已有则无需）
    }
    step.value = n
    emit('stepChange', n)
    if (n === 2) emit('focusSolid')
    void persistAnnot({ wizardStep: n })
    return
  }

  const visitingReached = n <= reachedStep.value
  if (n >= 2 && step.value === 1 && !visitingReached && !extractSkipped.value) {
    if (!allNotesVerified.value) {
      message.warning(
        `请先确认全部 NOTE（已确认 ${notesVerifiedCount.value}/${editNotes.value.length}）`
      )
      extractTab.value = 'note'
      return
    }
    if (!allMaterialsVerified.value) {
      message.warning(
        `请先确认全部材料（已确认 ${materialsVerifiedCount.value}/${editMaterials.value.length}）`
      )
      extractTab.value = 'material'
      return
    }
    freezeMaterialsBaseline()
  }
  if (visitingReached) {
    if (n === 2 && !canVisitStep2.value) {
      message.warning(step2BlockTip.value || '请先完成①抽取并对图核对')
      return
    }
    if (n === 3 && !canVisitStep3.value) {
      message.warning(step3BlockTip.value || '请先在②保存三维层次，或跳过层次')
      return
    }
    if (n === 4 && !canVisitStep4.value) {
      message.warning(step4BlockTip.value || '请先在③确认材料核对')
      return
    }
    if (n === 5 && !canVisitStep5.value) {
      message.warning(step5BlockTip.value || '请先在④确认译文与材料审校')
      return
    }
  } else {
    if (n === 2 && !canStep2.value) {
      message.warning(step2BlockTip.value || '请先完成①抽取并对图核对')
      return
    }
    if (n === 3 && !canStep3.value) {
      message.warning(step3BlockTip.value || '请先在②保存三维层次，或跳过层次')
      return
    }
    if (n === 4 && !canStep4.value) {
      message.warning(step4BlockTip.value || '请先在③确认材料核对')
      return
    }
    if (n === 5 && !canStep5.value) {
      message.warning(step5BlockTip.value || '请先在④确认译文与材料审校')
      return
    }
  }
  // 进⑤：先预拉层次预览，再切步
  if (n === 5 && step.value !== 5) {
    void enterComposeStep()
    return
  }
  if (n !== step.value) beginStepSwitchTip(n)
  if (n === 3) refreshMaterialWork(false)
  if (n === 4) scrubMaterialZhEnCopies()
  step.value = n
  markReached(n)
  emit('stepChange', n)
  if (n === 2) emit('focusSolid')
  // 步骤切换时落盘确认态 + wizardStep，避免重进丢失
  void persistAnnot({ wizardStep: n })
}

/** 非嵌入浮层：点遮罩回②层次；嵌入分栏无遮罩 */
function onDrawerMaskClick() {
  if (props.embedded || extracting.value || composing.value) return
  goStep(2)
}

function pickRecent(row: EngStackAssetSummary) {
  if (!ensureStepPersisted('选用层次')) return
  clearStackPreviewCache()
  stackAsset.value = row
  stackSkipped.value = false
  emit('stackSkipChange', false)
  // 已有抽取则进材料核对；否则留在层次步
  const next: WizardStep = preview.value && dwgFile.value ? 3 : 2
  step.value = next
  emit('stepChange', next)
  emit('stackBound', row.id)
  if (next === 3) refreshMaterialWork(false)
  message.success(`已选用：${row.title}`)
}

function acceptStepFile(file: File) {
  if (!/\.(stp|step)$/i.test(file.name)) {
    message.error('请上传 .stp / .step')
    return
  }
  emit('pickStep', file)
  emit('focusSolid')
}

function beforeStep(file: File) {
  acceptStepFile(file)
  return false
}

function runParse() {
  if (!hasStepFile.value) {
    message.warning('请先选择 STP')
    return
  }
  emit('needParse')
  emit('focusSolid')
}

function goEditStack() {
  if (!props.solidReady) {
    message.warning(hasStepFile.value ? '请先解析三维' : '请先选择 STP')
    return
  }
  emit('openStackEditor')
}

/** 无 3D 图纸：跳过层次，3D 材料为空，可继续③～⑤（合成不贴层次）；可与跳过①并存，⑤仍须客户 DWG */
function skipStackStep() {
  if (taskCompleted.value) {
    message.info('任务已完成，请先编辑后再改步骤')
    return
  }
  if (!extractSkipped.value && (!preview.value || !dwgFile.value)) {
    message.warning('请先完成①抽取')
    return
  }
  if (!extractSkipped.value && !extractFullyVerified.value && step.value === 1) {
    message.warning('请先在①逐条确认全部 NOTE 与材料')
    return
  }
  // 「至少保留一个」：跳过层次时须已有 DWG / STP / 层次（避免空任务双跳过）
  if (!hasDwgSource.value && !hasStepSource.value && !stackAsset.value) {
    message.warning('请先上传客户 DWG 或三维 STP（二维与三维至少保留一个）')
    return
  }
  Modal.confirm({
    title: '跳过三维层次？',
    content: hasDwgSource.value
      ? '确认后将不保存三维层次，材料核对中 3D 列为空（默认采用 2D），生成的工程图不含爆炸层次。之后仍可回②导入 STP 或选用最近层次。'
      : '确认后将不保存三维层次。当前尚无客户 DWG，材料 2D/3D 列均为空；⑤生成前须补传客户 DWG。之后仍可回①上传图纸或回②导入 STP。',
    okText: '确认跳过',
    cancelText: '取消',
    centered: true,
    onOk: async () => {
      stackSkipped.value = true
      stackAsset.value = null
      stackCallouts.value = []
      clearStackPreviewCache()
      if (props.taskId != null && props.taskId !== '') {
        try {
          await taskApi.update(props.taskId, {
            stackSkipped: true,
            currentStep: 3,
            status: 'IN_PROGRESS'
          })
        } catch (e) {
          stackSkipped.value = false
          message.error(e instanceof Error ? e.message : '跳过失败，请稍后重试')
          return
        }
      }
      emit('stackSkipChange', true)
      refreshMaterialWork(false)
      step.value = 3
      markReached(3)
      emit('stepChange', 3)
      void persistAnnot({ wizardStep: 3 })
      message.success('已跳过层次，请核对材料（3D 为空）')
    }
  })
}

/** 跳过①抽取核对：NOTE/材料为空进②；无需预先上传（可到②再传 STP；⑤仍须 DWG） */
function skipExtractStep() {
  if (taskCompleted.value) {
    message.info('任务已完成，请先编辑后再改步骤')
    return
  }
  Modal.confirm({
    title: '跳过抽取核对？',
    content:
      '确认后将不核对 NOTE/材料，直接进入②三维层次。可在②导入 STP 或跳过层次；⑤生成前仍须上传客户 DWG。之后仍可回①上传图纸并抽取。',
    okText: '确认跳过',
    cancelText: '取消',
    centered: true,
    onOk: async () => {
      extractSkipped.value = true
      // 空表确认态置齐，等价放行后续门禁
      emptyNotesAcked.value = true
      emptyMaterialsAcked.value = true
      if (props.taskId != null && props.taskId !== '') {
        try {
          await taskApi.update(props.taskId, {
            extractSkipped: true,
            currentStep: 2,
            status: 'IN_PROGRESS'
          })
        } catch (e) {
          extractSkipped.value = false
          message.error(e instanceof Error ? e.message : '跳过失败，请稍后重试')
          return
        }
      }
      emit('extractSkipChange', true)
      step.value = 2
      markReached(2)
      emit('stepChange', 2)
      emit('focusSolid')
      void persistAnnot({ wizardStep: 2 })
      message.success('已跳过抽取；请在②导入 STP，或跳过层次（须已有 DWG/STP）')
    }
  })
}

async function onStackSaved(payload: {
  drawingNo: string
  plates: StackExplodeExportBody['plates']
  callouts: StackExplodeExportBody['callouts']
  previewPngBase64: string
}): Promise<boolean> {
  if (!ensureStepPersisted('保存层次')) return false
  saving.value = true
  startBusy('save')
  try {
    const saved = await saveEngStack({
      drawingNo: payload.drawingNo || props.drawingNo,
      title: `${payload.drawingNo || props.drawingNo || '层次'}-层次图`,
      plates: payload.plates,
      callouts: payload.callouts,
      previewPngBase64: payload.previewPngBase64,
    })
    stackAsset.value = saved
    stackSkipped.value = false
    emit('stackSkipChange', false)
    clearStackPreviewCache()
    stackCallouts.value = calloutsFromStack(payload.callouts)
    await refreshStacks()
    step.value = 3
    emit('stepChange', 3)
    emit('stackBound', saved.id)
    refreshMaterialWork(false)
    message.success('层次已保存')
    return true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '层次保存失败')
    return false
  } finally {
    saving.value = false
    stopBusy()
  }
}

defineExpose({
  onStackSaved,
  goStep,
  getStackAssetId: () => stackAsset.value?.id || '',
  /** 2D 已确认材料，供层次标注按序号预填 */
  getMaterialSeeds: () =>
    editMaterials.value.map((m) => ({
      noteNo: String(m.noteNo || m.seq || '').trim(),
      material: (m.materialEn || '').trim(),
      color: (m.color || '').trim(),
      thickness: (m.thickness || '').trim(),
    })),
  restoreProgress,
  applyRegionCapture,
})

function acceptDwg(file: File) {
  if (!/\.dwg$/i.test(file.name)) {
    message.error('仅支持客户 DWG')
    return
  }
  dwgFile.value = file
  result.value = null
  preview.value = null
  materialsConfirmed.value = false
  notesConfirmed.value = false
  step.value = 1
  emit('stepChange', 1)
  emit('dwgPicked', file)
  void runSheetPreview(file)
}

function beforeDwg(file: File) {
  acceptDwg(file)
  return false
}

/** 重新渲图：仅高清预览 */
function rePreviewCurrentDwg() {
  if (!dwgFile.value) {
    message.warning('请先选择客户 DWG')
    return
  }
  if (extracting.value) return
  void runSheetPreview(dwgFile.value)
}

/** 用已有 DWG 全量再抽一次（覆盖当前 NOTE/材料） */
function reExtractCurrentDwg() {
  if (!dwgFile.value) {
    message.warning('请先选择客户 DWG')
    return
  }
  if (extracting.value) return
  void runExtract(dwgFile.value)
}

function onDwgDragOver(e: DragEvent) {
  e.preventDefault()
  dwgDragging.value = true
}

function onDwgDragLeave() {
  dwgDragging.value = false
}

function onDwgDrop(e: DragEvent) {
  dwgDragging.value = false
  const file = e.dataTransfer?.files?.[0]
  if (file) acceptDwg(file)
}

async function confirmMaterials() {
  if (saving.value) return
  if (!preview.value && !extractSkipped.value) {
    message.warning('请先完成图纸抽取，或跳过抽取')
    return
  }
  if (!materialWorkRows.value.length) {
    message.warning('暂无可确认材料，请检查 2D 材料表或 3D 旁注')
    return
  }
  const pending = unresolvedWorkRows(materialWorkRows.value)
  if (pending.length) {
    const nos = pending
      .slice(0, 8)
      .map((r) => r.noteNo)
      .join('、')
    const more = pending.length > 8 ? '…' : ''
    Modal.warning({
      title: '还有字段差未处置',
      content: `序号 ${nos}${more} 共 ${pending.length} 行仍为「待确认」。请先选择采用 3D / 采用 2D，或手工修改采用结果后再确认。`,
      okText: '知道了'
    })
    materialFilter.value = 'field_diff'
    await nextTick()
    document
      .querySelector('.eng-mat-table tr[data-pending="1"]')
      ?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    return
  }
  if (!stackAsset.value?.id && !stackSkipped.value) {
    message.warning('请先保存层次后再确认材料（需回写层次 DXF），或在②跳过层次')
    return
  }
  syncEditMaterialsFromWork()
  if (stackSkipped.value || !stackAsset.value?.id) {
    materialsConfirmed.value = true
    notesConfirmed.value = false
    resetNoteTranslateVerified(editNotes.value.length)
    resetMaterialReviewVerified(editMaterials.value.length)
    translateTab.value = 'note'
    message.success('材料核对已确认（已跳过三维层次，未回写层次图）')
    step.value = 4
    emit('stepChange', 4)
    void persistAnnot({ wizardStep: 4, materialsConfirmed: true, notesConfirmed: false })
    return
  }
  saving.value = true
  startBusy('material')
  try {
    await rewriteStackFromAdopted()
  } catch (e) {
    message.error(e instanceof Error ? e.message : '材料回写层次失败')
    return
  } finally {
    saving.value = false
    stopBusy()
  }
  materialsConfirmed.value = true
  notesConfirmed.value = false
  resetNoteTranslateVerified(editNotes.value.length)
  resetMaterialReviewVerified(editMaterials.value.length)
  translateTab.value = 'note'
  message.success(
    compareHasConflict.value
      ? '已确认材料并回写层次图（含对照差异，已按采用结果落表）'
      : '材料核对已确认，层次绿表已按采用结果更新'
  )
  step.value = 4
  emit('stepChange', 4)
  void persistAnnot({ wizardStep: 4, materialsConfirmed: true, notesConfirmed: false })
}

/** 滚动到翻译审校中第一条未确认/未译行 */
async function scrollFirstUnverified(
  kind: 'note' | 'material' | 'note-untranslated' | 'material-untranslated'
) {
  await nextTick()
  const sel =
    kind === 'material'
      ? '.eng-trans-mat-table tr[data-review-pending="1"]'
      : kind === 'material-untranslated'
        ? '.eng-trans-mat-table tr[data-untranslated="1"]'
        : kind === 'note-untranslated'
          ? '.eng-trans-table:not(.eng-trans-mat-table) tr[data-untranslated="1"]'
          : '.eng-trans-table:not(.eng-trans-mat-table) tr[data-review-pending="1"]'
  document.querySelector(sel)?.scrollIntoView({ block: 'center', behavior: 'smooth' })
}

function confirmNotes() {
  if (!materialsConfirmed.value) {
    message.warning('请先完成材料核对')
    return
  }
  if (!allNotesTranslateVerified.value) {
    message.warning(
      `请先逐条确认全部 NOTE（已确认 ${notesTranslateVerifiedCount.value}/${editNotes.value.length}）`
    )
    translateTab.value = 'note'
    void scrollFirstUnverified('note')
    return
  }
  if (!allMaterialsReviewVerified.value) {
    message.warning(
      `请先逐条确认全部材料（已确认 ${materialsReviewVerifiedCount.value}/${editMaterials.value.length}）`
    )
    translateTab.value = 'material'
    void scrollFirstUnverified('material')
    return
  }
  const matUntranslated = editMaterials.value.filter((m) => materialNeedsTranslate(m)).length
  if (matUntranslated > 0) {
    message.warning(`仍有 ${matUntranslated} 行材料未译，请补全中文名后再确认`)
    translateTab.value = 'material'
    void scrollFirstUnverified('material-untranslated')
    return
  }
  const noteUntranslated = editNotes.value.filter((n) => n.status === 'untranslated').length
  if (noteUntranslated > 0) {
    message.warning(`仍有 ${noteUntranslated} 条 NOTE 未译，请补全译文后再确认`)
    translateTab.value = 'note'
    void scrollFirstUnverified('note-untranslated')
    return
  }
  // 用户改过的译文标为 manual
  editNotes.value = editNotes.value.map((n) => {
    const orig = preview.value?.notes?.find(
      (x) => x.noteNo === n.noteNo && x.original === n.original
    )
    if (orig && n.translated !== orig.translated && n.status !== 'manual') {
      return { ...n, status: 'manual' }
    }
    return n
  })
  notesConfirmed.value = true
  message.success('译文与材料已确认')
  void goStep(5)
}

function onNoteTranslatedInput(idx: number, ev: Event) {
  const el = ev.target as HTMLTextAreaElement
  const v = el.value
  const row = editNotes.value[idx]
  if (!row) return
  row.translated = v
  row.status = 'manual'
  notesConfirmed.value = false
  if (noteTranslateVerified.value[idx]) {
    const next = noteTranslateVerified.value.slice()
    next[idx] = false
    noteTranslateVerified.value = next
  }
  autosizeNoteTextarea(el)
}

async function retranNote(idx: number) {
  const row = editNotes.value[idx]
  if (!row) return
  const text = (row.original || '').trim()
  if (!text) {
    message.warning('原文为空，无法翻译')
    return
  }
  if (batchTranslateBusy.value || retranNoteIdx.value >= 0) return
  const first = !noteHasZh(row)
  retranNoteIdx.value = idx
  try {
    const res = await convertTranslate(text, 'note')
    row.translated = res.translated
    row.status = res.status || 'llm'
    notesConfirmed.value = false
    if (noteTranslateVerified.value[idx]) {
      const next = noteTranslateVerified.value.slice()
      next[idx] = false
      noteTranslateVerified.value = next
    }
    message.success(first ? '已翻译，请核对后确认' : '已重译，请核对后确认')
    void nextTick(() => autosizeAllNoteTextareas())
    void persistAnnot()
  } catch {
    // 拦截器已 toast
  } finally {
    retranNoteIdx.value = -1
  }
}

async function retranMaterial(idx: number) {
  const row = editMaterials.value[idx]
  if (!row) return
  const text = (row.materialEn || '').trim()
  if (!text) {
    message.warning('英文/原文为空，无法翻译')
    return
  }
  if (batchTranslateBusy.value || retranMatIdx.value >= 0) return
  const first = !materialHasZh(row)
  retranMatIdx.value = idx
  // 开译即取消确认，避免「翻译中 + 已确认」
  if (materialReviewVerified.value[idx]) {
    const next = materialReviewVerified.value.slice()
    next[idx] = false
    materialReviewVerified.value = next
  }
  try {
    const res = await convertTranslate(text, 'material')
    row.materialZh = res.translated
    notesConfirmed.value = false
    message.success(first ? '已翻译，请核对后确认' : '已重译，请核对后确认')
    void persistAnnot()
  } catch {
    // 拦截器已 toast
  } finally {
    retranMatIdx.value = -1
  }
}

/** ④ 一键翻译：只补当前 Tab 缺译文且有原文的行（串行，避限流） */
async function translateAllNotes() {
  if (batchTranslateBusy.value || retranNoteIdx.value >= 0) return
  const targets = editNotes.value
    .map((n, idx) => ({ n, idx }))
    .filter(({ n }) => !!(n.original || '').trim() && !noteHasZh(n))
  if (!targets.length) {
    message.info('没有待译 NOTE（缺原文或已有译文）')
    return
  }
  batchTranslateBusy.value = true
  batchTranslateKind.value = 'note'
  batchTranslateDone.value = 0
  batchTranslateTotal.value = targets.length
  let ok = 0
  let fail = 0
  try {
    for (const { n, idx } of targets) {
      try {
        const res = await convertTranslate((n.original || '').trim(), 'note')
        n.translated = res.translated
        n.status = res.status || 'llm'
        if (noteTranslateVerified.value[idx]) {
          const next = noteTranslateVerified.value.slice()
          next[idx] = false
          noteTranslateVerified.value = next
        }
        ok += 1
      } catch {
        fail += 1
      }
      batchTranslateDone.value += 1
    }
    notesConfirmed.value = false
    void persistAnnot()
    void nextTick(() => autosizeAllNoteTextareas())
    if (fail) {
      message.warning(`NOTE 翻译完成：成功 ${ok}，失败 ${fail}，请核对后确认`)
    } else {
      message.success(`已翻译 ${ok} 条 NOTE，请核对后确认`)
    }
  } finally {
    batchTranslateBusy.value = false
    batchTranslateKind.value = ''
    batchTranslateDone.value = 0
    batchTranslateTotal.value = 0
  }
}

async function translateAllMaterials() {
  if (batchTranslateBusy.value || retranMatIdx.value >= 0) return
  const targets = editMaterials.value
    .map((m, idx) => ({ m, idx }))
    .filter(({ m }) => !!(m.materialEn || '').trim() && !materialHasZh(m))
  if (!targets.length) {
    message.info('没有待译材料（缺英文或已有中文）')
    return
  }
  batchTranslateBusy.value = true
  batchTranslateKind.value = 'material'
  batchTranslateDone.value = 0
  batchTranslateTotal.value = targets.length
  let ok = 0
  let fail = 0
  try {
    for (const { m, idx } of targets) {
      // 开译即取消确认，避免「翻译中 + 已确认」
      if (materialReviewVerified.value[idx]) {
        const next = materialReviewVerified.value.slice()
        next[idx] = false
        materialReviewVerified.value = next
      }
      try {
        const res = await convertTranslate((m.materialEn || '').trim(), 'material')
        m.materialZh = res.translated
        ok += 1
      } catch {
        fail += 1
      }
      batchTranslateDone.value += 1
    }
    notesConfirmed.value = false
    void persistAnnot()
    if (fail) {
      message.warning(`材料翻译完成：成功 ${ok}，失败 ${fail}，请核对后确认`)
    } else {
      message.success(`已翻译 ${ok} 条材料，请核对后确认`)
    }
  } finally {
    batchTranslateBusy.value = false
    batchTranslateKind.value = ''
    batchTranslateDone.value = 0
    batchTranslateTotal.value = 0
  }
}

/** 原文/译文框按内容撑开行高 */
function autosizeNoteTextarea(el: HTMLTextAreaElement | null) {
  if (!el) return
  el.style.height = 'auto'
  el.style.height = `${Math.max(el.scrollHeight, 56)}px`
}

function autosizeAllNoteTextareas() {
  void nextTick(() => {
    const nodes = document.querySelectorAll<HTMLTextAreaElement>(
      'textarea.eng-note-item-body, textarea.eng-note-trans-body'
    )
    nodes.forEach((el) => autosizeNoteTextarea(el))
  })
}

/** ①/④ 改 NOTE 原文：① 清对图确认；④ 清译文确认 */
function onNoteOriginalInput(idx: number, ev: Event) {
  const el = ev.target as HTMLTextAreaElement
  const v = el.value
  const row = editNotes.value[idx]
  if (!row) return
  row.original = v
  notesConfirmed.value = false
  let cleared = false
  if (step.value === 4) {
    // ④ 改原文只取消本行译文确认，不回退①对图确认
    if (noteTranslateVerified.value[idx]) {
      const next = noteTranslateVerified.value.slice()
      next[idx] = false
      noteTranslateVerified.value = next
      cleared = true
    }
  } else if (noteVerified.value[idx]) {
    const next = noteVerified.value.slice()
    next[idx] = false
    noteVerified.value = next
    cleared = true
  }
  autosizeNoteTextarea(el)
  if (cleared) void persistAnnot()
}

/** ①/④ 材料 2D 字段就地改；不触发 3D 采用逻辑 */
function onMaterialFieldInput(
  idx: number,
  field: 'materialEn' | 'materialZh' | 'thickness' | 'color',
  ev: Event
) {
  const v = (ev.target as HTMLInputElement | HTMLTextAreaElement).value
  const row = editMaterials.value[idx]
  if (!row) return
  row[field] = v
  notesConfirmed.value = false
  if (step.value === 4) {
    // ④ 改字段只取消本行审校确认，不回退③材料核对门禁
    if (materialReviewVerified.value[idx]) {
      const next = materialReviewVerified.value.slice()
      next[idx] = false
      materialReviewVerified.value = next
      void persistAnnot()
    }
    return
  }
  // ① 改过 2D 后，材料确认需重做（③ 基线已变）
  let changed = false
  if (materialsConfirmed.value) {
    materialsConfirmed.value = false
    changed = true
  }
  if (materialVerified.value[idx]) {
    const next = materialVerified.value.slice()
    next[idx] = false
    materialVerified.value = next
    changed = true
  }
  if (changed) void persistAnnot()
}

function onMaterialZhInput(idx: number, ev: Event) {
  onMaterialFieldInput(idx, 'materialZh', ev)
}

async function runCompose() {
  if (taskCompleted.value) {
    message.warning('任务已完成。若要重新生成，请先到结果页或列表点「编辑」')
    return
  }
  if (!stackAsset.value && !stackSkipped.value) {
    message.warning('请先保存层次，或在②跳过层次')
    return
  }
  if (!canComposeSources.value) {
    message.warning('请上传客户 DWG 或完成三维层次（二维与三维至少保留一个）')
    return
  }
  if (!materialsConfirmed.value || !notesConfirmed.value) {
    message.warning('请先完成材料核对与翻译审校')
    return
  }
  // 有层次时：生成前再读一次旁注；跳过层次则不校验
  if (stackAsset.value?.id) {
    try {
      const asset = await getEngStack(stackAsset.value.id)
      const bad = unlabeledCalloutLabels(asset.job?.callouts)
      if (bad.length) {
        const preview = bad.slice(0, 6).join('、')
        const more = bad.length > 6 ? '…' : ''
        message.warning(
          `层次旁注仍有 ${bad.length} 条未填齐序号/材料/颜色（${preview}${more}）。请回②补全并重新保存，或回③确认以回写层次 DXF`
        )
        return
      }
    } catch (e) {
      message.error(e instanceof Error ? e.message : '读取层次资产失败')
      return
    }
  }
  await doCompose()
}

function stopComposePoll() {
  if (composePollTimer != null) {
    window.clearInterval(composePollTimer)
    composePollTimer = null
  }
}

function applyComposeJobView(job: EngComposeJobView) {
  composeJobStage.value = humanizeProcessLog(job.stage || '')
  composeJobPercent.value = Number(job.percent || 0)
  composeLogs.value = Array.isArray(job.logs) ? humanizeProcessLogs(job.logs) : []
  // 只校准已用时，不在每次轮询里重建计时器（旧逻辑易导致显示卡在 00:00）
  syncComposeElapsedFromJob(job)
}

function beginComposeUiFromJob(job: EngComposeJobView) {
  composing.value = true
  startBusy('compose')
  if (job.startedAt && Number(job.startedAt) > 0) {
    writeComposeStartAt(Number(job.startedAt))
  } else if (!readComposeStartAt()) {
    writeComposeStartAt(Date.now())
  }
  applyComposeJobView(job)
  beginComposeTimer()
}

async function finishComposeSuccess(composed: EngComposeResult) {
  result.value = composed
  const partial = isComposePartial(composed)
  const tip = humanizeComposeMessage(composed.message || '工程图已生成')
  if (partial) {
    message.warning(`${tip}（用时 ${composeElapsedText.value}）。请检查层次后可重试生成，或先下载核对`)
  } else {
    message.success(`${tip}（用时 ${composeElapsedText.value}）`)
  }

  await persistAnnot({ wizardStep: 5, materialsConfirmed: true, notesConfirmed: true })
  if (annotPersistFailed.value) {
    await persistAnnot({ wizardStep: 5, materialsConfirmed: true, notesConfirmed: true })
  }
  if (annotPersistFailed.value) {
    pendingFinalize.value = true
    message.warning('工程图已生成，但审校结果未保存。请点下方「再存一次」后再完成任务')
    return
  }
  pendingFinalize.value = true
  if (partial) {
    return
  }
  emit('composed', composed)
}

function endComposeUi(ok: boolean) {
  composing.value = false
  stopComposeElapsedTick()
  stopComposePoll()
  if (ok) clearComposeStartAt()
  stopBusy()
  composeJobStage.value = ''
  composeJobPercent.value = 0
}

async function handleComposeTerminal(job: EngComposeJobView): Promise<'success' | 'failed' | 'running'> {
  applyComposeJobView(job)
  const st = String(job.status || '').toUpperCase()
  if (st === 'RUNNING' || st === 'QUEUED') {
    return 'running'
  }
  if (st === 'SUCCESS') {
    if (!job.result) {
      endComposeUi(false)
      message.error('合成已完成但产物丢失，请重新生成')
      return 'failed'
    }
    try {
      await finishComposeSuccess(job.result)
      endComposeUi(true)
      return 'success'
    } catch (e) {
      endComposeUi(false)
      message.error(e instanceof Error ? e.message : '工程图结果处理失败')
      return 'failed'
    }
  }
  if (st === 'FAILED') {
    endComposeUi(false)
    message.error(job.message || '工程图生成失败')
    return 'failed'
  }
  return 'running'
}

function startComposePolling() {
  stopComposePoll()
  const taskId = props.taskId
  if (taskId == null || taskId === '') return
  composePollTimer = window.setInterval(() => {
    void pollComposeOnce()
  }, 2000)
}

async function pollComposeOnce() {
  const taskId = props.taskId
  if (taskId == null || taskId === '') return
  try {
    const job = await getComposeStatus(taskId)
    const terminal = await handleComposeTerminal(job)
    if (terminal !== 'running') {
      stopComposePoll()
    }
  } catch {
    // 轮询失败不打断；下次再试
  }
}

/** 进入⑤或挂载：若服务端仍在跑/刚成功，自动续跟，无需再点生成 */
async function resumeComposeIfNeeded() {
  if (composeResumeTried && composing.value) return
  const taskId = props.taskId
  if (taskId == null || taskId === '') return
  if (step.value !== 5) return
  composeResumeTried = true
  try {
    const job = await getComposeStatus(taskId)
    const st = String(job.status || '').toUpperCase()
    if (st === 'RUNNING' || st === 'QUEUED') {
      beginComposeUiFromJob(job)
      startComposePolling()
      return
    }
    if (st === 'SUCCESS' && job.result && !result.value) {
      // 只回显上次产物，禁止走 finishComposeSuccess（会 toast + 自动 complete 踢回结果页）
      result.value = job.result
      // 已完成任务仅回看，不再提示「完成任务」
      pendingFinalize.value = !taskCompleted.value
      return
    }
    if (st === 'FAILED' && Array.isArray(job.logs) && job.logs.length) {
      composeLogs.value = humanizeProcessLogs(job.logs)
      composeJobStage.value = job.stage || '失败'
    }
  } catch {
    // 状态查询失败不阻断手动生成
  }
}

async function doCompose() {
  if ((!stackAsset.value && !stackSkipped.value) || !canComposeSources.value) return
  // 有 taskId 走异步：离页可续跟 + 过程日志
  if (props.taskId != null && props.taskId !== '') {
    await doComposeAsync()
    return
  }
  await doComposeSync()
}

async function doComposeAsync() {
  if ((!stackAsset.value && !stackSkipped.value) || !canComposeSources.value) return
  composing.value = true
  clearComposeStartAt()
  writeComposeStartAt(Date.now())
  beginComposeTimer()
  startBusy('compose')
  composeLogs.value = []
  composeJobStage.value = '提交工程图生成…'
  composeJobPercent.value = 5
  pendingFinalize.value = false
  try {
    const job = await startComposeAsync(
      dwgFile.value || null,
      stackAsset.value?.id || '',
      editNotes.value,
      editMaterials.value,
      preview.value?.sheetLayout,
      props.taskId
    )
    applyComposeJobView(job)
    beginComposeTimer()
    const terminal = await handleComposeTerminal(job)
    if (terminal === 'running') {
      startComposePolling()
      return
    }
  } catch (e) {
    endComposeUi(false)
    message.error(e instanceof Error ? e.message : '工程图生成失败')
  }
}

/** 无 taskId 时回退同步合成（兼容嵌入预览） */
async function doComposeSync() {
  if ((!stackAsset.value && !stackSkipped.value) || !canComposeSources.value) return
  composing.value = true
  clearComposeStartAt()
  beginComposeTimer()
  startBusy('compose')
  let ok = false
  pendingFinalize.value = false
  try {
    result.value = await composeEngDrawing(
      dwgFile.value || null,
      stackAsset.value?.id || '',
      editNotes.value,
      editMaterials.value,
      preview.value?.sheetLayout,
      props.taskId
    )
    await finishComposeSuccess(result.value)
    ok = true
  } catch (e) {
    message.error(e instanceof Error ? e.message : '工程图生成失败')
  } finally {
    endComposeUi(ok)
  }
}

/** 半成功或快照补存后：手动完成任务 */
async function finalizeComposedTask() {
  if (taskCompleted.value) {
    message.info('任务已完成，可到结果页查看或下载')
    return
  }
  if (!result.value || finalizing.value) return
  if (annotPersistFailed.value) {
    await persistAnnot({ wizardStep: 5, materialsConfirmed: true, notesConfirmed: true })
    if (annotPersistFailed.value) {
      message.warning('审校结果仍未保存，请稍后重试「再存一次」')
      return
    }
    message.success('审校结果已保存')
  }
  const go = async () => {
    finalizing.value = true
    try {
      emit('composed', result.value!)
      pendingFinalize.value = false
    } finally {
      finalizing.value = false
    }
  }
  if (isComposePartial(result.value)) {
    Modal.confirm({
      title: '层次可能未放入工程图',
      content: '三维层次可能没有出现在工程图预留区。仍要完成任务并进入结果页吗？（可先下载 DXF 核对）',
      okText: '仍要完成',
      cancelText: '再检查',
      onOk: go,
    })
    return
  }
  await go()
}

function resultHasDxf(r?: EngComposeResult | null) {
  return !!(r?.hasDxf || (r?.dxfBase64 && r.dxfBase64.length > 0))
}

const dxfDownloading = ref(false)

async function downloadDxf() {
  if (!resultHasDxf(result.value)) {
    message.warning('请先生成工程图')
    return
  }
  const b64 = result.value?.dxfBase64
  if (b64) {
    const bin = atob(b64)
    const bytes = new Uint8Array(bin.length)
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i)
    const url = URL.createObjectURL(new Blob([bytes], { type: 'application/dxf' }))
    const a = document.createElement('a')
    a.href = url
    a.download = result.value?.dxfFileName || engineeringDxfFileName(result.value?.drawingNo)
    a.click()
    URL.revokeObjectURL(url)
    return
  }
  if (props.taskId == null || props.taskId === '') {
    message.warning('请先生成工程图')
    return
  }
  if (dxfDownloading.value) return
  dxfDownloading.value = true
  try {
    await taskApi.saveEngineeringDxf(
      props.taskId,
      result.value?.dxfFileName || engineeringDxfFileName(result.value?.drawingNo)
    )
  } catch {
    // toast 已在 api 层提示
  } finally {
    dxfDownloading.value = false
  }
}

/** 浏览器另存本地 File（上传会话内尚未走接口时） */
function saveLocalFile(file: File, fallbackName: string) {
  const url = URL.createObjectURL(file)
  const a = document.createElement('a')
  a.href = url
  a.download = file.name || fallbackName
  a.click()
  URL.revokeObjectURL(url)
}

const sourceDownloading = ref(false)

/** ① 下载本任务用户上传的客户 DWG */
async function downloadSourceDwg() {
  if (sourceDownloading.value) return
  if (dwgFile.value) {
    saveLocalFile(dwgFile.value, 'customer.dwg')
    return
  }
  if (props.taskId == null || props.taskId === '') {
    message.warning('请先上传客户 DWG')
    return
  }
  sourceDownloading.value = true
  try {
    const fallback =
      preview.value?.fileName && /\.dwg$/i.test(preview.value.fileName)
        ? preview.value.fileName
        : 'customer.dwg'
    await taskApi.saveDwg(props.taskId, fallback)
  } catch {
    // toast 已在 api 层提示
  } finally {
    sourceDownloading.value = false
  }
}

/** ② 下载本任务用户上传的 STP / STEP */
async function downloadSourceStep() {
  if (sourceDownloading.value) return
  if (!hasStepFile.value) {
    message.warning('请先上传三维模型')
    return
  }
  if (props.taskId == null || props.taskId === '') {
    message.warning('任务未就绪，无法下载')
    return
  }
  sourceDownloading.value = true
  try {
    await taskApi.saveStep(props.taskId, props.stepFileName || 'model.stp')
  } catch {
    // toast 已在 api 层提示
  } finally {
    sourceDownloading.value = false
  }
}

function statusLabel(s: string) {
  if (s === 'match') return '一致'
  if (s === 'missing_2d') return '缺2D'
  if (s === 'missing_3d') return '缺3D'
  if (s === 'field_diff') return '字段差'
  return s
}

function cropImgSrc(raw: string) {
  if (!raw) return ''
  return raw.startsWith('data:') ? raw : `data:image/png;base64,${raw}`
}

const extractCrops = computed(() =>
  (preview.value?.materialCrops || []).filter((c) => !!c && String(c).trim())
)

/** ① 后端质检 / 融合提示（过滤空串；对用户弱化术语） */
const extractQaWarnings = computed(() =>
  (preview.value?.warnings || [])
    .map((w) => humanizeUserTip(String(w || '').trim()))
    .filter(Boolean)
)
</script>

<template>
  <div class="eng-wiz" :class="{ chrome: chromeOnly, panel: !chromeOnly, embedded: !!embedded }">
    <header class="eng-bar">
      <div class="eng-brand">
        <h2>工程图生成</h2>
        <a-tooltip v-if="embedded && taskTitle" :title="taskTitle">
          <span class="eng-task-title">{{ taskTitle }}</span>
        </a-tooltip>
        <a-tooltip :title="HELP_TIP" placement="bottomLeft">
          <button type="button" class="eng-icon-btn" aria-label="流程说明">
            <QuestionCircleOutlined />
          </button>
        </a-tooltip>
      </div>

      <nav class="eng-steps" aria-label="流程步骤" data-tour="wizard-steps">
        <button
          type="button"
          class="eng-step"
          data-tour="wizard-step-1"
          :class="{
            on: step === 1,
            done: !stageContentPending && (!!preview || extractSkipped)
          }"
          @click="goStep(1)"
        >
          <span class="eng-step-no">
            <CheckOutlined v-if="!stageContentPending && (preview || extractSkipped)" />
            <template v-else>1</template>
          </span>
          <span class="eng-step-lab">{{
            !stageContentPending && extractSkipped && !preview ? '已跳过' : '抽取'
          }}</span>
        </button>
        <span class="eng-step-sep" />
        <a-tooltip
          :title="step >= 2 ? '返回三维层次' : step2BlockTip || '进入三维层次'"
          placement="bottom"
        >
          <span class="eng-step-wrap" data-tour="wizard-step-2">
            <button
              type="button"
              class="eng-step"
              :class="{
                on: step === 2,
                done: !stageContentPending && (!!stackAsset || stackSkipped)
              }"
              :disabled="!(step >= 2 || canVisitStep2)"
              @click="goStep(2)"
            >
              <span class="eng-step-no">
                <CheckOutlined v-if="!stageContentPending && (stackAsset || stackSkipped)" />
                <template v-else>2</template>
              </span>
              <span class="eng-step-lab">{{
                !stageContentPending && stackSkipped && !stackAsset ? '已跳过' : '层次'
              }}</span>
            </button>
          </span>
        </a-tooltip>
        <span class="eng-step-sep" />
        <a-tooltip
          :title="step >= 3 ? '返回材料核对' : step3BlockTip || '进入材料核对'"
          placement="bottom"
        >
          <span class="eng-step-wrap" data-tour="wizard-step-3">
            <button
              type="button"
              class="eng-step"
              :class="{ on: step === 3, done: !stageContentPending && materialsConfirmed }"
              :disabled="!(step >= 3 || canVisitStep3)"
              @click="goStep(3)"
            >
              <span class="eng-step-no">
                <CheckOutlined v-if="!stageContentPending && materialsConfirmed" />
                <template v-else>3</template>
              </span>
              <span class="eng-step-lab">材料</span>
            </button>
          </span>
        </a-tooltip>
        <span class="eng-step-sep" />
        <a-tooltip
          :title="step >= 4 ? '返回翻译审校' : step4BlockTip || '进入翻译审校'"
          placement="bottom"
        >
          <span class="eng-step-wrap" data-tour="wizard-step-4">
            <button
              type="button"
              class="eng-step"
              :class="{ on: step === 4, done: !stageContentPending && notesConfirmed }"
              :disabled="!(step >= 4 || canVisitStep4)"
              @click="goStep(4)"
            >
              <span class="eng-step-no">
                <CheckOutlined v-if="!stageContentPending && notesConfirmed" />
                <template v-else>4</template>
              </span>
              <span class="eng-step-lab">翻译</span>
            </button>
          </span>
        </a-tooltip>
        <span class="eng-step-sep" />
        <a-tooltip
          :title="step >= 5 ? '返回生成工程图' : step5BlockTip || '进入生成工程图'"
          placement="bottom"
        >
          <span class="eng-step-wrap" data-tour="wizard-step-5">
            <button
              type="button"
              class="eng-step"
              :class="{ on: step === 5 }"
              :disabled="!(step >= 5 || canVisitStep5) || composeEntering"
              @click="goStep(5)"
            >
              <span class="eng-step-no">5</span>
              <span class="eng-step-lab">生成</span>
            </button>
          </span>
        </a-tooltip>
      </nav>

      <div class="eng-bar-right">
        <span v-if="chromeOnly" class="eng-chip" :class="statusChip.tone">{{ statusChip.text }}</span>
        <a-tooltip v-if="chromeOnly && fileChip" :title="stepFileName">
          <span class="eng-chip file">{{ fileChip }}</span>
        </a-tooltip>
        <a-tooltip v-if="!embedded" title="关闭向导">
          <button type="button" class="eng-icon-btn close" aria-label="关闭" @click="emit('close')">
            <CloseOutlined />
          </button>
        </a-tooltip>
      </div>
    </header>

    <div
      v-if="stepSwitching && stepSwitchTip"
      class="eng-step-switch"
      role="status"
      aria-live="polite"
    >
      <LoadingOutlined spin />
      <span>{{ stepSwitchTip }}</span>
    </div>

    <div v-show="step === 2" class="eng-toolbar">
      <a-upload :before-upload="beforeStep" :show-upload-list="false" accept=".stp,.step">
        <a-tooltip :title="stepFileName ? '重新选择 STP / STEP' : '选择三维模型（.stp / .step）'">
          <button type="button" class="eng-tool">
            <FolderOpenOutlined />
            <span>{{ stepFileName ? '更换模型' : '导入 STP' }}</span>
          </button>
        </a-tooltip>
      </a-upload>
      <a-tooltip :title="hasStepFile ? '下载本任务上传的三维源文件' : '请先上传 STP'">
        <button
          type="button"
          class="eng-tool"
          :class="{ busy: sourceDownloading }"
          :disabled="!hasStepFile || sourceDownloading || taskId == null || taskId === ''"
          @click="downloadSourceStep"
        >
          <LoadingOutlined v-if="sourceDownloading" spin />
          <DownloadOutlined v-else />
          <span>{{ sourceDownloading ? '下载中…' : '下载 STP' }}</span>
        </button>
      </a-tooltip>
      <a-tooltip title="解析三维几何与实体">
        <button
          type="button"
          class="eng-tool primary"
          :disabled="!hasStepFile || !!stepScanning"
          @click="runParse"
        >
          <LoadingOutlined v-if="stepScanning" spin />
          <ThunderboltOutlined v-else />
          <span>{{ stepScanning ? '解析中…' : '解析三维' }}</span>
        </button>
      </a-tooltip>
      <a-tooltip title="打开层次爆炸编辑器，标注序号/材料后保存">
        <button
          type="button"
          class="eng-tool accent"
          :class="{ busy: stackEditorOpening }"
          :disabled="!solidReady || saving || !!stackEditorOpening"
          @click="goEditStack"
        >
          <LoadingOutlined v-if="saving || stackEditorOpening" spin />
          <SaveOutlined v-else />
          <span>{{
            saving ? '保存中…' : stackEditorOpening ? '打开中…' : '标注层次'
          }}</span>
        </button>
      </a-tooltip>
      <a-tooltip
        :title="
          hasDwgSource || hasStepSource || stackAsset
            ? hasDwgSource
              ? '无三维 STP 时可跳过；材料 3D 列为空，工程图不含爆炸层次'
              : '可跳过层次；尚无客户 DWG 时⑤生成前须补传（可与跳过①并存）'
            : '请先在本步导入 STP，或回①上传客户 DWG'
        "
      >
        <button
          type="button"
          class="eng-tool"
          :disabled="
            taskCompleted ||
            saving ||
            !!stackEditorOpening ||
            !(hasDwgSource || hasStepSource || stackAsset)
          "
          @click="skipStackStep"
        >
          <span>{{ stackSkipped && !stackAsset ? '已跳过层次' : '跳过层次' }}</span>
        </button>
      </a-tooltip>

      <div v-if="recentStacks.length" class="eng-recent">
        <span class="eng-recent-lab">最近</span>
        <a-tooltip v-for="row in recentStacks.slice(0, 3)" :key="row.id" :title="`选用层次：${row.title}`">
          <button type="button" class="eng-recent-btn" @click="pickRecent(row)">
            {{ row.title.length > 18 ? row.title.slice(0, 16) + '…' : row.title }}
          </button>
        </a-tooltip>
      </div>
    </div>

    <div v-if="chromeOnly && (stepScanning || saving || stackEditorOpening)" class="eng-busy eng-busy-inline">
      <div class="eng-busy-head">
        <LoadingOutlined spin />
        <span class="eng-busy-text">{{
          busyLabel
            || (saving ? '保存中…' : stackEditorOpening ? '正在打开层次标注…' : '解析中…')
        }}</span>
      </div>
      <a-progress
        class="eng-busy-bar"
        :percent="busyPercent"
        status="active"
        :show-info="false"
        size="small"
      />
      <p class="eng-busy-hint">处理中，请稍候，勿关闭或刷新页面</p>
    </div>

    <Teleport to="#eng-wizard-side" :disabled="!embedded || chromeOnly" defer>
      <div
        v-if="!chromeOnly"
        :class="embedded ? 'eng-side-root' : 'eng-drawer-mask'"
        @click.self="onDrawerMaskClick"
      >
        <aside
          :class="embedded ? ['eng-side-panel', { wide: drawerWide }] : ['eng-drawer', { wide: drawerWide }]"
          role="dialog"
          :aria-label="drawerTitle"
        >
          <div
            class="eng-drawer-head"
            :class="{ 'eng-drawer-head--compact': step === 1 || step === 3 || step === 4 }"
          >
            <div class="eng-drawer-head-main">
              <div class="eng-drawer-title-row">
                <h3>{{ drawerTitle }}</h3>
                <p v-if="step === 3" class="eng-drawer-hint-inline">
                  共 {{ materialSummary.total }} ·
                  <span class="ok">一致 {{ materialSummary.match }}</span>
                  ·
                  <span :class="{ bad: materialSummary.conflict }"
                    >不一致 {{ materialSummary.conflict }}</span
                  >
                  <template v-if="materialSummary.missing3d">
                    · 缺3D {{ materialSummary.missing3d }}</template
                  >
                  <template v-if="materialSummary.missing2d">
                    · 缺2D {{ materialSummary.missing2d }}</template
                  >
                  <template v-if="materialSummary.fieldDiff">
                    · 字段差 {{ materialSummary.fieldDiff }}</template
                  >
                  <template v-if="materialSummary.unresolved">
                    · <span class="warn">待确认 {{ materialSummary.unresolved }}</span></template
                  >
                </p>
                <p v-if="step === 4" class="eng-drawer-hint-inline">
                  NOTE {{ notesTranslateVerifiedCount }}/{{ editNotes.length }} · 材料
                  {{ materialsReviewVerifiedCount }}/{{ editMaterials.length }} · 未译
                  {{ untranslatedCount }} · 料号/标准号/数字保持原文；改后需重确认
                </p>
              </div>
              <p
                v-if="step === 1 && preview && !extracting"
                class="eng-drawer-sub"
                :title="dwgFile?.name || preview.fileName"
              >
                {{ dwgFile?.name || preview.fileName || 'customer.dwg' }}
                <template v-if="preview.drawingNo"> · {{ preview.drawingNo }}</template>
                <template v-if="stackAsset"> · 层次：{{ stackAsset.title }}</template>
              </p>
              <p v-else-if="stackAsset && step !== 3 && step !== 4" class="eng-drawer-sub">
                层次：{{ stackAsset.title }}
              </p>
            </div>
            <!-- ① 已出预览：操作钮上移到标题行；核对态也常驻「重新渲图」 -->
            <div
              v-if="step === 1 && preview && !extracting"
              class="eng-drawer-head-actions"
            >
              <button
                type="button"
                class="eng-tool accent"
                :disabled="!dwgFile || regionRecognizing"
                title="仅重新生成高清预览，不抽取 NOTE/材料"
                @click="rePreviewCurrentDwg"
              >
                <ReloadOutlined />
                <span>重新渲图</span>
              </button>
              <button
                v-if="!showExtractChooser"
                type="button"
                class="eng-tool accent"
                :disabled="!dwgFile || regionRecognizing"
                title="重新跑全量自动抽取（覆盖当前 NOTE/材料）"
                @click="reExtractCurrentDwg"
              >
                <ReloadOutlined />
                <span>重新自动抽取</span>
              </button>
              <a-upload :before-upload="beforeDwg" :show-upload-list="false" accept=".dwg">
                <button type="button" class="eng-tool accent" :disabled="regionRecognizing">
                  <FileOutlined />
                  <span>重新选择</span>
                </button>
              </a-upload>
              <a-tooltip
                :title="
                  dwgFile || (taskId != null && taskId !== '')
                    ? '下载本任务上传的客户 DWG 源文件'
                    : '请先上传客户 DWG'
                "
              >
                <button
                  type="button"
                  class="eng-tool accent"
                  :class="{ busy: sourceDownloading }"
                  :disabled="
                    sourceDownloading ||
                    regionRecognizing ||
                    (!dwgFile && (taskId == null || taskId === ''))
                  "
                  @click="downloadSourceDwg"
                >
                  <LoadingOutlined v-if="sourceDownloading" spin />
                  <DownloadOutlined v-else />
                  <span>{{ sourceDownloading ? '下载中…' : '下载 DWG' }}</span>
                </button>
              </a-tooltip>
              <a-tooltip title="不核对 NOTE/材料，直接进入②；可在②导入 STP 或跳过层次。⑤仍须客户 DWG">
                <button
                  type="button"
                  class="eng-tool"
                  :disabled="taskCompleted || extracting || regionRecognizing"
                  @click="skipExtractStep"
                >
                  <span>{{ extractSkipped ? '已跳过抽取' : '跳过抽取' }}</span>
                </button>
              </a-tooltip>
              <button
                v-if="!showExtractChooser"
                type="button"
                class="eng-tool accent"
                :class="{ busy: regionRecognizing }"
                data-tour="region-capture"
                :disabled="regionRecognizing || extractTab === 'crop'"
                title="在左图框选区域，识别 NOTE / 材料文字（推荐）"
                @click="startRegionCapture"
              >
                <LoadingOutlined v-if="regionRecognizing" spin />
                <ScanOutlined v-else />
                <span>{{ regionRecognizing ? '识别中…' : '截图抽取' }}</span>
              </button>
            </div>
            <div
              v-else-if="step === 1 && extracting && taskId != null && taskId !== ''"
              class="eng-drawer-head-actions"
            >
              <button
                type="button"
                class="eng-tool accent"
                :class="{ busy: extractCanceling }"
                :disabled="extractCanceling"
                title="停止当前解析/抽取"
                @click="stopExtractJob"
              >
                <LoadingOutlined v-if="extractCanceling" spin />
                <CloseOutlined v-else />
                <span>{{ extractCanceling ? '停止中…' : '停止' }}</span>
              </button>
            </div>
            <a-tooltip v-if="step > 1" :title="extracting || composing ? '处理中，请稍候' : '返回三维层次'">
              <button
                type="button"
                class="eng-icon-btn"
                :disabled="extracting || composing"
                @click="goStep(2)"
              >
                <CloseOutlined />
              </button>
            </a-tooltip>
          </div>

        <!-- ① 上传预览后：选截图/自动抽取，再对图核对 -->
        <div
          v-if="step === 1"
          ref="extractBodyRef"
          class="eng-drawer-body"
          :class="{ 'eng-extract-body': !!(preview && !extracting) }"
        >
          <!-- 未出预览：恢复中 / 大上传区 -->
          <div v-if="hydrating" class="eng-drop busy eng-drop--hydrate">
            <div class="eng-hydrate-ico" aria-hidden="true">
              <LoadingOutlined spin class="eng-drop-ico" />
            </div>
            <strong>正在为您恢复任务…</strong>
            <span>客户图纸与审校结果加载中，请耐心等待</span>
            <span class="eng-hydrate-note">勿关闭或刷新页面，完成后会回到上次步骤</span>
          </div>
          <div
            v-else-if="!preview || extracting"
            class="eng-drop"
            :class="{
              drag: dwgDragging,
              ready: !extracting,
              busy: extracting,
            }"
            @dragover.prevent="onDwgDragOver"
            @dragenter.prevent="onDwgDragOver"
            @dragleave="onDwgDragLeave"
            @drop.prevent="onDwgDrop"
          >
            <span v-if="extracting" class="eng-extract-elapsed" title="已用时（离页回来继续累计）">
              已用时 {{ extractElapsedText }}
            </span>
            <CloudUploadOutlined class="eng-drop-ico" />
            <strong>拖放客户 DWG</strong>
            <span>上传后先生成高清预览，再选择截图抽取或自动抽取</span>
            <a-upload :before-upload="beforeDwg" :show-upload-list="false" accept=".dwg">
              <button
                type="button"
                class="eng-tool accent"
                :class="{ busy: extracting }"
                :disabled="extracting"
              >
                <LoadingOutlined v-if="extracting" spin />
                <FileOutlined v-else />
                <span>{{
                  extracting
                    ? extractJobMode === 'extract'
                      ? '抽取中…'
                      : '解析中…'
                    : '选择 DWG'
                }}</span>
              </button>
            </a-upload>
            <a-tooltip
              v-if="!extracting"
              title="不核对 NOTE/材料，直接进入②；可在②导入 STP 或跳过层次。⑤仍须客户 DWG"
            >
              <button
                type="button"
                class="eng-tool"
                :disabled="taskCompleted"
                @click="skipExtractStep"
              >
                <span>{{ extractSkipped ? '已跳过抽取' : '跳过抽取' }}</span>
              </button>
            </a-tooltip>
          </div>
          <p v-if="dwgFile && (!preview || extracting)" class="eng-ok-line">
            <CheckOutlined /> {{ dwgFile.name }}
            <template v-if="preview?.drawingNo"> · 图号 {{ preview.drawingNo }}</template>
          </p>
          <div v-if="extracting" class="eng-busy">
            <div class="eng-busy-head">
              <LoadingOutlined spin />
              <span class="eng-busy-text">{{
                busyLabel ||
                (extractJobMode === 'extract' ? '正在抽取 NOTE / 材料…' : '正在解析图纸并生成高清预览…')
              }}</span>
              <span class="eng-compose-elapsed" title="已用时">已用时 {{ extractElapsedText }}</span>
            </div>
            <a-progress
              class="eng-busy-bar"
              :percent="extractDisplayPercent"
              status="active"
              :show-info="true"
              size="small"
            />
            <p class="eng-busy-hint">
              {{
                extractJobMode === 'extract'
                  ? '抽取在服务端排队（多人共用处理通道）；可离开页面，回来后自动续跟。'
                  : '图纸解析在服务端排队（多人共用处理通道）；可离开页面，回来后自动续跟。'
              }}
              请看已用时与过程日志。
              <template v-if="taskId != null && taskId !== ''">也可随时点「停止」中断。</template>
            </p>
            <div
              v-if="taskId != null && taskId !== ''"
              class="eng-busy-actions"
            >
              <button
                type="button"
                class="eng-tool accent"
                :class="{ busy: extractCanceling }"
                :disabled="extractCanceling"
                title="停止当前解析/抽取"
                @click="stopExtractJob"
              >
                <LoadingOutlined v-if="extractCanceling" spin />
                <CloseOutlined v-else />
                <span>{{ extractCanceling ? '停止中…' : '停止' }}</span>
              </button>
            </div>
            <div v-if="extractLogs.length" class="eng-compose-log" aria-label="过程日志">
              <div class="eng-compose-log-title">过程日志</div>
              <pre class="eng-compose-log-body">{{ extractLogs.join('\n') }}</pre>
            </div>
          </div>

          <!-- 已有预览：选抽取方式 + 核对工作台 -->
          <template v-if="preview && !extracting">
            <!-- 截图优先：左右双通道（操作钮已上移标题栏） -->
            <div v-if="showExtractChooser" class="eng-extract-chooser" data-tour="extract-chooser">
              <div class="eng-extract-chooser-actions">
                <button
                  type="button"
                  class="eng-tool accent eng-chooser-btn"
                  :class="{ busy: regionRecognizing }"
                  data-tour="region-capture"
                  :disabled="regionRecognizing || extractTab === 'crop'"
                  @click="startRegionCapture"
                >
                  <LoadingOutlined v-if="regionRecognizing" spin />
                  <ScanOutlined v-else />
                  <span>{{ regionRecognizing ? '识别中…' : '截图抽取（推荐）' }}</span>
                </button>
                <button
                  type="button"
                  class="eng-tool eng-chooser-btn eng-chooser-btn--outline"
                  :disabled="!dwgFile || regionRecognizing"
                  @click="reExtractCurrentDwg"
                >
                  <ThunderboltOutlined />
                  <span>自动抽取</span>
                </button>
              </div>
            </div>

            <p class="eng-extract-summary">
              <strong>NOTE {{ editNotes.length }}</strong>
              <span>·</span>
              <strong>材料 {{ editMaterials.length }}</strong>
              <span v-if="extractCrops.length">·</span>
              <strong v-if="extractCrops.length">裁剪 {{ extractCrops.length }}</strong>
              <a-popover
                placement="bottomLeft"
                trigger="click"
                overlay-class-name="eng-extract-help-pop"
              >
                <template #title>
                  <span v-if="extractQaWarnings.length">
                    抽取质检 {{ extractQaWarnings.length }} 项，请优先核对
                  </span>
                  <span v-else>核对说明</span>
                </template>
                <template #content>
                  <ul v-if="extractQaWarnings.length" class="eng-extract-qa-list">
                    <li v-for="(w, i) in extractQaWarnings" :key="'qa-' + i">{{ w }}</li>
                  </ul>
                  <p class="eng-extract-help-tip">
                    上传后先出高清预览；建议用「截图抽取」框选，或改用「自动抽取」。抽漏可「新增」、抽多可「删除」。须先确认全部 NOTE，再确认全部材料，方可进入层次。识别结果请对照左图核对后再确认。
                  </p>
                </template>
                <button
                  type="button"
                  class="eng-extract-help-btn"
                  :class="{ warn: extractQaWarnings.length > 0 }"
                >
                  <ExclamationCircleOutlined v-if="extractQaWarnings.length" />
                  <QuestionCircleOutlined v-else />
                  <span>
                    {{
                      extractQaWarnings.length
                        ? `说明 · ${extractQaWarnings.length}`
                        : '说明'
                    }}
                  </span>
                </button>
              </a-popover>
            </p>
            <a-alert
              v-if="annotPersistFailed"
              type="warning"
              show-icon
              style="margin-bottom: 8px"
              message="审校快照上次保存失败，重进可能丢失确认态"
            >
              <template #action>
                <a-button size="small" type="link" @click="retryPersistAnnot">再存一次</a-button>
              </template>
            </a-alert>

            <div class="eng-extract-tabs" role="tablist">
              <button
                type="button"
                class="eng-extract-tab"
                role="tab"
                :class="{ on: extractTab === 'note' }"
                :aria-selected="extractTab === 'note'"
                @click="extractTab = 'note'"
              >
                NOTE（{{ editNotes.length }}）
              </button>
              <button
                type="button"
                class="eng-extract-tab"
                role="tab"
                :class="{ on: extractTab === 'material' }"
                :aria-selected="extractTab === 'material'"
                @click="extractTab = 'material'"
              >
                材料（{{ editMaterials.length }}）
              </button>
              <button
                v-if="extractCrops.length"
                type="button"
                class="eng-extract-tab"
                role="tab"
                :class="{ on: extractTab === 'crop' }"
                :aria-selected="extractTab === 'crop'"
                @click="extractTab = 'crop'"
              >
                裁剪（{{ extractCrops.length }}）
              </button>
            </div>

            <div class="eng-extract-scroll">
              <section v-show="extractTab === 'note'" class="eng-extract-sec" aria-label="NOTE">
                <div class="eng-extract-tools">
                  <button type="button" class="eng-extract-add" @click="addExtractNote">
                    <PlusOutlined /> 新增 NOTE
                  </button>
                  <button
                    type="button"
                    class="eng-extract-confirm-all"
                    :disabled="!editNotes.length || allNotesVerified"
                    :title="
                      !editNotes.length
                        ? '暂无 NOTE'
                        : allNotesVerified
                          ? '已全部确认'
                          : '一键确认全部 NOTE（需二次确认）'
                    "
                    @click="confirmAllExtractNotes"
                  >
                    <CheckOutlined /> 一键确认
                  </button>
                  <span class="eng-extract-tools-tip">可增删、改序号</span>
                </div>
                <div v-if="editNotes.length" class="eng-note-list">
                  <article
                    v-for="(n, idx) in editNotes"
                    :key="'ex-n-' + idx"
                    class="eng-note-item"
                    :class="{ verified: !!noteVerified[idx] }"
                  >
                    <header class="eng-note-item-hd">
                      <label class="eng-note-no-wrap">
                        <span class="eng-note-no-lab">序</span>
                        <input
                          class="eng-note-no-input"
                          type="text"
                          :value="n.noteNo || ''"
                          :placeholder="String(idx + 1)"
                          @input="onNoteNoInput(idx, $event)"
                          @blur="onNoteNoBlur"
                        />
                      </label>
                      <div class="eng-note-item-actions">
                        <button
                          type="button"
                          class="eng-note-verify"
                          :class="{ on: !!noteVerified[idx] }"
                          @click="toggleNoteVerified(idx)"
                        >
                          <CheckOutlined v-if="noteVerified[idx]" />
                          <span>{{ noteVerified[idx] ? '已确认' : '确认' }}</span>
                        </button>
                        <button
                          type="button"
                          class="eng-note-del"
                          title="删除本条 NOTE"
                          @click="removeExtractNote(idx)"
                        >
                          <DeleteOutlined />
                        </button>
                      </div>
                    </header>
                    <textarea
                      class="eng-inline-area eng-note-item-body"
                      rows="1"
                      :value="n.original || ''"
                      placeholder="对照左图填写 NOTE 原文"
                      @input="onNoteOriginalInput(idx, $event)"
                    />
                  </article>
                </div>
                <div v-else class="eng-empty-ack">
                  <p class="eng-empty">未抽到 NOTE（可点上方「新增 NOTE」补录）</p>
                  <label class="eng-ack-check">
                    <input type="checkbox" :checked="emptyNotesAcked" @change="ackEmptyNotes" />
                    <span>确认本图无 NOTE</span>
                  </label>
                </div>
                <p v-if="editNotes.length" class="eng-hint">
                  逐条对图确认（{{ notesVerifiedCount }}/{{ editNotes.length }}）；增删/改序号或原文后需重新确认；译文在「翻译」步再审
                </p>
                <p v-else class="eng-hint">空 NOTE 须勾选确认，或新增后再逐条确认</p>
              </section>

              <section v-show="extractTab === 'material'" class="eng-extract-sec" aria-label="材料">
                <div class="eng-extract-tools">
                  <button type="button" class="eng-extract-add" @click="addExtractMaterial">
                    <PlusOutlined /> 新增材料
                  </button>
                  <button
                    type="button"
                    class="eng-extract-confirm-all"
                    :disabled="!editMaterials.length || allMaterialsVerified"
                    :title="
                      !editMaterials.length
                        ? '暂无材料'
                        : allMaterialsVerified
                          ? '已全部确认'
                          : '一键确认全部材料（需二次确认）'
                    "
                    @click="confirmAllExtractMaterials"
                  >
                    <CheckOutlined /> 一键确认
                  </button>
                  <span class="eng-extract-tools-tip">可增删、改序号</span>
                </div>
                <div class="eng-table-wrap eng-table-wrap--extract-full eng-table-wrap--dense">
                  <table class="eng-review-table eng-review-table--dense eng-mat-extract">
                    <thead>
                      <tr>
                        <th class="col-seq">序</th>
                        <th class="col-en">英文/原文</th>
                        <th class="col-thk">厚度</th>
                        <th class="col-color">颜色</th>
                        <th class="col-verify">确认</th>
                        <th class="col-act">删</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr
                        v-for="(m, idx) in editMaterials"
                        :key="'ex-m-' + idx"
                        :class="{ verified: !!materialVerified[idx] }"
                      >
                        <td class="col-seq">
                          <input
                            class="eng-note-no-input eng-note-no-input--cell"
                            type="text"
                            :value="m.noteNo || m.seq || ''"
                            :placeholder="String(idx + 1)"
                            @input="onMaterialNoteNoInput(idx, $event)"
                            @blur="onMaterialNoteNoBlur"
                          />
                        </td>
                        <td class="col-en">
                          <textarea
                            class="eng-inline-area"
                            rows="2"
                            :value="m.materialEn || ''"
                            @input="onMaterialFieldInput(idx, 'materialEn', $event)"
                          />
                        </td>
                        <td class="col-thk">
                          <input
                            class="eng-inline-input eng-inline-input--mat-field"
                            type="text"
                            :value="m.thickness || ''"
                            :title="m.thickness || ''"
                            @input="onMaterialFieldInput(idx, 'thickness', $event)"
                          />
                        </td>
                        <td class="col-color">
                          <input
                            class="eng-inline-input eng-inline-input--mat-field"
                            type="text"
                            :value="m.color || ''"
                            :title="m.color || ''"
                            @input="onMaterialFieldInput(idx, 'color', $event)"
                          />
                        </td>
                        <td class="col-verify">
                          <button
                            type="button"
                            class="eng-note-verify"
                            :class="{ on: !!materialVerified[idx] }"
                            @click="toggleMaterialVerified(idx)"
                          >
                            <CheckOutlined v-if="materialVerified[idx]" />
                            <span>{{ materialVerified[idx] ? '已确认' : '确认' }}</span>
                          </button>
                        </td>
                        <td class="col-act">
                          <button
                            type="button"
                            class="eng-note-del"
                            title="删除本行材料"
                            @click="removeExtractMaterial(idx)"
                          >
                            <DeleteOutlined />
                          </button>
                        </td>
                      </tr>
                      <tr v-if="!editMaterials.length">
                        <td colspan="6" class="eng-empty">
                          <div class="eng-empty-ack">
                            <span>未抽到材料行（可点上方「新增材料」补录）</span>
                            <label class="eng-ack-check">
                              <input
                                type="checkbox"
                                :checked="emptyMaterialsAcked"
                                @change="ackEmptyMaterials"
                              />
                              <span>确认本图无材料行</span>
                            </label>
                          </div>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <p v-if="editMaterials.length" class="eng-hint">
                  逐条对图确认材料原文（{{ materialsVerifiedCount }}/{{ editMaterials.length }}）；中文在「翻译」步再填；增删/改字段后需重新确认
                </p>
                <p v-else class="eng-hint">空材料须勾选确认，或新增后再逐条确认</p>
              </section>

              <section v-show="extractTab === 'crop'" class="eng-extract-sec" aria-label="材料裁剪">
                <p class="eng-hint">按方框裁剪，请与材料表逐条对照</p>
                <div class="eng-crop-grid">
                  <figure v-for="(c, idx) in extractCrops" :key="'crop-' + idx" class="eng-crop-item">
                    <img :src="cropImgSrc(c)" :alt="`材料裁剪 ${idx + 1}`" />
                    <figcaption>#{{ idx + 1 }}</figcaption>
                  </figure>
                </div>
              </section>

              <div class="eng-extract-foot">
                <button
                  type="button"
                  class="eng-tool accent lg"
                  :disabled="!extractFootEnabled"
                  @click="onFinishExtract"
                >
                  {{ extractFootLabel }}
                </button>
              </div>
            </div>
          </template>
        </div>

        <!-- ③ 材料核对：全宽对比 + 采用结果可编辑 -->
        <div v-if="step === 3" class="eng-drawer-body eng-mat-body">
          <div class="eng-mat-toolbar">
            <div class="eng-mat-filters">
              <button
                type="button"
                class="eng-chip-btn"
                :class="{ on: materialFilter === 'all' }"
                @click="materialFilter = 'all'"
              >
                全部
              </button>
              <button
                type="button"
                class="eng-chip-btn"
                :class="{ on: materialFilter === 'conflict' }"
                @click="materialFilter = 'conflict'"
              >
                仅不一致
              </button>
              <button
                type="button"
                class="eng-chip-btn"
                :class="{ on: materialFilter === 'missing_3d' }"
                @click="materialFilter = 'missing_3d'"
              >
                缺3D
              </button>
              <button
                type="button"
                class="eng-chip-btn"
                :class="{ on: materialFilter === 'missing_2d' }"
                @click="materialFilter = 'missing_2d'"
              >
                缺2D
              </button>
              <button
                type="button"
                class="eng-chip-btn"
                :class="{ on: materialFilter === 'field_diff' }"
                @click="materialFilter = 'field_diff'"
              >
                字段差
              </button>
              <button
                type="button"
                class="eng-chip-btn"
                :class="{ on: materialFilter === 'manual' }"
                @click="materialFilter = 'manual'"
              >
                已手工
              </button>
            </div>
            <div class="eng-mat-batch">
              <button type="button" class="eng-tool sm" @click="batchAdopt2d">全部采用 2D</button>
              <button type="button" class="eng-tool sm" @click="batchAdopt3d">全部采用 3D</button>
              <button type="button" class="eng-tool sm" @click="resetMaterialWork">重置</button>
            </div>
          </div>
          <div class="eng-table-wrap eng-mat-table-wrap">
            <table class="eng-review-table eng-mat-table">
              <thead>
                <tr>
                  <th class="col-no">序号</th>
                  <th class="col-st">状态</th>
                  <th class="col-diff">差异</th>
                  <th>3D 材料/色/厚</th>
                  <th>2D 材料/色/厚</th>
                  <th class="col-adopt">采用结果</th>
                  <th class="col-act">处置</th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="row in filteredMaterialRows"
                  :key="'cmp-' + row.noteNo"
                  :data-pending="row.disposition === 'pending' ? '1' : undefined"
                  :class="{
                    bad: row.status !== 'match' && row.disposition === 'pending',
                    warn: row.status !== 'match' && row.disposition !== 'pending',
                    ok: row.status === 'match'
                  }"
                >
                  <td>{{ row.noteNo }}</td>
                  <td>
                    <span class="eng-status-chip" :data-st="row.status">{{ statusLabel(row.status) }}</span>
                  </td>
                  <td>{{ row.diffs.length ? row.diffs.join('、') : '—' }}</td>
                  <td class="eng-ref">
                    <template v-if="row.stack">
                      {{ formatSideTriple(row.stack.material, row.stack.color, row.stack.thickness) }}
                    </template>
                    <template v-else>—</template>
                  </td>
                  <td class="eng-ref">
                    <template v-if="row.dwg">
                      {{
                        formatSideTriple(
                          row.dwg.materialEn || row.dwg.materialZh,
                          row.dwg.color,
                          row.dwg.thickness
                        )
                      }}
                    </template>
                    <template v-else>—</template>
                  </td>
                  <td class="col-adopt">
                    <div class="eng-adopt-fields">
                      <textarea
                        class="eng-inline-input eng-adopt-mat"
                        rows="2"
                        :value="row.adopted.material"
                        :title="row.adopted.material || '材料'"
                        placeholder="材料"
                        @input="onMaterialAdoptedInput(row, 'material', $event)"
                      />
                      <div class="eng-adopt-meta">
                        <input
                          class="eng-inline-input"
                          :value="row.adopted.color"
                          :title="row.adopted.color || '颜色'"
                          placeholder="颜色"
                          @input="onMaterialAdoptedInput(row, 'color', $event)"
                        />
                        <input
                          class="eng-inline-input thin"
                          :value="row.adopted.thickness"
                          :title="row.adopted.thickness || '厚度'"
                          placeholder="厚度"
                          @input="onMaterialAdoptedInput(row, 'thickness', $event)"
                        />
                      </div>
                    </div>
                  </td>
                  <td class="col-act">
                    <div class="eng-disp-btns">
                      <button
                        type="button"
                        class="eng-mini"
                        :class="{ on: row.disposition === 'adopt_3d' }"
                        :disabled="!row.stack"
                        @click="adoptRow3d(row)"
                      >
                        3D
                      </button>
                      <button
                        type="button"
                        class="eng-mini"
                        :class="{ on: row.disposition === 'adopt_2d' }"
                        :disabled="!row.dwg"
                        @click="adoptRow2d(row)"
                      >
                        2D
                      </button>
                      <span class="eng-disp-lab" :data-d="row.disposition">{{
                        dispositionLabel(row.disposition)
                      }}</span>
                    </div>
                  </td>
                </tr>
                <tr v-if="!filteredMaterialRows.length">
                  <td colspan="7" class="eng-empty">
                    {{
                      materialWorkRows.length
                        ? '当前筛选无行'
                        : '暂无可比对材料（请确认 2D 含 MATERIAL 或层次有旁注）'
                    }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="saving" class="eng-busy">
            <div class="eng-busy-head">
              <LoadingOutlined spin />
              <span class="eng-busy-text">{{ busyLabel || '正在确认材料并回写层次…' }}</span>
            </div>
            <a-progress
              class="eng-busy-bar"
              :percent="busyPercent"
              status="active"
              :show-info="false"
              size="small"
            />
            <p class="eng-busy-hint">处理中，请稍候，勿关闭或刷新页面</p>
          </div>
          <div class="eng-cta-row eng-mat-cta">
            <button type="button" class="eng-tool sm" :disabled="saving" @click="goStep(2)">
              返回层次
            </button>
            <button type="button" class="eng-tool sm" :disabled="saving" @click="saveMaterialDraft">
              保存草稿
            </button>
            <button
              type="button"
              class="eng-tool accent"
              :class="{ busy: saving }"
              :disabled="saving"
              @click="confirmMaterials"
            >
              <LoadingOutlined v-if="saving" spin />
              <span>{{ saving ? '确认中…' : '确认材料并进入翻译' }}</span>
            </button>
          </div>
        </div>

        <!-- ④ 翻译审校：NOTE / 材料双 Tab，均确认后可进生成 -->
        <div v-if="step === 4" class="eng-drawer-body eng-trans-body">
          <div class="eng-extract-tabs eng-trans-tabs" role="tablist">
            <button
              type="button"
              class="eng-extract-tab"
              role="tab"
              :class="{ on: translateTab === 'note' }"
              :aria-selected="translateTab === 'note'"
              @click="translateTab = 'note'"
            >
              NOTE（{{ notesTranslateVerifiedCount }}/{{ editNotes.length }}）
            </button>
            <button
              type="button"
              class="eng-extract-tab"
              role="tab"
              :class="{ on: translateTab === 'material' }"
              :aria-selected="translateTab === 'material'"
              @click="translateTab = 'material'"
            >
              材料（{{ materialsReviewVerifiedCount }}/{{ editMaterials.length }}）
            </button>
          </div>

          <div v-show="translateTab === 'note'" class="eng-trans-pane">
            <div class="eng-extract-tools eng-trans-tools">
              <button
                type="button"
                class="eng-extract-confirm-all eng-trans-batch"
                :disabled="
                  !editNotes.length ||
                  batchTranslateBusy ||
                  retranNoteIdx >= 0 ||
                  !editNotes.some((n) => !!(n.original || '').trim() && !(n.translated || '').trim())
                "
                :title="
                  batchTranslateBusy && batchTranslateKind === 'note'
                    ? `正在翻译 ${batchTranslateLabel}`
                    : '将缺译文的 NOTE 全部翻译（已有译文的跳过）'
                "
                @click="translateAllNotes"
              >
                <LoadingOutlined v-if="batchTranslateBusy && batchTranslateKind === 'note'" spin />
                <ThunderboltOutlined v-else />
                <span>
                  {{
                    batchTranslateBusy && batchTranslateKind === 'note'
                      ? `翻译中 ${batchTranslateLabel}`
                      : '一键翻译'
                  }}
                </span>
              </button>
              <button
                type="button"
                class="eng-extract-confirm-all"
                :disabled="!editNotes.length || allNotesTranslateVerified || batchTranslateBusy"
                :title="
                  !editNotes.length
                    ? '暂无 NOTE'
                    : allNotesTranslateVerified
                      ? '已全部确认'
                      : '一键确认全部 NOTE 译文（需二次确认）'
                "
                @click="confirmAllTranslateNotes"
              >
                <CheckOutlined /> 一键确认
              </button>
              <span class="eng-extract-tools-tip">
                先一键/逐条翻译，再审校确认
              </span>
            </div>
            <div class="eng-table-wrap eng-table-wrap--trans">
              <table class="eng-review-table eng-trans-table">
                <thead>
                  <tr>
                    <th class="col-no">编号</th>
                    <th class="col-orig">原文（可改）</th>
                    <th class="col-zh">译文（可改）</th>
                    <th class="col-act">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="(n, idx) in editNotes"
                    :key="'note-' + idx"
                    :data-review-pending="!noteTranslateVerified[idx] ? '1' : undefined"
                    :data-untranslated="n.status === 'untranslated' ? '1' : undefined"
                    :class="{
                      warn: n.status === 'untranslated',
                      verified: !!noteTranslateVerified[idx]
                    }"
                  >
                    <td class="col-no">{{ n.noteNo || '—' }}</td>
                    <td class="col-orig">
                      <textarea
                        class="eng-inline-area eng-note-trans-body"
                        rows="1"
                        :value="n.original || ''"
                        placeholder="填写 NOTE 原文"
                        @input="onNoteOriginalInput(idx, $event)"
                      />
                    </td>
                    <td class="col-zh">
                      <textarea
                        class="eng-inline-area eng-note-trans-body"
                        rows="1"
                        :value="n.translated"
                        placeholder="填写中文译文"
                        @input="onNoteTranslatedInput(idx, $event)"
                      />
                    </td>
                    <td class="col-act">
                      <div class="eng-act-btns">
                        <button
                          type="button"
                          class="eng-note-verify ghost"
                          :disabled="batchTranslateBusy || retranNoteIdx >= 0"
                          @click="retranNote(idx)"
                        >
                          <LoadingOutlined v-if="retranNoteIdx === idx" spin />
                          <ReloadOutlined v-else />
                          <span>
                            {{
                              retranNoteIdx === idx
                                ? noteHasZh(n)
                                  ? '重译中'
                                  : '翻译中'
                                : noteHasZh(n)
                                  ? '重新翻译'
                                  : '翻译'
                            }}
                          </span>
                        </button>
                        <button
                          type="button"
                          class="eng-note-verify"
                          :class="{ on: !!noteTranslateVerified[idx] }"
                          :disabled="batchTranslateBusy || retranNoteIdx === idx"
                          @click="toggleNoteTranslateVerified(idx)"
                        >
                          <CheckOutlined v-if="noteTranslateVerified[idx]" />
                          <span>{{ noteTranslateVerified[idx] ? '已确认' : '确认' }}</span>
                        </button>
                      </div>
                    </td>
                  </tr>
                  <tr v-if="!editNotes.length">
                    <td colspan="4" class="eng-empty">未抽到 NOTE</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div v-show="translateTab === 'material'" class="eng-trans-pane">
            <div class="eng-extract-tools eng-trans-tools">
              <button
                type="button"
                class="eng-extract-confirm-all eng-trans-batch"
                :disabled="
                  !editMaterials.length ||
                  batchTranslateBusy ||
                  retranMatIdx >= 0 ||
                  !editMaterials.some(
                    (m) => !!(m.materialEn || '').trim() && !materialHasZh(m)
                  )
                "
                :title="
                  batchTranslateBusy && batchTranslateKind === 'material'
                    ? `正在翻译 ${batchTranslateLabel}`
                    : '将缺中文、或中英相同（原文非中文）的材料全部翻译（已有真中文/原文已是中文的跳过）'
                "
                @click="translateAllMaterials"
              >
                <LoadingOutlined v-if="batchTranslateBusy && batchTranslateKind === 'material'" spin />
                <ThunderboltOutlined v-else />
                <span>
                  {{
                    batchTranslateBusy && batchTranslateKind === 'material'
                      ? `翻译中 ${batchTranslateLabel}`
                      : '一键翻译'
                  }}
                </span>
              </button>
              <button
                type="button"
                class="eng-extract-confirm-all"
                :disabled="
                  !editMaterials.length ||
                  allMaterialsReviewVerified ||
                  batchTranslateBusy ||
                  editMaterials.some((m) => materialNeedsTranslate(m))
                "
                :title="
                  !editMaterials.length
                    ? '暂无材料'
                    : allMaterialsReviewVerified
                      ? '已全部确认'
                      : editMaterials.some((m) => materialNeedsTranslate(m))
                        ? '请先一键/逐条翻译后再确认'
                        : '一键确认全部材料（需二次确认）'
                "
                @click="confirmAllTranslateMaterials"
              >
                <CheckOutlined /> 一键确认
              </button>
              <span class="eng-extract-tools-tip">
                先一键/逐条翻译，再审校确认
              </span>
            </div>
            <div class="eng-table-wrap eng-table-wrap--trans">
              <table class="eng-review-table eng-trans-table eng-trans-mat-table">
                <thead>
                  <tr>
                    <th class="col-seq">序号</th>
                    <th class="col-en">英文/原文</th>
                    <th class="col-zh">中文名</th>
                    <th class="col-thk">厚度</th>
                    <th class="col-color">颜色</th>
                    <th class="col-act">操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr
                    v-for="(m, idx) in editMaterials"
                    :key="'mat-' + idx"
                    :data-review-pending="!materialReviewVerified[idx] ? '1' : undefined"
                    :data-untranslated="materialNeedsTranslate(m) ? '1' : undefined"
                    :class="{ verified: !!materialReviewVerified[idx] }"
                  >
                    <td class="col-seq">{{ m.noteNo || m.seq }}</td>
                    <td class="col-en">
                      <input
                        class="eng-inline-input"
                        :value="m.materialEn || ''"
                        @input="onMaterialFieldInput(idx, 'materialEn', $event)"
                      />
                    </td>
                    <td class="col-zh">
                      <input
                        class="eng-inline-input"
                        :value="m.materialZh || ''"
                        @input="onMaterialFieldInput(idx, 'materialZh', $event)"
                      />
                    </td>
                    <td class="col-thk">
                      <input
                        class="eng-inline-input thin"
                        :value="m.thickness || ''"
                        @input="onMaterialFieldInput(idx, 'thickness', $event)"
                      />
                    </td>
                    <td class="col-color">
                      <input
                        class="eng-inline-input thin"
                        :value="m.color || ''"
                        @input="onMaterialFieldInput(idx, 'color', $event)"
                      />
                    </td>
                    <td class="col-act">
                      <div class="eng-act-btns">
                        <button
                          type="button"
                          class="eng-note-verify ghost"
                          :disabled="batchTranslateBusy || retranMatIdx >= 0"
                          @click="retranMaterial(idx)"
                        >
                          <LoadingOutlined v-if="retranMatIdx === idx" spin />
                          <ReloadOutlined v-else />
                          <span>
                            {{
                              retranMatIdx === idx
                                ? materialHasZh(m)
                                  ? '重译中'
                                  : '翻译中'
                                : materialHasZh(m)
                                  ? '重新翻译'
                                  : '翻译'
                            }}
                          </span>
                        </button>
                        <button
                          type="button"
                          class="eng-note-verify"
                          :class="{ on: !!materialReviewVerified[idx] }"
                          :disabled="batchTranslateBusy || retranMatIdx === idx"
                          @click="toggleMaterialReviewVerified(idx)"
                        >
                          <CheckOutlined v-if="materialReviewVerified[idx]" />
                          <span>{{ materialReviewVerified[idx] ? '已确认' : '确认' }}</span>
                        </button>
                      </div>
                    </td>
                  </tr>
                  <tr v-if="!editMaterials.length">
                    <td colspan="6" class="eng-empty">未抽到材料行</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>

          <div class="eng-cta-row eng-trans-cta">
            <button
              type="button"
              class="eng-tool accent lg"
              :disabled="composeEntering"
              @click="confirmNotes"
            >
              {{ composeEntering ? '整理预览中…' : '确认译文并进入生成' }}
            </button>
          </div>
        </div>

        <!-- ⑤ 生成：操作栏；产物全量在左栏 ComposeArtifactBoard -->
        <div v-if="step === 5" class="eng-drawer-body">
          <div class="eng-meta-card">
            <div class="eng-meta-row">
              <span>层次资产</span>
              <strong>{{
                stageContentPending
                  ? '整理中…'
                  : stackAsset?.title || (stackSkipped ? '已跳过（无三维层次）' : '—')
              }}</strong>
            </div>
            <div class="eng-meta-row">
              <span>客户 DWG</span>
              <strong>{{
                stageContentPending ? '整理中…' : dwgFile?.name || '—'
              }}</strong>
            </div>
            <div class="eng-meta-row">
              <span>材料/译文</span>
              <strong>{{ stageContentPending ? '整理中…' : '已确认' }}</strong>
            </div>
          </div>

          <p class="eng-gen-tip">
            {{
              stageContentPending
                ? '左侧产物整理中，请稍候…'
                : '左侧可切换查看①～④全部产物；确认无误后再生成。'
            }}
          </p>

          <div class="eng-cta-row">
            <a-tooltip
              :title="
                stageContentPending
                  ? '产物整理中，请稍候'
                  : taskCompleted
                    ? '任务已完成，请先到结果页或列表点「编辑」再生成'
                    : !canComposeSources
                      ? '请上传客户 DWG 或完成三维层次（二维与三维至少保留一个）'
                      : !hasDwgForCompose && hasStackForCompose
                        ? '仅三维层次合成（不含客户二维 content）'
                        : stackSkipped && !stackAsset
                          ? '以已审中文 NOTE/材料合成（不含三维层次）'
                          : '以已审中文 NOTE/材料合成；层次覆盖爆炸槽'
              "
            >
              <button
                type="button"
                class="eng-tool accent lg"
                :disabled="
                  taskCompleted ||
                  !canStep5 ||
                  composing ||
                  !canComposeSources ||
                  stageContentPending
                "
                @click="runCompose"
              >
                <LoadingOutlined v-if="composing" spin />
                <ThunderboltOutlined v-else />
                <span>{{ composing ? '生成中…' : '生成工程图' }}</span>
              </button>
            </a-tooltip>
          </div>
          <div v-if="composing" class="eng-busy">
            <div class="eng-busy-head">
              <LoadingOutlined spin />
              <span class="eng-busy-text">{{ busyLabel || '正在生成工程图…' }}</span>
              <span class="eng-compose-elapsed" title="生成已用时（离页回来自动续跟）"
                >已用时 {{ composeElapsedText }}</span
              >
            </div>
            <a-progress
              class="eng-busy-bar"
              :percent="composeDisplayPercent"
              status="active"
              :show-info="true"
              size="small"
            />
            <p class="eng-busy-hint">
              工程图生成在服务端排队执行（多人共用处理通道），可离开页面；回来后自动续跟。请看已用时与下方过程日志。
            </p>
            <div v-if="composeLogs.length" class="eng-compose-log" aria-label="过程日志">
              <div class="eng-compose-log-title">过程日志</div>
              <pre class="eng-compose-log-body">{{ composeLogs.join('\n') }}</pre>
            </div>
          </div>
          <div
            v-else-if="composeLogs.length && !result"
            class="eng-compose-log eng-compose-log--idle"
            aria-label="上次生成日志"
          >
            <div class="eng-compose-log-title">上次生成日志</div>
            <pre class="eng-compose-log-body">{{ composeLogs.join('\n') }}</pre>
          </div>
          <div class="eng-cta-row secondary">
            <a-tooltip :title="resultHasDxf(result) ? '下载 DXF' : '请先生成工程图'">
              <button type="button" class="eng-tool" :disabled="!resultHasDxf(result) || dxfDownloading" @click="downloadDxf">
                <DownloadOutlined />
                <span>DXF</span>
              </button>
            </a-tooltip>
          </div>

          <a-alert
            v-if="annotPersistFailed && result"
            type="error"
            show-icon
            style="margin-bottom: 10px"
            message="审校结果未保存"
          >
            <template #description>
              <div>
                <p style="margin: 0 0 8px">完成任务前须先保存。请点「再存一次」，成功后再点「完成任务」。</p>
                <a-button size="small" type="primary" @click="retryPersistAnnot">再存一次</a-button>
              </div>
            </template>
          </a-alert>
          <a-alert
            v-else-if="composePartial && result"
            type="warning"
            show-icon
            style="margin-bottom: 10px"
            message="层次可能未放入工程图"
            description="可回②重存层次后重新生成；或先下载核对，确认无误后再完成任务。"
          />

          <div v-if="result && !taskCompleted" class="eng-cta-row">
            <button
              type="button"
              class="eng-tool accent lg"
              :disabled="finalizing || annotPersistFailed"
              @click="finalizeComposedTask"
            >
              <LoadingOutlined v-if="finalizing" spin />
              <span>{{
                finalizing
                  ? '提交中…'
                  : pendingFinalize
                    ? '完成任务并进入结果页'
                    : '重新提交完成任务'
              }}</span>
            </button>
          </div>

          <p v-if="result && taskCompleted" class="eng-ok-line">
            <CheckOutlined /> 任务已完成 · 可下载 DXF 或到结果页查看
          </p>
          <p v-else-if="result && !composePartial" class="eng-ok-line">
            <CheckOutlined /> {{ humanizeComposeMessage(result.message || '工程图已生成') }}
          </p>
          <p v-else-if="result && composePartial" class="eng-warn-line">
            {{ humanizeComposeMessage(result.message || '') }}
          </p>
          <p v-for="(w, i) in result?.warnings || []" :key="'ew-' + i" class="eng-warn-line">
            {{ humanizeComposeMessage(w) }}
          </p>
        </div>
      </aside>
      </div>
    </Teleport>

    <a-modal
      v-model:open="regionModalOpen"
      :title="regionKind === 'material' ? '截图抽取 · 材料' : '截图抽取 · NOTE'"
      :width="1080"
      wrap-class-name="eng-region-modal"
      :mask-closable="false"
      ok-text="写入表格"
      cancel-text="取消"
      centered
      destroy-on-close
      @ok="commitRegionPicks"
      @cancel="closeRegionModal"
    >
      <p v-if="regionWarnings.length" class="eng-region-warn">
        {{ regionWarnings.map((w) => humanizeUserTip(w)).join('；') }}
      </p>
      <p class="eng-hint" style="margin-bottom: 8px">
        勾选要写入的行
        <template v-if="regionHasWriteModeChoice">
          ；同序号可选「追加」或「覆盖」。覆盖已确认行会要求二次确认
        </template>
        。
      </p>
      <div v-if="regionPickRows.length" class="eng-region-table-wrap">
        <table class="eng-region-table">
          <thead>
            <tr>
              <th class="col-check">选</th>
              <th class="col-no">序</th>
              <th>{{ regionKind === 'material' ? '材料英文' : '原文' }}</th>
              <th v-if="regionKind === 'material'" class="col-thick">厚度</th>
              <th v-if="regionKind === 'material'" class="col-color">颜色</th>
              <th v-if="regionHasWriteModeChoice" class="col-mode">写入</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in regionPickRows" :key="row.key">
              <td class="col-check">
                <input v-model="row.checked" type="checkbox" />
              </td>
              <td class="col-no">
                <input
                  v-model="row.noteNo"
                  class="eng-region-no-input"
                  type="text"
                  maxlength="4"
                  title="可改序号"
                  @change="onRegionNoteNoChange(row)"
                />
              </td>
              <td class="col-body">
                <textarea
                  v-if="regionKind === 'note'"
                  v-model="row.original"
                  class="eng-region-body-input"
                  :rows="regionBodyRows(row.original)"
                  title="可改原文"
                />
                <template v-else>{{ row.materialEn }}</template>
              </td>
              <td v-if="regionKind === 'material'" class="col-thick">{{ row.thickness }}</td>
              <td v-if="regionKind === 'material'" class="col-color">{{ row.color }}</td>
              <td v-if="regionHasWriteModeChoice" class="col-mode">
                <select v-if="row.existIdx >= 0" v-model="row.mode">
                  <option value="overwrite">
                    覆盖{{ row.existVerified ? '（已确认）' : '' }}
                  </option>
                  <option value="append">追加</option>
                </select>
                <span v-else class="eng-muted">追加</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="eng-hint">未抽出结构化行</p>
      <details v-if="regionOcrText" class="eng-region-ocr">
        <summary>识别原文预览</summary>
        <pre>{{ regionOcrText }}</pre>
      </details>
    </a-modal>

    <!-- 截图识别：全屏遮罩，明显提示且禁止操作整页（含左侧看图） -->
    <Teleport to="body">
      <div
        v-if="regionRecognizing"
        class="eng-region-mask"
        role="alertdialog"
        aria-modal="true"
        aria-busy="true"
        aria-label="正在识别截图文字"
        @keydown.prevent
      >
        <div class="eng-region-mask-card">
          <LoadingOutlined spin class="eng-region-mask-spin" />
          <div class="eng-region-mask-title">正在识别截图文字…</div>
          <p class="eng-region-mask-hint">请稍候，识别完成前页面暂不可操作</p>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.eng-wiz.chrome {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 1100;
  background: var(--jy-sidebar, #001529);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.12);
  pointer-events: auto;
}
.eng-wiz.chrome.embedded {
  position: relative;
  top: auto;
  left: auto;
  right: auto;
  z-index: 5;
  flex-shrink: 0;
}
.eng-wiz.panel {
  position: fixed;
  inset: 0;
  z-index: 1100;
  pointer-events: none;
}
/* 嵌入任务：顶栏占位；操作栏 Teleport 到右栏，禁止 absolute 盖住三维 */
.eng-wiz.panel.embedded {
  position: relative;
  inset: auto;
  z-index: 5;
  flex-shrink: 0;
  pointer-events: auto;
}
.eng-wiz.panel .eng-bar {
  pointer-events: auto;
  background: var(--jy-sidebar, #001529);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.eng-wiz.panel.embedded .eng-bar {
  border-radius: 0;
}

.eng-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 10px;
  min-height: 36px;
}
.eng-brand {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
  max-width: 36%;
  min-width: 0;
}
.eng-task-title {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  line-height: 20px;
  color: rgba(255, 255, 255, 0.55);
}
.eng-brand h2 {
  margin: 0;
  font-size: 13px;
  font-weight: 650;
  letter-spacing: 0.02em;
  color: #fff;
  line-height: 20px;
  flex-shrink: 0;
}
.eng-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: rgba(255, 255, 255, 0.65);
  cursor: pointer;
  font-size: 13px;
}
.eng-icon-btn:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}
.eng-icon-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.eng-icon-btn.close:hover {
  color: #ffa39e;
}

.eng-steps {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 1;
  min-width: 0;
  justify-content: center;
}
.eng-step-wrap {
  display: inline-flex;
}
.eng-step {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  border: 0;
  background: transparent;
  color: rgba(255, 255, 255, 0.55);
  cursor: pointer;
  padding: 2px 5px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 16px;
}
.eng-step:hover:not(:disabled) {
  color: #fff;
  background: rgba(255, 255, 255, 0.1);
}
.eng-step.on {
  color: #fff;
  background: rgba(22, 119, 255, 0.28);
  font-weight: 600;
}
.eng-step.done:not(.on) {
  color: #91caff;
}
.eng-step:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.eng-step-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  font-size: 10px;
  line-height: 1;
  background: rgba(255, 255, 255, 0.16);
  color: inherit;
}
.eng-step.on .eng-step-no,
.eng-step.done .eng-step-no {
  background: var(--jy-primary, #1677ff);
  color: #fff;
}
.eng-step-sep {
  width: 10px;
  height: 1px;
  background: rgba(255, 255, 255, 0.2);
  flex-shrink: 0;
}
.eng-bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.eng-chip {
  font-size: 11px;
  line-height: 18px;
  padding: 2px 8px;
  border-radius: 999px;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.eng-chip.muted {
  background: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.65);
}
.eng-chip.warn {
  background: rgba(245, 166, 35, 0.22);
  color: var(--jy-accent, #f5a623);
}
.eng-chip.ok {
  background: rgba(74, 222, 128, 0.18);
  color: var(--jy-ok, #4ade80);
}
.eng-chip.busy {
  background: rgba(22, 119, 255, 0.28);
  color: #91caff;
}
.eng-chip.file {
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.7);
}

.eng-step-switch {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  flex-shrink: 0;
  height: 28px;
  padding: 0 12px;
  background: #e6f4ff;
  border-bottom: 1px solid #91caff;
  color: #0958d9;
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
}
.eng-step-switch :deep(.anticon) {
  font-size: 13px;
  color: var(--jy-primary, #1677ff);
}
.eng-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  padding: 4px 10px 6px;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(0, 0, 0, 0.22);
}
.eng-tool {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 8px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  font-size: 12px;
  line-height: 20px;
  cursor: pointer;
  outline: none;
}
.eng-tool:focus-visible {
  box-shadow: 0 0 0 2px rgba(22, 119, 255, 0.45);
}
.eng-tool:hover:not(:disabled) {
  border-color: var(--jy-primary, #1677ff);
  background: rgba(22, 119, 255, 0.35);
}
.eng-tool:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.eng-tool.primary {
  background: var(--jy-primary, #1677ff);
  border-color: transparent;
  color: #fff;
  font-weight: 650;
}
.eng-tool.accent {
  background: var(--jy-primary, #1677ff);
  border-color: transparent;
  color: #fff;
}
.eng-tool.lg {
  height: 40px;
  padding: 0 18px;
  font-size: 14px;
  width: 100%;
  justify-content: center;
}
.eng-tool.sm {
  height: 24px;
  padding: 0 8px;
  font-size: 12px;
  width: auto;
}

.eng-recent {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
  min-width: 0;
}
.eng-recent-lab {
  font-size: 11px;
  line-height: 18px;
  color: rgba(255, 255, 255, 0.45);
  flex-shrink: 0;
}
.eng-recent-btn {
  border: 1px solid rgba(255, 255, 255, 0.18);
  background: transparent;
  color: rgba(255, 255, 255, 0.65);
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 11px;
  line-height: 18px;
  cursor: pointer;
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.eng-recent-btn:hover {
  color: #fff;
  border-color: var(--jy-primary, #1677ff);
}

.eng-drawer-mask {
  position: absolute;
  inset: 0;
  top: 48px;
  background: rgba(0, 21, 41, 0.45);
  display: flex;
  justify-content: flex-end;
  pointer-events: auto;
}
.eng-side-root {
  flex: 1 1 0%;
  min-height: 0;
  max-height: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  pointer-events: auto;
}
.eng-side-panel {
  flex: 1 1 0%;
  min-height: 0;
  max-height: 100%;
  width: 100%;
  height: 100%;
  background: #fff;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.eng-side-panel.wide {
  /* 宽度由宿主 .eng-side-host.wide 控制 */
  width: 100%;
}
.eng-drawer {
  width: min(420px, 100%);
  height: 100%;
  background: #fff;
  border-left: 1px solid var(--jy-border, #d0d5dd);
  box-shadow: -8px 0 24px rgba(15, 23, 42, 0.12);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  animation: eng-slide 0.2s ease-out;
}
.eng-drawer.wide {
  width: min(480px, 100%);
}
@keyframes eng-slide {
  from {
    transform: translateX(12px);
    opacity: 0.6;
  }
  to {
    transform: translateX(0);
    opacity: 1;
  }
}
.eng-drawer-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  flex-shrink: 0;
  padding: 16px 18px 12px;
  border-bottom: 1px solid var(--jy-border, #d0d5dd);
  background: #fff;
}
.eng-drawer-head--compact {
  padding: 8px 12px 6px;
  align-items: center;
  gap: 8px;
}
.eng-drawer-head-main {
  min-width: 0;
  flex: 1;
}
.eng-drawer-head-actions {
  flex-shrink: 0;
  display: grid;
  /* max-content：单钮（如「停止」）不占半宽；多钮时同列等宽对齐 */
  grid-template-columns: repeat(2, max-content);
  gap: 4px;
  justify-content: end;
  max-width: 58%;
}
/* a-upload 多层包裹：占满格子，内部按钮与同列等宽 */
.eng-drawer-head-actions :deep(.ant-upload-wrapper),
.eng-drawer-head-actions :deep(.ant-upload) {
  display: block;
  width: 100%;
}
.eng-drawer-head-actions .eng-tool {
  height: 26px;
  padding: 0 8px;
  font-size: 12px;
  width: 100%;
  box-sizing: border-box;
  justify-content: center;
}
.eng-drawer-title-row {
  display: flex;
  align-items: baseline;
  gap: 10px 14px;
  flex-wrap: wrap;
  min-width: 0;
}
.eng-drawer-head h3 {
  margin: 0;
  font-size: 14px;
  line-height: 22px;
  color: var(--jy-text-strong, #0f172a);
  flex-shrink: 0;
}
.eng-drawer-hint-inline {
  margin: 0;
  font-size: 11px;
  line-height: 16px;
  color: #64748b;
  font-weight: 600;
  min-width: 0;
  flex: 1;
}
.eng-drawer-hint-inline .ok {
  color: #166534;
}
.eng-drawer-hint-inline .bad {
  color: #9f1239;
}
.eng-drawer-hint-inline .warn {
  color: #b45309;
}
.eng-drawer-sub {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 20px;
  color: #64748b;
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.eng-drawer-head .eng-icon-btn {
  color: #64748b;
}
.eng-drawer-head .eng-icon-btn:hover:not(:disabled) {
  background: var(--jy-header-bg, #eef1f6);
  color: var(--jy-text-strong, #0f172a);
}
.eng-drawer-head .eng-icon-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
/*
 * height:0 + flex:1 = 经典「flex 子项可滚动」写法：
 * 先按 0 高参与分配，再吃满剩余，内容溢出才出现滚动条。
 */
.eng-drawer-body {
  flex: 1 1 0%;
  height: 0;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: scroll;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: #64748b #e2e8f0;
  padding: 16px 18px 24px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.eng-drawer-body::-webkit-scrollbar {
  width: 10px;
}
.eng-drawer-body::-webkit-scrollbar-track {
  background: #e2e8f0;
  border-radius: 4px;
}
.eng-drawer-body::-webkit-scrollbar-thumb {
  background: #64748b;
  border-radius: 4px;
}
.eng-drawer-body::-webkit-scrollbar-thumb:hover {
  background: #475569;
}
.eng-drawer-body.eng-extract-body {
  gap: 6px;
  padding-bottom: 10px;
  font-size: 11px;
}
.eng-drawer-body.eng-extract-body .eng-drawer-head-actions .eng-tool {
  font-size: 11px;
  height: 24px;
  padding: 0 8px;
}
.eng-extract-chooser {
  flex-shrink: 0;
  margin-top: 0;
  padding: 5px 6px;
  border: 1px solid #91caff;
  border-radius: 6px;
  background: #f0f7ff;
}
.eng-extract-chooser-actions {
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  align-items: stretch;
  gap: 6px;
}
/* 覆盖 .eng-tool.lg 的 width:100%，强制左右并排 */
.eng-extract-chooser-actions .eng-chooser-btn {
  flex: 1 1 0;
  width: auto !important;
  min-width: 0;
  height: 28px;
  min-height: 28px;
  padding: 0 8px;
  justify-content: center;
  font-size: 11px;
  font-weight: 650;
}
.eng-extract-chooser-actions .eng-chooser-btn.accent,
.eng-extract-chooser-actions .eng-chooser-btn.accent.busy:disabled {
  background: var(--jy-primary, #1677ff) !important;
  color: #fff !important;
  border-color: transparent !important;
  opacity: 1;
}
.eng-extract-chooser-actions .eng-chooser-btn.accent span,
.eng-extract-chooser-actions .eng-chooser-btn.accent :deep(.anticon),
.eng-extract-chooser-actions .eng-chooser-btn.accent.busy:disabled span,
.eng-extract-chooser-actions .eng-chooser-btn.accent.busy:disabled :deep(.anticon) {
  color: #fff !important;
  margin-bottom: 0;
}
.eng-extract-chooser-actions .eng-chooser-btn--outline {
  background: #fff !important;
  color: #1677ff !important;
  border: 1px solid #1677ff !important;
  font-weight: 650;
}
.eng-extract-chooser-actions .eng-chooser-btn--outline span,
.eng-extract-chooser-actions .eng-chooser-btn--outline :deep(.anticon) {
  color: #1677ff !important;
}
.eng-extract-chooser-actions .eng-chooser-btn--outline:hover:not(:disabled) {
  background: #e6f4ff !important;
  color: #0b5fff !important;
}
.eng-extract-chooser-actions .eng-chooser-btn--outline:disabled {
  color: #475569 !important;
  border-color: #cbd5e1 !important;
  background: #e2e8f0 !important;
}
.eng-extract-chooser-actions .eng-chooser-btn--outline:disabled span,
.eng-extract-chooser-actions .eng-chooser-btn--outline:disabled :deep(.anticon) {
  color: #475569 !important;
}
.eng-extract-summary {
  flex-shrink: 0;
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  line-height: 16px;
  color: #64748b;
}
.eng-extract-summary strong {
  color: var(--jy-text-strong, #0f172a);
  font-weight: 700;
}
.eng-extract-help-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: 2px;
  height: 20px;
  padding: 0 6px;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 4px;
  background: #fff;
  color: #64748b;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
}
.eng-extract-help-btn:hover {
  color: var(--jy-primary, #1677ff);
  border-color: #91caff;
  background: #f0f5ff;
}
.eng-extract-help-btn.warn {
  color: #ad6800;
  border-color: #ffd666;
  background: #fffbe6;
}
.eng-extract-help-btn.warn:hover {
  color: #ad6800;
  border-color: #f5a623;
  background: #fff7e0;
}
.eng-extract-tabs {
  flex-shrink: 0;
  display: flex;
  gap: 2px;
  padding: 2px;
  border-radius: 6px;
  background: #f1f5f9;
}
.eng-extract-tab {
  flex: 1;
  height: 24px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  font-size: 11px;
  line-height: 16px;
  font-weight: 600;
  color: #64748b;
  cursor: pointer;
}
.eng-extract-tab:hover {
  color: var(--jy-primary, #1677ff);
}
.eng-extract-tab.on {
  background: #fff;
  color: var(--jy-primary, #1677ff);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
}
.eng-extract-scroll {
  /* 内容随整栏一起滚，自身不再做 flex 滚动视口 */
  flex: 0 0 auto;
  overflow: visible;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-bottom: 4px;
}
.eng-extract-sec {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
}
.eng-extract-tools {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.eng-extract-add {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 8px;
  border: 1px solid #91caff;
  border-radius: 4px;
  background: #e6f4ff;
  color: #1677ff;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
}
.eng-extract-add:hover {
  border-color: #1677ff;
  background: #bae0ff;
}
.eng-extract-confirm-all {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 8px;
  border: 1px solid #86efac;
  border-radius: 4px;
  background: #f0fdf4;
  color: #15803d;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
}
.eng-extract-confirm-all:hover:not(:disabled) {
  border-color: #22c55e;
  background: #dcfce7;
}
.eng-extract-confirm-all:disabled {
  /* 禁用也要可辨认：勿白字/过淡绿 */
  opacity: 1;
  cursor: not-allowed;
  color: #64748b;
  border-color: #cbd5e1;
  background: #f1f5f9;
}
.eng-extract-confirm-all:disabled :deep(.anticon) {
  color: #64748b;
}
.eng-trans-tools {
  flex-shrink: 0;
  margin-bottom: 0;
}
/* 一键翻译：描边主色，与绿色「一键确认」区分；遵守按钮对比度 */
.eng-trans-batch {
  background: #fff !important;
  color: #1677ff !important;
  border-color: #1677ff !important;
}
.eng-trans-batch:hover:not(:disabled) {
  background: #e6f4ff !important;
  border-color: #1677ff !important;
  color: #1677ff !important;
}
.eng-trans-batch:not(:disabled) :deep(.anticon),
.eng-trans-batch:not(:disabled) span {
  color: #1677ff !important;
}
.eng-trans-batch:disabled {
  background: #e2e8f0 !important;
  color: #475569 !important;
  border-color: #cbd5e1 !important;
}
.eng-trans-batch:disabled :deep(.anticon),
.eng-trans-batch:disabled span {
  color: #475569 !important;
}
.eng-trans-pane {
  display: flex;
  flex-direction: column;
  flex: 1 1 0%;
  min-height: 180px;
  gap: 8px;
  overflow: hidden;
}
.eng-extract-tools-tip {
  font-size: 10px;
  color: #64748b;
}
.eng-note-item-actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}
.eng-note-no-wrap {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
.eng-note-no-lab {
  font-size: 10px;
  font-weight: 600;
  color: #64748b;
}
.eng-note-no-input {
  width: 48px;
  height: 20px;
  padding: 0 4px;
  border: 1px solid #d0d5dd;
  border-radius: 4px;
  background: #fff;
  font-size: 11px;
  font-weight: 700;
  color: var(--jy-primary, #1677ff);
  line-height: 18px;
}
.eng-note-no-input--cell {
  width: 100%;
  max-width: 48px;
  height: 24px;
  text-align: center;
}
.eng-note-item.verified .eng-note-no-input {
  color: #15803d;
  border-color: #86efac;
  background: #f0fdf4;
}
.eng-note-del {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 1px solid #fecaca;
  border-radius: 4px;
  background: #fff;
  color: #b91c1c;
  cursor: pointer;
}
.eng-note-del:hover {
  background: #fef2f2;
  border-color: #f87171;
}
.eng-extract-foot {
  flex-shrink: 0;
  margin-top: 4px;
  padding: 10px 0 6px;
  border-top: 1px solid var(--jy-border, #d0d5dd);
  background: #fff;
}
.eng-extract-foot .eng-tool.lg {
  height: 32px;
  min-height: 32px;
  font-size: 12px;
}
/* NOTE 按序号逐条 */
.eng-note-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.eng-note-item {
  margin: 0;
  padding: 6px 8px;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 6px;
  background: #fff;
}
.eng-note-item.verified {
  border-color: #86efac;
  background: #f0fdf4;
}
.eng-note-item-hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}
.eng-note-verify {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 22px;
  padding: 0 8px;
  border: 1px solid #d0d5dd;
  border-radius: 4px;
  background: #fff;
  font-size: 11px;
  line-height: 16px;
  font-weight: 600;
  color: #64748b;
  cursor: pointer;
}
.eng-note-verify:hover {
  border-color: #4ade80;
  color: #15803d;
}
.eng-note-verify.on {
  border-color: #4ade80;
  background: #4ade80;
  color: #000;
  font-weight: 800;
}
.eng-note-item-body {
  margin: 0;
  min-height: 56px;
  max-height: none;
  resize: none;
  font-size: 11px;
  line-height: 1.55;
  font-weight: 500;
  color: var(--jy-text-strong, #0f172a);
  border-color: #e2e8f0;
  background: #f8fafc;
}
.eng-note-item.verified .eng-note-item-body {
  color: #14532d;
  font-weight: 600;
  border-color: #86efac;
  background: #dcfce7;
}
.eng-note-block {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 8px;
  background: #f8fafc;
  font-size: 11px;
  line-height: 1.65;
  font-weight: 500;
  color: var(--jy-text-strong, #0f172a);
  white-space: pre-wrap;
  word-break: break-word;
  font-family: inherit;
  max-height: none;
}
/* 材料表：更小字号 + 紧凑 padding，尽量单行展示 */
.eng-review-table--dense {
  font-size: 10px;
  line-height: 14px;
}
.eng-review-table--dense th,
.eng-review-table--dense td {
  padding: 3px 4px;
  vertical-align: top;
  white-space: normal;
  word-break: break-word;
}
.eng-review-table--dense thead th {
  white-space: nowrap;
  font-size: 10px;
  font-weight: 700;
}
.eng-review-table--dense .eng-inline-input {
  height: 20px;
  font-size: 10px;
  font-weight: 550;
  padding: 0 3px;
}
.eng-inline-area {
  display: block;
  width: 100%;
  min-height: 32px;
  max-height: 88px;
  resize: vertical;
  border: 1px solid #d0d5dd;
  border-radius: 4px;
  padding: 2px 4px;
  font-size: 10px;
  line-height: 1.4;
  font-weight: 550;
  color: #0f172a;
  background: #fff;
  word-break: break-word;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  font-family: inherit;
  box-sizing: border-box;
}
.eng-inline-area:focus {
  outline: none;
  border-color: var(--jy-primary, #1677ff);
}
/* 须压过 .eng-inline-area 的 max-height:88px，保证 NOTE 全文展开 */
.eng-inline-area.eng-note-item-body,
.eng-inline-area.eng-note-trans-body {
  min-height: 56px;
  max-height: none;
  height: auto;
  overflow: hidden;
  resize: none;
  field-sizing: content;
}
.eng-inline-area--cell {
  min-height: 28px;
  max-height: 56px;
  font-size: 10px;
}
.eng-trans-body {
  gap: 6px;
  padding: 8px 12px 12px;
  /* 表头 sticky 由翻译表 wrap 承接滚动，避免与侧栏双滚动条 */
  overflow: hidden;
}
.eng-trans-tabs {
  flex-shrink: 0;
  width: fit-content;
  max-width: 100%;
  align-self: flex-start;
  gap: 2px;
  padding: 2px;
}
.eng-trans-tabs .eng-extract-tab {
  flex: 0 0 auto;
  height: 24px;
  padding: 0 10px;
  font-size: 12px;
}
.eng-trans-cta {
  flex-shrink: 0;
  margin-top: auto;
  justify-content: flex-end;
}
.eng-trans-cta .eng-tool.lg {
  width: auto;
  min-width: 160px;
  height: 32px;
  padding: 0 14px;
  font-size: 13px;
}
.eng-trans-table {
  table-layout: fixed;
  width: 100%;
  /* collapse 会导致 sticky 表头被行内容盖住/透出 */
  border-collapse: separate;
  border-spacing: 0;
}
.eng-trans-table thead th {
  position: sticky;
  top: 0;
  z-index: 3;
  background: var(--jy-header-bg, #eef1f6);
  box-shadow: inset 0 -1px 0 #d0d5dd;
}
.eng-trans-table .col-no {
  width: 48px;
  vertical-align: top;
  font-weight: 700;
}
.eng-trans-table .col-orig {
  width: 42%;
  vertical-align: top;
}
.eng-trans-table .col-zh {
  width: 40%;
  vertical-align: top;
}
.eng-trans-table .col-act {
  width: 168px;
  text-align: center;
  vertical-align: middle;
}
.eng-trans-table tr.verified td {
  background: #f0fdf4;
}
.eng-act-btns {
  display: flex;
  flex-direction: row;
  flex-wrap: nowrap;
  align-items: center;
  justify-content: center;
  gap: 4px;
}
.eng-act-btns .eng-note-verify {
  justify-content: center;
  width: auto;
  min-width: 0;
  height: 22px;
  padding: 0 6px;
  font-size: 11px;
  white-space: nowrap;
}
.eng-note-verify.ghost {
  border-color: #91caff;
  color: var(--jy-primary, #1677ff);
  background: #fff;
}
.eng-note-verify.ghost:hover:not(:disabled) {
  border-color: var(--jy-primary, #1677ff);
  background: #e6f4ff;
  color: var(--jy-primary, #1677ff);
}
.eng-note-verify:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.eng-trans-mat-table .col-seq {
  width: 52px;
  font-weight: 700;
  vertical-align: middle;
}
.eng-trans-mat-table .col-en {
  width: 28%;
  vertical-align: middle;
}
.eng-trans-mat-table .col-zh {
  width: 24%;
  vertical-align: middle;
}
.eng-trans-mat-table .col-thk {
  width: 12%;
  vertical-align: middle;
}
.eng-trans-mat-table .col-color {
  width: 10%;
  vertical-align: middle;
}
.eng-trans-mat-table .col-act {
  vertical-align: middle;
}
.eng-mat-extract {
  table-layout: fixed;
  width: 100%;
}
.eng-mat-extract .col-seq {
  width: 40px;
  vertical-align: middle;
}
/* 英文略收，厚度/颜色吃满侧栏剩余宽度，避免 BLACK / TRANSPARENT 被拆行截断 */
.eng-mat-extract .col-en {
  width: 32%;
}
.eng-mat-extract .col-thk {
  width: 24%;
  vertical-align: middle;
  white-space: nowrap;
}
.eng-mat-extract .col-color {
  width: 26%;
  vertical-align: middle;
  white-space: nowrap;
}
.eng-mat-extract .col-verify {
  width: 68px;
  text-align: center;
  vertical-align: middle;
}
.eng-mat-extract .col-act {
  width: 32px;
  text-align: center;
  vertical-align: middle;
}
.eng-mat-extract .col-thk .eng-inline-input--mat-field,
.eng-mat-extract .col-color .eng-inline-input--mat-field {
  display: block;
  width: 100%;
  height: 28px;
  box-sizing: border-box;
  border: 1px solid #d0d5dd;
  border-radius: 4px;
  padding: 0 8px;
  font-size: 11px;
  font-weight: 600;
  color: #0f172a;
  background: #fff;
  white-space: nowrap;
  overflow-x: auto;
  text-overflow: clip;
}
.eng-mat-extract .col-thk .eng-inline-input--mat-field:focus,
.eng-mat-extract .col-color .eng-inline-input--mat-field:focus {
  border-color: #1677ff;
  outline: none;
  box-shadow: 0 0 0 2px rgba(22, 119, 255, 0.15);
}
.eng-mat-extract thead th.col-thk,
.eng-mat-extract thead th.col-color {
  white-space: nowrap;
}
.eng-mat-extract .col-act .eng-note-del {
  margin: 0 auto;
}
.eng-mat-extract tr.verified td {
  background: #f0fdf4;
}
.eng-mat-extract .col-verify .eng-note-verify {
  margin: 0 auto;
  height: 20px;
  padding: 0 6px;
  gap: 2px;
  font-size: 10px;
  line-height: 1;
  white-space: nowrap;
  flex-wrap: nowrap;
}
.eng-mat-extract .col-verify .eng-note-verify .anticon {
  font-size: 10px;
}
.eng-review-table--dense .eng-src {
  font-size: 10px;
  line-height: 14px;
  color: #64748b;
  word-break: break-all;
}

.eng-drop {
  position: relative;
  border: 1.5px dashed #94a3b8;
  border-radius: 10px;
  min-height: 160px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #64748b;
  background: #f8fafc;
  text-align: center;
  padding: 24px 16px;
}
.eng-extract-elapsed {
  position: absolute;
  top: 10px;
  right: 12px;
  margin: 0;
  font-size: 12px;
  line-height: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--jy-primary, #1677ff);
  letter-spacing: 0.02em;
}
.eng-drop.ready {
  border-color: #91caff;
  background: #e6f4ff;
}
.eng-drop.drag {
  border-color: var(--jy-primary, #1677ff);
  background: #bae0ff;
}
.eng-drop.compact {
  min-height: 0;
  padding: 12px 12px;
  gap: 4px;
}
.eng-drop.compact .eng-drop-ico {
  font-size: 22px;
  margin-bottom: 0;
}
.eng-drop.compact > span {
  margin-bottom: 4px;
}
.eng-drop.busy {
  border-style: solid;
  border-color: #91caff;
  background: #f0f7ff;
  pointer-events: none;
}
.eng-drop.busy .eng-drop-ico {
  color: var(--jy-primary, #1677ff);
  opacity: 0.85;
}
.eng-drop.busy strong {
  color: var(--jy-text-strong, #0f172a);
  font-weight: 650;
}
.eng-drop.busy > span {
  color: #475569;
}
.eng-drop--hydrate {
  gap: 10px;
  border-color: #91caff;
  background: linear-gradient(160deg, #f8fbff 0%, #e6f4ff 100%);
  box-shadow: inset 0 0 0 1px rgba(22, 119, 255, 0.06);
}
.eng-drop--hydrate .eng-hydrate-ico {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  border: 1px solid #91caff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.eng-drop--hydrate .eng-drop-ico {
  margin: 0;
  font-size: 22px;
}
.eng-drop--hydrate strong {
  font-size: 15px;
  font-weight: 700;
}
.eng-drop--hydrate > span {
  margin-bottom: 0;
  max-width: 320px;
}
.eng-drop--hydrate .eng-hydrate-note {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  font-weight: 600;
  color: #475569;
}
.eng-drop-ico {
  font-size: 36px;
  color: var(--jy-primary, #1677ff);
  margin-bottom: 4px;
}
.eng-drop strong {
  color: var(--jy-text-strong, #0f172a);
  font-size: 14px;
  line-height: 22px;
  font-weight: 650;
}
/* 仅说明文案；勿命中按钮内 span/anticon，否则蓝底灰字看不清 */
.eng-drop > span:not(.eng-extract-elapsed) {
  font-size: 12px;
  line-height: 20px;
  margin-bottom: 8px;
  color: #64748b;
}

.eng-meta-card {
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 8px;
  background: #f8fafc;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.eng-gen-tip {
  margin: 0 0 4px;
  font-size: 12px;
  line-height: 18px;
  color: #64748b;
}
.eng-meta-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  line-height: 20px;
}
.eng-meta-row span {
  color: #94a3b8;
  flex-shrink: 0;
}
.eng-meta-row strong {
  color: var(--jy-text-strong, #0f172a);
  font-weight: 550;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.eng-cta-row {
  display: flex;
  gap: 8px;
}
.eng-cta-row.secondary .eng-tool {
  flex: 1;
  justify-content: center;
  background: #fff;
  color: #1f2937;
  border-color: var(--jy-border, #d0d5dd);
}
.eng-cta-row.secondary .eng-tool:hover:not(:disabled) {
  border-color: var(--jy-primary, #1677ff);
  color: var(--jy-primary, #1677ff);
  background: #e6f4ff;
}

.eng-ok-line {
  margin: 0;
  color: #0958d9;
  font-size: 12px;
  line-height: 20px;
  font-weight: 550;
  display: flex;
  align-items: center;
  gap: 6px;
}
.eng-busy {
  border: 1px solid #91caff;
  border-radius: 8px;
  background: #e6f4ff;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.eng-busy-inline {
  margin: 0 10px 8px;
  flex-shrink: 0;
}
.eng-busy-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
  color: var(--jy-text-strong, #0f172a);
  line-height: 22px;
  flex-wrap: wrap;
}
.eng-busy-head :deep(.anticon) {
  color: var(--jy-primary, #1677ff);
  font-size: 15px;
}
.eng-busy-text {
  letter-spacing: 0.01em;
  color: var(--jy-text-strong, #0f172a);
  flex: 1;
  min-width: 0;
}
.eng-compose-elapsed {
  margin-left: auto;
  font-size: 12px;
  line-height: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--jy-primary, #1677ff);
  letter-spacing: 0.02em;
  white-space: nowrap;
}
.eng-busy-hint {
  margin: 0;
  font-size: 12px;
  line-height: 18px;
  color: #475569;
}
.eng-busy-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}
.eng-compose-log {
  margin-top: 8px;
  border: 1px solid #d6e4ff;
  border-radius: 8px;
  background: #0b1220;
  overflow: hidden;
}
.eng-compose-log--idle {
  margin-top: 10px;
}
.eng-compose-log-title {
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 600;
  color: #e2e8f0;
  background: rgba(255, 255, 255, 0.06);
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.eng-compose-log-body {
  margin: 0;
  padding: 8px;
  max-height: 160px;
  overflow: auto;
  font-size: 11px;
  line-height: 1.45;
  color: #cbd5e1;
  white-space: pre-wrap;
  word-break: break-all;
}
.eng-busy-bar :deep(.ant-progress-inner) {
  background: #e8eef6;
  border-radius: 999px;
}
.eng-busy-bar :deep(.ant-progress-bg) {
  border-radius: 999px;
  transition: width 0.65s ease;
}
.eng-region-mask {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(15, 23, 42, 0.55);
  backdrop-filter: blur(2px);
  cursor: wait;
  user-select: none;
}
.eng-region-mask-card {
  min-width: 280px;
  max-width: 420px;
  padding: 28px 32px 24px;
  border-radius: 12px;
  border: 1px solid #91caff;
  background: #fff;
  box-shadow: 0 12px 40px rgba(15, 23, 42, 0.28);
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.eng-region-mask-spin {
  font-size: 32px;
  color: var(--jy-primary, #1677ff);
  line-height: 1;
}
.eng-region-mask-title {
  margin: 4px 0 0;
  font-size: 16px;
  font-weight: 700;
  line-height: 24px;
  color: var(--jy-text-strong, #0f172a);
}
.eng-region-mask-hint {
  margin: 0;
  font-size: 13px;
  line-height: 20px;
  color: #475569;
}
.eng-warn-line {
  margin: 0;
  color: #b45309;
  font-size: 12px;
  line-height: 20px;
}
.eng-hint {
  margin: 0;
  font-size: 12px;
  line-height: 20px;
  color: #64748b;
}
.eng-region-warn {
  margin: 0 0 8px;
  font-size: 12px;
  color: #b45309;
}
.eng-region-table-wrap {
  /* 整表跟弹窗 body 一起滚，行内原文不再套矮滚动区 */
  max-height: none;
  overflow: visible;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 6px;
}
.eng-region-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  table-layout: fixed;
}
.eng-region-table th,
.eng-region-table td {
  padding: 6px 8px;
  border-bottom: 1px solid #eef2f7;
  text-align: left;
  vertical-align: top;
  word-break: break-word;
}
.eng-region-table th {
  position: sticky;
  top: 0;
  background: #f8fafc;
  font-weight: 600;
  z-index: 1;
}
.eng-region-table .col-check {
  width: 36px;
  text-align: center;
}
.eng-region-table .col-no {
  width: 56px;
}
.eng-region-no-input {
  width: 100%;
  max-width: 48px;
  height: 24px;
  padding: 0 4px;
  border: 1px solid #d0d5dd;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 700;
  color: var(--jy-text-strong, #0f172a);
  text-align: center;
  background: #fff;
}
.eng-region-body-input {
  display: block;
  width: 100%;
  min-height: 72px;
  max-height: none;
  height: auto;
  padding: 6px 8px;
  border: 1px solid #d0d5dd;
  border-radius: 4px;
  font-size: 12px;
  line-height: 18px;
  color: var(--jy-text-strong, #0f172a);
  background: #fff;
  resize: vertical;
  overflow: hidden;
  field-sizing: content;
  font-family: inherit;
  box-sizing: border-box;
  white-space: pre-wrap;
  word-break: break-word;
}
.eng-region-table .col-thick,
.eng-region-table .col-color {
  width: 72px;
}
.eng-region-table .col-mode {
  width: 110px;
}
.eng-region-table select {
  width: 100%;
  font-size: 12px;
}
.eng-region-ocr {
  margin-top: 10px;
  font-size: 12px;
  color: #475569;
}
.eng-region-ocr summary {
  cursor: pointer;
  font-weight: 600;
  user-select: none;
}
.eng-region-ocr pre {
  margin: 6px 0 0;
  max-height: none;
  overflow: visible;
  padding: 10px 12px;
  background: #0b1220;
  color: #e2e8f0;
  border-radius: 6px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.5;
}
.eng-muted {
  color: #94a3b8;
}
.eng-preview {
  width: 100%;
  max-height: 40vh;
  overflow: auto;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 8px;
  background: #0b1220;
}
.eng-preview img {
  display: block;
  max-width: 100%;
}
.eng-svg {
  color: #e2e8f0;
}

.eng-table-wrap {
  overflow: auto;
  max-height: 42vh;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 8px;
}
.eng-table-wrap--extract {
  max-height: 22vh;
}
/* ② 全量明细：随侧栏整体滚动，不截断表格 */
.eng-table-wrap--extract-full {
  max-height: none;
  overflow: visible;
}
/* ④ 翻译表：本区滚动 + 表头 sticky（实底防透字） */
.eng-table-wrap--trans {
  flex: 1 1 0%;
  min-height: 180px;
  max-height: none;
  overflow: auto;
  -webkit-overflow-scrolling: touch;
}
.eng-crop-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.eng-crop-item {
  margin: 0;
  border: 1px solid var(--jy-border, #d0d5dd);
  border-radius: 6px;
  background: #f8fafc;
  overflow: hidden;
}
.eng-crop-item img {
  display: block;
  width: 100%;
  height: auto;
  background: #fff;
}
.eng-crop-item figcaption {
  padding: 4px 6px;
  font-size: 11px;
  line-height: 16px;
  color: #64748b;
  text-align: center;
}
.eng-review-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-size: 12px;
  line-height: 18px;
}
.eng-review-table th,
.eng-review-table td {
  border-bottom: 1px solid #e5e7eb;
  padding: 6px 8px;
  text-align: left;
  vertical-align: top;
  word-break: break-word;
}
.eng-review-table thead th {
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--jy-header-bg, #eef1f6);
  font-weight: 600;
  color: var(--jy-text-strong, #0f172a);
}
.eng-review-table tr.bad td {
  background: #fff1f0;
  color: #9f1239;
  font-weight: 600;
}
.eng-review-table tr.warn td {
  background: #fffbeb;
}
.eng-orig {
  color: #64748b;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
  line-height: 1.5;
  font-size: 12px;
  vertical-align: top;
}
.eng-empty {
  margin: 0;
  text-align: center;
  color: #94a3b8;
  font-size: 11px;
  line-height: 16px;
}
.eng-empty-ack {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 4px 0;
  font-size: 11px;
}
.eng-ack-check {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
  cursor: pointer;
  user-select: none;
}
.eng-ack-check input {
  margin: 0;
}
.eng-inline-input {
  width: 100%;
  height: 26px;
  border: 1px solid #d0d5dd;
  border-radius: 4px;
  padding: 0 6px;
  font-size: 12px;
  font-weight: 600;
  color: #0f172a;
  background: #fff;
}
.eng-inline-input:focus {
  outline: none;
  border-color: var(--jy-primary, #1677ff);
}

.eng-mat-body {
  gap: 6px;
  padding: 8px 12px 12px;
  overflow: hidden;
}
.eng-mat-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  flex-shrink: 0;
}
.eng-mat-filters,
.eng-mat-batch {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}
.eng-chip-btn {
  height: 22px;
  padding: 0 8px;
  border-radius: 4px;
  border: 1px solid var(--jy-border, #d0d5dd);
  background: #fff;
  font-size: 11px;
  line-height: 20px;
  color: #334155;
  cursor: pointer;
}
.eng-chip-btn.on {
  border-color: var(--jy-primary, #1677ff);
  color: var(--jy-primary, #1677ff);
  background: #e6f4ff;
  font-weight: 600;
}
.eng-mat-table-wrap {
  flex: 1 1 0%;
  min-height: 160px;
  max-height: none;
  overflow: auto;
}
.eng-mat-table .col-no {
  width: 48px;
}
.eng-mat-table .col-st {
  width: 56px;
}
.eng-mat-table .col-diff {
  width: 64px;
}
.eng-mat-table .col-adopt {
  width: 38%;
  min-width: 280px;
}
.eng-mat-table .col-act {
  width: 100px;
}
.eng-mat-table .eng-ref {
  word-break: break-word;
  overflow-wrap: anywhere;
}
.eng-mat-table tr.ok td {
  background: #fff;
}
.eng-mat-table tr.warn td {
  background: #fffbeb;
  color: inherit;
  font-weight: 500;
}
.eng-ref {
  color: #64748b;
  font-weight: 500;
}
.eng-status-chip {
  display: inline-block;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  line-height: 18px;
  font-weight: 700;
}
.eng-status-chip[data-st='match'] {
  background: #dcfce7;
  color: #166534;
}
.eng-status-chip[data-st='missing_3d'],
.eng-status-chip[data-st='missing_2d'],
.eng-status-chip[data-st='field_diff'] {
  background: #ffe4e6;
  color: #9f1239;
}
.eng-adopt-fields {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
  min-width: 0;
}
.eng-adopt-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}
.eng-mat-table tr.bad .eng-inline-input,
.eng-mat-table tr.warn .eng-inline-input {
  color: #0f172a;
  font-weight: 600;
}
.eng-adopt-fields .eng-adopt-mat {
  flex: none;
  width: 100%;
  min-width: 0;
  height: auto;
  min-height: 40px;
  resize: vertical;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
  line-height: 16px;
  padding: 4px 6px;
  font-family: inherit;
  box-sizing: border-box;
}
.eng-adopt-meta .eng-inline-input {
  flex: 1 1 88px;
  min-width: 72px;
}
.eng-adopt-meta .eng-inline-input.thin {
  flex: 0 0 72px;
  max-width: 88px;
}
.eng-disp-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}
.eng-mini {
  height: 22px;
  padding: 0 6px;
  border-radius: 4px;
  border: 1px solid #d0d5dd;
  background: #fff;
  font-size: 11px;
  font-weight: 600;
  color: #334155;
  cursor: pointer;
}
.eng-mini.on {
  border-color: var(--jy-primary, #1677ff);
  background: #e6f4ff;
  color: var(--jy-primary, #1677ff);
}
.eng-mini:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.eng-disp-lab {
  font-size: 11px;
  color: #64748b;
  width: 100%;
}
.eng-disp-lab[data-d='pending'] {
  color: #b45309;
  font-weight: 700;
}
.eng-disp-lab[data-d='manual'] {
  color: #1d4ed8;
  font-weight: 600;
}
.eng-mat-cta {
  flex-shrink: 0;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 6px;
  margin-top: auto;
}
.eng-mat-cta .eng-tool {
  width: auto;
}
.eng-mat-cta .eng-tool.accent {
  min-width: 140px;
}

.eng-drawer .eng-tool,
.eng-side-panel .eng-tool {
  background: #fff;
  color: #1f2937;
  border-color: var(--jy-border, #d0d5dd);
  outline: none;
}
.eng-drawer .eng-tool:focus-visible,
.eng-side-panel .eng-tool:focus-visible {
  box-shadow: 0 0 0 2px rgba(22, 119, 255, 0.28);
  border-color: var(--jy-primary, #1677ff);
}
/* 主/强调按钮：实心主色 + 白字（含 span/图标，禁止浅底白字） */
.eng-drawer .eng-tool.primary,
.eng-side-panel .eng-tool.primary,
.eng-drawer .eng-tool.accent,
.eng-side-panel .eng-tool.accent {
  background: var(--jy-primary, #1677ff);
  color: #fff;
  border-color: transparent;
  font-weight: 650;
}
.eng-drawer .eng-tool.primary span,
.eng-side-panel .eng-tool.primary span,
.eng-drawer .eng-tool.accent span,
.eng-side-panel .eng-tool.accent span,
.eng-drawer .eng-tool.primary :deep(.anticon),
.eng-side-panel .eng-tool.primary :deep(.anticon),
.eng-drawer .eng-tool.accent :deep(.anticon),
.eng-side-panel .eng-tool.accent :deep(.anticon) {
  color: #fff;
  margin-bottom: 0;
}
.eng-drawer .eng-tool.primary:hover:not(:disabled),
.eng-side-panel .eng-tool.primary:hover:not(:disabled),
.eng-drawer .eng-tool.accent:hover:not(:disabled),
.eng-side-panel .eng-tool.accent:hover:not(:disabled) {
  background: var(--jy-primary-dark, #0b5fff);
  color: #fff;
}
.eng-drawer .eng-tool:hover:not(:disabled):not(.accent):not(.primary),
.eng-side-panel .eng-tool:hover:not(:disabled):not(.accent):not(.primary) {
  border-color: var(--jy-primary, #1677ff);
  color: var(--jy-primary, #1677ff);
  background: #e6f4ff;
}
/* 禁用：浅底深灰字，禁止白字/过淡 */
.eng-drawer .eng-tool:disabled,
.eng-side-panel .eng-tool:disabled {
  color: #475569;
  border-color: #cbd5e1;
  background: #e2e8f0;
  opacity: 1;
  font-weight: 600;
}
.eng-drawer .eng-tool:disabled span,
.eng-side-panel .eng-tool:disabled span,
.eng-drawer .eng-tool:disabled :deep(.anticon),
.eng-side-panel .eng-tool:disabled :deep(.anticon) {
  color: #475569;
  margin-bottom: 0;
}
/* 抽取/识别中：主色实心底 + 白字（须压过 disabled 的灰字规则） */
.eng-drawer .eng-tool.primary.busy:disabled,
.eng-side-panel .eng-tool.primary.busy:disabled,
.eng-drawer .eng-tool.accent.busy:disabled,
.eng-side-panel .eng-tool.accent.busy:disabled {
  background: var(--jy-primary, #1677ff);
  color: #fff;
  border-color: transparent;
  opacity: 1;
  font-weight: 650;
  cursor: wait;
}
.eng-drawer .eng-tool.primary.busy:disabled span,
.eng-side-panel .eng-tool.primary.busy:disabled span,
.eng-drawer .eng-tool.accent.busy:disabled span,
.eng-side-panel .eng-tool.accent.busy:disabled span,
.eng-drawer .eng-tool.primary.busy:disabled :deep(.anticon),
.eng-side-panel .eng-tool.primary.busy:disabled :deep(.anticon),
.eng-drawer .eng-tool.accent.busy:disabled :deep(.anticon),
.eng-side-panel .eng-tool.accent.busy:disabled :deep(.anticon) {
  color: #fff;
  margin-bottom: 0;
}

/* 上传区主按钮：浅底上强制主色，禁用也保持可辨认 */
.eng-drop .eng-tool.accent,
.eng-drop .eng-tool.accent:disabled,
.eng-drop .eng-tool.primary,
.eng-drop .eng-tool.primary:disabled {
  background: var(--jy-primary, #1677ff);
  color: #fff;
  border-color: transparent;
  font-weight: 650;
  opacity: 1;
}
.eng-drop .eng-tool.accent:disabled,
.eng-drop .eng-tool.primary:disabled {
  background: #69b1ff;
  cursor: not-allowed;
}
.eng-drop .eng-tool.accent span,
.eng-drop .eng-tool.accent:disabled span,
.eng-drop .eng-tool.primary span,
.eng-drop .eng-tool.primary:disabled span,
.eng-drop .eng-tool.accent :deep(.anticon),
.eng-drop .eng-tool.accent:disabled :deep(.anticon),
.eng-drop .eng-tool.primary :deep(.anticon),
.eng-drop .eng-tool.primary:disabled :deep(.anticon) {
  color: #fff;
  margin-bottom: 0;
}
</style>

<!-- Popover 挂到 body，需非 scoped 才能命中内容样式 -->
<style>
.eng-extract-help-pop {
  max-width: 420px;
}
.eng-extract-help-pop .eng-extract-qa-list {
  margin: 0 0 8px;
  padding-left: 18px;
  max-height: 240px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.45;
  color: #334155;
}
.eng-extract-help-pop .eng-extract-help-tip {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: #64748b;
}
/* 截图抽取弹窗：加宽 + body 整体可滚，原文框本身不截断 */
.eng-region-modal .ant-modal {
  max-width: calc(100vw - 32px);
  top: 24px;
  padding-bottom: 24px;
}
.eng-region-modal .ant-modal-content {
  max-height: calc(100vh - 48px);
  display: flex;
  flex-direction: column;
}
.eng-region-modal .ant-modal-body {
  flex: 1 1 auto;
  max-height: calc(100vh - 160px);
  overflow: auto;
  padding-top: 12px;
}
</style>
