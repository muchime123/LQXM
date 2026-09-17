/**
 * 产品目录类型定义
 */

// ========== 基础类型 ==========

export interface CatalogModel {
  id: number
  name: string
  nameEn?: string
  modelCode: string
  // ====== V2 字段 ======
  /** V2 产品分类（合并 productLineName / equipmentCategory / itemType） */
  category?: string
  /** V2 中文描述（合并 equipmentAdvantages + coreConfigDescription） */
  descriptionCn?: string
  /** V2 英文描述（合并 equipmentAdvantagesEn + coreConfigDescriptionEn） */
  descriptionEn?: string
  /** V2 上架状态（替代 saleStatus，枚举 ACTIVE / DISCONTINUED / PREVIEW） */
  status?: string
  /** 店铺链接（淘宝/天猫/京东/1688/Shopify 等电商详情页 URL）；V121 重新加回 */
  shopUrl?: string
  defaultImage: string
  defaultImageAssetId?: number
  defaultImageOwnerType?: 'product_model' | 'product_variant'
  sortOrder?: number
  remark: string
  applicableScope?: string
  applicableScopeEn?: string
  introductionCn?: string
  introductionEn?: string
  searchKeywordsCn?: string
  searchKeywordsEn?: string
  variantCount: number
  assetCount: number
  skuImageCount: number
  websiteEnabled: boolean
  websiteFeatured: boolean
  websiteSlug: string
  websiteSummary: string
  applicationTags: string[]
  containerTags: string[]
  packagingProcessTags: string[]
  capacityLevel: string
  /** 官网内容完整度评分（0-100），由后端 ModelSummary 计算 */
  completenessScore: number
  createdAt?: string
  updatedAt?: string
}

export interface CatalogVariant {
  id: number
  modelId: number
  variantName: string
  remark: string
  /** V2 规格标识键 */
  specKey?: string | null
  /** V2 排序序号：variant 在 machine_tier 内的排序 */
  sortOrder?: number
  moldSizeType?: 'RECT' | 'ROUND' | null
  moldLengthMm?: string | null
  moldWidthMm?: string | null
  moldDiameterMm?: string | null
  moldSizeLabel?: string | null
  baseline?: boolean
  /** 关联机型档位 ID（330/390/500 等），未关联为 null */
  machineTierId?: number | null
  machineTierCode?: string | null
  machineTierLabel?: string | null
    [key: string]: unknown
}

export interface CatalogAsset {
  id: number
  modelId: number
  variantId: number | null
  category: string
  fileName: string
  tags?: string
  contentTags?: string
  specificationTags?: string
  effectiveTags?: string
  machineTierIds?: number[]
  machineTierLabels?: string[]
  videoUsageChannels?: string
  videoContentStatus?: string
  [key: string]: unknown
  sourceRowNumber: number
  defaultAsset: boolean
  /** 是否在官网图库可见（V78 新增） */
  websiteVisible?: boolean
  /** 官网图库独立排序（V78 新增） */
  websiteSortOrder?: number
}

export interface CatalogDetail {
  model: CatalogModel
  variants: CatalogVariant[]
  assets: CatalogAsset[]
  variantCount: number
  assetCount: number
}

// ========== 筛选和查询 ==========

export interface CatalogQuery {
  keyword?: string
  category?: string
  status?: string
  sort?: string
  dir?: 'asc' | 'desc'
}

export interface FilterOptions {
  categories: string[]
  statuses: string[]
}

// ========== 分页 ==========

export interface PageRequest {
  page: number
  size: number
  sort?: string
}

// ========== 健康度 ==========

export interface HealthIssue {
  key: string
  issueLabel: string
  action: string
}

export interface HealthCheck {
  key: string
  label: string
  done: boolean
  doneText: string
  todoText: string
  action: string
}

export interface HealthScore {
  score: number
  level: 'good' | 'warning' | 'danger'
}

export interface ModelHealth {
  score: HealthScore
  issues: HealthIssue[]
}

// ========== 编辑器 ==========

export type EditorMode = 'create' | 'edit'

export type ModelEditorForm = Partial<CatalogModel>

export type VariantEditorForm = Partial<CatalogVariant>

export interface BatchEditState {
  checked: Record<string, boolean>
  values: Record<string, string>
}

// ========== 导入导出 ==========

export interface ImportResult {
  models: number
  variants: number
  assets: number
}

// ========== 组件 Props ==========

export interface ModelEditorModalProps {
  visible: boolean
  mode: EditorMode
  modelValue: ModelEditorForm
  dirty: boolean
  imageUploading: boolean
  manualUploading: boolean
  validationErrors: string[]
  existingImages: CatalogAsset[]
  imagePageExpanded: boolean
}

export interface VariantEditorModalProps {
  visible: boolean
  mode: EditorMode
  modelValue: VariantEditorForm
  dirty: boolean
  imageUploading: boolean
}

export interface BatchEditModalProps {
  visible: boolean
  count: number
  fieldGroups: Array<{
    title: string
    fields: Array<{ key: string; label: string; required?: boolean }>
  }>
}

// ========== 事件类型 ==========

export interface CatalogEvents {
  // 工具栏事件
  search: (keyword: string) => void
  'filter-change': (filters: Partial<CatalogQuery>) => void
  'clear-filters': () => void
  'open-todo': () => void
  'open-health': () => void
  'open-dashboard': () => void

  // 列表面板事件
  'toggle-batch-mode': () => void
  'new-model': () => void
  import: (event: Event) => void
  export: () => void
  'download-template': () => void
  'select-model': (id: number) => void
  'toggle-model-select': (id: number) => void
  'preview-model-image': (model: CatalogModel) => void
  'page-change': (page: number) => void
  'sort-change': (sort: string) => void
  'image-error': (modelId: number) => void

  // 详情面板事件
  'generate-manual': (variant?: CatalogVariant) => void
  'edit-model': () => void
  'delete-model': () => void
  'tab-change': (tab: string) => void

  // 通用事件
  'preview-asset': (asset: { category: string; fileName: string }) => void
  imported: (result?: ImportResult) => void
  close: () => void
}
