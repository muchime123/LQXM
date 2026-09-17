import { http } from '../http'

// ====== Types ======

export interface PackagingMoldSummary {
  id: number
  modelId?: number
  modelName?: string
  groupId?: number
  groupName?: string
  moldCode: string
  baseMoldCode?: string
  moldTypeCode?: string
  cavityCount?: number
  moldRole?: string
  moldCategory?: string
  websiteEnabled?: boolean
  websiteSortOrder?: number
  compartmentCount?: string
  surfaceStructure?: string
  bottomEngraving?: string
  engravingType?: string
  status?: string
  variantCount: number
  accessoryCount: number
  representativeCaliber?: string
  representativeHeight?: string
}

export interface PackagingMoldView {
  id: number
  modelId?: number
  modelName?: string
  groupId?: number
  groupName?: string
  moldCode: string
  baseMoldCode?: string
  moldTypeCode?: string
  cavityCount?: number
  moldRole?: string
  moldCategory?: string
  websiteEnabled?: boolean
  websiteSortOrder?: number
  compartmentCount?: string
  surfaceStructure?: string
  bottomEngraving?: string
  engravingType?: string
  rawAccessoriesText?: string
  rawCaliber?: string
  rawHeight?: string
  status?: string
  remark?: string
  sourceFile?: string
  sourceSheet?: string
  sourceRowNumber?: number
  importBatchId?: number
  createdAt?: string
  updatedAt?: string
}

export interface MoldGroupSummary {
  id: number
  modelId: number
  modelName?: string
  name: string
  normalizedKey?: string
  shapeType?: string
  lengthMm?: string
  widthMm?: string
  diameterMm?: string
  createdAt?: string
  moldCount: number
}

export interface MoldGroupDetail {
  id: number
  modelId: number
  modelName?: string
  name: string
  normalizedKey?: string
  shapeType?: string
  lengthMm?: string
  widthMm?: string
  diameterMm?: string
  remark?: string
  createdAt?: string
  molds: MoldInGroup[]
}

export interface MoldInGroup {
  id: number
  moldCode: string
  baseMoldCode?: string
  moldTypeCode?: string
  cavityCount?: number
  status?: string
  variantLinkCount: number
  accessoryCount: number
}

export type MoldGroupPayload = {
  modelId: number
  name: string
  shapeType?: string
  lengthMm?: string
  widthMm?: string
  diameterMm?: string
  remark?: string
}

export interface MoldVariantLinkView {
  id: number
  moldId?: number
  variantId?: number
  variantName?: string
  variantHeight?: string
  variantCapacity?: string
  relationType: string
  isDefault: boolean
  remark?: string
  createdAt?: string
}

export interface MoldAccessoryView {
  id: number
  moldId?: number
  accessoryType: string
  accessoryCode?: string
  quantity: number
  sharedScope: string
  remark?: string
  createdAt?: string
}

export interface PackagingMoldDetail {
  mold: PackagingMoldView
  variantLinks: MoldVariantLinkView[]
  accessories: MoldAccessoryView[]
}

export interface MoldByVariantView {
  linkId: number
  moldId?: number
  moldCode?: string
  baseMoldCode?: string
  moldTypeCode?: string
  cavityCount?: number
  moldRole?: string
  status?: string
  relationType: string
  isDefault: boolean
}

/**
 * 规格的模具汇总，用于规格卡片快速显示默认模具 + 其他模具数量。
 */
export interface VariantMoldSummary {
  variantId: number
  defaultMoldId?: number
  defaultMoldCode?: string
  defaultMoldStatus?: string
  totalMoldCount: number
}

export interface MoldStats {
  totalMolds: number
  inUseCount: number
  stoppedCount: number
  repairCount: number
  unverifiedCount: number
  mainMoldCount: number
  accessoryMoldCount: number
  variantLinkCount: number
  accessoryItemCount: number
}

export interface MoldHistoryEntry {
  id: number
  moldId?: number
  fieldKey: string
  fieldLabel?: string
  oldValue?: string
  newValue?: string
  changeType: 'status_change' | 'field_update'
  changeSource: string
  changeReason?: string
  changedBy?: string
  createdAt?: string
}

