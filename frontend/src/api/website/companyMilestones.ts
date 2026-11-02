import { http } from '../http'

export interface WebsiteCompanyMilestone {
  id: number
  year: number
  title: string
  description: string | null
  sortOrder: number
  status: 'DRAFT' | 'PUBLISHED'
}

export type WebsiteCompanyMilestoneFields = Pick<WebsiteCompanyMilestone, 'year' | 'title' | 'description' | 'sortOrder'>

const base = '/website/admin/company-milestones'

export interface WebsiteCompanyMilestoneParity {
  legacyCount: number
  websiteCount: number
  publishedMatches: number
  draftMatches: number
  legacyRows: Array<{ legacyId: number; year: number; title: string; description: string | null; websiteId: number | null; matchStatus: 'PUBLISHED' | 'DRAFT' | 'MISSING' }>
  unmatchedWebsiteRows: Array<{ websiteId: number; year: number; title: string; description: string | null; status: string }>
}

export async function fetchWebsiteCompanyMilestoneParity(): Promise<WebsiteCompanyMilestoneParity> {
  return (await http.get<WebsiteCompanyMilestoneParity>(`${base}/parity`)).data
}

export async function fetchWebsiteCompanyMilestones(): Promise<WebsiteCompanyMilestone[]> {
  return (await http.get<WebsiteCompanyMilestone[]>(base)).data
}
export async function createWebsiteCompanyMilestone(fields: WebsiteCompanyMilestoneFields): Promise<void> {
  await http.post(base, fields)
}
export async function updateWebsiteCompanyMilestone(id: number, fields: WebsiteCompanyMilestoneFields): Promise<void> {
  await http.put(`${base}/${id}`, fields)
}
export async function publishWebsiteCompanyMilestone(id: number): Promise<void> {
  await http.post(`${base}/${id}/publish`)
}
export async function archiveWebsiteCompanyMilestone(id: number): Promise<void> {
  await http.delete(`${base}/${id}`)
}
