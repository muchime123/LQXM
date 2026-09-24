import { http } from '../http'
import { newRequestKey } from '../salesOutbound'

/** 采购订单履约方式：WAREHOUSE 到货入本地库存账；DROP_SHIP 供应商直发客户（不进本地仓）。 */
export type PurchaseFulfillmentMode = 'WAREHOUSE' | 'DROP_SHIP'
/** 审批关口（#35）：草稿 → 待审批 → 已确认（或驳回落回草稿）。 */
export type PurchaseOrderStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'CONFIRMED' | 'RECEIVING' | 'CLOSED' | 'CANCELLED'
export type ApprovalStatus = 'NONE' | 'PENDING' | 'APPROVED' | 'REJECTED'
export type ApprovalAction = 'SUBMIT' | 'APPROVE' | 'REJECT'

/** 审批流水一条（append-only，含驳回次数）。 */
export interface ApprovalEntry {
  id: number
  docType: string
  docId: number
  docNo: string
  action: ApprovalAction
  fromStatus?: string | null
  toStatus?: string | null
  actorEmployeeId: number
  actorName: string
  comment?: string | null
  createdAt: string
}

export interface PurchaseOrderLine {
  id?: number
  lineNo?: number
  productName: string
  specification?: string | null
  salesProductId?: number | null
  unit: string
  quantity: number
  purchasePrice?: number | null
  amount?: number | null
  receivedQuantity?: number
  pendingQuantity?: number
  remark?: string | null
}

export interface PurchaseOrder {
  id: number
  poNo: string
  status: PurchaseOrderStatus
  supplierId?: number | null
  supplierName: string
  warehouseId?: number | null
  warehouseName?: string | null
  fulfillmentMode: PurchaseFulfillmentMode
  salesOrderItemId?: number | null
  salesOrderId?: number | null
  salesOrderNo?: string | null
  customerName?: string | null
  expectedArrivalDate?: string | null
  totalAmount?: number | null
  itemCount?: number
  orderedQuantity?: number
  receivedQuantity?: number
  createdByName?: string
  createdAt?: string
  remark?: string | null
  confirmedAt?: string | null
  approvalStatus?: ApprovalStatus
  approvalSubmittedAt?: string | null
  approvalSubmittedByEmployeeId?: number | null
  approvalDecidedAt?: string | null
  approvalDecidedByEmployeeId?: number | null
}

export interface PurchaseDemand {
  itemId: number
  orderId: number
  orderNo: string
  customerName: string
  salespersonName: string
  productName: string
  specification?: string | null
  unit: string
  quantity: number
  remark?: string | null
  dueDate?: string | null
}

export interface PurchaseReceipt {
  id: number
  receiptNo: string
  status: 'POSTED' | 'REVERSED'
  receivedAt: string
  receivedByName?: string | null
  remark?: string | null
  reversedAt?: string | null
  reversedByName?: string | null
  reversalReason?: string | null
  warehouseId: number
  warehouseName?: string | null
}

export interface PurchaseOrderPayload {
  supplierId?: number | null
  supplierName: string
  warehouseId?: number | null
  fulfillmentMode: PurchaseFulfillmentMode
  salesOrderItemId?: number | null
  expectedArrivalDate?: string | null
  remark?: string
  lines: Array<{
    productName: string
    specification?: string
    salesProductId?: number | null
    unit: string
    quantity: number
    purchasePrice?: number | null
    remark?: string
  }>
}

export interface PurchaseOrderDetail {
  head: PurchaseOrder
  items: PurchaseOrderLine[]
  receipts: PurchaseReceipt[]
  approvals: ApprovalEntry[]
  dropShipOutbound: { id: number; outboundNo: string; status: string } | null
  warehouses: Array<{ id: number; warehouseCode: string; warehouseName: string }>
}

const key = (requestKey?: string) => ({ headers: { 'X-Request-Key': requestKey || newRequestKey() } })

export const fetchPurchaseOrders = async (params: Record<string, unknown>) =>
  (await http.get<{ items: PurchaseOrder[]; total: number; page: number; size: number }>('/erp-purchase-orders', { params })).data

/** 待采购需求：已确认销售订单里供货方式为「外采」、且还没挂有效采购订单的明细行。 */
export const fetchPurchaseDemands = async () =>
  (await http.get<PurchaseDemand[]>('/erp-purchase-orders/demands')).data

