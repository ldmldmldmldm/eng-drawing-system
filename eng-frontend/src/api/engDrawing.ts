/**
 * 工程图 API：路径约定为 `/eng-drawing/...`
 * （前端 baseURL=/api，代理去掉 /api 后后端映射 `/eng-drawing`）
 */
import request from './http'

/** 工厂层身份只来自页面确认，不从三维文件名猜测。 */
export type FactoryFace = '未指定' | 'zMin朝产品' | 'zMax朝产品'
/** 刀型：客户图通常无语义，默认厂注待审。 */
export type FactoryKnife = 'CUT' | 'HALF_CUT' | 'CREASE'

export interface StepScanView {
  fileName: string
  drawingNo: string
  headerFileName: string
  headerPartNo: string
  originatingSystem: string
  timestamp: string
  author: string
  organization: string
  preprocessorVersion: string
  authorization: string
  fileDescription: string
  implementationLevel: string
  schema: string
  protocol: string
  lengthUnit: string
  productNames: string[]
  productListTruncated: boolean
  assemblyNames: string[]
  revisions: string[]
  layers: string[]
  solidCount: number
  solidEntityIds?: string[]
  assemblyUseCount: number
  assembly: boolean
  hasSolid: boolean
  canExplode: boolean
  colors: string[]
  properties: string[]
  measures: string[]
  pmi: string
  envelope: string
  edges: number[][]
  edgesTruncated: boolean
  message: string
}

export interface StackExplodeExportView {
  dxfBase64: string
  fileName: string
  message: string
  warnings: string[]
}

export interface StackExplodeExportBody {
  drawingNo?: string
  plates: Array<{
    bodyId: string
    thickness?: number
    segments: Array<{ x1: number; y1: number; x2: number; y2: number }>
  }>
  callouts: Array<{
    bodyId: string
    calloutNo: string
    anchorX: number
    anchorY: number
    textX: number
    textY: number
    elbows?: Array<{ x: number; y: number }>
    line1: string
    line2?: string
    /** 结构化旁注（回读优先；旧数据可从 line1/line2 解析） */
    material?: string
    color?: string
    specialReq?: string
    thickness?: number
    placed?: boolean
  }>
}

export interface EngStackAssetSummary {
  id: string
  drawingNo: string
  title: string
  savedAt: number
  plateCount: number
  calloutCount: number
}

export interface EngStackAsset extends EngStackAssetSummary {
  fileName?: string
  job?: {
    plates?: StackExplodeExportBody['plates']
    callouts?: StackExplodeExportBody['callouts']
    drawingNo?: string
  }
}

export interface EngStackSaveBody {
  drawingNo?: string
  title?: string
  plates: StackExplodeExportBody['plates']
  callouts: StackExplodeExportBody['callouts']
  previewPngBase64?: string
}

export interface EngComposeResult {
  fileName: string
  drawingNo: string
  stackAssetId: string
  message: string
  warnings: string[]
  previewPngBase64: string
  previewSvg: string
  zipBase64: string
  dxfFileName: string
  /** 已废弃：新产物不再回传 DXF Base64，下载走 /tasks/{id}/dxf */
  dxfBase64?: string
  /** 任务目录已有成品 DXF（compose-result.dxf / result.dxf） */
  hasDxf?: boolean
  explosionSlot?: { page?: number; x?: number; y?: number; w?: number; h?: number }
}

/** ⑤ 异步合成任务状态 */
export type ComposeJobStatus = 'IDLE' | 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED' | string

export interface EngComposeJobView {
  taskId?: number
  jobId?: string
  status: ComposeJobStatus
  stage?: string
  percent?: number
  startedAt?: number
  finishedAt?: number | null
  /** 服务端计算的已用时（ms）；RUNNING 随查询刷新，供前端计时器 */
  elapsedMs?: number
  message?: string
  logs?: string[]
  stackAssetId?: string
  result?: EngComposeResult | null
}

export interface OcctMesh {
  color?: number[]
  attributes: {
    position: { array: ArrayLike<number> }
    normal?: { array: ArrayLike<number> }
  }
  index: { array: ArrayLike<number> }
}

export interface OcctModule {
  ReadStepFile(content: Uint8Array, params: Record<string, unknown> | null): { success: boolean; meshes: OcctMesh[] }
}

const LONG = 120000
/** ① 抽取 / ⑤ 合成：与 nginx proxy_read_timeout 900s 对齐 */
const VERY_LONG = 900000

export async function scanStep(file: File): Promise<StepScanView> {
  const fd = new FormData()
  fd.append('file', file)
  return request.post<any, StepScanView>('/eng-drawing/step', fd, { timeout: LONG })
}

