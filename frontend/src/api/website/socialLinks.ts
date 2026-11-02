import { http } from '../http'

export interface WebsiteSocialLink {
  id: number
  platform: string
  accountName: string
  link: string
  sortOrder: number
  status: 'DRAFT' | 'PUBLISHED'
}
export type WebsiteSocialLinkFields = Pick<WebsiteSocialLink, 'platform' | 'accountName' | 'link' | 'sortOrder'>
const base = '/website/admin/social-links'

export interface WebsiteSocialLinkParity {
  legacyCount: number
  websiteCount: number
  publishedMatches: number
  draftMatches: number
  legacyRows: Array<{ legacyId: number; platform: string; accountName: string; link: string | null; websiteId: number | null; matchStatus: 'PUBLISHED' | 'DRAFT' | 'MISSING' }>
  unmatchedWebsiteRows: Array<{ websiteId: number; platform: string; accountName: string; link: string; status: string }>
}

export async function fetchWebsiteSocialLinkParity(): Promise<WebsiteSocialLinkParity> {
  return (await http.get<WebsiteSocialLinkParity>(`${base}/parity`)).data
}

export async function fetchWebsiteSocialLinks(): Promise<WebsiteSocialLink[]> {
  return (await http.get<WebsiteSocialLink[]>(base)).data
}
export async function createWebsiteSocialLink(fields: WebsiteSocialLinkFields): Promise<void> { await http.post(base, fields) }
export async function updateWebsiteSocialLink(id: number, fields: WebsiteSocialLinkFields): Promise<void> { await http.put(`${base}/${id}`, fields) }
export async function publishWebsiteSocialLink(id: number): Promise<void> { await http.post(`${base}/${id}/publish`) }
export async function archiveWebsiteSocialLink(id: number): Promise<void> { await http.delete(`${base}/${id}`) }