export const searchPurchaseSuppliers = async (keyword: string) =>
  (await http.get<Array<{ id: number; name: string }>>('/erp-purchase-orders/suppliers', { params: { keyword } })).data

export const fetchPurchaseWarehouses = async () =>
  (await http.get<Array<{ id: number; warehouseCode: string; warehouseName: string }>>('/erp-purchase-orders/warehouses')).data

export const fetchPurchaseOrder = async (id: number) =>
  (await http.get<PurchaseOrderDetail>(`/erp-purchase-orders/${id}`)).data

export const createPurchaseOrder = async (payload: PurchaseOrderPayload, requestKey?: string) =>
  (await http.post<number>('/erp-purchase-orders', payload, key(requestKey))).data

/** 提交审批（草稿 → 待审批）。⚠️ 已取代原 `confirm`：不保留「草稿直跳已确认」的口子，否则等于绕过审批。 */
export const submitPurchaseOrderApproval = async (id: number, requestKey?: string) =>
  (await http.post(`/erp-purchase-orders/${id}/submit`, {}, key(requestKey))).data

/** 审批通过（待审批 → 已确认）。权限 `purchase_order.approve`。 */
export const approvePurchaseOrder = async (id: number, comment?: string, requestKey?: string) =>
  (await http.post(`/erp-purchase-orders/${id}/approve`, { comment }, key(requestKey))).data

/** 驳回（待审批 → 草稿），原因必填。权限 `purchase_order.approve`。 */
export const rejectPurchaseOrder = async (id: number, comment: string, requestKey?: string) =>
  (await http.post(`/erp-purchase-orders/${id}/reject`, { comment }, key(requestKey))).data

/** 某张采购订单的完整审批历史（含驳回）。 */
export const fetchPurchaseOrderApprovals = async (id: number) =>
  (await http.get<ApprovalEntry[]>(`/erp-purchase-orders/${id}/approvals`)).data

/** 待审批清单（跨三种单据的审批人待办）。 */
export const fetchPendingApprovals = async () =>
  (await http.get<Array<{ docType: string; docLabel: string; docId: number; docNo: string; status: string
    submittedAt?: string | null; submittedByEmployeeId?: number | null; submittedByName?: string | null }>>(
    '/erp-approvals/pending')).data

export const cancelPurchaseOrder = async (id: number, reason: string, requestKey?: string) =>
  (await http.post(`/erp-purchase-orders/${id}/cancel`, { reason }, key(requestKey))).data

/** 到货入库（仅入库型）：按采购明细行提交本次到货数量，服务端写本地库存账。 */
export const receivePurchaseOrder = async (
  id: number,
  payload: { warehouseId: number; items: Array<{ poItemId: number; quantity: number }>; remark?: string },
  requestKey?: string,
) => (await http.post<number>(`/erp-purchase-orders/${id}/receipts`, payload, key(requestKey))).data

export const reversePurchaseReceipt = async (id: number, receiptId: number, reason: string, requestKey?: string) =>
  (await http.post(`/erp-purchase-orders/${id}/receipts/${receiptId}/reverse`, { reason }, key(requestKey))).data

/** 生成代发出库单草稿（仅代发型）；过账时豁免扣库存。 */
export const createDropShipOutbound = async (id: number, requestKey?: string) =>
  (await http.post<number>(`/erp-purchase-orders/${id}/drop-ship-outbound`, {}, key(requestKey))).data

export const PURCHASE_STATUS_TEXT: Record<PurchaseOrderStatus, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  CONFIRMED: '已确认',
  RECEIVING: '履行中',
  CLOSED: '已结案',
  CANCELLED: '已取消',
}

export const APPROVAL_ACTION_TEXT: Record<ApprovalAction, string> = {
  SUBMIT: '提交审批',
  APPROVE: '审批通过',
  REJECT: '驳回',
}

export const APPROVAL_STATUS_TEXT: Record<ApprovalStatus, string> = {
  NONE: '未提交',
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}

export const FULFILLMENT_MODE_TEXT: Record<PurchaseFulfillmentMode, string> = {
  WAREHOUSE: '到货入库',
  DROP_SHIP: '供应商直发',
}
