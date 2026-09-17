import { applicationGroupLabelOf } from '../../constants/videoMetadata'

export type InferredVideoConfidence = 'high' | 'medium' | 'conflict' | 'none'

export interface InferredVideoMetadata {
  /** 高/中置信度的用途，可直接合并保存 */
  applicationTags: string[]
  /** 跨分组多用途命中：疑似拼接错误文件名，只提示不自动应用 */
  conflictedApplications: string[]
  confidence: InferredVideoConfidence
  contentTags: string[]
  videoContentType: string
  featureTags: string[]
  specificationHints: string[]
}

const APPLICATION_RULES: Array<[string, RegExp]> = [
  ['腐乳', /腐乳|豆腐乳/],
  ['豆腐', /豆腐(?!乳)|豆制品|内酯豆腐/],
  ['豆干', /豆干|香干/],
  ['豆花', /豆花|豆脑/],
  ['酸奶', /酸奶|酸乳/],
  ['奶酪', /奶酪|芝士/],
  ['炼乳', /炼乳/],
  ['乳制品', /奶皮子|鲜奶|牛奶/],
  ['冰淇淋', /冰淇淋|冰激凌|冰淇凌/],
  ['冰杯', /冰杯/],
  ['果冻', /果冻/],
  ['酱料', /酱料|蜂蜜酱|牛肉酱|蘸酱/],
  ['蜂蜜', /蜂蜜/],
  ['茶叶', /茶叶|小罐茶|养生茶|茶叶罐/],
  ['咖啡', /咖啡|咖啡胶囊/],
  ['饮料', /饮料|奶茶/],
  ['益生菌/冻干粉', /益生菌|冻干粉|酵素|茶粉|抹茶/],
  ['米面小吃', /泡面|方便面|鲜面|面条|凉皮|麻辣烫/],
  ['休闲食品', /薯片|薯条|爆米花|瓜子|土豆干|红薯干|蚂蚱/],
  ['烘焙糕点', /面包|甜品|糕点|月饼/],
  ['预制菜', /预制菜|料理包/],
  ['熟食卤味', /熟食|卤味|卤制/],
  ['快餐', /快餐|盒饭|便当/],
  ['生鲜肉', /生鲜肉|鲜肉|猪肉|牛肉(?!酱)|羊肉|鸡肉/],
  ['海鲜水产', /海鲜|水产|鱼|虾|蟹/],
  ['净菜', /净菜|锁鲜盒/],
  ['水果', /水果|果切/],
  ['蔬菜', /蔬菜/],
  ['果蔬粉', /果蔬粉/],
  ['医疗用品', /医疗|试剂|生物制剂|隐形眼镜|美瞳|日抛盒/],
  ['日化用品', /湿敷|棉片|化学桶/]
]

const CONTENT_RULES: Array<[string, RegExp]> = [
  ['安装调试', /安装|装机|接线|空压机/],
  ['参数调整', /调整|怎么调|如何调|偏差|速度|托杯高度|上位|后位/],
  ['换模调机', /换模|更换上下模|安装模具/],
  ['部件更换', /更换(?!上下模|模具)|换气缸|换线路板|换温控表|换输送带|换保护开关/],
  ['清洁维护', /清洁|清洗|保养|维护|吸盘脏/],
  ['故障处理', /故障|解决|排查|报错|堵塞|停机保护|怎么办/],
  ['操作教学', /教学|教程|操作|使用方法|演示|穿膜|开箱/],
  ['客户现场', /客户|现场|工厂实拍|车间实拍/],
  ['封口效果', /封口效果|封膜效果|成品效果|封口样品|封口展示/],
  ['设备运行', /运行|运转|试机|测试|生产线|流水线/],
  ['宣传成片', /宣传片|成片|案例片/],
  ['原始素材', /原片|原始|未剪辑|素材/]
]

const DETAIL_RULES: Array<[string, RegExp]> = [
  ['设备正面', /正面|正视/],
  ['设备侧面', /侧面|侧视/],
  ['设备背面', /背面|后面|后视/],
  ['细节特写', /细节|特写|近景/],
  ['上料过程', /上料|进料/],
  ['出料过程', /出料|下料/],
  ['封口过程', /封口过程|封膜过程/]
]

