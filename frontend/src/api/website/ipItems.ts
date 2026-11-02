import { http } from '../http'

export interface WebsiteIpItem {
  id: number
  name: string
  category: string
  patentType: string | null
  publicNumber: string | null
  applicant: string | null
  legalStatus: string | null
  mediaId: number | null
  sortOrder: number
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
}

export type WebsiteIpItemFields = Omit<WebsiteIpItem, 'id' | 'status'>
const base = '/website/admin/ip-items'

export async function listWebsiteIpItems(): Promise<WebsiteIpItem[]> {
  return (await http.get<WebsiteIpItem[]>(base)).data
}
export async function createWebsiteIpItem(fields: WebsiteIpItemFields): Promise<WebsiteIpItem> {
  return (await http.post<WebsiteIpItem>(base, fields)).data
}
export async function updateWebsiteIpItem(id: number, fields: WebsiteIpItemFields): Promise<WebsiteIpItem> {
  return (await http.put<WebsiteIpItem>(`${base}/${id}`, fields)).data
}
export async function publishWebsiteIpItem(id: number): Promise<WebsiteIpItem> {
  return (await http.post<WebsiteIpItem>(`${base}/${id}/publish`)).data
}
export async function unpublishWebsiteIpItem(id: number): Promise<WebsiteIpItem> {
  return (await http.post<WebsiteIpItem>(`${base}/${id}/unpublish`)).data
}
