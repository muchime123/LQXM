import type { ApiError, BackendApiResponse } from '@/types'
import type { AxiosInstance, AxiosResponse } from 'axios'
import axios from 'axios'
import { reportBackendStatus } from '@/composables/ui/useAppStatus'

export const http: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 120000
})

http.interceptors.request.use(config => {
  // P7 正式登录会话（主身份依据）
  const token = localStorage.getItem('lq.token')
  if (token) config.headers['Authorization'] = `Bearer ${token}`
  // 开发环境兼容：X-Employee-Id 仅在后端 app.security.allow-employee-header=true 时生效
  const employeeId = localStorage.getItem('lq.currentEmployeeId')
  const userName = localStorage.getItem('lq.currentUserName')
  if (employeeId) config.headers['X-Employee-Id'] = employeeId
  if (userName) config.headers['X-User-Name'] = userName
  return config
})

http.interceptors.response.use(
  (response: AxiosResponse) => {
    reportBackendStatus(true)
    const data = response.data as BackendApiResponse | unknown
    if (isApiResponse(data)) {
      if (data.code !== 0 && data.code !== 200) {
        return Promise.reject(apiRejection(data.message, data.timestamp, null))
      }
      return { ...response, data: data.data }
    }
    return response
  },
  error => {
    const responseData = error?.response?.data as BackendApiResponse | unknown
    const noResponse = !error?.response
    const serverError = error?.response?.status >= 500
    reportBackendStatus(!(noResponse || serverError))
    let message = error?.message || '请求失败'
    let timestamp = new Date().toISOString()

    if (isApiResponse(responseData)) {
      message = responseData.message
      timestamp = responseData.timestamp
    }

    // P7：会话失效（401）→ 清除本地 Token 并跳转登录页（hash 路由，避免循环依赖）
    if (error?.response?.status === 401) {
      localStorage.removeItem('lq.token')
      const hash = window.location.hash || '#/'
      if (!hash.startsWith('#/login')) {
        const redirect = encodeURIComponent(hash.slice(1) || '/')
        window.location.hash = `#/login?redirect=${redirect}`
      }
    }

    const apiError = apiRejection(message, timestamp, error)
    return Promise.reject(apiError)
  }
)

/**
 * 把错误的拒绝值做成**真正的 Error 实例**（字段仍是 {@link ApiError} 的 message + timestamp）。
 *
 * <p>为什么必须是 Error：拦截器原先 reject 的是纯对象 `{message,timestamp}`，而各页面取消息
 * 普遍写成 `err instanceof Error ? err.message : '通用兜底'`（建单抽屉、证书页、公司信息页等
 * 十余处），对象过不了 `instanceof` 判断 —— 服务端给的具体原因（例如「产品未关联制程，且车间
 * 未设置默认制程」）就被吞掉，用户只看到「创建失败，请重试」和浏览器控制台的 400。
 * 2026-09-23 建单失败即由该缺陷导致无法自解释，故统一改为 Error 实例。
 *
 * <p>`name` 沿用原错误名（axios 取消请求叫 CanceledError/AbortError），以免打断
 * `err.name === 'AbortError'` 这类静默取消判断。
 */
function apiRejection(message: string, timestamp: string, original: unknown): Error & ApiError {
  const apiError = new Error(message) as Error & ApiError
  apiError.name = original instanceof Error && original.name ? original.name : 'ApiError'
  apiError.timestamp = timestamp
  return apiError
}

function isApiResponse(data: unknown): data is BackendApiResponse {
  if (!data || typeof data !== 'object') return false
  const obj = data as Record<string, unknown>
  return 'code' in obj && 'message' in obj && 'timestamp' in obj
}
