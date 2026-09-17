import { http } from '../http'

// 费用分类（中文标签，对应 DB 英文枚举在后端 ExpenseService 翻译）
export const EXPENSE_CATEGORY_OPTIONS = [
  { value: 'TRAVEL', label: '差旅费' },
  { value: 'OFFICE', label: '办公费' },
  { value: 'ENTERTAIN', label: '业务招待' },
  { value: 'TRANSPORT', label: '交通费' },
  { value: 'MEAL', label: '餐饮费' },
  { value: 'OTHER', label: '其他' },
  { value: 'CORP_PAY', label: '对公付款' }
]

// 审批状态（与后端字典对齐）
export const APPROVAL_STATUS_OPTIONS = [
  { value: 'APPROVED', label: '已通过', type: 'success' },
  { value: 'PENDING',  label: '审批中', type: 'warning' },
  { value: 'REJECTED', label: '已拒绝', type: 'danger' },
  { value: 'CANCELED', label: '已撤销', type: 'info' },
  { value: 'TERMINATED', label: '终止', type: 'info' }
]

// 费用类型（批量编辑枚举）
export type ExpenseType = 'SALES' | 'PRODUCTION' | 'ADMIN' | 'R&D' | 'OTHER'

export const EXPENSE_TYPE_OPTIONS: { value: ExpenseType; label: string }[] = [
  { value: 'SALES', label: '销售费用' },
  { value: 'PRODUCTION', label: '生产费用' },
  { value: 'ADMIN', label: '管理费用' },
  { value: 'R&D', label: '研发费用' },
  { value: 'OTHER', label: '其他' }
]

// 付款/结算状态（批量编辑枚举）
export type PaymentSettlementStatus = 'UNPAID' | 'PAID'

export const PAYMENT_SETTLEMENT_STATUS_OPTIONS: { value: PaymentSettlementStatus; label: string }[] = [
  { value: 'UNPAID', label: '未付款' },
  { value: 'PAID', label: '已付款' }
]

// 部门选项（可后续从员工/组织数据同步）
export const DEPARTMENT_OPTIONS: { value: string; label: string }[] = []

export interface ExpenseStats {
  totalValidClaims: number
  thisMonthAmount: number
  awaitingMappingCount: number
  pendingAllocationCount: number
  /** 待归集核定金额合计（对公付款待核对金额） */
  pendingAllocationAmount?: number
}

export interface ClaimRow {
  id: number
  claimNo: string
  title: string
  approvalStatus: string
  expenseCategory: string
  claimDate: string | null
  approvalFinishedDate: string | null
  employeeId: number | null
  submitterName: string | null
  submitterDepartment: string | null
  totalAmount: number | null
  approvedAmount: number | null
  remark: string | null
  createdAt: string | null
  updatedAt: string | null
  // ===== P6.2 归集进度聚合（列表页批量一次 JOIN 计算；历史接口可返回 null）=====
  confirmedAllocationSum?: number | null
  confirmedAllocationCount?: number | null
  allocationPct?: number | null
}

export interface ClaimItemRow {
  id: number
  sequenceNo: number | null
  expenseType: string | null
  itemDate: string | null
  itemDesc: string | null
  amount: number | null
  taxAmount: number | null
  invoiceNo: string | null
  receiptType: string | null
  remark: string | null
}

export interface ClaimDetail extends ClaimRow {
  externalSource: string
  externalId: string
  dingtalkUserId: string | null
  currency: string
  attachmentInfo: string | null
  sourceCreatedAt: string | null
  sourceUpdatedAt: string | null
  lastSyncedAt: string | null
  items: ClaimItemRow[]
}

export interface PageResult<T> { items: T[]; total: number; page: number; size: number }

export interface BatchUpdateExpenseFields {
  expenseType?: ExpenseType
  expenseDate?: string
  departmentName?: string
  settlementStatus?: PaymentSettlementStatus
  remark?: string
}

// ===== 顶部指标 =====
export function fetchExpenseStats() {
  return http.get<ExpenseStats>('/expenses/stats').then(r => r.data)
}

// ===== 分页搜索 =====
export function fetchExpenses(params?: Record<string, any>) {
  return http.get<PageResult<ClaimRow>>('/expenses', { params }).then(r => r.data)
}

// ===== 详情（含明细） =====
export function fetchExpenseDetail(id: number) {
  return http.get<ClaimDetail>(`/expenses/${id}`).then(r => r.data)
}

