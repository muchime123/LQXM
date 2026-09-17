/**
 * 主产品 (ProductModel) 字段常量
 */

export interface ModelField {
  key: string
  label: string
  required?: boolean
  readonly?: boolean
  multiline?: boolean
  unit?: string
}

export interface ModelFieldGroupItem {
  key: string
  label: string
  required?: boolean
  multiline?: boolean
  /** 控件类型 */
  fieldType?: 'text' | 'select' | 'buttonGroup' | 'toggle'
  /** buttonGroup / select 的选项列表 */
  options?: string[]
  /** 网格跨列数 */
  span?: number
  /** 字段单位，如 kg, mm, kW 等 */
  unit?: string
}

export interface ModelFieldGroup {
  title: string
  hint?: string
  fields: ModelFieldGroupItem[]
}

export interface TableColumn {
  key: string
  label: string
  required?: boolean
  readonly?: boolean
  sticky?: boolean
  multiline?: boolean
  width?: number
}

export const MODEL_FIELDS: ModelField[] = [
  { key: 'name', label: '主产品名称', required: true, readonly: true },
  { key: 'modelCode', label: '产品型号', readonly: false },
  // ====== V2 字段 ======
  { key: 'category', label: '产品分类', readonly: false },
  { key: 'descriptionCn', label: '中文描述', readonly: false, multiline: true },
  { key: 'descriptionEn', label: '英文描述', readonly: false, multiline: true },
  { key: 'status', label: '上架状态', readonly: false },
  { key: 'applicableScope', label: '适用范围', readonly: false, multiline: true },
  { key: 'remark', label: '备注', readonly: false, multiline: true },
  { key: 'defaultImage', label: '默认主图', readonly: true },
  { key: 'websiteEnabled', label: '官网发布', readonly: false },
  { key: 'websiteFeatured', label: '首页精选', readonly: false },
  { key: 'websiteSlug', label: '官网URL标识', readonly: false },
  { key: 'websiteSummary', label: '官网摘要', readonly: false, multiline: true },
  { key: 'applicationTags', label: '应用场景标签', readonly: false },
  { key: 'containerTags', label: '容器类型标签', readonly: false },
  { key: 'packagingProcessTags', label: '包装工艺标签', readonly: false },
  { key: 'capacityLevel', label: '产能分级', readonly: false },
  { key: 'nameEn', label: '主产品名称(英文)', readonly: false },
  { key: 'applicableScopeEn', label: '适用范围(英文)', readonly: false, multiline: true },
  { key: 'introductionCn', label: '产品介绍(中文)', readonly: false, multiline: true },
  { key: 'introductionEn', label: '产品介绍(英文)', readonly: false, multiline: true },
  { key: 'searchKeywordsCn', label: 'SEO关键字(中文)', readonly: false },
  { key: 'searchKeywordsEn', label: 'SEO关键字(英文)', readonly: false },
  { key: 'sortOrder', label: '排序', readonly: false }
]

export const DEFAULT_MODEL_VISIBLE_KEYS: string[] = [
  'name',
  'modelCode',
  'category',
  'status',
  'defaultImage'
]

export const MODEL_FIELD_GROUPS: ModelFieldGroup[] = [
  {
    title: '识别与分类',
    hint: '主产品核心身份字段，用于列表筛选和分类整理。英文(*En)字段用于官网国际站多语言展示。',
    fields: [
      { key: 'modelCode', label: '产品型号' },
      { key: 'name', label: '产品名称', required: true, span: 2 },
      { key: 'nameEn', label: '产品名称(英文)', span: 2 },
      // ====== V2 字段 ======
      { key: 'category', label: '产品分类' },
      { key: 'status', label: '上架状态', fieldType: 'select', options: ['ACTIVE', 'DISCONTINUED', 'PREVIEW'] },
      { key: 'sortOrder', label: '排序值(越大越靠前)' }
    ]
  },
  {
    title: '销售与描述',
    hint: '用于说明书、店铺详情和后续营销资料；英文(*En)字段用于官网国际站多语言。',
    fields: [
      { key: 'applicableScope', label: '适用范围', multiline: true, span: 2 },
      { key: 'applicableScopeEn', label: '适用范围(英文)', multiline: true, span: 2 },
      // ====== V2 字段 ======
      { key: 'descriptionCn', label: '中文描述', multiline: true, span: 2 },
      { key: 'descriptionEn', label: '英文描述', multiline: true, span: 2 },
      { key: 'introductionCn', label: '产品介绍(中文)', multiline: true, span: 2 },
      { key: 'introductionEn', label: '产品介绍(英文)', multiline: true, span: 2 },
      { key: 'searchKeywordsCn', label: 'SEO关键字(中文)', span: 2 },
      { key: 'searchKeywordsEn', label: 'SEO关键字(英文)', span: 2 },
      { key: 'remark', label: '备注', multiline: true, span: 2 }
    ]
  },
  {
    title: '官网发布',
    hint: '控制该产品在官网产品中心的展示；4 个分类标签用于官网产品中心的按维度筛选。',
    fields: [
      { key: 'websiteEnabled', label: '是否上架官网', fieldType: 'toggle' },
      { key: 'websiteFeatured', label: '首页核心产品', fieldType: 'toggle' },
      { key: 'websiteSlug', label: '官网URL标识', span: 2 },
      { key: 'websiteSummary', label: '官网摘要', multiline: true, span: 3 },
      { key: 'applicationTags', label: '应用场景标签(逗号分隔)', span: 2 },
      { key: 'containerTags', label: '容器类型标签(逗号分隔)', span: 2 },
      { key: 'packagingProcessTags', label: '包装工艺标签(逗号分隔)', span: 2 },
      { key: 'capacityLevel', label: '产能分级(如 小型/中型/大型)' }
    ]
  }
]

export const MODEL_TABLE_COLUMNS: TableColumn[] = [
  { key: 'name', label: '主产品', required: true, readonly: true, sticky: true, width: 320 },
  { key: 'modelCode', label: '产品型号' },
  { key: 'category', label: '产品分类' },
  { key: 'status', label: '上架状态' },
  { key: 'defaultImage', label: '默认图片', width: 240 },
  { key: 'remark', label: '备注', multiline: true, width: 260 },
]

export const DEFAULT_MODEL_TABLE_VISIBLE_KEYS: string[] = [
  'name',
  'modelCode',
  'category',
  'status',
  'defaultImage'
]
