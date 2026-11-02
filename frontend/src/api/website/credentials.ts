import { http } from '../http'

export interface WebsiteCredential {
  id: number
  name: string
  category: string | null
  publicDescription: string | null
  issuer: string | null
  validUntil: string | null
  mediaId: number | null
  sortOrder: number
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
}

export type WebsiteCredentialFields = Omit<WebsiteCredential, 'id' | 'status'>

export async function listWebsiteCredentials(): Promise<WebsiteCredential[]> {
  const response = await http.get<WebsiteCredential[]>('/website/admin/credentials')
  return response.data
}

export async function createWebsiteCredential(fields: WebsiteCredentialFields): Promise<WebsiteCredential> {
  const response = await http.post<WebsiteCredential>('/website/admin/credentials', fields)
  return response.data
}

export async function updateWebsiteCredential(id: number, fields: WebsiteCredentialFields): Promise<WebsiteCredential> {
  const response = await http.put<WebsiteCredential>(`/website/admin/credentials/${id}`, fields)
  return response.data
}

export async function publishWebsiteCredential(id: number): Promise<WebsiteCredential> {
  const response = await http.post<WebsiteCredential>(`/website/admin/credentials/${id}/publish`)
  return response.data
}

export async function unpublishWebsiteCredential(id: number): Promise<WebsiteCredential> {
  const response = await http.post<WebsiteCredential>(`/website/admin/credentials/${id}/unpublish`)
  return response.data
}
