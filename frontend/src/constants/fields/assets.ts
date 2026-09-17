/**
 * 素材分类 (ProductAsset) 常量
 */

export interface AssetCategory {
  key: string
  label: string
  isImage: boolean
  icon: string
  accept: string
}

/**
 * 素材分类 key 常量
 *
 * 用于避免在组件/composable 中硬编码 'main_image' 等字符串字面量。
 */
export const ASSET_CATEGORY_KEYS = {
  mainImage: 'main_image',
  sceneImage: 'scene_image',
  skuImage: 'sku_image',
  detailImage: 'detail_image',
  parameterImage: 'parameter_image',
  deviceVideo: 'device_video',
  operationVideo: 'operation_video',
  manual: 'manual',
  brochure: 'brochure',
  drawing: 'drawing',
  certificate: 'certificate'
} as const

export const ASSET_CATEGORIES: AssetCategory[] = [
  { key: ASSET_CATEGORY_KEYS.mainImage, label: '主图', isImage: true, icon: 'FILE', accept: 'image/*' },
  { key: ASSET_CATEGORY_KEYS.sceneImage, label: '场景图', isImage: true, icon: 'FILE', accept: 'image/*' },
  { key: ASSET_CATEGORY_KEYS.skuImage, label: '机型/配置图', isImage: true, icon: 'FILE', accept: 'image/*' },
  { key: ASSET_CATEGORY_KEYS.detailImage, label: '细节图', isImage: true, icon: 'FILE', accept: 'image/*' },
  { key: ASSET_CATEGORY_KEYS.parameterImage, label: '参数表', isImage: true, icon: 'FILE', accept: 'image/*' },
  { key: ASSET_CATEGORY_KEYS.deviceVideo, label: '设备视频', isImage: false, icon: 'VIDEO', accept: 'video/*' },
  { key: ASSET_CATEGORY_KEYS.operationVideo, label: '操作视频', isImage: false, icon: 'VIDEO', accept: 'video/*' },
  {
    key: ASSET_CATEGORY_KEYS.manual,
    label: '说明书',
    isImage: false,
    icon: 'DOC',
    accept: '.doc,.docx,.pdf,.md,.txt,.rtf'
  },
  {
    key: ASSET_CATEGORY_KEYS.brochure,
    label: '彩页',
    isImage: false,
    icon: 'PDF',
    accept: '.pdf,.doc,.docx,.jpg,.jpeg,.png,.webp,.txt,.md'
  },
  { key: ASSET_CATEGORY_KEYS.drawing, label: '图纸', isImage: false, icon: 'DWG', accept: '' },
  {
    key: ASSET_CATEGORY_KEYS.certificate,
    label: '证书',
    isImage: false,
    icon: 'CERT',
    accept: '.pdf,.doc,.docx,.jpg,.jpeg,.png,.webp,.txt,.md'
  }
]

export const IMAGE_ASSET_CATEGORIES: string[] = ASSET_CATEGORIES.filter(c => c.isImage).map(c => c.key)

export function getAssetCategoryConfig(key: string): AssetCategory | undefined {
  return ASSET_CATEGORIES.find(c => c.key === key)
}

export function getAssetLabel(key: string): string {
  const config = getAssetCategoryConfig(key)
  return config ? config.label : key
}

export function getAssetIcon(key: string): string {
  const config = getAssetCategoryConfig(key)
  return config ? config.icon : 'FILE'
}

export function getAssetAccept(key: string): string {
  const config = getAssetCategoryConfig(key)
  return config ? config.accept : ''
}
