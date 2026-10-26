/** 官网询盘状态 */
export type InquiryStatus = 'pending' | 'processed' | 'closed'

/** 询盘状态选项（含"全部"） */
export type InquiryStatusFilter = InquiryStatus | 'all'

/** 询盘列表摘要 */
export interface WebsiteInquirySummary {
  id: number
  name: string
  company: string | null
  phone: string
  email: string | null
  productFocus: string | null
  status: InquiryStatus
  sourcePage: string | null
  assignee: string | null
  createdAt: string
  nextContactAt: string | null
}

/** 询盘详情 */
export interface WebsiteInquiryDetail extends WebsiteInquirySummary {
  description: string
  followUpNote: string | null
  closedReason: string | null
  processedAt: string | null
  updatedAt: string | null
}

/** 询盘分页响应 */
export interface WebsiteInquiryPage {
  items: WebsiteInquirySummary[]
  total: number
  totalPages: number
  page: number
}

/** 询盘统计 */
export interface WebsiteInquiryStats {
  pending: number
  processed: number
  closed: number
  thisMonth: number
  pendingFollowUp: number
}

/** 询盘更新请求 */
export interface WebsiteInquiryUpdateRequest {
  status?: InquiryStatus
  assignee?: string | null
  followUpNote?: string
  nextContactAt?: string | null
  clearNextContactAt?: boolean
  closedReason?: string
}

/** 询盘查询参数 */
export interface WebsiteInquiryQuery {
  status?: InquiryStatusFilter
  keyword?: string
  page?: number
  size?: number
}

/** 状态标签映射 */
export const INQUIRY_STATUS_LABELS: Record<InquiryStatus, string> = {
  pending: '待处理',
  processed: '已联系',
  closed: '已关闭'
}

/** 状态颜色映射（用于 tag 配色） */
export const INQUIRY_STATUS_COLORS: Record<InquiryStatus, string> = {
  pending: 'warning',
  processed: 'success',
  closed: 'info'
}

/** 解决方案分区 */
export type SolutionSection = 'SOLUTION' | 'INDUSTRY' | 'PROCESS' | 'ARTICLE'

export interface SolutionContentSection {
  title: string
  body: string
  points: string[]
  imageUrl?: string
}

/** 解决方案条目 */
export interface WebsiteSolution {
  id: number
  section: SolutionSection
  itemKey: string | null
  slug: string | null
  title: string
  category: string | null
  summary: string | null
  coverImageUrl: string | null
  audience: string | null
  takeaway: string | null
  intro: string | null
  icon: string | null
  points: string[]
  contentSections: SolutionContentSection[]
  contentHtml: string | null
  relatedSceneKeys: string[]
  seoTitle: string | null
  seoDescription: string | null
  sortOrder: number
  websiteEnabled: boolean
  websiteFeatured: boolean
  createdAt: string
  updatedAt: string | null
}

/** 解决方案写入载荷 */
export interface WebsiteSolutionPayload {
  section: SolutionSection
  itemKey?: string
  slug?: string
  title: string
  category?: string
  summary?: string
  coverImageUrl?: string
  audience?: string
  takeaway?: string
  intro?: string
  icon?: string
  points?: string[]
  contentSections?: SolutionContentSection[]
  contentHtml?: string
  relatedSceneKeys?: string[]
  seoTitle?: string
  seoDescription?: string
  sortOrder?: number
  websiteEnabled?: boolean
  websiteFeatured?: boolean
}

/** 按分区分组的解决方案 */
export interface SolutionSectionGroup {
  section: SolutionSection
  items: WebsiteSolution[]
}

/** 分区标签 */
export const SOLUTION_SECTION_LABELS: Record<SolutionSection, string> = {
  SOLUTION: '解决方案',
  INDUSTRY: '行业场景',
  PROCESS: '服务流程',
  ARTICLE: '方案文章'
}

/** 管理界面可见的分区（仅应用场景 + 方案文章；SOLUTION/PROCESS 为历史数据，不在新建入口中暴露） */
export const VISIBLE_SOLUTION_SECTIONS: SolutionSection[] = ['INDUSTRY', 'ARTICLE']

/** Banner位置 */
export type BannerPosition = 'HERO' | 'SIDE' | 'ANNOUNCEMENT'

/** Banner位置标签 */
export const BANNER_POSITION_LABELS: Record<BannerPosition, string> = {
  HERO: '首页主图',
  SIDE: '侧边',
  ANNOUNCEMENT: '公告条'
}

/** Banner列表项 */
export interface WebsiteBannerSummary {
  id: number
  position: BannerPosition
  title: string
  subtitle: string | null
  imageUrl: string | null
  sortOrder: number
  websiteEnabled: boolean
  deleted: boolean
  startDate: string | null
  endDate: string | null
  updatedAt: string | null
}

/** Banner详情 */
export interface WebsiteBannerDetail {
  id: number
  position: BannerPosition
  title: string
  subtitle: string | null
  description: string | null
  imageUrl: string | null
  imageAssetId: number | null
  buttonText: string | null
  buttonUrl: string | null
  secondaryButtonText: string | null
  secondaryButtonUrl: string | null
  badge: string | null
  sortOrder: number
  websiteEnabled: boolean
  deleted: boolean
  startDate: string | null
  endDate: string | null
  createdAt: string | null
  updatedAt: string | null
}

