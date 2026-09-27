import { http } from '../http'
import { newRequestKey } from '../salesOutbound'
import type { ApprovalStatus, ApprovalEntry } from '../approval'

/** 审批关口（#35）：草稿 → 待审批 → 已确认（驳回则退回草稿）。 */
export type SalesOrderStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'CONFIRMED'

export interface ProductionDemandConfig {
  workshopId: number
  productName: string
  boxSize: string
  boxHeight: string
  moldArrangement: string
  boxShape: string
  boxMaterial: string
  voltage: string
  plug: string
  brand: string
  optionalConfigs: string[]
  configNote: string
  extraMold: boolean
  boxLengthMm?: string
  boxWidthMm?: string
  boxDiameterMm?: string
  boxShapeNote?: string
  boxSizeNote?: string
}

export interface SalesOrderLine {
  id?: number
  lineNo?: number
  productName: string
  specification?: string | null
  salesProductId?: number | null
  supplyMode: 'STOCK' | 'MAKE' | 'BUY' | 'UNDECIDED'
  unit: string
  quantity: number
  unitPrice?: number | null
  amount?: number | null
  allocatedQuantity?: number
  shippedQuantity?: number
  productionWorkshopId?: number | null
  productionConfig?: ProductionDemandConfig | null
  productionConfigJson?: string | null
  manufacturingOrderId?: number | null
  requestedDeliveryDate?: string | null
  remark?: string | null
  /** 仅前端用：该行现货产品当前的可售库存数量（不入提交体）。 */
  available?: number
}

export interface SalesOrder {
  id: number
  orderNo: string
  status: SalesOrderStatus
  approvalStatus?: ApprovalStatus
  approvalSubmittedAt?: string | null
  approvalSubmittedByEmployeeId?: number | null
  approvalDecidedAt?: string | null
  approvalDecidedByEmployeeId?: number | null
  customerId?: number | null
  customerName: string
  salespersonName: string
  orderDate: string
  requestedDeliveryDate?: string | null
  remark?: string | null
  totalAmount?: number | null
  itemCount?: number
  createdAt: string
}

export interface SalesOrderPayload {
  customerId?: number | null
  customerName: string
  orderDate: string
  requestedDeliveryDate?: string | null
  remark?: string
  lines: SalesOrderLine[]
}

const key = (requestKey?: string) => ({ headers: { 'X-Request-Key': requestKey || newRequestKey() } })

export const fetchSalesOrders = async (params: Record<string, unknown>) =>
  (await http.get<{ items: SalesOrder[]; total: number; page: number; size: number }>('/erp-sales-orders', { params })).data

export const searchOrderProducts = async (keyword: string) =>
  (await http.get<Array<{ id: number; productCode: string; productName: string; specification?: string; unit: string }>>(
    '/erp-sales-orders/products', { params: { keyword } })).data

export const fetchOrderProductionWorkshops = async () =>
  (await http.get<Array<{ id: number; workshopName: string }>>('/erp-sales-orders/production-workshops')).data

export const fetchOrderProductionOptions = async (workshopId: number) =>
  (await http.get<{ productNames: string[]; optionalConfigs: string[]; productTemplates: Record<string, number | null>; productTemplateNames: Record<string, string> }>(
    '/erp-sales-orders/production-options', { params: { workshopId } })).data

export interface ProductionDemand {
  itemId: number
  orderId: number
  orderNo: string
  customerName: string
  salespersonName: string
  productName: string
  specification?: string | null
  workshopId: number
  workshopName: string
  productionConfigJson: string
  quantity: number
  unit: string
  dueDate?: string | null
  remark?: string | null
}

export const fetchPendingProductionDemands = async () =>
  (await http.get<ProductionDemand[]>('/erp-sales-orders/production-demands')).data

export const acceptProductionDemand = async (orderId: number, itemId: number, requestKey?: string) =>
  (await http.post<number>(`/erp-sales-orders/${orderId}/items/${itemId}/accept-production`,
    {}, key(requestKey))).data

export const fetchSalesOrder = async (id: number) =>
  (await http.get<{ head: SalesOrder; items: SalesOrderLine[]; approvals: ApprovalEntry[] }>(
    `/erp-sales-orders/${id}`)).data

export const createSalesOrder = async (payload: SalesOrderPayload, requestKey?: string) =>
  (await http.post<number>('/erp-sales-orders', payload, key(requestKey))).data

export const updateSalesOrder = async (id: number, payload: SalesOrderPayload, requestKey?: string) =>
  (await http.put<number>(`/erp-sales-orders/${id}`, payload, key(requestKey))).data

/**
 * 提交审批（草稿 → 待审批）。
 * ⚠️ 已取代原 `confirmSalesOrder`：那是单步动作（草稿直跳已确认），保留它等于绕过审批（决策 #35）。
 * 权限 `sales_order.manage`（提交与审批是两个独立权限）。
 */
export const submitSalesOrderApproval = async (id: number, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/${id}/submit`, {}, key(requestKey))).data

/** 审批通过（待审批 → 已确认）。权限 `sales_order.approve`。 */
export const approveSalesOrder = async (id: number, comment?: string, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/${id}/approve`, { comment }, key(requestKey))).data

/** 驳回（待审批 → 草稿），原因必填。权限 `sales_order.approve`。 */
export const rejectSalesOrder = async (id: number, comment: string, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/${id}/reject`, { comment }, key(requestKey))).data

/** 某张销售订单的完整审批历史（含驳回）。 */
export const fetchSalesOrderApprovals = async (id: number) =>
  (await http.get<ApprovalEntry[]>(`/erp-sales-orders/${id}/approvals`)).data

export const SALES_ORDER_STATUS_TEXT: Record<SalesOrderStatus, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  CONFIRMED: '已确认',
}

export const createOrderOutboundDraft = async (orderId: number, itemId: number, warehouseId: number, quantity: number, requestKey?: string) =>
  (await http.post<number>(`/erp-sales-orders/${orderId}/items/${itemId}/outbound-draft`, { warehouseId, quantity }, key(requestKey))).data
