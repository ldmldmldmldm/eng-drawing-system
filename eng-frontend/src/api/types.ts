import type { ConvertMaterialRow, ConvertNoteLine } from './engDrawing'

export interface LoginResult {
  token: string
  username: string
  displayName: string
  role: string
  /** 首次登录需强制补全用户名称 */
  needProfile?: boolean
}

export interface SysUserItem {
  id: number
  username: string
  displayName: string
  role: string
  status: string
  createTime?: string
  updateTime?: string
  systemAdmin?: boolean
}

export interface SysUserPage {
  total: number
  page: number
  size: number
  items: SysUserItem[]
}

export interface UserImportResult {
  successCount: number
  failCount: number
  errors: string[]
}

export interface OperationLogItem {
  id: number
  operator: string
  module: string
  action: string
  targetKey?: string
  detail?: string
  status: string
  requestId?: string
  clientIp?: string
  createdTime?: string
}

export interface OperationLogPage {
  total: number
  page: number
  size: number
  items: OperationLogItem[]
}

export interface RuntimeLogTailResult {
  source: string
  fileName: string
  exists: boolean
  fileSize: number
  lineCount: number
  lines: string[]
  message?: string
}

/** 任务状态：与后端 eng_task.status 一致 */
export type TaskStatus = 'DRAFT' | 'IN_PROGRESS' | 'COMPLETED'

export interface TaskItem {
  id: number | string
  title: string
  status: TaskStatus
  /** 任务归属用户 ID */
  userId?: number | string
  /** 归属用户工号（管理员列表展示） */
  ownerUsername?: string
  /** 归属用户姓名（管理员列表展示） */
  ownerDisplayName?: string
  /** 当前步骤 1～5：层次/抽取/材料/翻译/生成 */
  currentStep?: number
  drawingNo?: string
  updateTime?: string
  createTime?: string
  completedAt?: string
  stackAssetId?: string
  /** 是否跳过②三维层次（无 STP 仍可进③～⑤） */
  stackSkipped?: boolean
  /** 是否跳过①抽取核对（有 DWG 或 STP 仍可进②～④） */
  extractSkipped?: boolean
  /** 原 STP 文件名（展示用） */
  stepFileName?: string
  /** 任务目录是否已落盘 STP */
  hasStepFile?: boolean
  /** 任务目录是否已落盘客户 DWG */
  hasCustomerDwg?: boolean
  /** 任务目录是否已落盘审校快照 annot.json */
  hasAnnot?: boolean
  previewPngBase64?: string
  /** 已完成任务结果页：有成品 DXF 时可走 /tasks/{id}/dxf */
  hasDxf?: boolean
  dxfBase64?: string
  zipBase64?: string
  dxfFileName?: string
}

export interface TaskPage {
  total: number
  page: number
  size: number
  items: TaskItem[]
}

export interface TaskCompleteBody {
  drawingNo?: string
  previewPngBase64?: string
  previewSvg?: string
  dxfBase64?: string
  dxfFileName?: string
  stackAssetId?: string
  message?: string
}

/** 任务审校快照（annot.json + 预览独立文件）：重进优先回显，跳过再抽 */
export interface TaskAnnotSnapshot {
  /** ≥2：步序①抽取/②层次；缺省或 1：旧语义①层次/②抽取（恢复时会互换） */
  version?: number
  fileName?: string
  drawingNo?: string
  message?: string
  warnings?: string[]
  notes?: ConvertNoteLine[]
  materials?: ConvertMaterialRow[]
  /** ① 确认后的 2D 材料基线（③ 对照用；与 materials 采用结果分离） */
  materialsBaseline?: ConvertMaterialRow[]
  sheetLayout?: Record<string, unknown> | null
  materialsConfirmed?: boolean
  notesConfirmed?: boolean
  /** ① 逐条 NOTE 对图确认 */
  noteVerified?: boolean[]
  /** ① 逐条材料对图确认 */
  materialVerified?: boolean[]
  /** ④ 逐条 NOTE 译文确认 */
  noteTranslateVerified?: boolean[]
  /** ④ 逐条材料审校确认 */
  materialReviewVerified?: boolean[]
  /** ① 抽取结果为空时：显式确认「无 NOTE」 */
  emptyNotesAcked?: boolean
  /** ① 抽取结果为空时：显式确认「无材料」 */
  emptyMaterialsAcked?: boolean
  /**
   * ③ 材料核对工作行快照（采用结果 + 处置）。
   * 返回③时恢复，避免未点「确认材料」仅改采用后丢失。
   */
  materialWorkSnapshot?: {
    noteNo: string
    disposition: 'pending' | 'adopt_3d' | 'adopt_2d' | 'manual'
    adopted: { material: string; color: string; thickness: string }
  }[]
  wizardStep?: number
  /** 客户图纸预览；后端落 convert-preview.*，接口进出时带回 */
  previewPngBase64?: string
  previewSvg?: string
  previewSvgs?: string[]
  materialCrops?: string[]
  /** true：仅高清预览，尚未全量/截图抽取 */
  sheetReady?: boolean
}
