export interface CertificateSummary {
  id: number
  assetStatus: string
  assetCategory: string
  patentType: string
  name: string
  patentNo: string
  applicationNo: string
  applicant: string
  applyDate: string
  grantDate: string
  expireDate: string
  daysRemaining: number
  feeStatus: string
  feeDueDate: string
  lastFeeAmount: number
  feeQueryDate: string
  specFile: string
  certFile: string
  invoiceStatus: string
  agencyContact: string
  niceClass: number
  applicantAddress: string
  websiteEnabled: boolean
  websiteFeatured: boolean
  websiteSortOrder: number
}

export interface CertificateDetail extends CertificateSummary {
  publicationNo: string
  queryNo: string
  inventors: string
  agency: string
  legalYears: number
  legalStatus: string
  hasDrawing: boolean
  feeDetail: string
  remark: string
  reviewProgress: string
  niceClassDescription: string
}

export interface CertificatePayload {
  assetStatus: string
  assetCategory: string
  patentType: string
  name: string
  specFile: string
  certFile: string
  publicationNo: string
  applicationNo: string
  queryNo: string
  patentNo: string
  applicant: string
  inventors: string
  agency: string
  applyDate: string
  grantDate: string
  legalYears: number
  expireDate: string
  legalStatus: string
  feeStatus: string
  feeQueryDate: string
  feeDueDate: string
  lastFeeAmount: number
  hasDrawing: boolean
  feeDetail: string
  remark: string
  invoiceStatus: string
  agencyContact: string
  reviewProgress: string
  niceClass: number
  niceClassDescription: string
  applicantAddress: string
  websiteEnabled: boolean
  websiteFeatured: boolean
  websiteSortOrder: number
}

export interface CertificateStats {
  totalCount: number
  activeCount: number
  expiredCount: number
  pendingCount: number
  transferredCount: number
  feeDueSoonCount: number
  inventionCount: number
  utilityCount: number
  designCount: number
  trademarkCount: number
  softwareCopyrightCount: number
  dueSoonItems: DueSoonItem[]
}

export interface DueSoonItem {
  id: number
  sourceType: string
  name: string
  number: string
  dueDate: string
  daysRemaining: number
  /** 纯数字查询号（官方检索用），资质到期来源无此字段 */
  queryNo?: string
}

export interface FilterOptions {
  assetStatuses: string[]
  assetCategories: string[]
  patentTypes: string[]
  feeStatuses: string[]
}

export interface FeeRecordSummary {
  id: number
  certificateId: number
  feeYear: number
  paidDate: string
  dueDate: string
  amount: number
  feeType: string
  receiptNo: string
  orderNo: string
  transactionNo: string
  receiptTitle: string
  invoiceStatus: string
  payer: string
  note: string
  createdAt: string
}

export interface FeeRecordPayload {
  feeYear: number
  paidDate: string
  dueDate: string
  amount: number
  feeType: string
  receiptNo: string
  orderNo: string
  transactionNo: string
  receiptTitle: string
  invoiceStatus: string
  payer: string
  note: string
}

export interface PaymentChecklist {
  beforePayment: ChecklistItem[]
  afterPayment: ChecklistItem[]
  archiveItems: ChecklistItem[]
}

export interface ChecklistItem {
  label: string
  description: string
}

export type AssetStatus = '有效' | '已失效' | '未生效' | '已转让'

export type AssetCategory = '专利' | '软著' | '商标'

export type PatentType = '发明专利' | '实用新型' | '外观设计' | '软著'

export type FeeStatus = '待查询/待核实' | '已支付' | '未支付' | '已失效待缴费' | '已登记/已公告'

export type InvoiceStatus = '未开票' | '已开票' | '已归档'
