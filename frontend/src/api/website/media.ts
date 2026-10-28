import { http } from '../http'

export interface WebsiteMediaItem {
  id: number
  displayName: string | null
  originalFilename: string
  mediaType: 'IMAGE' | 'VIDEO' | 'DOCUMENT'
  mimeType: string
  sizeBytes: number
  sha256: string
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
  altText: string | null
  caption: string | null
}

export interface WebsiteMediaPage {
  content: WebsiteMediaItem[]
  totalElements: number
  number: number
  size: number
}

export async function fetchWebsiteMedia(page = 0, size = 20, imageOnly = false): Promise<WebsiteMediaPage> {
  const response = await http.get<WebsiteMediaPage>('/website/admin/media', { params: { page, size, imageOnly } })
  return response.data
}

export async function uploadWebsiteMedia(file: File): Promise<WebsiteMediaItem> {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<WebsiteMediaItem>('/website/admin/media', form)
  return response.data
}

/** Admin previews use the authenticated axios instance; native img URLs cannot carry the token. */
export async function fetchWebsiteMediaBlob(id: number): Promise<Blob> {
  const response = await http.get<Blob>(`/website/admin/media/${id}/content`, { responseType: 'blob' })
  return response.data
}

export async function fetchWebsiteMediaItem(id: number): Promise<WebsiteMediaItem> {
  const response = await http.get<WebsiteMediaItem>(`/website/admin/media/${id}`)
  return response.data
}
