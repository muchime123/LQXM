/**
 * 品牌资产分类常量
 *
 * 每个分类定义：key, 中文名, 是否图片类型, 文件 accept
 */
export const BRAND_ASSET_CATEGORIES = [
  { key: 'brand_logo', label: '品牌标识', isImage: true, accept: '*/*', icon: '\u{1F5BC}' },
  { key: 'seal_nameplate', label: '印章与名称', isImage: true, accept: '*/*', icon: '\u{1F4DC}' },
  { key: 'factory_scene', label: '工厂实景', isImage: true, accept: '*/*', icon: '\u{1F3ED}' },
  { key: 'team_photo', label: '团队照片', isImage: true, accept: '*/*', icon: '\u{1F465}' },
  { key: 'office_env', label: '办公环境', isImage: true, accept: '*/*', icon: '\u{1F3E2}' },
  { key: 'product_showcase', label: '产品展示', isImage: true, accept: '*/*', icon: '\u{1F6E0}\uFE0F' },
  { key: 'website_media', label: '官网图片', isImage: true, accept: 'image/*', icon: '\u{1F310}' },
  {
    key: 'info_graphic',
    label: '信息图表',
    isImage: false,
    accept: '*/*',
    icon: '\u{1F4CA}'
  },
  {
    key: 'certification',
    label: '资质证书',
    isImage: false,
    accept: '*/*',
    icon: '\u{1F4DD}'
  },
  {
    key: 'promotion',
    label: '宣传资料',
    isImage: false,
    accept: '*/*',
    icon: '\u{1F4F0}'
  }
] as const

export type BrandAssetCategory = (typeof BRAND_ASSET_CATEGORIES)[number]['key']

/**
 * 获取分类信息 by key
 */
export function getBrandCategoryInfo(key: string) {
  return BRAND_ASSET_CATEGORIES.find(c => c.key === key)
}

/** 常用标签建议 */
export const COMMON_BRAND_TAGS = [
  // Logo 相关
  '横版',
  '竖版',
  '方形',
  '圆形',
  '彩色',
  '单色',
  // 场景相关
  '车间',
  '生产线',
  '展厅',
  '前台',
  '会议室',
  // 年份/版本
  '2024',
  '2025',
  '2026',
  '新版',
  '旧版',
  // 用途
  '官网用',
  '电商用',
  '画册用',
  '展会用'
]
