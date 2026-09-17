/**
 * 中国省市区县级联选择器数据（基于 element-china-area-data，value 即行政区划名称文本）。
 *
 * 设计约定：
 * - 直辖市（京/津/沪/渝）不保留「市辖区」占位层，区县直接挂在省下面（两级）；
 *   选择后「市」字段约定与省同名（如 北京市），与档案历史数据/出库口径保持一致。
 * - 非直辖市为「省 → 市 → 区县」三级文本路径。
 * - 文本带标准后缀（浙江省 / 杭州市 / 西湖区），与 ChinaAddressParser 输出同源，
 *   因此地址解析回填与选择器选中可以互相回显。
 */
import { pcaTextArr } from 'element-china-area-data'

export interface RegionNode {
  value: string
  label: string
  children?: RegionNode[]
}

const MUNICIPALITY_LABELS = new Set(['北京市', '天津市', '上海市', '重庆市'])

/** 直辖市中间层「市辖区」提升，区/县直接挂省下；重庆的「县」分支原样保留。 */
function liftMunicipality(nodes: RegionNode[]): RegionNode[] {
  return nodes.map((prov) => {
    if (!MUNICIPALITY_LABELS.has(prov.label) || !prov.children?.length) return prov
    const lifted: RegionNode[] = []
    for (const ch of prov.children) {
      if (ch.label === '市辖区' && ch.children?.length) lifted.push(...ch.children)
      else lifted.push(ch)
    }
    return { ...prov, children: lifted }
  })
}

/** 级联选择器选项（value = label = 行政区名称文本） */
export const chinaRegionOptions: RegionNode[] = liftMunicipality(pcaTextArr)

/** 是否为直辖市（省名即市名） */
export function isMunicipalityLabel(label: string): boolean {
  return MUNICIPALITY_LABELS.has(label)
}

/** 去掉末尾行政后缀（省/自治区/市/州/地区/盟/区/县等）后比较，容忍「嘉兴」vs「嘉兴市」这类旧写法 */
const REGION_SUFFIXES = [
  '特别行政区',
  '维吾尔自治区',
  '壮族自治区',
  '回族自治区',
  '自治区',
  '自治州',
  '自治县',
  '地区',
  '省',
  '市',
  '县',
  '区',
  '盟'
]

function stripRegionSuffix(name: string): string {
  let cur = name.trim()
  for (;;) {
    const hit = REGION_SUFFIXES.find((s) => cur.endsWith(s) && cur.length > s.length)
    if (!hit) break
    cur = cur.slice(0, -hit.length)
  }
  return cur
}

function matchNode(list: RegionNode[] | undefined, name: string): RegionNode | null {
  if (!list?.length || !name) return null
  const exact = list.find((n) => n.label === name)
  if (exact) return exact
  const key = stripRegionSuffix(name)
  return list.find((n) => stripRegionSuffix(n.label) === key) ?? null
}

/**
 * 由档案存值反查级联路径（用于编辑回显）。
 * - 直辖市：省 + 区县（市字段与省同名，不参与匹配）
 * - 非直辖市：省 + 市 + 区县；哪一层对不上，该层及以下不返回（宁缺毋滥，避免误导选中）
 */
export function resolveRegionPath(province?: string | null, city?: string | null, district?: string | null): string[] {
  if (!province) return []
  const provNode = matchNode(chinaRegionOptions, province)
  if (!provNode) return []
  const isMuni = isMunicipalityLabel(provNode.label)

  if (isMuni) {
    if (district) {
      const dNode = matchNode(provNode.children, district)
      if (dNode) return [provNode.label, dNode.label]
    }
    return [provNode.label]
  }

  if (!city) return [provNode.label]
  const cityNode = matchNode(provNode.children, city)
  if (!cityNode) return [provNode.label]
  if (!district) return [provNode.label, cityNode.label]
  const dNode = matchNode(cityNode.children, district)
  if (!dNode) return [provNode.label, cityNode.label]
  return [provNode.label, cityNode.label, dNode.label]
}