export type MoldPayload = Partial<{
  modelId: number
  groupId: number
  moldCode: string
  baseMoldCode: string
  moldTypeCode: string
  cavityCount: number
  moldRole: string
  moldCategory: string
  websiteEnabled: boolean
  websiteSortOrder: number
  compartmentCount: string
  surfaceStructure: string
  bottomEngraving: string
  engravingType: string
  rawAccessoriesText: string
  rawCaliber: string
  rawHeight: string
  status: string
  remark: string
  sourceFile: string
  sourceSheet: string
  sourceRowNumber: number
  importBatchId: number
}>

export type MoldVariantLinkPayload = {
  moldId: number
  variantId: number
  relationType?: string
  isDefault?: boolean
  remark?: string
}

export type MoldAccessoryPayload = {
  moldId: number
  accessoryType: string
  accessoryCode?: string
  quantity?: number
  sharedScope?: string
  remark?: string
}

export interface VariantMatchCandidate {
  variantId: number
  variantName: string
  score: number
  diffFields?: string[]
  isDefault?: boolean
  modelId?: number
  modelName?: string
  lengthMm?: string
  widthMm?: string
  topDiameterMm?: string
  heightMm?: string
}

export interface ImportRow {
  sourceRowNumber?: number
  productName?: string
  moldCode?: string
  baseMoldCode?: string
  moldTypeCode?: string
  cavityCount?: number
  moldCategory?: string
  compartmentCount?: string
  caliber?: string
  lengthMm?: string
  widthMm?: string
  topDiameterMm?: string
  heightMm?: string
  capacityMl?: string
  surfaceStructure?: string
  accessories?: string
  accessoryList?: string[]
  bottomEngraving?: string
  engravingType?: string
  rawRowText?: string
  disposition: 'new' | 'merge' | 'conflict' | 'unverified'
  issue?: string
  variantMatch?: 'matched' | 'multi_match' | 'no_match' | 'pending'
  matchCandidates?: VariantMatchCandidate[]
  selectedVariantId?: number
  matchedModelId?: number
  matchedModelName?: string
}

export interface ImportError {
  rowNumber?: number
  field?: string
  message: string
  severity: 'error' | 'warning'
}

export interface ImportPreview {
  modelId?: number
  modelName?: string
  fileName?: string
  sheetName?: string
  totalRows: number
  expandedRows: number
  newCount: number
  mergeCount: number
  unverifiedCount: number
  matchedCount: number
  multiMatchCount: number
  noMatchCount: number
  headers: string[]
  rows: ImportRow[]
  errors: ImportError[]
}

export interface ImportConfirmRequest {
  preview: ImportPreview
  rowIndexes?: number[]
  importBatchId?: number
  /** null 表示用户切换系列后明确清空旧规格选择，本次只导入模具、不建立规格关联。 */
  variantOverrides?: Record<number, number | null>
  modelOverrides?: Record<number, number>
}

export interface ImportConfirmResult {
  importBatchId?: number
  createdMolds: number
  mergedMolds: number
  createdGroups: number
  createdVariantLinks: number
  createdAccessories: number
  skipped: number
  issues: string[]
}

export interface MoldImportBatchSummary {
  importBatchId: number
  sourceFile?: string
  sourceSheet?: string
  importedAt?: string
  moldCount: number
  variantLinkCount: number
  accessoryCount: number
}

export interface MoldImportBatchItem {
  id: number
  batchId: number
  entityType: string
  entityId?: number
  operationType: 'CREATE' | 'MERGE' | 'RESTORE' | 'LINK'
  entityLabel?: string
  rollbackStatus: string
  createdAt?: string
}

export interface MoldImportRollbackAssessment {
  importBatchId: number
  reversible: boolean
  createCount: number
  linkCount: number
  blockers: string[]
}

export interface MoldImportRollbackResult {
  importBatchId: number
  rolledBackItems: number
  issues: string[]
}

export interface FilterOptions {
  moldTypeCodes: string[]
  statuses: string[]
  moldRoles: string[]
}

export interface FetchMoldsParams {
  keyword?: string
  modelId?: number
  moldRole?: string
  moldTypeCode?: string
  status?: string
  engravingType?: string
  hasVariant?: boolean
  sort?: string
  pageByGroup?: boolean
  page?: number
  size?: number
}

