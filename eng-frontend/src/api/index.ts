import request from './http'
import type {
  LoginResult,
  SysUserItem,
  SysUserPage,
  UserImportResult,
  OperationLogPage,
  RuntimeLogTailResult,
  TaskItem,
  TaskPage,
  TaskCompleteBody,
  TaskStatus,
  TaskAnnotSnapshot
} from './types'
import { useUserStore } from '@/stores/user'
import { message } from 'ant-design-vue'

export * from './engDrawing'
export { engDrawingApi } from './engDrawing'
export type {
  TaskItem,
  TaskPage,
  TaskCompleteBody,
  TaskStatus,
  TaskAnnotSnapshot
} from './types'

export const authApi = {
  login: (username: string, password: string) =>
    request.post<any, LoginResult>('/auth/login', { username, password }),
  profile: () => request.get<any, { username: string; displayName: string; role: string }>('/auth/profile'),
  updateDisplayName: (displayName: string) =>
    request.post<any, { username: string; displayName: string; role: string }>(
      '/auth/profile/display-name',
      { displayName }
    ),
  listUsers: (params?: {
    page?: number
    size?: number
    username?: string
    displayName?: string
    role?: string
    status?: string
  }) => request.get<any, SysUserPage>('/auth/users', { params }),
  createUser: (body: { username: string; displayName?: string }) =>
    request.post<any, SysUserItem>('/auth/users', body),
  updateUser: (id: number, body: { displayName?: string; status?: string }) =>
    request.put<any, SysUserItem>(`/auth/users/${id}`, body),
  resetPassword: (id: number) => request.post(`/auth/users/${id}/reset-password`),
  deleteUser: (id: number) => request.delete(`/auth/users/${id}`),
  changePassword: (oldPassword: string, newPassword: string) =>
    request.post('/auth/change-password', { oldPassword, newPassword }),
  logout: () => request.post('/auth/logout'),
  downloadTemplate: () => downloadAuthFile('/auth/users/template', '用户导入模板.xlsx'),
  exportCurrent: (params?: { username?: string; displayName?: string; role?: string; status?: string }) => {
    const q = new URLSearchParams()
    if (params?.username) q.set('username', params.username)
    if (params?.displayName) q.set('displayName', params.displayName)
    if (params?.role) q.set('role', params.role)
    if (params?.status) q.set('status', params.status)
    const qs = q.toString()
    return downloadAuthFile(`/auth/users/export${qs ? `?${qs}` : ''}`, '用户列表.xlsx')
  },
  importExcel: (file: File) => {
    const form = new FormData()
    form.append('file', file)
    return request.post<any, UserImportResult>('/auth/users/import', form)
  }
}

export const taskApi = {
  list: (params?: { page?: number; size?: number; status?: TaskStatus | ''; keyword?: string }) =>
    request.get<any, TaskPage>('/tasks', { params }),
  get: (id: number | string) => request.get<any, TaskItem>(`/tasks/${id}`),
  create: (body: { title: string }) => request.post<any, TaskItem>('/tasks', body),
  update: (
    id: number | string,
    body: {
      title?: string
      currentStep?: number
      drawingNo?: string
      status?: TaskStatus
      stackAssetId?: string
      stackSkipped?: boolean
      extractSkipped?: boolean
      stepFileName?: string
    }
  ) => request.put<any, TaskItem>(`/tasks/${id}`, body),
  remove: (id: number | string) => request.delete(`/tasks/${id}`),
  complete: (id: number | string, body: TaskCompleteBody) =>
    request.post<any, TaskItem>(`/tasks/${id}/complete`, {
      drawingNo: body.drawingNo,
      stackAssetId: body.stackAssetId,
      message: body.message,
      resultPreviewPngBase64: body.previewPngBase64
    }),
  result: (id: number | string) => request.get<any, TaskItem>(`/tasks/${id}/result`),
  /** 浏览器另存为：工作台合成稿或已完成 result.dxf */
  saveEngineeringDxf: (id: number | string, fileName?: string) =>
    saveTaskBinary(`/tasks/${id}/dxf`, fileName || 'engineering.dxf'),
  /** 上传 STP 到任务目录 */
  uploadStep: (id: number | string, file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return request.post<any, TaskItem>(`/tasks/${id}/step`, fd, { timeout: 120000 })
  },
  /** 下载任务 STP 为 File（供本地再解析三维） */
  downloadStep: (id: number | string, fileName?: string) =>
    downloadTaskBinary(`/tasks/${id}/step`, fileName || 'model.stp'),
  /** 浏览器另存为：任务已落盘的用户上传 STP */
  saveStep: (id: number | string, fileName?: string) =>
    saveTaskBinary(`/tasks/${id}/step`, fileName || 'model.stp'),
  uploadDwg: (id: number | string, file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return request.post<any, TaskItem>(`/tasks/${id}/dwg`, fd, { timeout: 120000 })
  },
  downloadDwg: (id: number | string) => downloadTaskBinary(`/tasks/${id}/dwg`, 'customer.dwg'),
  /** 浏览器另存为：任务已落盘的用户上传客户 DWG */
  saveDwg: (id: number | string, fileName?: string) =>
    saveTaskBinary(`/tasks/${id}/dwg`, fileName || 'customer.dwg'),
  /** 读取审校快照；无则 null */
  getAnnot: (id: number | string) =>
    request.get<any, TaskAnnotSnapshot | null>(`/tasks/${id}/annot`),
  /** 保存审校快照（抽取结果 + 确认态） */
  saveAnnot: (id: number | string, body: TaskAnnotSnapshot) =>
    request.put<any, TaskAnnotSnapshot>(`/tasks/${id}/annot`, body),
  /** 清除审校快照 */
  clearAnnot: (id: number | string) => request.delete(`/tasks/${id}/annot`)
}

