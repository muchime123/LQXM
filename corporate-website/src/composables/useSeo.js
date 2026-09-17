/**
 * SEO 管理：统一设置页面 title、meta description、Open Graph 和 Twitter Card。
 *
 * 用法：
 *   1. 静态页面：在路由配置的 meta 中声明 seo: { title, description }，
 *      路由 afterEach 守卫会自动调用 setSeo。
 *   2. 动态页面（如产品详情）：加载数据后调用 setSeo(title, description, { ogImage, ogType })。
 *
 * 注意：SPA 模式下 meta 仅在客户端运行时更新，搜索引擎抓取依赖 SSR/预渲染时
 * 需另行配置。当前用于改善浏览器标签页标题和基础 SEO 体验。
 */

export const SITE_NAME = '利强包装'
export const SITE_URL = 'https://www.liqiangbz.com'
const DEFAULT_OG_IMAGE = '/images/factory.webp'

/**
 * 设置或更新一个 meta 标签。
 * @param {string} selector 属性选择器，如 'meta[property="og:title"]'
 * @param {string} attr 属性名，如 'property' 或 'name'
 * @param {string} attrValue 属性值，如 'og:title'
 * @param {string} content 内容
 */
function setMetaTag(selector, attr, attrValue, content) {
  let meta = document.querySelector(selector)
  if (!meta) {
    meta = document.createElement('meta')
    meta.setAttribute(attr, attrValue)
    document.head.appendChild(meta)
  }
  meta.setAttribute('content', content)
}

/**
 * 设置页面 SEO（含 Open Graph + Twitter Card）。
 * @param {string} title       页面标题（不含站点名，会自动拼接）
 * @param {string} [description] 页面描述（meta description）
 * @param {object} [options]   额外选项
 * @param {string} [options.ogImage]  Open Graph 图片路径（绝对路径或完整 URL）
 * @param {string} [options.ogType]   Open Graph 类型，默认 website
 * @param {string} [options.canonical] canonical URL（不含 query / hash）
 */
export function setSeo(title, description, options = {}) {
  const { ogImage, ogType = 'website', canonical } = options

  const fullTitle = title
    ? title.includes(SITE_NAME) ? title : `${title}｜${SITE_NAME}`
    : `${SITE_NAME}｜食品包装设备与一站式包装解决方案`
  document.title = fullTitle

  const desc = description || '浙江利强包装科技有限公司专注食品包装设备研发制造，提供封口机、气调包装机、真空包装机、自动化包装产线及包装耗材模具。'
  const imageUrl = ogImage
    ? (ogImage.startsWith('http') ? ogImage : `${SITE_URL}${ogImage}`)
    : `${SITE_URL}${DEFAULT_OG_IMAGE}`

  /* og:url 与 canonical 保持一致：优先使用 canonical，否则取当前 URL 去掉 query/hash */
  const ogUrl = canonical || `${SITE_URL}${window.location.pathname}`

  // meta description
  setMetaTag('meta[name="description"]', 'name', 'description', desc)

  // Open Graph
  setMetaTag('meta[property="og:title"]', 'property', 'og:title', fullTitle)
  setMetaTag('meta[property="og:description"]', 'property', 'og:description', desc)
  setMetaTag('meta[property="og:type"]', 'property', 'og:type', ogType)
  setMetaTag('meta[property="og:site_name"]', 'property', 'og:site_name', SITE_NAME)
  setMetaTag('meta[property="og:image"]', 'property', 'og:image', imageUrl)
  setMetaTag('meta[property="og:url"]', 'property', 'og:url', ogUrl)

  // Twitter Card
  setMetaTag('meta[name="twitter:card"]', 'name', 'twitter:card', 'summary_large_image')
  setMetaTag('meta[name="twitter:title"]', 'name', 'twitter:title', fullTitle)
  setMetaTag('meta[name="twitter:description"]', 'name', 'twitter:description', desc)
  setMetaTag('meta[name="twitter:image"]', 'name', 'twitter:image', imageUrl)

  /* canonical：传入则设置，不传则移除旧标签（防止上一页的 canonical 残留） */
  let link = document.querySelector('link[rel="canonical"]')
  if (canonical) {
    if (!link) {
      link = document.createElement('link')
      link.setAttribute('rel', 'canonical')
      document.head.appendChild(link)
    }
    link.setAttribute('href', canonical)
  } else if (link) {
    link.remove()
  }
}

/**
 * 从路由 meta 中读取 SEO 信息并应用。
 * 供路由 afterEach 守卫调用。
 * - canonical 始终用 route.path（去掉 query / hash），避免分页/筛选/锚点产生重复内容
 * - 404 catch-all 路由不设 canonical（不应被搜索引擎索引随机路径）
 * @param {import('vue-router').Route} route
 */
export function applyRouteSeo(route) {
  const seo = route.meta?.seo
  if (seo) {
    /* 404 路由：不设置 canonical，避免搜索引擎索引不存在的路径 */
    const canonical = route.name === 'not-found'
      ? undefined
      : `${SITE_URL}${route.path}`
    setSeo(seo.title, seo.description, { canonical })
  } else {
    setSeo(null, null)
  }
}
