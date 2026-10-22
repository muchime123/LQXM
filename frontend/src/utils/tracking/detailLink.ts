/**
 * 「生产跟单详情」的深链约定（唯一来源）。
 *
 * 现状（2026-10-22 起）：生产产品与工期管理页的下钻**不再跳转**——它直接在本页打开
 * `TrackingDetailDrawer`（与生产跟单页共用同一个抽屉）。这里的深链因此只剩：
 *   · 生产跟单页自己识别 `?order=<id>`（兼容旧链接 / 外部直接贴地址）；
 *   · 将来若有「确实要跳过去看」的场景，仍可用 `trackingDetailLocation()`。
 *
 * 两边必须用同一个 query 键：这里改了、页面不认，就会变成「点了没反应」——
 * 历史上这类静默失效（参数名不一致）排查成本很高，所以集中在一个文件里。
 */

/** 生产跟单页打开某张工单详情的 query 键：/production-tracking?order=<production_orders.id>。 */
export const TRACKING_ORDER_QUERY = 'order'

/** 生产跟单页的读权限（任一即可）——与 routes.ts 的 meta.permission 同一组码。 */
export const TRACKING_READ_PERMISSIONS = [
  'manufacturing.read.self',
  'manufacturing.read.department',
  'manufacturing.read.all'
]

/** 深链到某张工单的跟单详情（抽屉）。id 是 production_orders.id，不是任务单号。 */
export function trackingDetailLocation(orderId: number) {
  return { path: '/production-tracking', query: { [TRACKING_ORDER_QUERY]: String(orderId) } }
}
