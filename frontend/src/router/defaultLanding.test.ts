import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 根路径 `/` 落点的**路由级**验证。
 *
 * 这里跑的是真实的 `router/index.ts` 守卫 + 真实路由表 + 真实 `resolveDefaultLanding`，
 * 只把「当前用户」换成一个可控假体（否则要打后端 `/api/auth/me`）。
 * 页面组件是懒加载，导航解析阶段不会真的渲染，所以不做 DOM 断言。
 *
 * 为什么值得单独测：默认落地页改错了不会报错，只会静默跳到别处；
 * 而 `utils/defaultLanding.ts` 的纯函数测试证明不了「守卫真的接上了这条口径」。
 */
const state: { user: { permissions: string[] } | null; token: boolean } = { user: null, token: true }

vi.mock('@/composables/auth/useCurrentUser', () => ({
  useCurrentUser: () => ({
    get user() {
      return { value: state.user }
    },
    hasToken: () => state.token,
    load: async () => state.user,
    can: () => true
  })
}))

const { router } = await import('./index')

/**
 * ⚠️ 每次先落到 `/403`（中性页、无权限门槛，登录态下可直接进）再推 `/`：
 * 连续两次 `push('/')` 若解析出同一个目标，vue-router 会当成重复导航直接 abort，
 * 断言就会随机读到上一次的落点 —— 全量并发跑时必现的假失败。
 * 「回到 `/`」这一跳才是被测对象本身。
 *
 * ⚠️ 这里**不能**用 `await router.isReady()`：没有 `app.use(router)` 就不会触发初始导航，它会一直挂着。
 */
beforeEach(async () => {
  await router.push('/403')
})

describe('默认落地页：根路径 `/` 的落点', () => {
  it('有 manufacturing.read.all → 落到生产跟单', async () => {
    state.user = { permissions: ['manufacturing.read.all'] }
    state.token = true
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/production-tracking')
  })

  it('有 manufacturing.read.self → 落到生产跟单', async () => {
    state.user = { permissions: ['manufacturing.read.self'] }
    state.token = true
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/production-tracking')
  })

  it('无生产跟单权限但有 customer.read → 回落客户档案（不是 403）', async () => {
    state.user = { permissions: ['customer.read'] }
    state.token = true
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/customers')
  })

  it('只有候选链末端的报销读权限 → 回落日常报销', async () => {
    state.user = { permissions: ['expense.read.self'] }
    state.token = true
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/system/reimbursement')
  })

  it('无任何候选权限 → 落 403 兜底页', async () => {
    state.user = { permissions: ['integration.h3'] }
    state.token = true
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/403')
  })

  it('未登录 → 登录页，且不带 redirect（登录后重新按权限解析落地页）', async () => {
    state.user = null
    state.token = false
    await router.push('/')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBeUndefined()
  })
})
