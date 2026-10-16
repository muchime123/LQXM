import { describe, expect, it } from 'vitest'
import {
  LANDING_CANDIDATES,
  NO_PERMISSION_LANDING_PATH,
  PRODUCTION_TRACKING_PATH,
  PRODUCTION_TRACKING_PERMISSIONS,
  resolveDefaultLanding
} from './defaultLanding'
import { archiveRoutes } from '@/apps/archive/routes'
import { archiveNavItems } from '@/apps/archive/navigation'
import type { NavItem } from '@/apps/types'

/**
 * 默认落地页是「登录后第一眼看到什么」的口径，改错了不会报错、只会静默跳到别处，
 * 所以把三处必须一致的东西钉死：路由 meta 权限 / 侧栏权限 / 工具类回落链。
 *
 * 2026-10-12 起客户档案挂 customer.read 门槛，回落由「无脑落客户档案」改为
 * 按优先级取第一个有权查看的候选页（LANDING_CANDIDATES）。
 */
describe('默认落地页：按权限回落链解析', () => {
  it('有生产跟单任一读权限 → 落地生产跟单（最高优先级）', () => {
    for (const p of PRODUCTION_TRACKING_PERMISSIONS) {
      expect(resolveDefaultLanding([p]), p).toBe(PRODUCTION_TRACKING_PATH)
    }
    // 多权限混杂时也要命中
    expect(resolveDefaultLanding(['integration.h3', 'manufacturing.read.all'])).toBe(PRODUCTION_TRACKING_PATH)
  })

  it('无生产跟单权限但有 customer.read → 回落客户档案', () => {
    expect(resolveDefaultLanding(['customer.read'])).toBe('/customers')
    expect(resolveDefaultLanding(['integration.h3', 'customer.read'])).toBe('/customers')
  })

  it('只有月度提成读权限 → 回落月度提成核算', () => {
    expect(resolveDefaultLanding(['monthly_commission.read.self'])).toBe('/monthly-commission')
  })

  it('只有报销读权限 → 回落日常报销', () => {
    expect(resolveDefaultLanding(['expense.read.self'])).toBe('/system/reimbursement')
  })

  it('无任何候选权限 / 未登录（权限未加载）→ 落 403 兜底页', () => {
    expect(resolveDefaultLanding([])).toBe(NO_PERMISSION_LANDING_PATH)
    expect(resolveDefaultLanding(undefined)).toBe(NO_PERMISSION_LANDING_PATH)
    expect(resolveDefaultLanding(null)).toBe(NO_PERMISSION_LANDING_PATH)
    expect(resolveDefaultLanding(['integration.h3', 'product_inventory.read'])).toBe(NO_PERMISSION_LANDING_PATH)
  })

  it('回落链每个候选的权限列表必须与路由 meta.permission 一致（否则落地页与直连行为漂移）', () => {
    expect(LANDING_CANDIDATES.length).toBeGreaterThan(0)
    for (const candidate of LANDING_CANDIDATES) {
      const route = archiveRoutes.find(r => r.path === candidate.path)
      expect(route, `${candidate.path} 路由必须存在`).toBeTruthy()
      const meta = route?.meta?.permission
      const metaList = Array.isArray(meta) ? meta : meta ? [meta] : []
      expect([...metaList], `${candidate.path} 路由权限`).toEqual([...candidate.permissions])
    }
  })

  it('回落链每个候选在侧栏必须有同权限的入口（否则侧栏藏了但落地页还在跳）', () => {
    const flat: NavItem[] = []
    const walk = (items: NavItem[]) => {
      for (const it of items) {
        flat.push(it)
        if (it.children?.length) walk(it.children)
      }
    }
    walk(archiveNavItems)
    for (const candidate of LANDING_CANDIDATES) {
      const nav = flat.find(i => i.to === candidate.path)
      expect(nav, `侧栏必须有 ${candidate.path} 入口`).toBeTruthy()
      const navList = Array.isArray(nav?.permission) ? nav?.permission : nav?.permission ? [nav.permission] : []
      expect([...navList], `${candidate.path} 侧栏权限`).toEqual([...candidate.permissions])
    }
  })

  it('根路径 `/` 不得有静态 redirect（静态 redirect 拿不到权限，会绕过权限感知落地）', () => {
    const home = archiveRoutes.find(r => r.path === '/')
    expect(
      home,
      '`/` 必须交给 router/index.ts 守卫解析 —— 若这里出现 `{ path: "/", redirect: ... }`，' +
        '无生产跟单权限的人会被直接送进 403'
    ).toBeUndefined()
  })
})
