/**
 * 官网公开 API 调用层。
 * 所有请求走 /api/website/* 命名空间，后端通过 PublicXxxView record 脱敏。
 * 不直接调用内部管理 API（/api/products 等）。
 */

import { getJson, postJson, apiBase } from './http.js'

/**
 * 拉取首页聚合数据（公司信息 + 产品 + 里程碑 + 资质）。
 * @returns {Promise<HomeView>}
 */
export function fetchHome() {
  return getJson('/api/website/home')
}

/**
 * 官网产品中心列表：分页 + 关键词 + 分类筛选。
 * @param {object} params { keyword, category, equipmentCategory, page, size }
 * @returns {Promise<PublicProductPage>}
 */
export function fetchProducts(params = {}) {
  const query = new URLSearchParams()
  if (params.keyword) query.set('keyword', params.keyword)
  // The route keeps its historical equipmentCategory query for bookmarks;
  // both website product readers accept the API parameter named category.
  if (params.category || params.equipmentCategory) query.set('category', params.category || params.equipmentCategory)
  if (params.application) query.set('application', params.application)
  if (params.container) query.set('container', params.container)
  if (params.process) query.set('process', params.process)
  if (params.capacity) query.set('capacity', params.capacity)
  query.set('page', params.page ?? 0)
  query.set('size', params.size ?? 12)
  return getJson(`/api/website/products?${query.toString()}`)
}

/**
 * 官网产品详情：按 slug 查询。
 * @param {string} slug
 * @returns {Promise<PublicProductDetail|null>} 不存在或未发布返回 null
 */
export async function fetchProductDetail(slug) {
  try {
    return await getJson(`/api/website/products/${encodeURIComponent(slug)}`)
  } catch (err) {
    if (err.message === 'HTTP 404') return null
    throw err
  }
}

/**
 * 官网首页精选产品：返回 website_featured=true 的产品。
 * @param {number} limit 最多返回数量，默认 6
 * @returns {Promise<PublicProductView[]>}
 */
export function fetchFeaturedProducts(limit = 500, options = {}) {
  return getJson(`/api/website/products/featured?limit=${limit}`, options)
}

/**
 * 官网产品分类导航。
 * @returns {Promise<PublicCategoryTree>}
 */
export function fetchProductCategories() {
  return getJson('/api/website/product-categories')
}

/** 官网公开包材档案。 */
export function fetchPackaging() {
  return getJson('/api/website/packaging')
}

/** 官网公开模具档案。 */
export function fetchMolds() {
  return getJson('/api/website/molds')
}

/** 构造包材档案缩略图 URL。 */
export function packagingImageUrl(item, size = 640) {
  return item.defaultImageAssetId
    ? `${apiBase}/api/file-assets/packaging_model/assets/${item.defaultImageAssetId}/thumbnail?size=${size}`
    : ''
}

/**
 * 官网公司详情（含工商信息）。
 * @returns {Promise<PublicCompanyDetailView>}
 */
export function fetchCompanyDetail() {
  return getJson('/api/website/company-detail')
}

/**
 * 官网官方展示的自媒体账号列表（由后台「自媒体账号管理」勾选「官网官方展示」）。
 * @returns {Promise<Array<PublicMediaAccountView>>}
 */
export function fetchOfficialMediaAccounts() {
  return getJson('/api/website/official-media-accounts')
}

/**
 * 构造产品缩略图 URL。
 * @param {object} product 产品对象
 * @param {number} size 缩略图尺寸，默认 640
 * @returns {string} 图片 URL
 */
export function productImageUrl(product, size = 640) {
  const websiteMediaId = product.cardMediaId || product.heroMediaId
  if (websiteMediaId) return websiteProductMediaUrl(websiteMediaId)
  return product.defaultImageAssetId
    ? `${apiBase}/api/file-assets/product_model/assets/${product.defaultImageAssetId}/thumbnail?size=${size}`
    : '/images/manual-sealer.jpg'
}

/**
 * 构造产品缩略图 srcset（响应式图片）。
 * 生成 320w / 640w / 960w 三档，浏览器按视口自动选择最合适的尺寸。
 * @param {object} product 产品对象
 * @returns {string} srcset 字符串，无图时返回空
 */