async function downloadTaskBinary(path: string, fallbackName: string): Promise<File> {
  const userStore = useUserStore()
  const resp = await fetch(`/api${path}`, {
    headers: userStore.token ? { Authorization: `Bearer ${userStore.token}` } : {}
  })
  const contentType = resp.headers.get('Content-Type') || ''
  // 业务异常经全局处理仍可能 HTTP 200 + JSON R
  if (contentType.includes('application/json')) {
    let msg = '下载失败'
    try {
      const body = (await resp.json()) as { msg?: string; code?: number }
      if (body?.msg) msg = body.msg
    } catch {
      // ignore
    }
    message.error(msg)
    throw new Error(msg)
  }
  if (!resp.ok) {
    let msg = '下载失败'
    try {
      const body = (await resp.json()) as { msg?: string }
      if (body?.msg) msg = body.msg
    } catch {
      // ignore
    }
    message.error(msg)
    throw new Error(msg)
  }
  const blob = await resp.blob()
  const cd = resp.headers.get('Content-Disposition') || ''
  let filename = fallbackName
  const m = /filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i.exec(cd)
  if (m) {
    filename = decodeURIComponent((m[1] || m[2] || fallbackName).trim())
  }
  return new File([blob], filename, { type: blob.type || 'application/octet-stream' })
}

/** 拉取任务二进制并触发浏览器另存为（源文件下载） */
async function saveTaskBinary(path: string, fallbackName: string) {
  const file = await downloadTaskBinary(path, fallbackName)
  const url = URL.createObjectURL(file)
  const a = document.createElement('a')
  a.href = url
  a.download = file.name || fallbackName
  a.click()
  URL.revokeObjectURL(url)
}

async function downloadAuthFile(path: string, fallbackName: string) {
  const userStore = useUserStore()
  const resp = await fetch(`/api${path}`, {
    headers: userStore.token ? { Authorization: `Bearer ${userStore.token}` } : {}
  })
  if (!resp.ok) {
    message.error('下载失败')
    throw new Error('download failed')
  }
  const blob = await resp.blob()
  const cd = resp.headers.get('Content-Disposition') || ''
  let filename = fallbackName
  const m = /filename\*=UTF-8''([^;]+)|filename="?([^";]+)"?/i.exec(cd)
  if (m) {
    filename = decodeURIComponent((m[1] || m[2] || fallbackName).trim())
  }
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

export const operationLogApi = {
  list: (params?: {
    page?: number
    size?: number
    operator?: string
    module?: string
    action?: string
    status?: string
    dateFrom?: string
    dateTo?: string
  }) => request.get<any, OperationLogPage>('/system/operation-logs', { params }),
  batchDelete: (ids: number[]) => request.post('/system/operation-logs/batch-delete', { ids })
}

export interface GlossaryRow {
  id: number
  enPhrase: string
  zhPhrase: string
  /** TERM / PHRASE */
  entryKind?: string
  status: string
  enabled?: boolean
  createTime?: string
  updateTime?: string
}

export interface LlmConfigRow {
  id: number
  name: string
  provider: string
  baseUrl: string
  modelName: string
  enabled: boolean
  apiKey?: string
  apiKeyMasked?: string
  remark?: string
  createTime?: string
  updateTime?: string
}

export interface LlmConfigBody {
  name?: string
  provider?: string
  baseUrl?: string
  apiKey?: string
  modelName?: string
  enabled?: boolean
  remark?: string
}

export interface LlmConfigTestResult {
  ok: boolean
  latencyMs?: number
  preview?: string
  message?: string
}

