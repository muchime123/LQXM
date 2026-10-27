import { http } from '../http'

export interface WebsiteProductDraft {
  id: number
  name: string
  nameEn: string | null
  publicModel: string | null
  slug: string
  summary: string | null
  summaryEn: string | null
  seoTitle: string | null
  seoDescription: string | null
  sortOrder: number
  featured: boolean
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
}

export type WebsiteProductDraftFields = Omit<WebsiteProductDraft, 'id' | 'status'>

export interface WebsiteProductPage {
  content: WebsiteProductDraft[]
  totalElements: number
  number: number
  size: number
}

export async function fetchWebsiteProductDrafts(page = 0, size = 20): Promise<WebsiteProductPage> {
  const response = await http.get<WebsiteProductPage>('/website/admin/products', { params: { page, size } })
  return response.data
}

export async function createWebsiteProductDraft(fields: WebsiteProductDraftFields): Promise<WebsiteProductDraft> {
  const response = await http.post<WebsiteProductDraft>('/website/admin/products', fields)
  return response.data
}

export async function updateWebsiteProductDraft(id: number, fields: WebsiteProductDraftFields): Promise<WebsiteProductDraft> {
  const response = await http.put<WebsiteProductDraft>(`/website/admin/products/${id}`, fields)
  return response.data
}
