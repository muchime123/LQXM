export const VIDEO_APPLICATION_SUGGESTIONS = [
  '豆腐', '腐乳', '豆干', '豆花', '酸奶', '奶酪', '炼乳',
  '冰淇淋', '冰杯', '果冻', '酱料', '蜂蜜', '茶叶', '咖啡', '饮料',
  '益生菌/冻干粉', '米面小吃', '休闲食品', '烘焙糕点', '预制菜', '熟食卤味',
  '快餐', '生鲜肉', '海鲜水产', '净菜', '水果', '蔬菜', '果蔬粉',
  '医疗用品', '日化用品'
] as const

/**
 * 用途词库父子层级：检索父级时自动展开包含子级；
 * 素材只保存最准确的叶子标签，避免一条视频同时写入父子标签。
 * 历史数据中的父级标签（乳制品/果蔬净菜）仍可作为筛选词直接命中旧行。
 */
export interface VideoApplicationGroup {
  label: string
  children: readonly string[]
}

export const VIDEO_APPLICATION_GROUPS: readonly VideoApplicationGroup[] = [
  { label: '豆制品', children: ['豆腐', '腐乳', '豆干', '豆花'] },
  { label: '乳制品', children: ['酸奶', '奶酪', '炼乳'] },
  { label: '冷冻饮品', children: ['冰淇淋', '冰杯'] },
  { label: '果蔬净菜', children: ['净菜', '水果', '蔬菜', '果蔬粉'] }
]

/** 展开一个用途筛选词：父级 → 父级 + 全部子级；叶子/独立标签原样返回。 */
export function expandApplicationTerm(term: string): string[] {
  const group = VIDEO_APPLICATION_GROUPS.find(item => item.label === term)
  return group ? [group.label, ...group.children] : [term]
}

/** 返回叶子标签所属的父级分组名；未分组的独立用途返回空字符串。 */
export function applicationGroupLabelOf(tag: string): string {
  const group = VIDEO_APPLICATION_GROUPS.find(item => item.children.includes(tag))
  return group ? group.label : ''
}

export const VIDEO_CONTENT_TYPES = [
  '设备运行', '客户现场', '封口效果', '操作教学', '安装调试', '参数调整',
  '换模调机', '部件更换', '清洁维护', '故障处理', '宣传成片', '原始素材'
] as const

export const VIDEO_USAGE_CHANNELS = ['可发客户', '销售演示', '官网可用', '内部参考'] as const

/**
 * 视频内容状态受控词表：必须与后端 VideoGovernanceRules.ALLOWED_STATUSES 保持一致。
 * 状态机：
 *   待处理 → 待确认 → 可用 → 精选 / 停用
 *   异常为旁路状态（文件缺失、画质差、转码失败等）
 * 旧词映射（由 V150 迁移完成）：待剪辑 → 待处理；画质较差 → 异常；已过时 → 停用。
 */
export const VIDEO_CONTENT_STATUSES = ['待处理', '待确认', '可用', '精选', '停用', '异常'] as const