export function productImageSrcSet(product) {
  if (product.cardMediaId || product.heroMediaId) return ''
  if (!product.defaultImageAssetId) return ''
  const base = `${apiBase}/api/file-assets/product_model/assets/${product.defaultImageAssetId}/thumbnail?size=`
  return `${base}320 320w, ${base}640 640w, ${base}960 960w`
}

/** 官网独立媒体公开地址。 */
export function websiteProductMediaUrl(mediaId) {
  return `${apiBase}/api/website/media/${mediaId}/content`
}

/**
 * 获取数学验证码。
 * @returns {Promise<{captchaId: string, question: string}>}
 */
export function fetchCaptcha() {
  return getJson('/api/website/captcha')
}

/**
 * 提交询盘。
 * @param {object} payload 询盘表单数据（含 captchaId + captchaAnswer + 蜜罐字段 website）
 * @returns {Promise<object>} 提交结果
 */
export function submitInquiry(payload) {
  return postJson('/api/website/inquiries', payload)
}

/**
 * 拉取官网可见的解决方案分组数据。
 * @returns {Promise<SolutionSectionGroup[]>}
 */
export function fetchSolutions() {
  return getJson('/api/website/solutions/public')
}

/** 官网可见的方案文章列表。 */
export function fetchSolutionArticles() {
  return getJson('/api/website/solutions/public/articles')
}

/** 按 slug 获取一篇官网方案文章。 */
export async function fetchSolutionArticle(slug) {
  try {
    return await getJson(`/api/website/solutions/public/articles/${encodeURIComponent(slug)}`)
  } catch (err) {
    if (err.message === 'HTTP 404') return null
    throw err
  }
}

/**
 * 拉取官网可见的Banner数据（按位置分组）。
 * @returns {Promise<Record<string, PublicBannerView[]>>}
 */
export function fetchBanners() {
  return getJson('/api/website/banners/public')
}

/**
 * 拉取某页面下启用的区块内容（标题/副标题/描述等）。
 * @param {string} pageKey 页面标识：HOME / ABOUT / CONTACT
 * @returns {Promise<Array<PublicPageSectionView>>}
 */
export function fetchPageSections(pageKey) {
  return getJson(`/api/website/page-sections/${pageKey}`)
}

/**
 * @typedef {object} PublicBannerView
 * @property {number} id
 * @property {string} title
 * @property {string} [subtitle]
 * @property {string} [description]
 * @property {string} [imageUrl]
 * @property {string} [buttonText]
 * @property {string} [buttonUrl]
 * @property {string} [secondaryButtonText]
 * @property {string} [secondaryButtonUrl]
 * @property {string} [badge]
 * @property {number} sortOrder
 * @property {string} position
 */

/**
 * @typedef {object} HomeView
 * @property {PublicCompanyView} company
 * @property {number} productCount
 * @property {PublicProductView[]} products
 * @property {PublicMilestoneView[]} milestones
 */

/**
 * @typedef {object} PublicCompanyView
 * @property {string} companyName
 * @property {string} brandName
 * @property {string} brandSlogan
 * @property {string} corePositioning
 * @property {string} mainBusiness
 * @property {string} advantages
 * @property {string} manufacturingStrengths  JSON字符串，包含制造实力列表
 * @property {string} phone
 * @property {string} email
 * @property {string} officeAddress
 * @property {string} factoryAddress
 * @property {string} website
 * @property {string} establishmentDate  ISO 日期字符串
 */

/**
 * 官网官方自媒体账号视图（仅含对外展示字段）。
 * @typedef {object} PublicMediaAccountView
 * @property {number} id
 * @property {string} platform      平台（抖音/视频号/小红书/快手 等）
 * @property {string} accountName   账号名称
 * @property {string} [link]        主页链接，非空时才能跳转
 */

/**
 * @typedef {object} PublicProductView
 * @property {number} id
 * @property {string} name
 * @property {string} modelCode
 * @property {string} productLineName
 * @property {string} equipmentCategory
 * @property {string} machineType
 * @property {number} variantCount
 * @property {number} [defaultImageAssetId]
 */

