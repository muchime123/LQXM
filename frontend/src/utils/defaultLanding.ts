/**
 * 【默认落地页 · 唯一来源】
 *
 * 登录成功后、以及用户直接打开系统根路径 `/` 时，打开的第一个页面。
 *
 * 2026-10-10 按 LQ 决定：默认落地页由「客户档案」(`/customers`) 改为「生产跟单」
 * (`/production-tracking`)。
 *
 * 2026-10-12 按 LQ 决定落地「查看权限」：客户档案挂上 `customer.read` 门槛，
 * 原「无生产跟单权限就无脑回落客户档案」会把无查看权限的人送进 403 ——
 * 改为按优先级逐个匹配的回落链，取第一个用户有权查看的页面；全部无权限落 `/403`。
 *
 * ⚠️ 每个候选页的权限列表必须与 `apps/archive/routes.ts` 的 `meta.permission`
 * 及 `apps/archive/navigation.ts` 侧栏项保持一致 —— `defaultLanding.test.ts` 钉死这条契约。
 */

/** 生产跟单页路径。 */
export const PRODUCTION_TRACKING_PATH = '/production-tracking'

/** 生产跟单可读权限（任一命中即可见，与路由 meta / 侧栏同源）。 */
export const PRODUCTION_TRACKING_PERMISSIONS: readonly string[] = [
  'manufacturing.read.self',
  'manufacturing.read.department',
  'manufacturing.read.all'
]

/** 落地候选（按优先级排列）：生产跟单 → 客户档案 → 月度提成 → 日常报销。 */
export const LANDING_CANDIDATES: ReadonlyArray<{ path: string; permissions: readonly string[] }> = [
  { path: PRODUCTION_TRACKING_PATH, permissions: PRODUCTION_TRACKING_PERMISSIONS },
  { path: '/customers', permissions: ['customer.read'] },
  {
    path: '/monthly-commission',
    permissions: [
      'monthly_commission.read.self',
      'monthly_commission.read.department',
      'monthly_commission.read.all'
    ]
  },
  { path: '/system/reimbursement', permissions: ['expense.read.self', 'expense.manage', 'integration.dingtalk'] }
]

/** 兜底：无任何查看权限时（新建员工未分配角色等），交给 403 页明确告知。 */
export const NO_PERMISSION_LANDING_PATH = '/403'

/**
 * 解析默认落地路径。
 * @param permissions 当前登录用户的权限列表（未登录 / 未加载时传空 → 走兜底）。
 */
export function resolveDefaultLanding(permissions?: readonly string[] | null): string {
  const list = permissions ?? []
  for (const candidate of LANDING_CANDIDATES) {
    if (candidate.permissions.some(p => list.includes(p))) {
      return candidate.path
    }
  }
  return NO_PERMISSION_LANDING_PATH
}