export async function saveEngStack(body: EngStackSaveBody): Promise<EngStackAssetSummary> {
  return request.post<any, EngStackAssetSummary>('/eng-drawing/stack/save', body, { timeout: LONG })
}

export async function listEngStacks(): Promise<EngStackAssetSummary[]> {
  return request.get<any, EngStackAssetSummary[]>('/eng-drawing/stacks')
}

export async function getEngStack(id: string): Promise<EngStackAsset> {
  return request.get<any, EngStackAsset>(`/eng-drawing/stack/${encodeURIComponent(id)}`)
}

/** 层次保存时的预览 PNG；无文件时 previewPngBase64 为空串 */
export async function getEngStackPreview(
  id: string
): Promise<{ previewPngBase64?: string }> {
  return request.get<any, { previewPngBase64?: string }>(
    `/eng-drawing/stack/${encodeURIComponent(id)}/preview`
  )
}

export interface ConvertNoteLine {
  noteNo?: string
  original: string
  translated: string
  status: string
  x?: number
  y?: number
}

export interface ConvertMaterialRow {
  seq: number
  noteNo?: string
  materialEn?: string
  materialZh?: string
  thickness?: string
  source?: string
  role?: string
  color?: string
}

export interface ConvertPreview {
  fileName: string
  drawingNo: string
  message: string
  warnings: string[]
  notes: ConvertNoteLine[]
  materials: ConvertMaterialRow[]
  previewPngBase64?: string
  previewSvg?: string
  /** 多页 SVG；有则优先用于分页预览 */
  previewSvgs?: string[]
  materialCrops?: string[]
  sheetLayout?: Record<string, unknown>
  /** true：仅高清预览，待用户选截图/自动抽取 */
  sheetReady?: boolean
}

export interface GlossaryItem {
  id?: number
  enPhrase: string
  zhPhrase: string
  enabled?: boolean
}

export async function composeEngDrawing(
  file: File | null | undefined,
  stackAssetId: string | null | undefined,
  notes?: ConvertNoteLine[],
  materials?: ConvertMaterialRow[],
  sheetLayout?: unknown,
  taskId?: number | string | null
): Promise<EngComposeResult> {
  const fd = new FormData()
  if (file) fd.append('file', file)
  fd.append('stackAssetId', stackAssetId || '')
  // 始终带审校 notes/materials（可空数组），与后端合成门禁对齐
  fd.append(
    'annotJson',
    JSON.stringify({
      notes: notes ?? [],
      materials: materials ?? [],
      sheetLayout: sheetLayout ?? null
    })
  )
  if (taskId != null && taskId !== '') {
    fd.append('taskId', String(taskId))
  }
  return request.post<any, EngComposeResult>('/eng-drawing/compose', fd, { timeout: VERY_LONG })
}

/** ⑤ 异步合成：立刻返回 job，后台生成；同任务 RUNNING 幂等。file 可空（仅三维）。 */
export async function startComposeAsync(
  file: File | null | undefined,
  stackAssetId: string | null | undefined,
  notes?: ConvertNoteLine[],
  materials?: ConvertMaterialRow[],
  sheetLayout?: unknown,
  taskId?: number | string | null
): Promise<EngComposeJobView> {
  if (taskId == null || taskId === '') {
    throw new Error('异步合成须绑定任务')
  }
  const fd = new FormData()
  if (file) fd.append('file', file)
  fd.append('stackAssetId', stackAssetId || '')
  fd.append('taskId', String(taskId))
  fd.append(
    'annotJson',
    JSON.stringify({
      notes: notes ?? [],
      materials: materials ?? [],
      sheetLayout: sheetLayout ?? null
    })
  )
  // 提交本身很快；真正耗时在后台
  return request.post<any, EngComposeJobView>('/eng-drawing/compose/async', fd, { timeout: LONG })
}

/** ⑤ 查询异步合成状态（进度/日志/结果） */
export async function getComposeStatus(
  taskId: number | string
): Promise<EngComposeJobView> {
  return request.get<any, EngComposeJobView>('/eng-drawing/compose/status', {
    params: { taskId },
    timeout: 20000,
    skipErrorToast: true
  })
}

export interface EngExtractJobView {
  taskId?: number
  jobId?: string
  status: ComposeJobStatus
  /** previewOnly | extract */
  mode?: string
  stage?: string
  percent?: number
  startedAt?: number
  finishedAt?: number | null
  elapsedMs?: number
  message?: string
  logs?: string[]
  result?: ConvertPreview | null
}

