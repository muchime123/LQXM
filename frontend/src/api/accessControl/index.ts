import { http } from '../http'

export interface SystemPermission {
  id: number
  permission_code: string
  permission_name: string
  enabled?: boolean
}

/** 功能动作：permission_code 归到功能 + 动作分类；variant 为查看的范围变体码标注。 */
export interface FeatureAction {
  feature_code: string
  action_kind: 'VIEW' | 'CREATE' | 'UPDATE' | 'DELETE' | 'EXECUTE'
  permission_code: string
  permission_name: string
  variant: 'SELF' | 'DEPARTMENT' | 'ALL' | null
}

/** 功能目录（system_features） */
export interface FeatureItem {
  id: number
  feature_code: string
  feature_name: string
  category: string
  sort_order: number
  enabled: boolean
  actions: FeatureAction[]
}

export type DataScope = 'SELF' | 'DEPARTMENT' | 'ALL' | 'CUSTOM' | null

/** 角色-功能授权行 */
export interface FeatureGrant {
  featureCode: string
  dataScope: DataScope
  employeeIds: number[]
  departmentNames: string[]
  /** 'VIEW'（查看）+ 勾选的功能下权限码 */
  actions: string[]
}

export interface SystemRole {
  id: number
  role_code: string
  role_name: string
  data_scope: 'SELF' | 'DEPARTMENT' | 'ALL'
  built_in: boolean
  permissionCodes?: string[]
  featureGrants?: FeatureGrant[]
  memberCount?: number
}

export interface RolePayload {
  roleCode?: string
  roleName: string
  grants: FeatureGrant[]
}

export function fetchSystemRoles() {
  return http.get<SystemRole[]>('/access-control/roles').then((r) => r.data)
}

export function fetchSystemPermissions() {
  return http.get<SystemPermission[]>('/access-control/permissions').then((r) => r.data)
}

/** 功能目录（含每功能动作与范围变体），权限配置 UI 的数据源。 */
export function fetchSystemFeatures() {
  return http.get<FeatureItem[]>('/access-control/features').then((r) => r.data)
}

/** 启停用权限码：停用=即时从所有用户权限中下线，启用=恢复。 */
export function setPermissionEnabled(permissionId: number, enabled: boolean) {
  return http.put(`/access-control/permissions/${permissionId}/enabled`, { enabled }).then((r) => r.data)
}

export function createRole(payload: RolePayload) {
  return http.post<SystemRole>('/access-control/roles', payload).then((r) => r.data)
}

export function updateRole(roleId: number, payload: RolePayload) {
  return http.put<SystemRole>(`/access-control/roles/${roleId}`, payload).then((r) => r.data)
}

export function deleteRole(roleId: number) {
  return http.delete(`/access-control/roles/${roleId}`).then((r) => r.data)
}

export function fetchEmployeeRoles(employeeId: number) {
  return http.get<{ employee: Record<string, unknown>; roleCodes: string[] }>(`/access-control/employees/${employeeId}/roles`).then((r) => r.data)
}

export function updateEmployeeRoles(employeeId: number, roleCodes: string[]) {
  return http.put(`/access-control/employees/${employeeId}/roles`, { roleCodes }).then((r) => r.data)
}

// ==================== 角色成员管理 ====================

export interface RoleMember {
  id: number
  employee_no: string | null
  name: string
  department: string | null
  status: string
}

export function fetchRoleMembers(roleId: number) {
  return http.get<RoleMember[]>(`/access-control/roles/${roleId}/members`).then((r) => r.data)
}

export function addRoleMembers(roleId: number, employeeIds: number[]) {
  return http.post(`/access-control/roles/${roleId}/members`, { employeeIds }).then((r) => r.data)
}

export function removeRoleMember(roleId: number, employeeId: number) {
  return http.delete(`/access-control/roles/${roleId}/members/${employeeId}`).then((r) => r.data)
}
