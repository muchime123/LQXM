/**
 * 公司域共享类型（后端 /company/* 接口返回结构）
 */

/** 品牌资产（公司素材库条目） */
export interface CompanyAsset {
  id: number
  category: string
  fileName: string
  fileSize?: number | null
  /** 逗号分隔的标签字符串，用 tagList() 解析 */
  tags?: string | null
  defaultAsset?: boolean
  sortOrder?: number | null
  fileExists?: boolean
  description?: string | null
  createdAt?: string | null
}

/** 财务账户 */
export interface CompanyAccount {
  id: number
  accountType: string
  accountName?: string
  accountHolder?: string
  accountNumber?: string
  bankName?: string
  bankLocation?: string
  bankAddress?: string
  domesticBankCode?: string
  swiftCode?: string
  iban?: string
  routingNumber?: string
  currency?: string
  isPrimary?: boolean
  notes?: string
}

/** 公司基本信息（/company/info） */
export interface CompanyInfo {
  companyName?: string | null
  companyNameEn?: string | null
  brandName?: string | null
  brandNameEn?: string | null
  formerName?: string | null
  companyType?: string | null
  enterpriseScale?: string | null
  legalRepresentative?: string | null
  unifiedSocialCode?: string | null
  establishmentDate?: string | null
  businessTerm?: string | null
  registeredCapital?: string | null
  paidCapital?: string | null
  insuredCount?: number | string | null
  registeredAddress?: string | null
  officeAddress?: string | null
  factoryAddress?: string | null
  phone?: string | null
  email?: string | null
  website?: string | null
  businessScope?: string | null
  mainBusiness?: string | null
  corePositioning?: string | null
  advantages?: string | null
  targetIndustries?: string | null
  licenseFileName?: string | null
  revenueDevicePct?: number | string | null
  revenueConsumablePct?: number | string | null
}

/** 概览统计（/company/stats） */
export interface CompanyOverviewStats {
  certificateTotal?: number
  certificateExpired?: number
  certificateByCategory?: Record<string, number>
  companyCertTotal?: number
  companyCertByCategory?: Record<string, number>
  mediaAccountTotal?: number
  mediaAccountByPlatform?: Record<string, number>
}

/** 知识库分类 */
export interface KbCategory {
  id: number
  name: string
  nameEn?: string | null
  description?: string | null
  icon?: string | null
  articleCount?: number
}

/** 知识库文章 */
export interface KbArticle {
  id: number
  categoryId?: number | null
  title: string
  content?: string | null
  author?: string | null
  source?: string | null
  tags?: string | null
  status?: string | null
  viewCount?: number
  /** 是否展示到官网 */
  websiteVisible?: boolean
}

/** 发展历程里程碑（字段与 MilestoneFormDialog 对齐） */
export interface CompanyMilestone {
  id: number
  year: number
  title: string
  description?: string
  icon?: string
  fileName?: string
  sortOrder?: number
  websiteEnabled?: boolean
}

/**
 * 公司资质与合规文件（检测报告、营业执照、ISO 认证等）。
 * 后端通过 /api/company/certifications 暴露；到期数据聚合到知识产权页的到期提醒看板。
 */
export interface CompanyCertification {
  id: number
  name: string
  nameEn?: string | null
  category: string
  certNumber?: string | null
  issueDate?: string | null
  expiryDate?: string | null
  isPermanent?: boolean | null
  daysRemaining?: number | null
  issuingAuthority?: string | null
  fileName?: string | null
  description?: string | null
  status: string
  createdAt?: string | null
  updatedAt?: string | null
}

/** 公司资质/合规文件新建/编辑载荷 */
export interface CompanyCertificationPayload {
  name: string
  nameEn?: string | null
  category: string
  certNumber?: string | null
  issueDate?: string | null
  expiryDate?: string | null
  isPermanent?: boolean | null
  issuingAuthority?: string | null
  fileName?: string | null
  description?: string | null
  status: string
}

/** 公司资质分类预设（含标准、认证、许可三类合规文件） */
export const COMPANY_CERTIFICATION_CATEGORIES = [
  '检测报告',
  'ISO 认证',
  '第三方认证',
  '团体标准',
  '企业标准',
  '国家标准',
  '营业执照',
  '生产许可证',
  '行业资质',
  '其他合规文件'
] as const

/** 公司资质状态预设 */
export const COMPANY_CERTIFICATION_STATUSES = ['有效', '即将到期', '已过期', '已注销'] as const
