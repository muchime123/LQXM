import { http } from '../http'
import { newRequestKey } from '../salesOutbound'
import type { ApprovalStatus, ApprovalEntry } from '../approval'

/** 审批关口（#35）：草稿 → 待审批 → 已确认（驳回则退回草稿）。 */
export type SalesOrderStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'CONFIRMED'

export interface ProductionDemandConfig {
  /** 规则引擎生成的只读名称快照；后端写入时会重新计算，不信任客户端值。 */
  nameSnapshot?: string
  baseProductId?: number
  configRuleId?: number
  optionIds?: number[]
  specificName?: string
  compatibleModel?: string
  moldType?: string
  drawingVersion?: string
  partSpecification?: string
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
  purchaseConfig?: PurchaseDemandConfig | null
  purchaseConfigJson?: string | null
  id?: number
  lineNo?: number
  productName: string
  processCategory?: string | null
  skuKey?: string | null
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

export interface SalesDeliveryOption {
  sourceLotId?: number
  sourcePurchaseItemId?: number
  sourceNo?: string
  warehouseId: number
  warehouseName: string
  salesProductId: number
  productName: string
  specification?: string
  unit: string
  sourceQuantity: number
  stockQuantity: number
  availableQuantity: number
}
export interface SalesOrderShipment {
  outboundId: number
  outboundNo: string
  status: string
  itemId: number
  quantity: number
  productName: string
  warehouseName?: string
  sourceLotNo?: string
  sourcePurchaseNo?: string
}

export const fetchSalesDeliveryOptions = async (orderId: number, itemId: number) =>
  (await http.get<SalesDeliveryOption[]>(`/erp-sales-orders/${orderId}/items/${itemId}/delivery-options`)).data

/** 由销售确认、采购直接引用的外采需求；供应商和采购价由采购环节决定。 */
export interface PurchaseDemandConfig {
  model: string
  brand: string
  technicalRequirements: string
  acceptanceCriteria: string
  packagingRequirements: string
  deliveryRequirements: string
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
  fulfillmentReviewRequestedAt?: string | null
  revisionNo?: number
}

export interface FulfillmentReviewLine {
  orderId: number
  itemId: number
  orderNo?: string
  customerName?: string
  salespersonName?: string
  lineNo: number
  productName: string
  specification?: string | null
  quantity: number
  unit: string
  requestedDeliveryDate?: string | null
  orderDeliveryDate?: string | null
  remark?: string | null
  productionConfigJson?: string | null
  purchaseConfigJson?: string | null
  reviewDecision?: 'ACCEPTED' | 'CHANGE_REQUIRED' | null
  reviewCurrent: boolean
  reviewComment?: string | null
  reviewerName?: string | null
  reviewedAt?: string | null
  proposedDeliveryDate?: string | null
}

export interface FulfillmentReviewHistory {
  itemId: number
  supplyMode: 'MAKE' | 'BUY'
  decision: 'ACCEPTED' | 'CHANGE_REQUIRED'
  comment?: string | null
  proposedDeliveryDate?: string | null
  reviewerName: string
  reviewedAt: string
}

export interface SalesExecutionException {
  pauseFollowup?: boolean | number
  proposedDeliveryDate?: string | null
  id: number
  orderId: number
  itemId: number
  status: 'OPEN' | 'RESOLVED'
  reason: string
  proposedAction?: string | null
  reportedByName: string
  reportedAt: string
  resolutionNote?: string | null
  resolvedByName?: string | null
  resolvedAt?: string | null
  latestChangeId?: number | null
  latestChangeStatus?: string | null
}

export interface SalesOrderChange {
  downstreamImpact?: SalesChangeImpact[]
  reviewedDownstreamImpact?: SalesChangeImpact[]
  reviewImpactCurrent?: boolean
  id: number
  exceptionId: number
  itemId: number
  status: 'PENDING_REVIEW' | 'PENDING_CUSTOMER' | 'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED'
  originalQuantity: number
  proposedQuantity: number
  originalDeliveryDate?: string | null
  proposedDeliveryDate?: string | null
  customerConfirmationRef: string
  reviewComment?: string | null
  reviewedByName?: string | null
  approvalComment?: string | null
  decidedByName?: string | null
  createdByName: string
  createdAt: string
}

export interface SalesOrderChangeReviewTask {
  downstreamImpact?: SalesChangeImpact[]
  impactToken: string
  changeId: number
  orderId: number
  itemId: number
  exceptionId: number
  orderNo: string
  customerName: string
  productName: string
  supplyMode: 'MAKE' | 'BUY'
  originalQuantity: number
  proposedQuantity: number
  originalDeliveryDate?: string | null
  proposedDeliveryDate?: string | null
  orderDeliveryDate?: string | null
  customerConfirmationRef: string
  exceptionReason: string
}

export interface SalesChangeImpact {
  documentType: 'PRODUCTION' | 'PURCHASE' | 'OUTBOUND'
  id: number
  documentNo: string
  status: string
  quantity: number
  deliveryDate?: string | null
  executedQuantity?: number | null
  reportedQuantity?: number | null
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
  (await http.get<{ head: SalesOrder; items: SalesOrderLine[]; approvals: ApprovalEntry[]; shipments?: SalesOrderShipment[]; preparation?: { production: OrderPreparationSource[]; purchases: OrderPreparationSource[] } }>(
    `/erp-sales-orders/${id}`)).data

/** 仅订单内的准备来源，不含采购成本；来自订单范围授权后的查询。 */
export interface OrderPreparationSource {
  id: number
  documentNo: string
  itemId: number
  status: string
  dueDate?: string | null
  quantity?: number
  unit?: string
  workshopName?: string | null
  fulfillmentMode?: 'WAREHOUSE' | 'DROP_SHIP'
}

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

export const requestSalesFulfillmentReview = async (id: number, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/${id}/request-fulfillment-review`, {}, key(requestKey))).data

export const fetchSalesFulfillmentReviewStatus = async (id: number) =>
  (await http.get<FulfillmentReviewLine[]>(`/erp-sales-orders/${id}/fulfillment-review-status`)).data

export const fetchSalesFulfillmentReviewHistory = async (id: number) =>
  (await http.get<FulfillmentReviewHistory[]>(`/erp-sales-orders/${id}/fulfillment-review-history`)).data

/** 一条「还没就绪」的原因；`itemId` 用于把提示点回具体明细行。 */
export interface SalesOrderReadinessBlocker {
  code: string
  message: string
  itemId: number | null
}

/**
 * 就绪度：这份订单还差什么才能「送履约评审」/「提交审批」。
 *
 * 与提交那一刻的校验**同一份后端实现**（`ErpSalesOrderService.evaluateReadiness`），
 * 所以不会出现「面板说可以提交、提交却被打回」这种第二种口径。
 */
export interface SalesOrderReadiness {
  reviewReady: boolean
  submitReady: boolean
  /** 明细/供货方式/产品配置类阻塞项 —— 送评与提交共用 */
  configBlockers: SalesOrderReadinessBlocker[]
  /** 履约评审类阻塞项 —— 只有提交看得到 */
  reviewBlockers: SalesOrderReadinessBlocker[]
  hints: string[]
}

export const fetchSalesOrderReadiness = async (id: number) =>
  (await http.get<SalesOrderReadiness>(`/erp-sales-orders/${id}/readiness`)).data

export const fetchSalesExecutionExceptions = async (id: number) =>
  (await http.get<SalesExecutionException[]>(`/erp-sales-orders/${id}/execution-exceptions`)).data

export const fetchSalesOrderChanges = async (id: number) =>
  (await http.get<SalesOrderChange[]>(`/erp-sales-orders/${id}/change-requests`)).data

export const reportSalesExecutionException = async (orderId: number, itemId: number, reason: string,
  proposedAction: string, requestKey?: string, options?: { pauseFollowup: boolean; proposedDeliveryDate?: string | null; impactToken?: string }) =>
  (await http.post<number>(`/erp-sales-orders/${orderId}/items/${itemId}/execution-exceptions`,
    { reason, proposedAction, ...options }, key(requestKey))).data

export const fetchSalesExceptionContext = async (orderId: number, itemId: number) =>
  (await http.get<{ downstreamImpact: SalesChangeImpact[]; impactToken: string }>(`/erp-sales-orders/${orderId}/items/${itemId}/exception-context`)).data

export const acceptSalesDeliveryProposal = async (exceptionId: number, customerConfirmationRef: string, requestKey?: string) =>
  (await http.post<number>(`/erp-sales-orders/execution-exceptions/${exceptionId}/accept-delivery-proposal`,
    { customerConfirmationRef }, key(requestKey))).data

export const confirmSalesDeliveryChange = async (changeId: number, customerConfirmationRef: string, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/change-requests/${changeId}/confirm-delivery`, { customerConfirmationRef }, key(requestKey))).data

export const withdrawSalesOrderChange = async (changeId: number, comment: string, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/change-requests/${changeId}/withdraw`, { comment }, key(requestKey))).data

export const proposeSalesOrderChange = async (exceptionId: number, quantity: number,
  deliveryDate: string | null, customerConfirmationRef: string, requestKey?: string) =>
  (await http.post<number>(`/erp-sales-orders/execution-exceptions/${exceptionId}/change-requests`,
    { quantity, deliveryDate, customerConfirmationRef }, key(requestKey))).data

export const resolveSalesExecutionException = async (exceptionId: number, comment: string, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/execution-exceptions/${exceptionId}/resolve`, { comment }, key(requestKey))).data