/** Banner写入载荷 */
export interface WebsiteBannerPayload {
  position: BannerPosition
  title: string
  subtitle?: string
  description?: string
  imageUrl?: string
  imageAssetId?: number
  buttonText?: string
  buttonUrl?: string
  secondaryButtonText?: string
  secondaryButtonUrl?: string
  badge?: string
  sortOrder?: number
  websiteEnabled?: boolean
  startDate?: string
  endDate?: string
}

/** Banner分页响应（Spring Data Page结构） */
export interface WebsiteBannerPage {
  content: WebsiteBannerSummary[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** Banner查询参数 */
export interface WebsiteBannerQuery {
  keyword?: string
  position?: BannerPosition
  websiteEnabled?: boolean
  page?: number
  size?: number
}

/** 官网公开Banner视图 */
export interface PublicBannerView {
  id: number
  title: string
  subtitle: string | null
  description: string | null
  imageUrl: string | null
  buttonText: string | null
  buttonUrl: string | null
  secondaryButtonText: string | null
  secondaryButtonUrl: string | null
  badge: string | null
  sortOrder: number
  position: BannerPosition
}

/** 按位置分组的Banner */
export type PublicBannerMap = Record<BannerPosition, PublicBannerView[]>

/** 官网工作台产品健康度示例产品 */
export interface DashboardProductIssueItem {
  id: number
  name: string
  modelCode: string | null
  category: string | null
  websiteSlug: string | null
}

/** 官网工作台产品健康度分组 */
export interface DashboardProductHealthIssue {
  key: string
  label: string
  count: number
  samples: DashboardProductIssueItem[]
}

export interface DashboardPublishReadinessIssue {
  key: string
  label: string
  domain: 'packaging' | 'mold' | 'solution' | string
  target: string
  count: number
  samples: Array<{ id: number; name: string }>
}

/** 官网工作台聚合数据 */
export interface WebsiteDashboard {
  publishedProducts: number
  featuredProducts: number
  incompleteProducts: number
  publishedPackaging: number
  publishedMolds: number
  publishedSolutions: number
  officialMediaAccounts: number
  publishedBanners: number
  pendingInquiries: number
  recentInquiries: number
  productIssues: DashboardProductHealthIssue[]
  readinessIssues: DashboardPublishReadinessIssue[]
}

/** 产品健康度问题键 -> 处理提示 映射 */
export const PRODUCT_ISSUE_TIPS: Record<string, string> = {
  belowListThreshold: '完整度低于 40 分，不会出现在官网产品中心',
  missingMainImage: '未设置主图，官网卡片显示占位图',
  missingSummary: '未填写官网摘要，或仍是系统默认文案',
  missingEnName: '未填写英文名称，影响海外用户浏览',
  missingCategory: '未填写设备类别，无法被分类筛选',
  noVariant: '没有任何规格参数，官网详情展示不完整',
  missingSlug: '缺少 Slug，产品详情页无法访问',
  duplicateSlug: 'Slug 与其它产品冲突，详情页可能互相覆盖'
}

/** 官网页面标识 */
export type PageSectionPageKey = 'HOME' | 'PRODUCTS' | 'SOLUTIONS' | 'ABOUT' | 'CONTACT' | 'PACKAGING' | 'MOLDS'

/** 页面标识标签 */
export const PAGE_SECTION_PAGE_LABELS: Record<PageSectionPageKey, string> = {
  HOME: '首页',
  PRODUCTS: '产品中心',
  SOLUTIONS: '解决方案',
  ABOUT: '关于我们',
  CONTACT: '联系我们',
  PACKAGING: '包装耗材',
  MOLDS: '模具中心'
}

/** 官网页面区块 */
export interface WebsitePageSection {
  id: number
  pageKey: PageSectionPageKey
  sectionKey: string
  title: string | null
  subtitle: string | null
  content: string | null
  imageUrl: string | null
  sortOrder: number
  enabled: boolean
  createdAt: string
  updatedAt: string | null
}

/** 官网页面区块写入载荷 */
export interface WebsitePageSectionPayload {
  pageKey: PageSectionPageKey
  sectionKey: string
  title?: string
  subtitle?: string
  content?: string
  imageUrl?: string
  sortOrder?: number
  enabled?: boolean
}

/** 区块标识 -> 说明 映射（后台列表提示用） */
export const PAGE_SECTION_HINTS: Record<string, string> = {
  hero: '页面首屏区',
  strength: '首屏后的企业实力口号',
  products: '核心设备区标题与描述',
  showcase: '设备与模具制造能力区',
  process: '合作流程标题与描述',
  industries: '按应用场景选择方案区',
  selector: '帮助选型·双路径引导区（标题+描述；PATH A 列表通过 selector-methods 结构化内容配置）',
  'selector-methods': '帮助选型·按包装方式卡片（结构化行，与 selector 标题配合使用）',
  cases: '典型配置参考区',
  proof: '交付事实区（标题+描述；条目列表通过结构化内容配置）',
  service: '服务与交付保障区',
  intro: '公司简介标题与描述',
  manufacturing: '厂房设备区',
  culture: '企业文化区',
  credentials: '企业荣誉与资质认证区',
  patents: '专利与知识产权区',
  contact: '联系利强区',
  catalog: '产品目录与筛选区',
  scenes: '生产场景入口区',
  articles: '实用方案内容区'
}
