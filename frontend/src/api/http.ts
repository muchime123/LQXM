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
        const error: ApiError = {
          message: data.message,
          timestamp: data.timestamp
        }
        return Promise.reject(error)
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

    const apiError: ApiError = { message, timestamp }
    return Promise.reject(apiError)
  }
)

function isApiResponse(data: unknown): data is BackendApiResponse {
  if (!data || typeof data !== 'object') return false
  const obj = data as Record<string, unknown>
  return 'code' in obj && 'message' in obj && 'timestamp' in obj
}
