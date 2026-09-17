import { http } from '../http'

export interface DepartmentItem {
  id: number
  departmentCode: string | null
  name: string
  parentId: number | null
  parentName: string | null
  companyId: number | null
  companyName: string | null
  ownershipStatus: 'CONFIRMED' | 'UNMAPPED' | 'CONFLICT'
  unitType: 'DEPARTMENT' | 'WORKSHOP' | 'LEGACY_ORGANIZATION'
  levelNo: number
  status: 'ACTIVE' | 'INACTIVE'
  sourceSystem: string
  activeEmployeeCount: number
  expenseClaimCount: number
  approvedExpenseAmount: number
}

export interface DepartmentQuality {
  departmentCount: number
  employeesWithoutDepartment: number
  claimsWithoutDepartment: number
  nameOnlyDingtalkMappings: number
  departmentsWithoutCompany: number
  organizationConflicts: number
}

export interface DepartmentPayload {
  departmentCode?: string | null
  name: string
  parentId?: number | null
  companyId?: number | null
  ownershipStatus?: 'CONFIRMED' | 'UNMAPPED' | 'CONFLICT'
  unitType?: 'DEPARTMENT' | 'WORKSHOP'
  status?: 'ACTIVE' | 'INACTIVE'
}

export interface CompanyItem {
  id: number
  companyCode: string | null
  name: string
  parentId: number | null
  parentName: string | null
  status: 'ACTIVE' | 'INACTIVE'
  departmentCount: number
  activeEmployeeCount: number
}

export interface CompanyPayload {
  companyCode?: string | null
  name: string
  parentId?: number | null
  status?: 'ACTIVE' | 'INACTIVE'
}

export const fetchDepartments = () =>
  http.get<DepartmentItem[]>('/departments').then(res => res.data)

export const fetchDepartmentQuality = () =>
  http.get<DepartmentQuality>('/departments/quality').then(res => res.data)

export const createDepartment = (data: DepartmentPayload) =>
  http.post<DepartmentItem>('/departments', data).then(res => res.data)

export const updateDepartment = (id: number, data: DepartmentPayload) =>
  http.put<DepartmentItem>(`/departments/${id}`, data).then(res => res.data)

export const fetchCompanies = () =>
  http.get<CompanyItem[]>('/departments/companies').then(res => res.data)

export const createCompany = (data: CompanyPayload) =>
  http.post<CompanyItem>('/departments/companies', data).then(res => res.data)

export const updateCompany = (id: number, data: CompanyPayload) =>
  http.put<CompanyItem>(`/departments/companies/${id}`, data).then(res => res.data)
