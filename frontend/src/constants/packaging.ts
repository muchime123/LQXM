/** 包材类型 */
export const PACKAGING_KINDS = [
  { key: 'box', label: '盒子' },
  { key: 'film', label: '膜' }
] as const

/** 膜形态，仅封口膜使用 */
export const FILM_FORMS = [
  { key: 'roll', label: '卷膜' },
  { key: 'sheet', label: '片膜' }
] as const

/** 形状 */
export const SHAPE_TYPES = [
  { key: 'round', label: '圆形' },
  { key: 'square', label: '方形' },
  { key: 'rectangle', label: '长方形' },
  { key: 'oval', label: '椭圆形' },
  { key: 'irregular', label: '异形' },
  { key: 'mixed', label: '多形状' }
] as const

/** 包材状态 */
export const PACKAGING_STATUSES = ['在售', '停产', '预售'] as const

/** 包材素材分类 */
export interface PackagingAssetCategory {
  key: string
  label: string
  isImage: boolean
  isVideo: boolean
  accept: string
}

/**
 * 包材素材分类 key 常量
 *
 * 用于避免在组件/composable 中硬编码 'pkg_main_image' 等字符串字面量。
 */
export const PACKAGING_ASSET_CATEGORY_KEYS = {
  mainImage: 'pkg_main_image',
  sceneImage: 'pkg_scene_image',
  specSheet: 'pkg_spec_sheet',
  drawing: 'pkg_drawing',
  printing: 'pkg_printing',
  package: 'pkg_package',
  usageVideo: 'pkg_usage_video'
} as const

export const PACKAGING_ASSET_CATEGORIES: readonly PackagingAssetCategory[] = [
  {
    key: PACKAGING_ASSET_CATEGORY_KEYS.mainImage,
    label: '主图',
    isImage: true,
    isVideo: false,
    accept: 'image/*,.pdf,.doc,.docx,.xls,.xlsx'
  },
  {
    key: PACKAGING_ASSET_CATEGORY_KEYS.sceneImage,
    label: '场景图',
    isImage: true,
    isVideo: false,
    accept: 'image/*,.pdf,.doc,.docx,.xls,.xlsx'
  },
  {
    key: PACKAGING_ASSET_CATEGORY_KEYS.specSheet,
    label: '规格表',
    isImage: false,
    isVideo: false,
    accept: 'image/*,.pdf,.doc,.docx,.xls,.xlsx'
  },
  {
    key: PACKAGING_ASSET_CATEGORY_KEYS.drawing,
    label: '尺寸图',
    isImage: false,
    isVideo: false,
    accept: 'image/*,.pdf,.doc,.docx,.xls,.xlsx'
  },
  {
    key: PACKAGING_ASSET_CATEGORY_KEYS.printing,
    label: '印刷稿',
    isImage: false,
    isVideo: false,
    accept: 'image/*,.pdf,.doc,.docx,.xls,.xlsx'
  },
  {
    key: PACKAGING_ASSET_CATEGORY_KEYS.package,
    label: '包装图',
    isImage: true,
    isVideo: false,
    accept: 'image/*,.pdf,.doc,.docx,.xls,.xlsx'
  },
  { key: PACKAGING_ASSET_CATEGORY_KEYS.usageVideo, label: '使用视频', isImage: false, isVideo: true, accept: 'video/*' }
] as const

/** 获取包材素材分类配置 */
export function getPackagingAssetCategoryConfig(key: string): PackagingAssetCategory | undefined {
  return PACKAGING_ASSET_CATEGORIES.find(item => item.key === key)
}

/** 获取包材素材分类 accept */
export function getPackagingAssetAccept(key: string): string {
  return getPackagingAssetCategoryConfig(key)?.accept || ''
}

/** 阶梯价格档位 */
export const TIER_LABELS = [
  { key: 'tier5099', label: '50-99kg' },
  { key: 'tier100299', label: '100-299kg' },
  { key: 'tier300499', label: '300-499kg' },
  { key: 'tier500999', label: '500-999kg' },
  { key: 'tier10001999', label: '1000-1999kg' },
  { key: 'tier2000Up', label: '2000kg+' }
] as const

