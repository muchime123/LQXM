import { http } from '../http'
import { newRequestKey } from '../salesOutbound'
import type { ApprovalStatus, ApprovalEntry } from '../approval'
import type { PurchaseDemandConfig } from '../salesOrder'

/** 采购订单履约方式：WAREHOUSE 到货入本地库存账；DROP_SHIP 供应商直发客户（不进本地仓）。 */
export type PurchaseFulfillmentMode = 'WAREHOUSE' | 'DROP_SHIP'
/** 审批关口（#35）：草稿 → 待审批 → 已确认（或驳回落回草稿）。 */
export type PurchaseOrderStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'CONFIRMED' | 'RECEIVING' | 'CLOSED' | 'CANCELLED'

// 审批的类型与词表统一放在 api/approval（三条链共用一套），这里只转出、不再各写一份。
export type { ApprovalStatus, ApprovalAction, ApprovalEntry, PendingApproval, ApprovalDocType } from '../approval'
export { fetchPendingApprovals } from '../approval'

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
  purchaseConfigJson?: string | null
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
  salesDemandDeliveryDate?: string | null
  salesOrderRevisionNo?: number | null
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
  salesProductId?: number | null
  purchaseConfigJson?: string | null
  purchaseConfig?: PurchaseDemandConfig | null
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
export const searchPurchaseProducts = async (keyword:string) =>
  (await http.get<Array<{id:number;productCode:string;productName:string;specification:string;unit:string}>>('/erp-purchase-orders/products',{params:{keyword}})).data

/** 为新外采品建立本地库存产品身份，无需先导入氚云产品目录。 */
export const createPurchaseProduct = async (payload: { productName: string; specification?: string; unit: string }, requestKey?: string) =>
  (await http.post<{id:number;productCode:string;productName:string;specification:string;unit:string}>(
    '/erp-purchase-orders/products', payload, key(requestKey)
  )).data

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

export const cancelPurchaseOrder = async (id: number, reason: string, requestKey?: string) =>
  (await http.post(`/erp-purchase-orders/${id}/cancel`, { reason }, key(requestKey))).data

/**
 * 结案（履行中 → 已结案）：已到货的部分照常发货，剩余不再追。
 *
 * 这是**部分到货**的唯一出口：`cancel` 会拒绝「履行中」（已经进过货，取消会让账实分叉），
 * 而「全部到货」由收货动作自动结案。没有它，只到了几成货、供应商后面不发的单会永久停在履行中。
 */
export const closePurchaseOrder = async (id: number, reason?: string, requestKey?: string) =>
  (await http.post(`/erp-purchase-orders/${id}/close`, { reason }, key(requestKey))).data

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

/**
 * 代发出库单**过账**（采购侧推进）。
 *
 * 代发的履行主体是采购 —— 货由供应商直发客户，不进本地仓、销售也看不到货。
 * 此前「生成草稿」要 `purchase_order.manage`、而「过账」要 `sales_order.manage`，
 * 只有采购权限的人会卡在草稿态等人接力。服务端内部复用同一个销售出库过账，
 * 所以代发依旧按 `fulfillment_source='DROP_SHIP'` 豁免扣本地库存。
 */
export const postDropShipOutbound = async (id: number, requestKey?: string) =>
  (await http.post(`/erp-purchase-orders/${id}/drop-ship-outbound/post`, {}, key(requestKey))).data

/** 代发出库单**冲销**（采购侧推进）：语义是「要求供应商别发/召回」；代发不过库存账，冲销也不回补库存。 */
export const reverseDropShipOutbound = async (id: number, reason: string, requestKey?: string) =>
  (await http.post(`/erp-purchase-orders/${id}/drop-ship-outbound/reverse`, { reason }, key(requestKey))).data

export const PURCHASE_STATUS_TEXT: Record<PurchaseOrderStatus, string> = {
  DRAFT: '草稿',
  PENDING_APPROVAL: '待审批',
  CONFIRMED: '已确认',
  RECEIVING: '履行中',
  CLOSED: '已结案',
  CANCELLED: '已取消',
}

// 词表也只此一份（api/approval）；这里转出，供视图沿用原有 import 路径。
export { APPROVAL_ACTION_TEXT, APPROVAL_STATUS_TEXT } from '../approval'

export const FULFILLMENT_MODE_TEXT: Record<PurchaseFulfillmentMode, string> = {
  WAREHOUSE: '到货入库',
  DROP_SHIP: '供应商直发',
}
