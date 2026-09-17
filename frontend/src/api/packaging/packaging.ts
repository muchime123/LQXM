import { http } from '../http'
import type { FileOwnerType } from '@/types/fileAsset'

// ====== Types ======

export interface PackagingModelSummary {
  id: number
  name: string
  nameEn?: string
  modelCode?: string
  packagingKind?: string
  websiteEnabled?: boolean
  websiteSortOrder?: number
  filmForm?: string
  shapeType?: string
  material?: string
  colorStyle?: string
  capacityMl?: string
  applicable?: string
  moq?: string
  unitPrice?: string
  leadTime?: string
  customizable?: boolean
  status?: string
  tags?: string
  defaultImage?: string
  defaultImageAssetId?: number
  advantages?: string
  remark?: string
  sortOrder: number
  variantCount: number
  assetCount: number
}

export interface PackagingVariantView {
  id: number
  variantName?: string
  specLabel?: string
  capacityMl?: string
  shapeType?: string
  lengthMm?: string
  widthMm?: string
  heightMm?: string
  topDiameterMm?: string
  bottomDiameterMm?: string
  weightG?: string
  boxesPerRoll?: string
  cartonSize?: string
  cartonWeight?: string
  cartonVolume?: string
  variantMoq?: string
  materialDetail?: string
  filmMaterial?: string
  filmSupplyType?: string
  filmFeatures?: string
  priceUnit?: string
  pricingNotes?: string
  dealerPrice?: string
  retailPrice?: string
  dealerPriceUsd?: string
  retailPriceUsd?: string
  peelability?: string
  thicknessUm?: string
  tier5099?: number
  tier100299?: number
  tier300499?: number
  tier500999?: number
  tier10001999?: number
  tier2000Up?: number
  plateFee?: number
  sortOrder: number
  remark?: string
  createdAt?: string
}

export interface PackagingVariantHistoryView {
  id: number
  variantId: number
  modelId: number
  fieldKey: string
  fieldLabel?: string
  oldValue?: string
  newValue?: string
  changeType?: string
  changeSource?: string
  changeReason?: string
  sourceFile?: string
  sourceSheet?: string
  changedBy?: string
  createdAt?: string
}

export interface PackagingAssetView {
  id: number
  ownerType?: FileOwnerType
  variantId?: number
  category: string
  fileName: string
  tags?: string
  sortOrder: number
  defaultAsset: boolean
  sourceFile?: string
  createdAt?: string
}

export interface PackagingModelDetail {
  model: PackagingModelSummary
  variants: PackagingVariantView[]
  assets: PackagingAssetView[]
  variantCount: number
  assetCount: number
}

export interface PackagingStats {
  totalModels: number
  totalVariants: number
  totalAssets: number
  inStock: number
  discontinued: number
  hasAssets: number
  kindDistribution: { name: string; count: number }[]
  materialDistribution: { name: string; count: number }[]
}

// ====== Models ======

export function fetchPackagingModels(params?: {
  keyword?: string
  packagingKind?: string
  material?: string
  status?: string
  sort?: string
  page?: number
  size?: number
}) {
  return http.get('/packaging/models', { params }).then(res => res.data)
}

export function fetchPackagingFilters() {
  return http.get('/packaging/models/filters').then(res => res.data)
}

export function fetchPackagingModel(id: number) {
  return http.get(`/packaging/models/${id}`).then(res => res.data as PackagingModelDetail)
}

export function createPackagingModel(data: Record<string, unknown>) {
  return http.post('/packaging/models', data).then(res => res.data)
}

export function updatePackagingModel(id: number, data: Record<string, unknown>) {
  return http.put(`/packaging/models/${id}`, data).then(res => res.data)
}

export function deletePackagingModel(id: number) {
  return http.delete(`/packaging/models/${id}`).then(res => res.data)
}

export function batchDeletePackagingModels(ids: number[]) {
  return http.post('/packaging/models/batch-delete', { ids }).then(res => res.data)
}

export function batchUpdatePackagingModels(ids: number[], fields: Record<string, unknown>) {
  return http.put('/packaging/models/batch', { ids, fields }).then(res => res.data as { count: number })
}

// ====== Variants ======

export function createPackagingVariant(modelId: number, data: Record<string, unknown>) {
  return http.post(`/packaging/models/${modelId}/variants`, data).then(res => res.data)
}

export function updatePackagingVariant(id: number, data: Record<string, unknown>) {
  return http.put(`/packaging/variants/${id}`, data).then(res => res.data)
}

export function deletePackagingVariant(id: number) {
  return http.delete(`/packaging/variants/${id}`).then(res => res.data)
}

export function reorderPackagingVariants(items: { id: number; sortOrder: number }[]) {
  return http.put('/packaging/variants/reorder', { items }).then(res => res.data)
}

export function duplicatePackagingVariant(id: number) {
  return http.post(`/packaging/variants/${id}/duplicate`).then(res => res.data)
}

export function movePackagingVariant(id: number, targetModelId: number) {
  return http.post(`/packaging/variants/${id}/move`, { targetModelId }).then(res => res.data)
}

export function fetchPackagingVariantHistory(id: number) {
  return http.get(`/packaging/variants/${id}/history`).then(res => res.data as PackagingVariantHistoryView[])
}

export function batchUpdatePackagingVariants(ids: number[], payload: Record<string, unknown>) {
  return http.post('/packaging/variants/batch-update', { ids, payload }).then(res => res.data)
}

export function batchDeletePackagingVariants(ids: number[]) {
  return http.post('/packaging/variants/batch-delete', { ids }).then(res => res.data)
}

export function fetchPackagingVariantTrash(modelId?: number) {
  return http.get('/packaging/variants/trash', { params: { modelId } }).then(res => res.data as PackagingVariantView[])
}

export function restorePackagingVariant(id: number) {
  return http.put(`/packaging/variants/trash/${id}/restore`).then(res => res.data)
}

export function permanentDeletePackagingVariant(id: number) {
  return http.delete(`/packaging/variants/trash/${id}`).then(res => res.data)
}

// ====== Assets ======

export function addPackagingAsset(
  modelId: number,
  category: string,
  fileName: string,
  tags?: string,
  variantId?: number | null
) {
  return http
    .post(`/packaging/models/${modelId}/assets`, null, {
      params: { category, fileName, tags, variantId }
    })
    .then(res => res.data)
}

export function reorderPackagingAssets(items: { id: number; sortOrder: number }[]) {
  return http.put('/packaging/assets/reorder', { items }).then(res => res.data)
}