export const PRICE_UNITS = [
  { key: 'kg', label: '公斤' },
  { key: 'roll', label: '卷' },
  { key: 'sheet', label: '张' },
  { key: 'piece', label: '个' }
] as const

/** 膜供货类型 */
export const FILM_SUPPLY_TYPES = [
  { key: 'stock', label: '现货' },
  { key: 'custom', label: '定制' }
] as const

/** 容器材质 */
export const BOX_MATERIALS = [
  { key: 'PP', label: 'PP 聚丙烯' },
  { key: 'PET', label: 'PET 聚酯' },
  { key: 'PS', label: 'PS 聚苯乙烯' },
  { key: 'aluminum', label: '铝箔' },
  { key: 'paper', label: '淋膜纸/纸板' },
  { key: 'glass', label: '玻璃' },
  { key: 'bamboo', label: '竹纤维' },
  { key: 'other', label: '其他' }
] as const

/** 膜材质 */
export const FILM_MATERIALS = [
  { key: 'PET/PE', label: 'PET/PE 复合膜' },
  { key: 'PET/CPP', label: 'PET/CPP 复合膜' },
  { key: 'aluminum_film', label: '铝箔复合膜' },
  { key: 'PP_film', label: 'PP 纯膜' },
  { key: 'PE_film', label: 'PE 纯膜' },
  { key: 'universal', label: '万能膜/通用膜' },
  { key: 'anti_fog', label: '防雾膜' },
  { key: 'easy_peel', label: '易揭膜' },
  { key: 'printed_roll', label: '彩印卷膜' },
  { key: 'other', label: '其他' }
] as const

/** 膜易揭性 */
export const PEELABILITY_OPTIONS = [
  { key: 'easy', label: '易揭' },
  { key: 'hard', label: '不易揭' },
  { key: 'semi', label: '半易揭' }
] as const

/** 历史遗留的包材类型 → 现行 box/film 二分类 */
const KIND_LEGACY_MAP: Record<string, string> = {
  container: 'box',
  bowl: 'box',
  sauce_box: 'box',
  large_box: 'box',
  blister: 'box',
  tray: 'box',
  foil_box: 'box',
  sealing_film: 'film'
}

export function normalizeKindKey(key?: string): string {
  return key ? KIND_LEGACY_MAP[key] || key : ''
}

export function kindLabel(key?: string): string {
  const normalized = normalizeKindKey(key)
  return PACKAGING_KINDS.find(item => item.key === normalized)?.label || key || ''
}

export function filmFormLabel(key?: string): string {
  return FILM_FORMS.find(item => item.key === key)?.label || key || ''
}

export function filmSupplyTypeLabel(key?: string): string {
  return FILM_SUPPLY_TYPES.find(item => item.key === key)?.label || key || ''
}

export function priceUnitLabel(key?: string): string {
  return PRICE_UNITS.find(item => item.key === key)?.label || key || ''
}

export function normalizeShapeKey(key?: string): string {
  const legacyMap: Record<string, string> = {
    '\u5706\u5f62': 'round',
    '\u65b9\u5f62': 'square',
    '\u957f\u65b9\u5f62': 'rectangle',
    '\u692d\u5706\u5f62': 'oval',
    '\u5f02\u5f62': 'irregular',
    '\u5706\u5f62/\u65b9\u5f62/\u957f\u65b9\u5f62': 'mixed',
    '\u5706\u5f62/\u65b9\u5f62': 'mixed'
  }
  return key ? legacyMap[key] || key : ''
}

export function shapeLabel(key?: string): string {
  const normalized = normalizeShapeKey(key)
  return SHAPE_TYPES.find(item => item.key === normalized)?.label || key || ''
}

export function assetCategoryLabel(key: string): string {
  return PACKAGING_ASSET_CATEGORIES.find(item => item.key === key)?.label || key
}
