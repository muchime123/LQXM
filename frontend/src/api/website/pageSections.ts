import { http } from '../http'
import type {
  WebsitePageSection,
  WebsitePageSectionPayload
} from '../../types/website'

export async function fetchPageSections(pageKey?: string): Promise<WebsitePageSection[]> {
  const res = await http.get('/website/page-sections/admin/list', { params: { pageKey } })
  return res.data
}

export async function createPageSection(payload: WebsitePageSectionPayload): Promise<WebsitePageSection> {
  const res = await http.post('/website/page-sections/admin', payload)
  return res.data
}

export async function updatePageSection(id: number, payload: WebsitePageSectionPayload): Promise<WebsitePageSection> {
  const res = await http.put(`/website/page-sections/admin/${id}`, payload)
  return res.data
}

export async function deletePageSection(id: number): Promise<void> {
  await http.delete(`/website/page-sections/admin/${id}`)
}

export async function batchUpdatePageSectionEnabled(ids: number[], enabled: boolean): Promise<WebsitePageSection[]> {
  const res = await http.put('/website/page-sections/admin/batch-enabled', { ids, enabled })
  return res.data
}