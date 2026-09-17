import { http } from '../http'
import type { WebsiteDashboard } from '../../types/website'

/** 官网工作台聚合数据（官网发布数量 + 内容健康度）。 */
export async function fetchWebsiteDashboard(): Promise<WebsiteDashboard> {
  const res = await http.get('/website/admin/dashboard')
  return res.data
}