export interface AllocationRow {
  id: number
  expenseClaimId: number
  expenseItemId: number | null
  targetType: string
  targetId: number
  targetNoSnapshot: string | null
  targetNameSnapshot: string | null
  allocationRatioPct: number | null
  allocatedAmount: number
  allocationStatus: string
  allocationSource: 'MANUAL' | 'AUTO_RULE' | 'ADJUSTMENT' | string | null
  allocationRuleId: number | null
  allocationRunId: number | null
  hitRuleName: string | null
  remark: string | null
  createdBy: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface AllocationSummary {
  approvedAmount: number
  confirmedSum: number
  remainingToAllocate: number
  confirmedAllocationCount: number
  reversedSum: number
  reversedAllocationCount: number
}

export interface TargetCandidate {
  targetType: string
  targetId: number
  targetNo: string
  targetName: string
  extraInfo: string
}

// ===== API =====

export function fetchAllocationSummary(claimId: number) {
  return http.get<AllocationSummary>(`/expenses/${claimId}/allocations/summary`).then(r => r.data)
}

export function fetchAllocations(claimId: number, includeReversed = false) {
  return http.get<{ items: AllocationRow[]; total: number }>(
    `/expenses/${claimId}/allocations`, { params: { includeReversed } }
  ).then(r => r.data)
}

export function reverseAllocation(allocationId: number, reason?: string) {
  return http.post<AllocationRow>(
    `/expenses/allocations/${allocationId}/reverse`,
    null,
    { params: { reason } }
  ).then(r => r.data)
}

export function searchAllocationTargets(targetType: string, keyword?: string, page = 1, size = 20) {
  return http.get<PageResult<TargetCandidate>>(
    '/expenses/allocations/targets/search',
    { params: { targetType, keyword, page, size } }
  ).then(r => r.data)
}

// ==================== 钉钉报销 Excel 导入（2026-08-26） ====================
// 数据源：钉钉 OA 审批后台导出的「日常报销」Excel（两级表头，数据从第 3 行开始，
// 每行 = 一条明细，同一「审批编号」合并为一条报销单）。

export type ExpenseImportAction = 'CREATE' | 'UPDATE' | 'UNCHANGED' | 'ERROR'

export interface ExpenseImportGroupRow {
  /** 审批编号（= DB external_id / claim_no） */
  approvalNo: string
  title: string
  submitterName: string | null
  department: string | null
  approvalStatus: string
  expenseCategory: string
  itemCount: number
  totalAmount: number | null
  claimDate: string | null
  approvalFinishedDate: string | null
  action: ExpenseImportAction
  error: string | null
  /** 归属人未能映射到系统员工 */
  mappingMissed: boolean
}

export interface ExpenseImportPreview {
  fileName: string | null
  totalRows: number
  totalGroups: number
  createCount: number
  updateCount: number
  unchangedCount: number
  errorCount: number
  mappingMissedCount: number
  groups: ExpenseImportGroupRow[]
}

export interface ExpenseImportResult {
  totalGroups: number
  createdClaims: number
  updatedClaims: number
  unchangedClaims: number
  errorCount: number
  mappingMissedCount: number
  itemCount: number
  errors: string[]
}

/** 解析钉钉导出 Excel 并返回分组预览（不落库）。 */
export function previewExpenseImport(file: File) {
  const fd = new FormData()
  fd.append('file', file)
  // 不手动设置 Content-Type：浏览器自动带 boundary，手动覆盖会导致后端解析失败
  return http.post<ExpenseImportPreview>('/expenses/import/preview', fd, { timeout: 120000 }).then(r => r.data)
}

/** 确认导入；approvalNos 为空时导入文件内全部单。 */
export function confirmExpenseImport(file: File, approvalNos?: string[]) {
  const fd = new FormData()
  fd.append('file', file)
  ;(approvalNos ?? []).forEach(no => fd.append('approvalNos', no))
  return http.post<ExpenseImportResult>('/expenses/import/confirm', fd, { timeout: 300000 }).then(r => r.data)
}

export interface PendingOutboundAllocation {
  allocationId: number
  expenseClaimId: number
  claimNo: string
  claimTitle: string
  submitterName?: string | null
  outboundId: number
  outboundNo: string
  customerName?: string | null
  allocatedAmount: number
  allocationStatus: string
  allocationSource?: string | null
  matchMethod?: string | null
  confidence?: number | null
  remark?: string | null
  createdAt?: string | null
}

// ===== API =====

export function confirmAllocation(allocationId: number) {
  return http.post<AllocationRow>(`/expenses/allocations/${allocationId}/confirm`).then(r => r.data)
}

export function fetchPendingOutboundAllocations(page = 1, size = 20) {
  return http.get<PageResult<PendingOutboundAllocation>>('/expenses/allocations/pending-outbound', {
    params: { page, size }
  }).then(r => r.data)
}