const FEATURE_RULES: Array<[string, RegExp]> = [
  ['手动设备', /手动(?!放膜)/],
  ['手压设备', /手压|手压式/],
  ['手推自动', /手推自动/],
  ['按钮自动', /按钮自动/],
  ['全自动设备', /全自动/],
  ['半自动设备', /半自动/],
  ['气动设备', /气动/],
  ['自动感应', /自动感应/],
  ['片膜封口', /片膜|膜片/],
  ['卷膜封口', /卷膜/],
  ['手动放膜', /手动放膜/],
  ['充气包装', /充气|充氧/],
  ['日期打码', /打码|打印日期/]
]

function matches(source: string, rules: Array<[string, RegExp]>): string[] {
  return rules.filter(([, pattern]) => pattern.test(source)).map(([label]) => label)
}

/** 判定一组用途命中属于哪个父级分组；独立用途各自成组。 */
function confidenceForApplications(allApps: string[]): {
  applicationTags: string[]
  conflictedApplications: string[]
  confidence: InferredVideoConfidence
} {
  if (allApps.length === 0) return { applicationTags: [], conflictedApplications: [], confidence: 'none' }
  if (allApps.length === 1) return { applicationTags: allApps, conflictedApplications: [], confidence: 'high' }
  const groupLabels = new Set(allApps.map(tag => applicationGroupLabelOf(tag) || tag))
  if (groupLabels.size === 1) {
    // 同一分组内的多个子标签（如 豆腐+腐乳）可能是多用途并存，需人工确认
    return { applicationTags: allApps, conflictedApplications: [], confidence: 'medium' }
  }
  // 跨分组命中（如 麻辣烫+冰淇淋）疑似文件名拼接错误
  return { applicationTags: [], conflictedApplications: allApps, confidence: 'conflict' }
}

export function inferVideoMetadataFromFileName(fileName: string, category?: string): InferredVideoMetadata {
  const source = String(fileName || '')
    .replace(/\.[^.]+$/, '')
    .replace(/[\-_]+/g, ' ')

  const appMatch = confidenceForApplications(matches(source, APPLICATION_RULES))
  const contentTypes = matches(source, CONTENT_RULES)
  if (category === 'device_video' && contentTypes.length === 0) contentTypes.push('设备运行')
  const detailTags = matches(source, DETAIL_RULES)
  const featureTags = matches(source, FEATURE_RULES)
  const specificationHints = Array.from(new Set([
    ...(source.match(/一出(?:[一二三四五六七八九十百]+|\d+)/g) || []),
    ...(source.match(/(?:圆|方|异形)模具/g) || []),
    ...(source.match(/(?:Ø|Φ)?\d+(?:\.\d+)?(?:\s*[×xX*]\s*\d+(?:\.\d+)?)?\s*mm/gi) || []),
    ...(source.match(/\b\d{2,4}\s*[×xX*]\s*\d{2,4}\b/g) || [])
  ]))

  return {
    applicationTags: appMatch.applicationTags,
    conflictedApplications: appMatch.conflictedApplications,
    confidence: appMatch.confidence,
    contentTags: detailTags,
    videoContentType: contentTypes[0] || '',
    featureTags,
    specificationHints
  }
}

export function mergeInferredTags(current: string | undefined, inferred: string[]): string {
  const existing = String(current || '').split(/[,，]/).map(item => item.trim()).filter(Boolean)
  return Array.from(new Set([...existing, ...inferred])).join('，')
}

/** 由文件名清洗生成默认视频标题（与后端 cleanVideoTitle 规则一致）：去扩展名、去导入时间戳。 */
export function cleanVideoTitle(fileName?: string | null): string {
  let source = String(fileName || '')
  source = source.replace(/\.[^.]+$/, '')
  source = source.replace(/\d{10,}/g, '')
  source = source.replace(/[-_\s]+$/, '').replace(/^[-_\s]+/, '')
  return source.trim()
}

/** 卡片/搜索结果优先显示的标题：videoTitle 为空时回退清洗后的文件名。 */
export function displayVideoTitle(video: { videoTitle?: string | null; fileName: string }): string {
  const title = String(video.videoTitle || '').trim()
  return title || cleanVideoTitle(video.fileName)
}
