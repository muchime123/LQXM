import { http } from '../http'

// ==================== 查询 ====================

export function fetchEmployees(params?: Record<string, any>) {
  return http.get('/employees', { params }).then(res => res.data)
}

export function fetchEmployee(id: number) {
  return http.get(`/employees/${id}`).then(res => res.data)
}

export function fetchEmployeeFilters() {
  return http.get('/employees/filters').then(res => res.data)
}

// ==================== 增删改 ====================

export function createEmployee(data: Record<string, any>) {
  return http.post('/employees', data).then(res => res.data)
}

export function updateEmployee(id: number, data: Record<string, any>) {
  return http.put(`/employees/${id}`, data).then(res => res.data)
}

export function deleteEmployee(id: number) {
  return http
    .delete<{ action: 'DELETED' | 'RETIRED'; message: string; referenceTables: string[] }>(`/employees/${id}`)
    .then(res => res.data)
}

// ==================== 外部身份映射 ====================

export function fetchEmployeeMappings(employeeId: number) {
  return http.get(`/employees/${employeeId}/mappings`).then(res => res.data)
}

export function addEmployeeMapping(employeeId: number, data: Record<string, any>) {
  return http.post(`/employees/${employeeId}/mappings`, data).then(res => res.data)
}

export function updateEmployeeMapping(employeeId: number, mappingId: number, data: Record<string, any>) {
  return http.put(`/employees/${employeeId}/mappings/${mappingId}`, data).then(res => res.data)
}

export function removeEmployeeMapping(employeeId: number, mappingId: number) {
  return http.delete(`/employees/${employeeId}/mappings/${mappingId}`)
}

// ==================== 外部通讯录导入映射（V186：氚云/钉钉导出文件） ====================

export function parseExternalIdentities(hyFile?: File, ddFile?: File, rosterFile?: File) {
  const form = new FormData()
  if (hyFile) form.append('hyFile', hyFile)
  if (ddFile) form.append('ddFile', ddFile)
  if (rosterFile) form.append('rosterFile', rosterFile)
  return http.post('/employees/external-identities/parse', form).then(res => res.data)
}

export function applyExternalIdentities(items: Array<Record<string, any>>) {
  return http.post('/employees/external-identities/apply', items).then(res => res.data)
}

// ==================== 档案文件 ====================

export interface EmployeeFileRegistration {
  category: string
  fileName: string
  originalFileName?: string
  mimeType?: string
  fileSize?: number
}

export function registerEmployeeFile(employeeId: number, payload: EmployeeFileRegistration) {
  return http.post(`/employees/${employeeId}/files`, payload).then(res => res.data)
}
