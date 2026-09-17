export interface CustomerSummary {
  id: number
  customerCode: string
  customerName: string
  shortName: string
  customerType: string
  industry: string
  primarySalesperson: string
  contactName: string | null
  contactMobile: string | null
  province: string
  city: string
  district: string
  relationStatus: string
  status: string
  createdAt: string
}

export interface CustomerDetail {
  id: number
  customerCode: string
  customerName: string
  shortName: string
  customerType: string
  industry: string
  creditCode: string
  province: string
  city: string
  district: string
  address: string
  website: string
  source: string
  level: string
  relationStatus: string
  status: string
  remark: string
  createdAt: string
  updatedAt: string
}

export interface CustomerPayload {
  customerName?: string
  shortName?: string
  customerType?: string
  industry?: string
  creditCode?: string
  province?: string
  city?: string
  district?: string
  address?: string
  website?: string
  source?: string
  level?: string
  relationStatus?: string
  status?: string
  remark?: string
}

export interface ContactSummary {
  id: number
  customerId: number
  name: string
  department: string
  position: string
  mobile: string
  wechat: string
  email: string
  contactType: string
  isPrimary: boolean
  remark: string
}

export interface ContactPayload {
  name?: string
  department?: string
  position?: string
  mobile?: string
  wechat?: string
  email?: string
  contactType?: string
  isPrimary?: boolean
  remark?: string
}
export interface CustomerAddress {
  id: number; customerId: number; addressName: string; contactName: string; contactPhone: string
  province: string; city: string; district: string; detailAddress: string; isDefault: boolean
  source: string; remark: string; usageCount: number; firstUsedDate: string | null; lastUsedDate: string | null; createdAt: string
}
export interface CustomerAddressPayload {
  addressName?: string; contactName?: string; contactPhone?: string; province?: string; city?: string
  district?: string; detailAddress: string; isDefault?: boolean; remark?: string
}
export interface CustomerMergeHistory {
  id: number; mergedCustomerId: number; mergedCustomerCode: string; mergedCustomerName: string
  mergedAddress: string; mergeReason: string; mergedAt: string
}

export interface CustomerSourceIdentity {
  id: number
  sourceSystem: string
  sourceKey: string
  sourceName: string | null
  resolutionStatus: string
  confidence: number | null
  occurrenceCount: number
  resolutionNote: string | null
  resolvedAt: string | null
  /** 使用该氚云原始名称并已关联到当前客户的正式销售出库数量 */
  outboundCount: number
  firstOutboundDate: string | null
  lastOutboundDate: string | null
  phones: string[]
  addresses: string[]
}

export interface SalespersonSummary {
  id: number
  customerId: number
  salespersonId: number | null
  salespersonName: string
  role: string
  startDate: string
  endDate: string
}

export interface SalespersonPayload {
  salespersonId?: number | null
  salespersonName?: string
  role?: string
  startDate?: string
  endDate?: string
}

export interface CustomerOverview {
  outboundCount: number
  totalSalesAmount: number
  firstOutboundDate: string
  lastOutboundDate: string
  purchasedProducts: string[]
  salespersons: SalespersonSummary[]
  contacts: ContactSummary[]
}

export interface CustomerFilterOptions {
  customerTypes: string[]
  relationStatuses: string[]
  statuses: string[]
  industries: string[]
  provinces: string[]
  salespersons: string[]
}

export type CustomerType = '企业' | '合作社' | '农场' | '个体' | '个人' | '其他'
export type CustomerRelationStatus = '正常' | '重点关注' | '停止合作' | '禁止联系'

// ==================== 区域分布 ====================

export interface CustomerDistributionDistrict {
  /** 区划字典归一后的区县名称 */
  name: string
  count: number
}

export interface CustomerDistributionCity {
  /** 区划字典归一后的城市名称 */
  name: string
  count: number
  /** 已归属该市但尚未细分到有效区县 */
  unassignedDistrictCount: number
  /** 该市已细分到区县的分布（按客户数降序，可能为空） */
  districts: CustomerDistributionDistrict[]
}

export interface CustomerDistributionProvince {
  /** 清洗后的规范省名（与 ECharts 中国地图匹配，如 浙江省） */
  name: string
  /** 规范省名，用于客户列表地区筛选 */
  filterValue: string
  count: number
  /** 已归属该省但尚未细分到有效城市 */
  unassignedCityCount: number
  cities: CustomerDistributionCity[]
}

export interface CustomerDistributionData {
  totalCount: number
  /** 已归属有效大陆省份的客户数；与 noRegionCount、overseasCount 合计为 totalCount */
  locatedCount: number
  cityLocatedCount: number
  districtLocatedCount: number
  /** 省份有效，但市县缺失或地区质量异常 */
  partialRegionCount: number
  /** 未标注地区（省份为空或无法归一到官方省名）的客户数 */
  noRegionCount: number
  /** 已判定为境外的客户数（不参与大陆省级分布与地区治理） */
  overseasCount: number
  /** 覆盖的省级行政区数量 */
  provinceCount: number
  /** 按客户数降序的省级分布 */
  provinces: CustomerDistributionProvince[]
}

/** 区域分布弹窗确认后要落到客户列表的地区筛选 */
export interface CustomerRegionFilter {
  province?: string
  city?: string | null
  /** 区县（有 city 时可选），命中后列表按 省+市+区县 精确筛选 */
  district?: string | null
  /** 是否筛选“未标注地区（省份为空或非官方省名，上不了地图）”客户，为 true 时忽略 province/city */
  noRegion?: boolean
  /** 是否筛选“待补市县（省份已合法但市/区县缺失或异常）”客户，为 true 时忽略 province/city */
  regionPartial?: boolean
}
