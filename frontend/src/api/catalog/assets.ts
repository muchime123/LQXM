import { http } from '../http'

interface AssetData {
  [key: string]: unknown
}

export function addCatalogAsset(modelId: number, data: AssetData) {
  return http.post(`/catalog/models/${modelId}/assets`, data).then(res => res.data)
}

export function moveCatalogAsset(
  assetId: number,
  targetModelId: number,
  targetVariantId: number | null = null,
  targetCategory: string | null = null
) {
  return http
    .put(`/catalog/assets/${assetId}/move`, { targetModelId, targetVariantId, targetCategory })
    .then(res => res.data)
}

export function reorderCatalogAssets(items: { id: number; order: number }[]) {
  return http.put('/catalog/assets/reorder', { items }).then(res => res.data)
}

export interface CatalogAssetMetadataPayload {
  contentTags: string
  specificationAction?: 'KEEP' | 'ASSIGN' | 'UNLINK'
  variantId?: number | null
  machineTierAction?: 'KEEP' | 'ASSIGN' | 'UNLINK'
  machineTierId?: number | null
  machineTierIds?: number[]
  applicationTags?: string
  videoContentType?: string
  videoUsageStatus?: string
  featureTags?: string
  demonstrationTags?: string
  videoTitle?: string
  machineScopeConfirmed?: boolean
  videoUsageChannels?: string
  videoContentStatus?: string
  videoFavorite?: boolean
}

export function updateCatalogAssetMetadata(assetId: number, data: CatalogAssetMetadataPayload) {
  return http.put(`/catalog/assets/${assetId}/metadata`, data).then(res => res.data)
}

export interface CatalogAssetBatchMetadataPayload {
  ids: number[]
  contentTags?: string
  tagAction?: 'APPEND' | 'REPLACE' | 'REMOVE' | 'KEEP'
  specificationAction?: 'KEEP' | 'ASSIGN' | 'UNLINK'
  variantId?: number | null
  machineTierAction?: 'KEEP' | 'ASSIGN' | 'UNLINK'
  machineTierId?: number | null
  machineTierIds?: number[]
  applicationTags?: string
  /** 用途标签批量动作；提供时按 APPEND/REPLACE/REMOVE 处理 applicationTags */
  applicationTagsAction?: 'APPEND' | 'REPLACE' | 'REMOVE'
  videoContentType?: string
  videoUsageStatus?: string
  featureTags?: string
  /** 特征标签批量动作；提供时按 APPEND/REPLACE/REMOVE 处理 featureTags */
  featureTagsAction?: 'APPEND' | 'REPLACE' | 'REMOVE'
  demonstrationTags?: string
  videoUsageChannels?: string
  /** 渠道批量动作；提供时按 APPEND/REPLACE/REMOVE 处理 videoUsageChannels（不影响其它字段） */
  videoUsageChannelsAction?: 'APPEND' | 'REPLACE' | 'REMOVE'
  videoContentStatus?: string
  /** 内容状态批量动作；提供时将 videoContentStatus 设置为传入值 */
  videoContentStatusAction?: 'REPLACE'
  replaceVideoMetadata?: boolean
  /** 适用范围确认：true=产品通用（配合 machineTierAction:'UNLINK' 清空机型） */
  machineScopeConfirmed?: boolean
  /** 收藏标记（仅视频类生效） */
  videoFavorite?: boolean
}

export function updateCatalogAssetMetadataBatch(data: CatalogAssetBatchMetadataPayload) {
  return http.put('/catalog/assets/batch-metadata', data).then(res => res.data)
}

export function setPersonalVideoFavorite(assetId: number, favorite: boolean) {
  return http.put(`/catalog/assets/videos/${assetId}/favorite`, { favorite }).then(res => res.data)
}

export function recordVideoView(assetId: number) {
  return http.post(`/catalog/assets/videos/${assetId}/view`).then(res => res.data)
}

export interface VideoCollection {
  id: number
  name: string
  description?: string
  itemCount: number
  updatedAt?: string
}

export function fetchVideoCollections() {
  return http.get('/catalog/assets/videos/collections').then(res => res.data) as Promise<VideoCollection[]>
}

export function createVideoCollection(name: string, description = '') {
  return http.post('/catalog/assets/videos/collections', { name, description }).then(res => res.data) as Promise<VideoCollection>
}

export function addVideoToCollection(collectionId: number, assetId: number) {
  return http.post(`/catalog/assets/videos/collections/${collectionId}/items/${assetId}`).then(res => res.data)
}