export const fetchSalesChangeReviewQueue = async (mode: 'production' | 'purchase') =>
  (await http.get<SalesOrderChangeReviewTask[]>(`/erp-sales-orders/change-requests/${mode}`)).data

export const reviewSalesOrderChange = async (mode: 'production' | 'purchase', changeId: number,
  accept: boolean, comment: string, downstreamConfirmed: boolean, impactToken: string, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/change-requests/${changeId}/review-${mode}`,
    { comment, downstreamConfirmed, impactToken }, { ...key(requestKey), params: { accept } })).data

export const requestSalesChangeReReview = async (changeId: number, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/change-requests/${changeId}/request-review`, {}, key(requestKey))).data

export const decideSalesOrderChange = async (changeId: number, accept: boolean, comment: string, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/change-requests/${changeId}/decide`,
    { comment }, { ...key(requestKey), params: { accept } })).data

export const fetchFulfillmentReviewQueue = async (mode: 'production' | 'purchase') =>
  (await http.get<FulfillmentReviewLine[]>(`/erp-sales-orders/fulfillment-reviews/${mode}`)).data

export const decideFulfillmentReview = async (mode: 'production' | 'purchase', orderId: number, itemId: number,
  decision: 'ACCEPTED' | 'CHANGE_REQUIRED', comment: string, proposedDeliveryDate?: string | null, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/fulfillment-reviews/${mode}/${orderId}/items/${itemId}`,
    { decision, comment, proposedDeliveryDate }, key(requestKey))).data

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

export const createOrderOutboundDraft = async (orderId: number, itemId: number, warehouseId: number, quantity: number, requestKey?: string, sourceLotId?: number) =>
  (await http.post<number>(`/erp-sales-orders/${orderId}/items/${itemId}/outbound-draft`, { warehouseId, quantity, sourceLotId }, key(requestKey))).data
