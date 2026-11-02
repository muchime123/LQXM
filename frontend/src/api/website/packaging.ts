import { http } from '../http'

export interface WebsitePackagingItem {
  id: number
  slug: string
  name: string
  packagingKind: string | null
  material: string | null
  shapeType: string | null
  capacityMl: string | null
  applicable: string | null
  customizable: boolean
  coverMediaId: number | null
  sortOrder: number
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
}

export type WebsitePackagingFields = Omit<WebsitePackagingItem, 'id' | 'status'>

interface Page<T> { content: T[]; totalElements: number }

export interface WebsitePackagingCutoverReadiness {
  technicallyReady: boolean
  totalItems: number
  publishedItems: number
  publishedVariants: number
  blockers: string[]
  itemIssues: Array<{ id: number; name: string; reasons: string[] }>
}

export async function fetchWebsitePackagingReadiness(): Promise<WebsitePackagingCutoverReadiness> {
  const response = await http.get<WebsitePackagingCutoverReadiness>('/website/admin/packaging/cutover-readiness')
  return response.data
}

export async function listWebsitePackaging(page = 0, size = 20): Promise<Page<WebsitePackagingItem>> {
  const response = await http.get<Page<WebsitePackagingItem>>('/website/admin/packaging', { params: { page, size } })
  return response.data
}

export async function createWebsitePackaging(fields: WebsitePackagingFields): Promise<WebsitePackagingItem> {
  const response = await http.post<WebsitePackagingItem>('/website/admin/packaging', fields)
  return response.data
}

export async function updateWebsitePackaging(id: number, fields: WebsitePackagingFields): Promise<WebsitePackagingItem> {
  const response = await http.put<WebsitePackagingItem>(`/website/admin/packaging/${id}`, fields)
  return response.data
}

export async function publishWebsitePackaging(id: number): Promise<WebsitePackagingItem> {
  const response = await http.post<WebsitePackagingItem>(`/website/admin/packaging/${id}/publish`)
  return response.data
}

export async function unpublishWebsitePackaging(id: number): Promise<WebsitePackagingItem> {
  const response = await http.post<WebsitePackagingItem>(`/website/admin/packaging/${id}/unpublish`)
  return response.data
}

export interface WebsitePackagingVariant {
  id: number
  itemId: number
  name: string
  publicSku: string | null
  dimensions: string | null
  capacityMl: string | null
  description: string | null
  sortOrder: number
}

export type WebsitePackagingVariantFields = Omit<WebsitePackagingVariant, 'id' | 'itemId'>

export async function listWebsitePackagingVariants(itemId: number): Promise<WebsitePackagingVariant[]> {
  const response = await http.get<WebsitePackagingVariant[]>(`/website/admin/packaging/${itemId}/variants`)
  return response.data
}

export async function createWebsitePackagingVariant(itemId: number, fields: WebsitePackagingVariantFields): Promise<WebsitePackagingVariant> {
  const response = await http.post<WebsitePackagingVariant>(`/website/admin/packaging/${itemId}/variants`, fields)
  return response.data
}

export async function updateWebsitePackagingVariant(itemId: number, id: number, fields: WebsitePackagingVariantFields): Promise<WebsitePackagingVariant> {
  const response = await http.put<WebsitePackagingVariant>(`/website/admin/packaging/${itemId}/variants/${id}`, fields)
  return response.data
}

export async function removeWebsitePackagingVariant(itemId: number, id: number): Promise<void> {
  await http.delete(`/website/admin/packaging/${itemId}/variants/${id}`)
}
