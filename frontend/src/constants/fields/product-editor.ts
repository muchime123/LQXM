import { MODEL_FIELD_GROUPS, type ModelFieldGroupItem } from './models'

/**
 * 产品编辑弹窗（ProductEditorModal）中可被字段设置管理（显示/隐藏）的字段键。
 * 必须与编辑弹窗真实渲染的字段完全一致：
 *   - PRIMARY_FIELD_SECTIONS（主产品身份 + 产品说明）
 *   - parameterRows（常用参数行）
 * 其余模型字段（膜材质、适用行业、定价等）在编辑弹窗中不渲染，不应出现在字段设置中。
 * 官网发布字段由侧边栏 SidebarWebsitePanel 常驻维护，不参与字段设置隐藏。
 */
export const PRODUCT_EDITOR_MANAGED_FIELD_KEYS: string[] = [
  // 主产品身份
  'modelCode',
  'name',
  'nameEn',
  // ====== V2 字段 ======
  'category',
  'status',
  'sortOrder',
  // 产品说明 / 营销 / SEO
  'applicableScope',
  'applicableScopeEn',
  'descriptionCn',
  'descriptionEn',
  'introductionCn',
  'introductionEn',
  'searchKeywordsCn',
  'searchKeywordsEn',
  'remark',
  // 官网发布（SidebarWebsitePanel 已常驻，这里仅允许控制字段设置里是否显示）
  'websiteSummary',
  'capacityLevel'
]

export function findProductModelField(key: string): ModelFieldGroupItem | null {
  for (const group of MODEL_FIELD_GROUPS) {
    const field = group.fields.find(item => item.key === key)
    if (field) return field
  }
  return null
}
