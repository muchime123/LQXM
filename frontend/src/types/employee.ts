export interface EmployeeSummary {
  id: number
  employeeNo: string | null
  name: string
  gender: string | null
  department: string | null
  departmentId: number | null
  companyId: number | null
  position: string | null
  phone: string | null
  status: string // 在职 / 离职 / 停薪留职
  isSalesperson: boolean
  entryDate: string | null
  createdAt: string
  accountStatus: 'NORMAL' | 'MISSING_PHONE' | 'MISSING_ACCOUNT' | 'DISABLED'
}

export interface EmployeeDetail {
  id: number
  employeeNo: string | null
  name: string
  gender: string | null
  department: string | null
  departmentId: number | null
  companyId: number | null
  position: string | null
  phone: string | null
  idCardNumber: string | null
  email: string | null
  entryDate: string | null
  leaveDate: string | null
  status: string
  isSalesperson: boolean
  remark: string | null
  createdBy: string | null
  updatedBy: string | null
  createdAt: string
  updatedAt: string
  mappings: EmployeeMappingSummary[]
}

export interface EmployeePayload {
  employeeNo?: string | null
  name?: string
  gender?: string | null
  department?: string | null
  departmentId?: number | null
  companyId?: number | null
  position?: string | null
  phone?: string | null
  idCardNumber?: string | null
  email?: string | null
  entryDate?: string | null
  leaveDate?: string | null
  status?: string
  isSalesperson?: boolean
  remark?: string | null
}

export interface EmployeeMappingSummary {
  id: number
  employeeId: number
  sourceSystem: string // 氚云 / 钉钉 / 系统账号
  externalUserId: string
  externalName: string | null
  externalDepartment: string | null
  externalStatus: string | null
  externalPosition: string | null
  externalRoles: string | null
  isPrimary: boolean
  remark: string | null
  mappedAt: string
}

export interface EmployeeMappingPayload {
  sourceSystem?: string
  externalUserId?: string
  externalName?: string | null
  externalDepartment?: string | null
  isPrimary?: boolean
  remark?: string | null
}

export interface EmployeeFilterOptions {
  departments: string[]
  companies: Array<{ id: number; name: string; parentId: number | null; parentName: string | null }>
  statuses: string[]
  sourceSystems: string[]
  totalCount: number
  activeCount: number
  salespersonCount: number
}