export type ExtractJobMode = 'previewOnly' | 'extract'

/** ① 异步：立刻返回 job；须 taskId。mode 默认 extract（兼容）；上传预览用 previewOnly */
export async function startExtractAsync(
  file: File,
  taskId: number | string,
  mode: ExtractJobMode = 'extract'
): Promise<EngExtractJobView> {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('taskId', String(taskId))
  fd.append('mode', mode)
  return request.post<any, EngExtractJobView>('/eng-drawing/convert/preview/async', fd, { timeout: LONG })
}

export async function getExtractStatus(taskId: number | string): Promise<EngExtractJobView> {
  return request.get<any, EngExtractJobView>('/eng-drawing/convert/preview/status', {
    params: { taskId },
    timeout: 20000,
    skipErrorToast: true
  })
}

/** ① 停止进行中的解析/抽取 */
export async function cancelExtract(taskId: number | string): Promise<EngExtractJobView> {
  return request.post<any, EngExtractJobView>(
    '/eng-drawing/convert/preview/cancel',
    null,
    {
      params: { taskId },
      timeout: 20000,
      skipErrorToast: true
    }
  )
}

/** 图纸转换预览：抽取 NOTE + 材料并词典预译；有 taskId 时后端落盘源 DXF */
export async function convertPreview(
  file: File,
  taskId?: number | string | null
): Promise<ConvertPreview> {
  const fd = new FormData()
  fd.append('file', file)
  if (taskId != null && taskId !== '') {
    fd.append('taskId', String(taskId))
  }
  return request.post<any, ConvertPreview>('/eng-drawing/convert/preview', fd, { timeout: VERY_LONG })
}

export interface ConvertTranslateResult {
  original: string
  translated: string
  status: string
}

/** ④ 单条强制重译 */
export async function convertTranslate(
  text: string,
  kind: 'note' | 'material'
): Promise<ConvertTranslateResult> {
  return request.post<any, ConvertTranslateResult>(
    '/eng-drawing/convert/translate',
    { text, kind },
    { timeout: LONG }
  )
}

export interface RegionExtractResult {
  kind: 'note' | 'material' | string
  ocrText?: string
  usedVision?: boolean
  notes?: ConvertNoteLine[]
  materials?: ConvertMaterialRow[]
  warnings?: string[]
}

/** ① 框选区域识别：OCR + Vision（兼容上传 PNG） */
export async function convertRegionExtract(
  file: Blob | File,
  kind: 'note' | 'material',
  taskId?: number | string | null
): Promise<RegionExtractResult> {
  const fd = new FormData()
  const name = file instanceof File ? file.name : 'region.png'
  fd.append('file', file, name.endsWith('.png') ? name : 'region.png')
  fd.append('kind', kind)
  if (taskId != null && taskId !== '') {
    fd.append('taskId', String(taskId))
  }
  return request.post<any, RegionExtractResult>('/eng-drawing/convert/region-extract', fd, {
    timeout: LONG
  })
}

/** ① 框选只交归一化坐标，服务端从高清底图裁切 */
export async function convertRegionExtractByRect(
  kind: 'note' | 'material',
  taskId: number | string,
  rect: { x: number; y: number; w: number; h: number }
): Promise<RegionExtractResult> {
  const fd = new FormData()
  fd.append('kind', kind)
  fd.append('taskId', String(taskId))
  fd.append('x', String(rect.x))
  fd.append('y', String(rect.y))
  fd.append('w', String(rect.w))
  fd.append('h', String(rect.h))
  return request.post<any, RegionExtractResult>('/eng-drawing/convert/region-extract', fd, {
    timeout: LONG
  })
}

export async function exportStackExplodeDxf(body: StackExplodeExportBody): Promise<StackExplodeExportView> {
  return request.post<any, StackExplodeExportView>('/eng-drawing/stack-explode/dxf', body, {
    timeout: LONG
  })
}

export async function listGlossary(): Promise<GlossaryItem[]> {
  const res = await request.get<any, { items?: GlossaryItem[] }>('/system/glossary', {
    params: { page: 1, size: 500 }
  })
  return res?.items || []
}

export const engDrawingApi = {
  scanStep,
  saveEngStack,
  listEngStacks,
  getEngStack,
  getEngStackPreview,
  composeEngDrawing,
  startComposeAsync,
  getComposeStatus,
  convertPreview,
  startExtractAsync,
  getExtractStatus,
  cancelExtract,
  convertTranslate,
  convertRegionExtract,
  convertRegionExtractByRect,
  exportStackExplodeDxf,
  listGlossary
}
