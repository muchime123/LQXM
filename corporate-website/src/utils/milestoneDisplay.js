const legacyFallback = [
  { year: 2013, title: '品牌创立', description: '利强包装正式注册，专注食品包装设备研发与制造。' },
  { year: 2018, title: '产品升级', description: '推出台式气调保鲜包装系列，切入锁鲜包装赛道。' },
  { year: 2021, title: '产线拓展', description: '部署自动化产线与非标定制能力，覆盖大中型客户。' },
  { year: 2024, title: '服务升级', description: '建立客户服务体系，支持样品试封与长期技术服务。' },
]

/** The old placeholder timeline must never appear when native content is empty. */
export function displayMilestones(rows, companySource) {
  if (rows.length > 0) return [...rows].sort((a, b) => (a.year || 0) - (b.year || 0))
  return companySource === 'native' ? [] : legacyFallback
}
