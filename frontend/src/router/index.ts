import { createRouter, createWebHashHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { isDirty, askConfirm } from '../composables'
import { startProgress, doneProgress, failProgress } from '../composables/ui/useRouteProgress'
import { addTabFromRoute } from '../composables/common/useMultiTabs'
import { useCurrentUser } from '../composables/auth/useCurrentUser'
import { archiveRoutes } from '../apps/archive/routes'
import { resolveDefaultLanding } from '../utils/defaultLanding'

/**
 * 路由聚合层。
 *
 * 业务路由按应用维护在 `apps/archive/routes.ts`，本文件只做两件事：
 * 拼接 + 全局守卫。
 *
 * 2026-10-06 按 LQ 决定下线 ERP 前端页面：`apps/erp/routes.ts` 随整目录删除，
 * 后端与共享 API 层保留。留底于 commit 23a0eda。
 */
const routes: RouteRecordRaw[] = [...archiveRoutes]

routes.push({
  path: '/403',
  name: 'forbidden',
  component: () => import('../shell/ForbiddenView.vue'),
  meta: { title: '无权限', layout: 'default' }
})

routes.push({
  path: '/:pathMatch(.*)*',
  name: 'notFound',
  component: () => import('../shell/NotFoundView.vue'),
  meta: { title: '页面不存在', layout: 'default' }
})

export const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 路由守卫：P7 登录校验 + 编辑未保存时离开确认 + 启动顶部进度条
router.beforeEach(async (to, from) => {
  if (to.path !== from.path) startProgress()
  const auth = useCurrentUser()
  const isLoginPage = to.path === '/login'
  const isAuthPage = isLoginPage

  // 默认落地页：根路径 `/` 不静态 redirect（静态 redirect 是同步的、拿不到权限），
  // 在这里等 `/api/auth/me` 回来再按权限解析 —— 见 utils/defaultLanding.ts。
  if (to.path === '/') {
    await auth.load()
    const hasAnyIdentity = auth.hasToken() || !!localStorage.getItem('lq.currentEmployeeId')
    if (!auth.user.value && !hasAnyIdentity) {
      // 未登录：进登录页且**不带 redirect** —— 登录后再由本处解析默认落地页，
      // 避免把「无生产跟单权限的人」按 redirect 直接送进 403。
      return { path: '/login', replace: true }
    }
    return { path: resolveDefaultLanding(auth.user.value?.permissions), replace: true }
  }

  // P7 登录守卫：已登录访问登录页 → 回到目标页；未登录访问业务页 → 跳登录页
  if (isLoginPage) {
    if (auth.user.value && auth.hasToken()) {
      const redirect = to.query.redirect
      const target =
        typeof redirect === 'string' && redirect.startsWith('/')
          ? redirect
          : resolveDefaultLanding(auth.user.value?.permissions)
      return { path: target }
    }
  } else {
    const hasAnyIdentity = auth.hasToken() || !!localStorage.getItem('lq.currentEmployeeId')
    await auth.load()
    if (!auth.user.value && !hasAnyIdentity) {
      return {
        path: '/login',
        query: to.fullPath && to.fullPath !== '/' ? { redirect: to.fullPath } : undefined
      }
    }
  }

  const required = to.meta?.permission as string | string[] | undefined
  if (required && !isAuthPage) {
    if (!auth.user.value) await auth.load()
    if (!auth.can(required)) return { path: '/403', query: { from: to.fullPath } }
  }
  if (isDirty() && from.name) {
    const ok = await askConfirm({
      title: '未保存的修改',
      message: '当前有未保存的修改，确定要离开吗？离开后修改将丢失。',
      confirmText: '离开',
      cancelText: '留下',
      danger: true
    })
    if (!ok) {
      // 用户取消跳转：结束进度条
      doneProgress()
      return false
    }
  }
})

// 路由后置守卫：同步浏览器标签页标题 + 结束顶部进度条 + 多标签页同步
// P5.2a：系统定位从「设备资料管理系统」升级为「利强内部业务管理系统」
const APP_TITLE = '利强包装科技内部业务系统'
router.afterEach(to => {
  const pageTitle = to.meta?.title as string | undefined
  document.title = pageTitle ? `${pageTitle} - ${APP_TITLE}` : APP_TITLE
  addTabFromRoute(to)
  doneProgress()
})

// 路由错误：进度条变红并淡出
router.onError(() => {
  failProgress()
})
