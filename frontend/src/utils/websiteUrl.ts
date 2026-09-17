/**
 * 官网 URL 统一工具
 *
 * 后台所有「打开官网 / 预览官网」场景必须经过此工具，禁止直接拼接 `/#/path`。
 * 官网使用 createWebHistory()，正式地址为 /products/xxx（无 hash）。
 *
 * 用法：
 *   import { openCorporateWebsite, getCorporateProductUrl } from '@/utils/websiteUrl'
 *   openCorporateWebsite('/solutions')
 *   const url = getCorporateProductUrl(slug)
 */

const DEFAULT_PORT = '5180'

/** 官网 origin（协议 + 主机 + 端口），读取 VITE_CORPORATE_WEBSITE_URL，默认当前主机 5180 端口 */
export function getCorporateWebsiteOrigin(): string {
  return import.meta.env.VITE_CORPORATE_WEBSITE_URL || `${location.protocol}//${location.hostname}:${DEFAULT_PORT}`
}

/** 拼接完整官网 URL，path 需以 / 开头 */
export function getCorporateWebsiteUrl(path = '/'): string {
  const origin = getCorporateWebsiteOrigin()
  const normalized = path.startsWith('/') ? path : `/${path}`
  return `${origin}${normalized}`
}

/** 打开官网指定路径，默认新标签页 */
export function openCorporateWebsite(path = '/', target: '_blank' | '_self' = '_blank'): void {
  window.open(getCorporateWebsiteUrl(path), target)
}

/** 产品详情页完整 URL */
export function getCorporateProductUrl(slug: string): string {
  return getCorporateWebsiteUrl(`/products/${encodeURIComponent(slug)}`)
}

/** 方案文章详情页完整 URL */
export function getCorporateSolutionArticleUrl(slug: string): string {
  return getCorporateWebsiteUrl(`/solutions/${encodeURIComponent(slug)}`)
}
