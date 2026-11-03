/**
 * 产品中心 URL 查询状态工具（纯函数，便于单元测试）。
 *
 * 约定：URL query 是筛选状态的唯一来源。本模块只负责
 *  - 页码规范化与越界收敛：异常 URL 不直接传给公开接口；
 *  - query 构造 / 等价比较：避免把无变化的筛选写进浏览器历史；
 *  - 最新请求令牌：快速切换筛选时，旧响应不得覆盖新结果。
 *
 * 不依赖 Vue / 浏览器 API，可在 Node 测试中直接导入。
 */

/* 防御性上限：超出后按第 0 页处理，避免手工构造超大页码打穿分页 */
export const MAX_PAGE = 1000

const FILTER_KEYS = [
  'keyword', 'equipmentCategory', 'application', 'container', 'process', 'capacity',
]

/**
 * 把 URL 中的 page 解析成合法的非负整数页码。
 * 负数、非数字、小数（parseInt 截断后按整数处理）、数组、超上限一律归 0。
 */
export function normalizePage(value, maxPage = MAX_PAGE) {
  const raw = Array.isArray(value) ? value[0] : value
  const parsed = Number.parseInt(raw, 10)
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > maxPage) return 0
  return parsed
}

/** 数据返回后，把请求页码收敛到实际总页数范围内。 */
export function clampPage(requested, totalPages) {
  const page = normalizePage(requested)
  if (!Number.isInteger(totalPages) || totalPages <= 0) return 0
  return Math.min(page, totalPages - 1)
}

/** 由当前筛选状态构造 URL query；空值与第 0 页不写入地址栏。 */
export function buildProductQuery(state = {}) {
  const query = {}
  for (const key of FILTER_KEYS) {
    const value = state[key]
    if (value === undefined || value === null || value === '') continue
    query[key] = Array.isArray(value) ? String(value[0] ?? '') : String(value)
  }
  const page = normalizePage(state.page)
  if (page > 0) query.page = page
  return query
}

/** 比较两个 query 是否等价（忽略空值、键顺序、数组退化）。 */
export function sameQuery(a = {}, b = {}) {
  const normalize = (obj) => Object.entries(obj)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .map(([key, value]) => [key, Array.isArray(value) ? String(value[0] ?? '') : String(value)])
    .sort(([left], [right]) => left.localeCompare(right))
  const left = normalize(a)
  const right = normalize(b)
  return left.length === right.length
    && left.every(([key, value], index) => key === right[index][0] && value === right[index][1])
}

/**
 * 最新请求令牌。每次发起请求 start()，响应回来后只有 isLatest(token) 为真才允许更新页面。
 */
export function createLatestRequestGuard() {
  let latest = 0
  return {
    start() {
      latest += 1
      return latest
    },
    isLatest(token) {
      return token === latest
    },
  }
}