export interface VideoHashBackfillResult {
  total: number
  computed: number
  alreadyHashed: number
  missingFiles: number
}

/** 为缺失摘要的视频计算 SHA-256（幂等，只处理 file_hash 为空的记录）。 */
export function backfillVideoHashes() {
  return http.post('/catalog/assets/videos/hash-backfill').then(res => res.data) as Promise<VideoHashBackfillResult>
}

export interface VideoDuplicateGroup {
  id: number
  modelId: number
  modelName: string
  category: string
  fileName: string
  videoTitle: string | null
  createdAt: string
}

export interface VideoDuplicatesResult {
  exactGroups: VideoDuplicateGroup[][]
  suspectedGroups: VideoDuplicateGroup[][]
}

/** 重复检测：exactGroups 同哈希精确重复，suspectedGroups 清洗后标题一致。仅提示不删除。 */
export function fetchVideoDuplicates() {
  return http.get('/catalog/assets/videos/duplicates').then(res => res.data) as Promise<VideoDuplicatesResult>
}

export interface VideoMediaInfoBackfillResult {
  total: number
  extracted: number
  failed: number
}

/** 为缺失时长/分辨率信息的视频执行 ffprobe 提取（幂等）。 */
export function backfillVideoMediaInfo(limit = 500) {
  return http
    .post(`/catalog/assets/videos/media-info-backfill?limit=${limit}`)
    .then(res => res.data) as Promise<VideoMediaInfoBackfillResult>
}

export interface VideoAutoCoverResult {
  generated: boolean
  reason?: string
  coverFileName?: string
  durationSeconds?: number
  width?: number
  height?: number
}

/** 为单个视频生成自动封面；force=true 覆盖已有自定义封面。 */
export function autoCoverVideo(assetId: number, force = false) {
  return http.post(`/catalog/assets/videos/${assetId}/auto-cover?force=${force}`).then(res => res.data) as Promise<VideoAutoCoverResult>
}

export interface VideoAutoCoverBackfillResult {
  total: number
  generated: number
  skippedHasCover: number
  failed: number
}

/** 批量为无封面的视频生成自动封面。 */
export function backfillVideoAutoCovers(limit = 200) {
  return http
    .post(`/catalog/assets/videos/auto-covers?limit=${limit}`)
    .then(res => res.data) as Promise<VideoAutoCoverBackfillResult>
}

export interface VideoAssetSearchParams {
  keyword?: string
  modelId?: number
  /** 产品分类（product_models.category） */
  productCategory?: string
  category?: string
  /** 用途筛选词；支持多个（父级词展开为父子一起 OR 匹配），axios 会序列化为重复的 application= 参数 */
  application?: string | string[]
  contentType?: string
  usageStatus?: string
  usageChannel?: string
  contentStatus?: string
  featureTag?: string
  demonstrationTag?: string
  machineTierId?: number
  hasCover?: boolean
  incomplete?: boolean
  /** 适用范围：ALL 产品通用 / SELECTED 指定机型 / UNREVIEWED 尚未确认 */
  scope?: '' | 'ALL' | 'SELECTED' | 'UNREVIEWED'
  favorite?: boolean
  missingItem?: string
  page?: number
  size?: number
}

/**
 * 视频检索。手动拼接查询串：axios 默认会把数组参数序列化为 application[]=x，
 * Spring 无法绑定带方括号的重复键，这里统一展开为 application=x&application=y。
 */
export function fetchCatalogVideos(params: VideoAssetSearchParams = {}) {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return
    if (Array.isArray(value)) value.forEach(item => search.append(key, String(item)))
    else search.append(key, String(value))
  })
  return http.get('/catalog/assets/videos', { params: search }).then(res => res.data)
}

export function fetchCatalogVideoFilterOptions() {
  return http.get('/catalog/assets/videos/filter-options').then(res => res.data)
}

export interface VideoGovernanceSummary {
  total: number
  complete: number
  incomplete: number
  statuses: Record<string, number>
  missingItems: Record<string, number>
}

export function fetchVideoGovernanceSummary() {
  return http.get('/catalog/assets/videos/governance-summary').then(res => res.data) as Promise<VideoGovernanceSummary>
}

/**
 * 素材官网可见性批量更新。专用端点，仅接受 websiteVisible / websiteSortOrder。
 * websiteVisible 传字符串 'true'/'false'，websiteSortOrder 传整数字符串。
 */
export function batchUpdateAssetWebsiteFields(ids: number[], fields: Record<string, string>) {
  return http.put('/catalog/assets/website-batch', { ids, fields }).then(res => res.data)
}
