import { createApp } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import ProductCatalogView from '@/apps/archive/views/product/ProductCatalogView.vue'

/**
 * B7-B2b-1：产品中心页（ProductCatalogView）权限入口门控验收入口。
 *
 * 最小装配：真实业务页面 + router + Element Plus + 项目样式。
 *
 * 为什么必须给 router：该页 `script setup` 顶层调用 `useRoute()`（:324），
 * 不提供 router 会在挂载阶段直接抛错。挂载推迟到 `router.isReady()` 之后，
 * 避免首帧读到 `START_LOCATION` 产生脏状态。
 *
 * 不引入真实登录态：权限通过「本地 token + /api/auth/me 拦截」由用例注入。
 * 所有 /api/ 请求亦由用例拦截，因此不存在任何写入副作用。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [{ path: '/', component: ProductCatalogView }]
})

const app = createApp(ProductCatalogView)

app.use(router)
app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn })

void router.isReady().then(() => app.mount('#e2e-app'))
