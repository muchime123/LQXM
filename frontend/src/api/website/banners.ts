import { http } from '../http'
import type {
  WebsiteBannerDetail,
  WebsiteBannerPayload,
  WebsiteBannerPage,
  WebsiteBannerQuery
} from '../../types/website'

/** Banner列表（管理端） */
export async function fetchBanners(params: WebsiteBannerQuery = {}): Promise<WebsiteBannerPage> {
  const query: Record<string, string> = {}
  if (params.keyword) query.keyword = params.keyword
  if (params.position) query.position = params.position
  if (params.websiteEnabled !== undefined) query.websiteEnabled = String(params.websiteEnabled)
  query.page = String(params.page || 0)
  query.size = String(params.size || 20)
  const res = await http.get('/admin/website/banners', { params: query })
  return res.data
}

/** Banner详情 */
export async function fetchBannerById(id: number): Promise<WebsiteBannerDetail> {
  const res = await http.get(`/admin/website/banners/${id}`)
  return res.data
}

/** 创建Banner */
export async function createBanner(payload: WebsiteBannerPayload): Promise<WebsiteBannerDetail> {
  const res = await http.post('/admin/website/banners', payload)
  return res.data
}

/** 更新Banner */
export async function updateBanner(id: number, payload: WebsiteBannerPayload): Promise<WebsiteBannerDetail> {
  const res = await http.put(`/admin/website/banners/${id}`, payload)
  return res.data
}

/** 删除Banner（软删除） */
export async function deleteBanner(id: number): Promise<void> {
  await http.delete(`/admin/website/banners/${id}`)
}

/** 恢复Banner */
export async function restoreBanner(id: number): Promise<WebsiteBannerDetail> {
  const res = await http.post(`/admin/website/banners/${id}/restore`)
  return res.data
}

/** 切换官网可见状态 */
export async function toggleBannerEnabled(id: number): Promise<WebsiteBannerDetail> {
  const res = await http.post(`/admin/website/banners/${id}/toggle-enabled`)
  return res.data
}

/** 批量更新排序 */
export async function batchUpdateBannerSortOrder(sortOrders: Record<number, number>): Promise<void> {
  await http.put('/admin/website/banners/batch-sort', sortOrders)
}
