/**
 * 分页页码窗口（纯函数，便于单元测试）。
 *
 * 产品数量增长后不能把所有页码都渲染出来（300 款 = 25 个按钮，手机端无法操作）。
 * 规则：总数 <= 7 全显示；否则固定为「首页 + 省略 + 当前页附近 3 个 + 省略 + 末页」。
 * 页码沿用页面内部的 0 基索引，展示时再 +1。
 */

export const PAGE_GAP = 'gap'

const MAX_FULL_PAGES = 7

export function buildPageWindow(current, totalPages) {
  const total = Number.isInteger(totalPages) && totalPages > 0 ? totalPages : 0
  if (total === 0) return []
  if (total <= MAX_FULL_PAGES) return Array.from({ length: total }, (_, index) => index)

  const last = total - 1
  const active = Math.min(Math.max(Number.isInteger(current) ? current : 0, 0), last)
  const start = Math.max(1, Math.min(active - 1, last - 3))
  const end = Math.min(last - 1, start + 2)

  const pages = [0]
  if (start > 1) pages.push(PAGE_GAP)
  for (let page = start; page <= end; page += 1) pages.push(page)
  if (end < last - 1) pages.push(PAGE_GAP)
  pages.push(last)
  return pages
}
