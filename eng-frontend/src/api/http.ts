import axios from 'axios'
import { message } from 'ant-design-vue'
import { useUserStore } from '@/stores/user'

declare module 'axios' {
  export interface AxiosRequestConfig {
    skipErrorToast?: boolean
  }
}

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

request.interceptors.request.use((config) => {
  const userStore = useUserStore()
  if (userStore.token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${userStore.token}`
  }
  return config
})

function isLoginRequest(url?: string): boolean {
  return !!url && url.includes('/auth/login')
}

/** 仅在已登录态且非登录接口时清会话并跳转 */
function handleUnauthorized(url?: string) {
  if (isLoginRequest(url)) return
  const userStore = useUserStore()
  if (!userStore.token) return
  userStore.clearAuth()
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}

function toastByCode(code: number | undefined, text: string) {
  if (code === 403) {
    message.warning(text || '无权限访问')
    return
  }
  // 429：共用算力满员，属容量提示而非业务失败
  if (code === 429) {
    message.warning(text || '当前处理人数较多，请稍后再试')
    return
  }
  message.error(text || '请求失败')
}

function resolveErrorMessage(error: unknown): string {
  if (!axios.isAxiosError(error)) {
    return error instanceof Error ? error.message : '请求失败'
  }
  const data = error.response?.data as { msg?: string; code?: number } | undefined
  if (data?.msg) return data.msg
  const status = error.response?.status
  if (status === 401) return '未登录或登录已失效'
  if (status === 403) return '无权限访问'
  if (status === 429) return '当前处理人数较多，请稍后再试'
  if (status && status >= 500) return '服务异常，请稍后重试'
  // 超时 / 连接中断（含前端 timeout、代理断开）
  const code = error.code || ''
  const msg = (error.message || '').toLowerCase()
  if (code === 'ECONNABORTED' || msg.includes('timeout')) {
    return '请求超时，请稍后重试；若长时间无响应可刷新页面'
  }
  if (!error.response) {
    if (code === 'ERR_NETWORK' || msg.includes('network')) {
      return '网络异常，请检查网络后重试'
    }
    return '网络异常，请稍后重试'
  }
  // 避免把 axios 英文状态句直接抛给用户
  return '请求失败，请稍后重试'
}

request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res && typeof res === 'object' && 'code' in res) {
      if (res.code !== 200) {
        const errMsg = (res as { msg?: string }).msg || '请求失败'
        const err = new Error(errMsg)
        if (!response.config.skipErrorToast) {
          toastByCode(Number(res.code), errMsg)
        }
        if (res.code === 401) {
          handleUnauthorized(response.config.url)
        }
        return Promise.reject(err)
      }
      return res.data
    }
    return response.data
  },
  (error) => {
    const msg = resolveErrorMessage(error)
    if (axios.isAxiosError(error) && error.response?.status === 401) {
      handleUnauthorized(error.config?.url)
    }
    if (!axios.isAxiosError(error) || !error.config?.skipErrorToast) {
      const status = axios.isAxiosError(error) ? error.response?.status : undefined
      const bodyCode = axios.isAxiosError(error)
        ? (error.response?.data as { code?: number } | undefined)?.code
        : undefined
      toastByCode(bodyCode ?? status, msg)
    }
    return Promise.reject(new Error(msg))
  }
)

export default request
