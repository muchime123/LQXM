import { http } from '../http'

export interface H3ExecutionOrder {
  id: number
  workOrderNo: string
  productName?: string
  customerName?: string
  productionLine?: string
  status?: string
  requiredQuantity?: number
  requiredCompletionDate?: string
  lastSyncedAt?: string
  componentCount: number
  completedComponentCount: number
  productionRequirement?: string
  processName?: string
  completedQuantity?: number
  warehousedQuantity?: number
  warehousingCompleted?: boolean | number
  localPostedCompletionCount?: number
  productionProgress?: string
  reworkStatus?: 'REWORKING' | 'PENDING_REINSPECTION' | null
}
export interface H3ExecutionComponent {
  externalId?: string
  sequenceNo?: number
  componentName?: string
  componentSerialNo?: string
  requiredQuantity?: number
  completedQuantity?: number
  completionRatio?: string
  completedAt?: string
  completed?: boolean | number
  effectiveCompleted?: number
  effectiveQuantity?: number
  localReportedQuantity?: number
  activeReworkId?: number
  reworkTargetQuantity?: number
  reworkReportedQuantity?: number
  reworkStatus?: 'REWORKING' | 'PENDING_REINSPECTION'
}
export interface H3ExecutionReport {
  id: number
  reportTime?: string
  componentSerial?: string
  componentName?: string
  reportQty?: number
  reporter?: string
  reporterAssist?: string
  dataStatus?: string
}
export interface H3ExecutionDetail {
  order: H3ExecutionOrder
  components: H3ExecutionComponent[]
  reports: H3ExecutionReport[]
  localReports: H3LocalReport[]
  reportCount: number
  componentCount: number
  completedComponentCount: number
}
export interface H3LocalReport {
  id: number
  componentExternalId: string
  componentNameSnapshot: string
  quantity: number
  status: 'VALID' | 'REVERSED'
  remark?: string
  reportedByName: string
  reportedAt: string
  reversedByName?: string
  reversedAt?: string
  reversalReason?: string
}
export const fetchH3ExecutionOrders = async (params: { keyword: string; page: number; size: number; state?: string }) =>
  (await http.get<{ items: H3ExecutionOrder[]; total: number }>('/h3-production-execution', { params })).data
/** 看板统计卡：与列表同一数据范围、同一阶段口径；keyword 生效，卡片筛选不影响分母。 */
export interface H3ExecutionSummary {
  total: number
  notStarted: number
  inProduction: number
  pendingInspection: number
  rework: number
  warehoused: number
  overdue: number
}
export const fetchH3ExecutionSummary = async (params: { keyword: string }) =>
  (await http.get<H3ExecutionSummary>('/h3-production-execution/summary', { params })).data
export const fetchH3ExecutionDetail = async (id: number) =>
  (await http.get<H3ExecutionDetail>(`/h3-production-execution/${id}`)).data
export const submitH3ExecutionReport = async (id: number, componentExternalId: string,
  payload: { quantity: number; remark: string }, key: string) =>
  (await http.post<{ id: number }>(`/h3-production-execution/${id}/components/${encodeURIComponent(componentExternalId)}/reports`,
    payload, { headers: { 'X-Request-Key': key } })).data
export const reverseH3ExecutionReport = async (id: number, reportId: number, reason: string, key: string) =>
  (await http.post<{ id: number }>(`/h3-production-execution/${id}/reports/${reportId}/reverse`,
    { reason }, { headers: { 'X-Request-Key': key } })).data

export interface H3CompletionItem {
  completionId: number
  lineNo: number
  isPrimary: boolean | number
  productCode: string
  productName: string
  specification?: string
  unit: string
  quantity: number
  transactionNo?: string
  balanceAfter?: number
}
export interface H3Completion {
  id: number
  workOrderNo: string
  status: 'POSTED' | 'REVERSED'
  warehouseId: number
  warehouseName: string
  remark?: string
  inspectedByName: string
  inspectedAt: string
  reversedByName?: string
  reversedAt?: string
  reversalReason?: string
}
export interface H3Warehouse { id: number; warehouseCode: string; warehouseName: string }
export interface H3ProductOption { id: number; productCode: string; productName: string; specification?: string; unit: string }
export interface H3CompletionDetail { completions: H3Completion[]; items: H3CompletionItem[] }
export interface H3CompletionInputItem { salesProductId: number; quantity: number; primary: boolean }
export const fetchH3Completion = async (orderId: number) =>
  (await http.get<H3CompletionDetail>(`/h3-production-completions/${orderId}`)).data
export const fetchH3Warehouses = async () =>
  (await http.get<H3Warehouse[]>('/h3-production-completions/warehouses')).data
export const searchH3Products = async (keyword: string) =>
  (await http.get<H3ProductOption[]>('/h3-production-completions/products', { params: { keyword } })).data
export const completeH3Production = async (orderId: number,
  payload: { warehouseId: number; items: H3CompletionInputItem[]; remark: string }, key: string) =>
  (await http.post<{ id: number }>(`/h3-production-completions/${orderId}`, payload,
    { headers: { 'X-Request-Key': key } })).data
export const reverseH3Completion = async (orderId: number, completionId: number, reason: string, key: string) =>
  (await http.post<{ id: number }>(`/h3-production-completions/${orderId}/${completionId}/reverse`,
    { reason }, { headers: { 'X-Request-Key': key } })).data

export interface H3Rework {
  id: number
  componentExternalId: string
  componentName: string
  targetQuantity: number
  reportedQuantity: number
  status: 'REWORKING' | 'PENDING_REINSPECTION' | 'REJECTED' | 'RESOLVED'
  failureReason: string
  inspectedByName: string
  inspectedAt: string
  closedAt?: string
}
export interface H3ReworkReport {
  id: number
  reworkId: number
  quantity: number
  status: 'VALID' | 'REVERSED'
  remark?: string
  reportedByName: string
  reportedAt: string
  reversedByName?: string
  reversedAt?: string
  reversalReason?: string
}
export interface H3ReworkDetail {
  activeRework: H3Rework | null
  reworks: H3Rework[]
  reworkReports: H3ReworkReport[]
}
export const fetchH3Reworks = async (orderId: number) =>
  (await http.get<H3ReworkDetail>(`/h3-production-reworks/${orderId}`)).data
export const failH3Inspection = async (orderId: number,
  payload: { componentExternalId: string; quantity: number; reason: string }, key: string) =>
  (await http.post<{ id: number }>(`/h3-production-reworks/${orderId}/fail`, payload,
    { headers: { 'X-Request-Key': key } })).data
export const reportH3Rework = async (orderId: number, reworkId: number,
  payload: { quantity: number; remark: string }, key: string) =>
  (await http.post<{ id: number }>(`/h3-production-reworks/${orderId}/${reworkId}/reports`, payload,
    { headers: { 'X-Request-Key': key } })).data
export const reverseH3ReworkReport = async (orderId: number, reworkId: number, reportId: number,
  reason: string, key: string) =>
  (await http.post<{ id: number }>(`/h3-production-reworks/${orderId}/${reworkId}/reports/${reportId}/reverse`,
    { reason }, { headers: { 'X-Request-Key': key } })).data