// ====== CRUD ======

export function fetchPackagingMolds(params?: FetchMoldsParams) {
  return http.get('/packaging-molds', { params }).then(res => res.data)
}

export function fetchPackagingMoldFilterOptions() {
  return http.get('/packaging-molds/filters').then(res => res.data as FilterOptions)
}

export function fetchPackagingMoldStats() {
  return http.get('/packaging-molds/stats').then(res => res.data as MoldStats)
}

export function fetchPackagingMold(id: number) {
  return http.get(`/packaging-molds/${id}`).then(res => res.data as PackagingMoldDetail)
}

export function fetchMoldHistory(moldId: number) {
  return http.get(`/packaging-molds/${moldId}/history`).then(res => res.data as MoldHistoryEntry[])
}

export function fetchMoldsByVariant(variantId: number) {
  return http.get(`/packaging-molds/by-variant/${variantId}`).then(res => res.data as MoldByVariantView[])
}

/**
 * 按系列批量汇总各规格的模具：默认模具 + 模具总数，供规格卡片展示。
 */
export function fetchVariantMoldSummaryByModel(modelId: number) {
  return http.get(`/packaging-molds/summarize/by-model/${modelId}`).then(res => res.data as VariantMoldSummary[])
}

export function createPackagingMold(data: MoldPayload) {
  return http.post('/packaging-molds', data).then(res => res.data as PackagingMoldView)
}

export function updatePackagingMold(id: number, data: MoldPayload) {
  return http.put(`/packaging-molds/${id}`, data).then(res => res.data as PackagingMoldView)
}

export function deletePackagingMold(id: number) {
  return http.delete(`/packaging-molds/${id}`).then(res => res.data)
}

export function batchDeletePackagingMolds(ids: number[]) {
  return http.post('/packaging-molds/batch-delete', { ids }).then(res => res.data as { count: number })
}

export function batchUpdatePackagingMolds(ids: number[], fields: Record<string, unknown>) {
  return http.put('/packaging-molds/batch', { ids, fields }).then(res => res.data as { count: number })
}

// ====== 模具↔规格 关联 ======

export function createMoldVariantLink(data: MoldVariantLinkPayload) {
  return http.post('/packaging-molds/links', data).then(res => res.data as MoldVariantLinkView)
}

export function updateMoldVariantLink(id: number, data: MoldVariantLinkPayload) {
  return http.put(`/packaging-molds/links/${id}`, data).then(res => res.data as MoldVariantLinkView)
}

export function deleteMoldVariantLink(id: number) {
  return http.delete(`/packaging-molds/links/${id}`).then(res => res.data)
}

// ====== 配件 ======

export function createMoldAccessory(data: MoldAccessoryPayload) {
  return http.post('/packaging-molds/accessories', data).then(res => res.data as MoldAccessoryView)
}

export function updateMoldAccessory(id: number, data: MoldAccessoryPayload) {
  return http.put(`/packaging-molds/accessories/${id}`, data).then(res => res.data as MoldAccessoryView)
}

export function deleteMoldAccessory(id: number) {
  return http.delete(`/packaging-molds/accessories/${id}`).then(res => res.data)
}

// ====== Excel 导入 ======

export function previewPackagingMoldImport(file: File, modelId?: number) {
  const fd = new FormData()
  fd.append('file', file)
  const query = modelId != null ? `?modelId=${modelId}` : ''
  return http
    .post(`/packaging-molds/import/preview${query}`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    .then(res => res.data as ImportPreview)
}

export function confirmPackagingMoldImport(request: ImportConfirmRequest) {
  return http.post('/packaging-molds/import/confirm', request).then(res => res.data as ImportConfirmResult)
}

// ====== 模具规格族 (MoldGroup) ======

export function fetchMoldGroups(params?: { modelId?: number }) {
  return http.get('/packaging-mold-groups', { params }).then(res => res.data as MoldGroupSummary[])
}

export function createMoldGroup(data: MoldGroupPayload) {
  return http.post('/packaging-mold-groups', data).then(res => res.data as MoldGroupDetail)
}
