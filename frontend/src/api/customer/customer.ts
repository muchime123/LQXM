import { http } from '../http'

export function fetchCustomers(params?: Record<string, any>) {
  return http.get('/customers', { params }).then(res => res.data)
}

export function fetchCustomer(id: number) {
  return http.get(`/customers/${id}`).then(res => res.data)
}

export function createCustomer(data: Record<string, any>) {
  return http.post('/customers', data).then(res => res.data)
}

export function updateCustomer(id: number, data: Record<string, any>) {
  return http.put(`/customers/${id}`, data).then(res => res.data)
}

export function deleteCustomer(id: number) {
  return http.delete(`/customers/${id}`)
}

export function batchUpdateCustomers(ids: number[], fields: Record<string, unknown>) {
  return http.put('/customers/batch', { ids, fields }).then(res => res.data as { affected: number })
}

export function fetchCustomerFilters() {
  return http.get('/customers/filters').then(res => res.data)
}

export function fetchCustomerDistribution() {
  return http.get('/customers/distribution').then(res => res.data)
}

/** 「地址 → 省市区县」解析结果（后端 ChinaAddressParser 纯文本解析，无副作用） */
export interface AddressParseResult {
  province: string | null
  city: string | null
  district: string | null
}

/** 通用地址解析：输入任意公司地址文本，返回省市区县（无法识别的层级为 null） */
export function parseCustomerAddress(address: string) {
  return http.post<AddressParseResult>('/customers/address/parse', { address }).then(res => res.data)
}

export function fetchCustomerOverview(id: number) {
  return http.get(`/customers/${id}/overview`).then(res => res.data)
}

// ==================== 联系人 ====================

export function fetchCustomerContacts(customerId: number) {
  return http.get(`/customers/${customerId}/contacts`).then(res => res.data)
}

export function addCustomerContact(customerId: number, data: Record<string, any>) {
  return http.post(`/customers/${customerId}/contacts`, data).then(res => res.data)
}

export function updateCustomerContact(customerId: number, contactId: number, data: Record<string, any>) {
  return http.put(`/customers/${customerId}/contacts/${contactId}`, data).then(res => res.data)
}

export function deleteCustomerContact(customerId: number, contactId: number) {
  return http.delete(`/customers/${customerId}/contacts/${contactId}`)
}
export function fetchCustomerAddresses(customerId: number) { return http.get(`/customers/${customerId}/addresses`).then(res => res.data) }
export function addCustomerAddress(customerId: number, data: Record<string, any>) { return http.post(`/customers/${customerId}/addresses`, data).then(res => res.data) }
export function updateCustomerAddress(customerId: number, addressId: number, data: Record<string, any>) { return http.put(`/customers/${customerId}/addresses/${addressId}`, data).then(res => res.data) }
export function deleteCustomerAddress(customerId: number, addressId: number) { return http.delete(`/customers/${customerId}/addresses/${addressId}`) }
export function fetchCustomerMergeHistory(customerId: number) { return http.get(`/customers/${customerId}/merge-history`).then(res => res.data) }
export function fetchCustomerSourceIdentities(customerId: number) { return http.get(`/customers/${customerId}/source-identities`).then(res => res.data) }

// ==================== 业务员关系 ====================

export function fetchCustomerSalespersons(customerId: number) {
  return http.get(`/customers/${customerId}/salespersons`).then(res => res.data)
}

export function addCustomerSalesperson(customerId: number, data: Record<string, any>) {
  return http.post(`/customers/${customerId}/salespersons`, data).then(res => res.data)
}

export function updateCustomerSalesperson(customerId: number, relId: number, data: Record<string, any>) {
  return http.put(`/customers/${customerId}/salespersons/${relId}`, data).then(res => res.data)
}

export function deleteCustomerSalesperson(customerId: number, relId: number) {
  return http.delete(`/customers/${customerId}/salespersons/${relId}`)
}

// ==================== 地区缺口治理（写法别名 + 待标注清单导出） ====================

/** 地区写法别名（kind ∈ PROVINCE / CITY / DISTRICT） */
export interface RegionAliasItem {
  id: number
  kind: 'PROVINCE' | 'CITY' | 'DISTRICT'
  /** 待识别写法（归一后存储） */
  alias: string
  /** 生效省份（CITY/DISTRICT 必填，PROVINCE 为 null） */
  province: string | null
  /** 映射到的官方行政区名 */
  target: string
  remark: string | null
  createdAt: string | null
}

export function fetchCustomerRegionAliases() {
  return http.get<RegionAliasItem[]>('/customers/region/aliases').then(res => res.data)
}

export function createCustomerRegionAlias(data: {
  kind: 'PROVINCE' | 'CITY' | 'DISTRICT'
  alias: string
  province?: string | null
  target: string
  remark?: string
}) {
  return http.post<RegionAliasItem>('/customers/region/aliases', data).then(res => res.data)
}

export function deleteCustomerRegionAlias(id: number) {
  return http.delete(`/customers/region/aliases/${id}`)
}

/** 按客户列表筛选口径导出地区缺口 CSV（返回 Blob） */
export function exportCustomerRegionCsv(params: {
  noRegion?: boolean
  /** true 时导出“待补市县”：省份已合法但市/区县缺失或治理状态异常 */
  regionPartial?: boolean
  province?: string
  city?: string
  district?: string
}) {
  return http.get<Blob>('/customers/region/export.csv', { params, responseType: 'blob' }).then(res => res.data)
}

