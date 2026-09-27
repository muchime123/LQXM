import { http } from '../http'
import { newRequestKey } from '../salesOutbound'

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
  status: 'DRAFT' | 'CONFIRMED'
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
  (await http.get<{ head: SalesOrder; items: SalesOrderLine[] }>(`/erp-sales-orders/${id}`)).data

export const createSalesOrder = async (payload: SalesOrderPayload, requestKey?: string) =>
  (await http.post<number>('/erp-sales-orders', payload, key(requestKey))).data

export const updateSalesOrder = async (id: number, payload: SalesOrderPayload, requestKey?: string) =>
  (await http.put<number>(`/erp-sales-orders/${id}`, payload, key(requestKey))).data

export const confirmSalesOrder = async (id: number, requestKey?: string) =>
  (await http.post(`/erp-sales-orders/${id}/confirm`, {}, key(requestKey))).data

export const createOrderOutboundDraft = async (orderId: number, itemId: number, warehouseId: number, quantity: number, requestKey?: string) =>
  (await http.post<number>(`/erp-sales-orders/${orderId}/items/${itemId}/outbound-draft`, { warehouseId, quantity }, key(requestKey))).data