export const glossaryApi = {
  page: (params?: {
    page?: number
    size?: number
    keyword?: string
    status?: string
    entryKind?: string
  }) =>
    request.get<any, { total: number; page: number; size: number; items: GlossaryRow[] }>(
      '/system/glossary',
      { params }
    ),
  create: (body: { enPhrase: string; zhPhrase: string; status?: string; entryKind?: string }) =>
    request.post<any, GlossaryRow>('/system/glossary', body),
  update: (
    id: number,
    body: { enPhrase?: string; zhPhrase?: string; status?: string; entryKind?: string }
  ) => request.put<any, GlossaryRow>(`/system/glossary/${id}`, body),
  remove: (id: number) => request.delete(`/system/glossary/${id}`),
  reclassify: () =>
    request.post<
      any,
      { termCount: number; phraseCount: number; exampleCount: number; total: number }
    >('/system/glossary/reclassify'),
  exportExcel: (params?: { keyword?: string; status?: string; entryKind?: string }) => {
    const q = new URLSearchParams()
    if (params?.keyword) q.set('keyword', params.keyword)
    if (params?.status) q.set('status', params.status)
    if (params?.entryKind) q.set('entryKind', params.entryKind)
    const qs = q.toString()
    return downloadAuthFile(`/system/glossary/export${qs ? `?${qs}` : ''}`, '语料库.xlsx')
  },
  downloadTemplate: () => downloadAuthFile('/system/glossary/template', '语料导入模板.xlsx'),
  importExcel: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return request.post<any, { successCount: number; failCount: number; errors: string[] }>(
      '/system/glossary/import',
      fd
    )
  }
}

export const llmConfigApi = {
  list: () => request.get<any, LlmConfigRow[]>('/system/llm-configs'),
  create: (body: LlmConfigBody) => request.post<any, LlmConfigRow>('/system/llm-configs', body),
  update: (id: number, body: LlmConfigBody) =>
    request.put<any, LlmConfigRow>(`/system/llm-configs/${id}`, body),
  enable: (id: number) => request.post(`/system/llm-configs/${id}/enable`),
  test: (id: number) =>
    request.post<any, LlmConfigTestResult>(`/system/llm-configs/${id}/test`, null, {
      timeout: 25000,
      skipErrorToast: true
    }),
  remove: (id: number) => request.delete(`/system/llm-configs/${id}`)
}

export type RuntimeLogSource = 'all' | 'error' | 'biz'
export type RuntimeLogLevel = 'ALL' | 'ERROR' | 'WARN' | 'INFO' | 'DEBUG' | 'TRACE'

export interface RuntimeLogQuery {
  source?: RuntimeLogSource
  lines?: number
  level?: RuntimeLogLevel
  keyword?: string
}

export const runtimeLogApi = {
  tail: (params?: RuntimeLogQuery) =>
    request.get<any, RuntimeLogTailResult>('/system/runtime-logs/tail', { params }),

  openStream: (
    params: RuntimeLogQuery,
    handlers: {
      onLog?: (line: string) => void
      onMeta?: (text: string) => void
      onStatus?: (text: string) => void
      onError?: (err: Error) => void
      onDone?: () => void
    }
  ) => {
    const userStore = useUserStore()
    const q = new URLSearchParams()
    if (params.source) q.set('source', params.source)
    if (params.lines != null) q.set('lines', String(params.lines))
    if (params.level) q.set('level', params.level)
    if (params.keyword) q.set('keyword', params.keyword)
    const ctrl = new AbortController()
    let closed = false

    const finish = () => {
      if (closed) return
      closed = true
      handlers.onDone?.()
    }

    ;(async () => {
      try {
        const res = await fetch(`/api/system/runtime-logs/stream?${q}`, {
          method: 'GET',
          headers: userStore.token ? { Authorization: `Bearer ${userStore.token}` } : {},
          signal: ctrl.signal
        })
        if (!res.ok) {
          let msg = `实时日志连接失败（${res.status}）`
          try {
            const body = (await res.json()) as { msg?: string }
            if (body?.msg) msg = body.msg
          } catch {
            // ignore
          }
          throw new Error(msg)
        }
        if (!res.body) {
          throw new Error('浏览器不支持流式读取')
        }
        const reader = res.body.getReader()
        const decoder = new TextDecoder('utf-8')
        let buffer = ''
        while (true) {
          const { done, value } = await reader.read()
          if (done) break
          buffer += decoder.decode(value, { stream: true })
          const chunks = buffer.split(/\n\n/)
          buffer = chunks.pop() || ''
          for (const chunk of chunks) {
            const lines = chunk.split(/\n/)
            let eventName = 'message'
            const dataParts: string[] = []
            for (const raw of lines) {
              if (raw.startsWith('event:')) {
                eventName = raw.slice(6).trim()
              } else if (raw.startsWith('data:')) {
                dataParts.push(raw.slice(5).replace(/^ /, ''))
              }
            }
            const data = dataParts.join('\n')
            if (eventName === 'log') handlers.onLog?.(data)
            else if (eventName === 'meta') handlers.onMeta?.(data)
            else if (eventName === 'status') handlers.onStatus?.(data)
          }
        }
        finish()
      } catch (err) {
        if ((err as { name?: string })?.name === 'AbortError') {
          finish()
          return
        }
        const e = err instanceof Error ? err : new Error('实时日志中断')
        handlers.onError?.(e)
        finish()
      }
    })()

    return () => {
      ctrl.abort()
      finish()
    }
  }
}
