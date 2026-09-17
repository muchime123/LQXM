/**
 * API 统一导出
 *
 * 使用方式：
 * import { fetchProducts, fetchCatalogStats } from '@/api'
 */

// HTTP 实例
export { http } from './http'

// Catalog 相关
export * from './catalog'

// 公司信息
export * as companyApi from './company'

// 媒体账号 & 内容选题
export * from './media'

// 知识产权
export * from './certificate'

// 文件上传
export * from './file'

// 包装管理
export * from './packaging'

// 包材模具
export * from './packagingMold'

// 行政物品资产
export * from './officeAsset'

// 官网管理（Banner / 询盘 / 解决方案 / 页面区块 / 仪表盘）
export * as websiteBannerApi from './website/banners'
export * as websiteInquiryApi from './website/inquiries'
export * as websiteSolutionApi from './website/solutions'
export * as websitePageSectionApi from './website/pageSections'
export * as websiteDashboardApi from './website/dashboard'