export interface CustomerRegionImportRow {
  rowNumber: number
  customerCode: string | null
  customerName: string | null
  province: string | null
  city: string | null
  district: string | null
  status: 'VALID' | 'UNCHANGED' | 'ERROR'
  message: string
}

export interface CustomerRegionImportPreview {
  total: number
  valid: number
  unchanged: number
  errors: number
  items: CustomerRegionImportRow[]
}

function regionImportForm(file: File) {
  const data = new FormData()
  data.append('file', file)
  return data
}

export function previewCustomerRegionCsv(file: File) {
  return http.post<CustomerRegionImportPreview>('/customers/region/import/preview', regionImportForm(file), {
    headers: { 'Content-Type': 'multipart/form-data' }
  }).then(res => res.data)
}

export function applyCustomerRegionCsv(file: File) {
  return http.post<{ total: number; applied: number; unchanged: number; errors: number; tidyStatusChanged: number }>(
    '/customers/region/import/apply', regionImportForm(file), { headers: { 'Content-Type': 'multipart/form-data' } }
  ).then(res => res.data)
}

export interface CustomerRegionAutoParseRow {
  id: number
  customerCode: string | null
  customerName: string | null
  address: string | null
  currentProvince: string | null
  currentCity: string | null
  currentDistrict: string | null
  province: string | null
  city: string | null
  district: string | null
  status: 'READY' | 'ERROR'
  message: string
}

export interface CustomerRegionAutoParsePreview {
  total: number
  ready: number
  errors: number
  items: CustomerRegionAutoParseRow[]
}

/** 扫描地区字段异常客户，预览详细地址解析结果（不写数据）。 */
export function previewCustomerRegionAutoParse() {
  return http.post<CustomerRegionAutoParsePreview>('/customers/region/auto-parse/preview').then(res => res.data)
}

/** 写回完整且通过省/市/区县三级字典校验的解析结果。 */
export function applyCustomerRegionAutoParse() {
  return http.post<{ total: number; applied: number; errors: number; tidyStatusChanged: number }>(
    '/customers/region/auto-parse/apply'
  ).then(res => res.data)
}

export interface CustomerRegionFallbackRow {
  id: number
  customerCode: string | null
  customerName: string | null
  address: string | null
  outboundRegions: string | null
  province: string | null
  status: 'READY' | 'ERROR'
  message: string
}

export interface CustomerRegionFallbackPreview {
  total: number
  ready: number
  errors: number
  items: CustomerRegionFallbackRow[]
}

/** 预览无地址客户的出库单「地区」标注可回填的省份（不写数据）。 */
export function previewCustomerRegionOutboundFallback() {
  return http.post<CustomerRegionFallbackPreview>('/customers/region/outbound-fallback/preview').then(res => res.data)
}

/** 写回出库单地区标注解析出的省份；仅补空白省份，不覆盖已有值。 */
export function applyCustomerRegionOutboundFallback() {
  return http.post<{ total: number; applied: number; errors: number; tidyStatusChanged: number }>(
    '/customers/region/outbound-fallback/apply'
  ).then(res => res.data)
}

export interface CustomerRegionOverseasRow {
  id: number
  customerCode: string | null
  customerName: string | null
  address: string | null
  contactPhone: string | null
  outboundRegions: string | null
  dealerNames: string | null
  orderCount: number
  /** OVERSEAS=像真外贸客户；DOMESTIC_FORWARD=档案地址可解析出国内行政区划，疑似国内转发 */
  suggested: 'OVERSEAS' | 'DOMESTIC_FORWARD'
  suggestedProvince: string | null
  message: string
}

export interface CustomerRegionOverseasPreview {
  total: number
  overseas: number
  domestic: number
  items: CustomerRegionOverseasRow[]
}

/** 预览「出库单地区=国外」的客户判定清单（不写数据）。 */
export function previewCustomerRegionOverseas() {
  return http.post<CustomerRegionOverseasPreview>('/customers/region/overseas/preview').then(res => res.data)
}

/** 写回境外判定：OVERSEAS=标记境外，DOMESTIC=按档案地址回填省市，PENDING=保持待定。 */
export function applyCustomerRegionOverseas(decisions: Array<{ customerId: number; decision: 'OVERSEAS' | 'DOMESTIC' | 'PENDING' }>) {
  return http.post<{ overseas: number; domestic: number; skipped: number; tidyStatusChanged: number }>(
    '/customers/region/overseas/apply', decisions
  ).then(res => res.data)
}

export interface CustomerOutboundReference {
  orderCount: number
  lastShipDate: string | null
  lastShippingMethod: string | null
  regions: string[]
  addresses: string[]
  contactPhones: string[]
  dealerNames: string[]
  /** 地区标注或收货地址能定出的唯一官方省名；null 表示定不下来 */
  suggestedProvince: string | null
}

/** 编辑弹窗「销售出库参考」：该客户出库单上的地区/地址/电话/经销商等可带入档案的信息。 */
export function fetchCustomerOutboundReference(customerId: number) {
  return http.get<CustomerOutboundReference>(`/customers/${customerId}/outbound-reference`).then(res => res.data)
}
