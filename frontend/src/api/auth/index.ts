import { http } from '../http'

export type DataScope = 'SELF' | 'DEPARTMENT' | 'ALL'
export type LoginType = 'LOCAL' | 'COMPAT' | 'NONE'

export interface CurrentUser {
  employeeId: number | null
  name: string
  department: string | null
  roles: string[]
  permissions: string[]
  dataScope: DataScope
  compatibilityMode: boolean
  loginType: LoginType
  loginAccountId: number | null
  externalUserId: string | null
}

export interface LoginResult {
  token: string
  user: CurrentUser
}

export interface LoginConfig {
  localLoginEnabled: boolean
  allowEmployeeHeader: boolean
}

export interface LoginAccount {
  id: number
  employeeId: number
  employeeName: string
  department: string
  username: string
  enabled: boolean
  lastLoginAt: string | null
  createdAt: string
}

// ==================== 会话 ====================

export async function getCurrentUser() {
  return (await http.get<CurrentUser>('/auth/me')).data
}

export async function loginLocal(username: string, password: string) {
  return (await http.post<LoginResult>('/auth/login/local', { username, password })).data
}

export async function logout() {
  return http.post('/auth/logout')
}

export async function getLoginConfig() {
  return (await http.get<LoginConfig>('/auth/login-config')).data
}

/** 登录页公开员工列表（无需 Token）：首次引导或开发兼容模式返回在职员工。 */
export async function getPublicEmployees() {
  return (await http.get<Array<{ id: number; name: string; department: string | null }>>('/auth/public-employees')).data
}

// ==================== 首次引导 / 登录账号管理 ====================

export async function bootstrapAccount(payload: { employeeId: number }) {
  return (await http.post<LoginResult>('/auth/bootstrap', payload)).data
}

export async function fetchEmployeeLoginAccount(employeeId: number) {
  return (await http.get<LoginAccount | null>(`/auth/accounts/employee/${employeeId}`)).data
}

export async function resetLoginPassword(id: number) {
  return http.post(`/auth/accounts/${id}/reset-password`)
}

export async function setLoginAccountEnabled(id: number, enabled: boolean) {
  return http.put(`/auth/accounts/${id}/enabled`, { enabled })
}
