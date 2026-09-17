/**
 * 字段常量 — 统一导出入口
 *
 * 按实体拆分为子模块：
 *   models.ts   — 主产品字段
 *   variants.ts — 配置字段
 *   assets.ts   — 素材分类
 *   platform.ts — 平台常量
 *   utils.ts    — 工具函数
 */

// 类型
export type { ModelField, ModelFieldGroupItem, ModelFieldGroup, TableColumn } from './fields/models'
export type { AssetCategory } from './fields/assets'

// 主产品
export {
  MODEL_FIELDS,
  DEFAULT_MODEL_VISIBLE_KEYS,
  MODEL_FIELD_GROUPS,
  MODEL_TABLE_COLUMNS,
  DEFAULT_MODEL_TABLE_VISIBLE_KEYS
} from './fields/models'

// 素材
export {
  ASSET_CATEGORIES,
  ASSET_CATEGORY_KEYS,
  IMAGE_ASSET_CATEGORIES,
  getAssetCategoryConfig,
  getAssetLabel,
  getAssetIcon,
  getAssetAccept
} from './fields/assets'

// 平台
export { PLATFORMS, PLATFORM_COLORS, platformColor } from './fields/platform'

// 工具
export { formatValue } from './fields/utils'

// 品牌资产
export * from './fields/brand-assets'

// 产品编辑器
export * from './fields/product-editor'
