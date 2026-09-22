import { http } from '../http'

/**
 * ERP 正式销售出库单（P2）。
 *
 * ⚠️ 与同目录 `orderProfit.ts` 的区别：那个读的是**氚云台账**（`sales_outbounds`，只读历史），
 * 这里操作的是 **ERP 自建单据**（`erp_sales_outbounds`，可录单/可扣库存/可追溯）。
 * 两者不是同一批数据，页面不要混用。
 */

export interface SalesOutboundItem {
  id?: number
  lineNo?: number
  salesProductId: number
  canonicalProductId?: number
  productCode?: string
  productName: string
  specification?: string
  unit: string
  quantity: number
  unitPrice?: number | null
  amount?: number | null
  remark?: string
}

export interface SalesOutbound {
  id: number
  outboundNo: string
  status: 'DRAFT' | 'POSTED' | 'REVERSED'
  customerId?: number | null
  customerName: string
  salespersonId?: number | null
  salespersonName?: string | null
  warehouseId: number
  warehouseName?: string
  salesOrderRef?: string | null
  outboundDate: string
  totalAmount?: number | null
  remark?: string | null
  postedAt?: string | null
  postedByName?: string | null
  reversedAt?: string | null
  reversedByName?: string | null
  reversalReason?: string | null
  createdByName?: string | null
  createdAt: string
  itemCount?: number
}

/** 可售库存行（canonical 口径）：业务员按「有没有货」选品。 */
export interface AvailableStock {
  balanceId: number
  warehouseId: number
  warehouseName: string
  salesProductId: number
  canonicalProductId: number
  productCode?: string
  productName: string
  specification?: string
  unit: string
  available: number
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface OutboundPayload {
  customerId?: number | null
  customerName: string
  warehouseId: number
  salesOrderRef?: string
  outboundDate: string
  remark?: string
  lines: Array<{ productId: number; quantity: number; unitPrice?: number | null; remark?: string }>
}

// ============ M0 幂等契约：写动作请求键 ============
// 后端对建单 / 改单 / 过账 / 冲销要求 X-Request-Key（决策 #12「无键不直通」）：
//  - 同一次提交的重试必须复用同一个键 → 后端认定为「重放」，直接返回首次结果，不会重复落账；
//  - 新的一次提交必须换新键 → 否则会被当成上一次的重放而被静默丢弃。
export function newRequestKey(): string {
  const c = globalThis.crypto as Crypto | undefined
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return `rk-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function idemConfig(requestKey?: string) {
  return { headers: { 'X-Request-Key': requestKey || newRequestKey() } }
}

export const fetchSalesOutbounds = async (params: Record<string, unknown>) =>
  (await http.get<PageResult<SalesOutbound>>('/sales-outbounds', { params })).data

export const fetchSalesOutbound = async (id: number) =>
  (await http.get<{ head: SalesOutbound; items: SalesOutboundItem[] }>(`/sales-outbounds/${id}`)).data

export const fetchSalesOutboundWarehouses = async () =>
  (await http.get<Array<{ id: number; warehouseCode: string; warehouseName: string }>>('/sales-outbounds/warehouses')).data

/** 可售库存：返回空数组表示没有现货 —— 这是「该下生产任务」的业务信号，不是错误。 */
export const fetchAvailableStock = async (params: { keyword?: string; warehouseId?: number; limit?: number }) =>
  (await http.get<AvailableStock[]>('/sales-outbounds/available-stock', { params })).data

export const createSalesOutbound = async (payload: OutboundPayload, requestKey?: string) =>
  (await http.post<number>('/sales-outbounds', payload, idemConfig(requestKey))).data

export const updateSalesOutbound = async (id: number, payload: OutboundPayload, requestKey?: string) =>
  (await http.put<number>(`/sales-outbounds/${id}`, payload, idemConfig(requestKey))).data

export const postSalesOutbound = async (id: number, requestKey?: string) => {
  await http.post(`/sales-outbounds/${id}/post`, {}, idemConfig(requestKey))
}

export const reverseSalesOutbound = async (id: number, reason: string, requestKey?: string) => {
  await http.post(`/sales-outbounds/${id}/reverse`, { reason }, idemConfig(requestKey))
}
