import { http } from '../http'

export interface WebsiteProductDraft {
  id: number
  categoryId: number | null
  name: string
  nameEn: string | null
  publicModel: string | null
  heroMediaId: number | null
  cardMediaId: number | null
  slug: string
  summary: string | null
  summaryEn: string | null
  seoTitle: string | null
  seoDescription: string | null
  sortOrder: number
  featured: boolean
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
}

export interface WebsiteProductReview extends WebsiteProductDraft {
  descriptionHtml: string | null
  descriptionHtmlEn: string | null
  applicationTags: string[]
  containerTags: string[]
  processTags: string[]
  capacityLevel: string | null
  categoryName: string | null
  categorySlug: string | null
  gallery: Array<{
    id: number
    mediaId: number
    role: string
    sortOrder: number
    altText: string | null
    displayName: string | null
    originalFilename: string
    mediaStatus: string
  }>
}

export interface WebsiteProductPublicationReadiness {
  productId: number
  ready: boolean
  blockers: string[]
  warnings: string[]
}

export interface WebsiteNativeProductPreview {
  product: {
    id: number
    name: string
    publicModel: string | null
    category: string | null
    slug: string
    summary: string | null
    seoTitle: string | null
    seoDescription: string | null
    heroMediaId: number | null
    cardMediaId: number | null
  }
  descriptionHtml: string | null
  technicalParameters: Record<string, unknown>
  gallery: Array<{ id: number; mediaId: number; role: string; altText: string | null; sortOrder: number }>
  variants: Array<{ id: number; name: string; publicSku: string | null; dimensions: string | null }>
}

export interface WebsiteProductCutoverReadiness {
  technicallyReady: boolean
  publishedProducts: number
  draftProducts: number
  publishedFeaturedProducts: number
  publishedCategories: number
  draftCategories: number
  publishedMedia: number
  draftMedia: number
  blockers: string[]
  warnings: string[]
  productIssues: Array<{ id: number; name: string; blockers: string[]; warnings: string[] }>
}

export type WebsiteProductDraftFields = Omit<WebsiteProductDraft, 'id' | 'status'>

export interface WebsiteProductSelectionFields {
  applicationTags: string[]
  containerTags: string[]
  processTags: string[]
  capacityLevel: string | null
}

export async function updateWebsiteProductSelection(id: number, fields: WebsiteProductSelectionFields): Promise<void> {
  await http.put(`/website/admin/products/${id}/selection`, fields)
}

export async function updateWebsiteProductParameters(id: number, technicalParameters: Record<string, unknown>): Promise<void> {
  await http.put(`/website/admin/products/${id}/technical-parameters`, { technicalParameters })
}

export async function updateWebsiteProductDescription(id: number, descriptionText: string, descriptionTextEn: string): Promise<void> {
  await http.put(`/website/admin/products/${id}/description`, { descriptionText, descriptionTextEn })
}

export interface WebsiteProductGalleryFields {
  mediaId: number
  role: 'HERO' | 'GALLERY' | 'DETAIL' | 'SCENE' | 'PARAMETER'
  altText: string | null
  sortOrder: number
}

export async function addWebsiteProductGallery(id: number, fields: WebsiteProductGalleryFields): Promise<void> {
  await http.post(`/website/admin/products/${id}/gallery`, fields)
}

export async function updateWebsiteProductGallery(id: number, assignmentId: number, fields: WebsiteProductGalleryFields): Promise<void> {
  await http.put(`/website/admin/products/${id}/gallery/${assignmentId}`, fields)
}

export interface WebsiteProductPage {
  content: WebsiteProductDraft[]
  totalElements: number
  number: number
  size: number
}

export interface WebsiteProductCategory {
  id: number
  name: string
  slug: string
  sortOrder: number
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
}

export interface WebsiteProductVariant {
  id: number
  productId: number
  name: string
  publicSku: string | null
  dimensions: string | null
  description: string | null
  sortOrder: number
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
}

export type WebsiteProductVariantFields = Omit<WebsiteProductVariant, 'id' | 'productId' | 'status'>

export async function fetchWebsiteProductVariants(productId: number): Promise<WebsiteProductVariant[]> {
  const response = await http.get<WebsiteProductVariant[]>(`/website/admin/products/${productId}/variants`)
  return response.data
}

export async function createWebsiteProductVariant(productId: number, fields: WebsiteProductVariantFields): Promise<WebsiteProductVariant> {
  const response = await http.post<WebsiteProductVariant>(`/website/admin/products/${productId}/variants`, fields)
  return response.data
}

export async function updateWebsiteProductVariant(productId: number, id: number, fields: WebsiteProductVariantFields): Promise<WebsiteProductVariant> {
  const response = await http.put<WebsiteProductVariant>(`/website/admin/products/${productId}/variants/${id}`, fields)
  return response.data
}

export async function fetchWebsiteProductCategories(): Promise<WebsiteProductCategory[]> {
  const response = await http.get<WebsiteProductCategory[]>('/website/admin/product-categories')
  return response.data
}

export async function createWebsiteProductCategory(fields: { name: string; slug: string; sortOrder: number }): Promise<WebsiteProductCategory> {
  const response = await http.post<WebsiteProductCategory>('/website/admin/product-categories', fields)
  return response.data
}

export async function updateWebsiteProductCategory(id: number, fields: { name: string; slug: string; sortOrder: number }): Promise<WebsiteProductCategory> {
  const response = await http.put<WebsiteProductCategory>(`/website/admin/product-categories/${id}`, fields)
  return response.data
}

export async function fetchWebsiteProductDrafts(page = 0, size = 20): Promise<WebsiteProductPage> {
  const response = await http.get<WebsiteProductPage>('/website/admin/products', { params: { page, size } })
  return response.data
}

export async function fetchWebsiteProductReview(id: number): Promise<WebsiteProductReview> {
  const response = await http.get<WebsiteProductReview>(`/website/admin/products/${id}/review`)
  return response.data
}

export async function fetchWebsiteProductPublicationReadiness(id: number): Promise<WebsiteProductPublicationReadiness> {
  const response = await http.get<WebsiteProductPublicationReadiness>(`/website/admin/products/${id}/publication-readiness`)
  return response.data
}

export async function fetchWebsiteNativeProductPreview(id: number): Promise<WebsiteNativeProductPreview> {
  const response = await http.get<WebsiteNativeProductPreview>(`/website/admin/native-products/drafts/${id}`)
  return response.data
}

export async function fetchWebsiteProductCutoverReadiness(): Promise<WebsiteProductCutoverReadiness> {
  const response = await http.get<WebsiteProductCutoverReadiness>('/website/admin/native-products/cutover-readiness')
  return response.data
}

export async function publishWebsiteMedia(id: number): Promise<void> {
  await http.post(`/website/admin/publication/media/${id}`)
}

export async function publishWebsiteProductCategory(id: number): Promise<void> {
  await http.post(`/website/admin/publication/categories/${id}`)
}

export async function publishWebsiteProduct(id: number): Promise<void> {
  await http.post(`/website/admin/publication/products/${id}`)
}

export async function createWebsiteProductDraft(fields: WebsiteProductDraftFields): Promise<WebsiteProductDraft> {
  const response = await http.post<WebsiteProductDraft>('/website/admin/products', fields)
  return response.data
}

export async function updateWebsiteProductDraft(id: number, fields: WebsiteProductDraftFields): Promise<WebsiteProductDraft> {
  const response = await http.put<WebsiteProductDraft>(`/website/admin/products/${id}`, fields)
  return response.data
}
