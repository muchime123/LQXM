import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { vReveal } from './composables/vReveal'
import { applyRouteSeo, SITE_URL } from './composables/useSeo'
import { setSchema, clearSchema, createBreadcrumbSchema } from './composables/useSchema'

import './style.css'
import { FALLBACK_IMAGE_URL } from './utils/fallbackImage'

/* 全局图片保底：任何 <img> 加载失败（含 srcset 全部失效）时，
   捕获阶段统一替换为品牌占位图，避免出现破图图标。
   同一元素只兜底一次，防止循环。 */
window.addEventListener(
  'error',
  (event) => {
    const target = event.target
    if (!target || target.tagName !== 'IMG' || target.dataset.imgFallbackApplied) return
    target.dataset.imgFallbackApplied = '1'
    target.removeAttribute('srcset')
    target.removeAttribute('sizes')
    target.src = FALLBACK_IMAGE_URL
  },
  true,
)

const ROUTE_BREADCRUMB = {
  home: { name: '首页' },
  products: { name: '产品中心' },
  'product-detail': { name: '产品详情', dynamic: true },
  solutions: { name: '解决方案' },
  credentials: { name: '企业资质' },
  patents: { name: '专利与知识产权' },
  about: { name: '关于我们' },
  contact: { name: '联系我们' },
  packaging: { name: '包装耗材' },
  molds: { name: '模具中心' },
  'not-found': { name: '页面未找到' },
}

const app = createApp(App)
app.use(router)
app.directive('reveal', vReveal)

/* 路由切换：清除页面专属 schema（Product/FAQPage 等），保留 Organization/LocalBusiness/BreadcrumbList
   由各页面自行重新注入；统一注入 BreadcrumbList。 */
router.beforeEach(() => {
  clearSchema(['Product', 'FAQPage', 'BreadcrumbList'])
})

router.afterEach((to) => {
  applyRouteSeo(to)
  /* 构造面包屑：首页 → 一级路由 → (动态详情页) */
  const crumbs = [{ name: '首页', url: `${SITE_URL}/` }]
  const leaf = ROUTE_BREADCRUMB[to.name]
  if (leaf && to.name !== 'home') {
    if (to.name === 'product-detail') {
      crumbs.push({ name: '产品中心', url: `${SITE_URL}/products` })
      crumbs.push({ name: to.params?.slug ? decodeURIComponent(to.params.slug) : '产品详情' })
    } else {
      crumbs.push({ name: leaf.name, url: `${SITE_URL}${to.path.split('#')[0]}` })
    }
  }
  const bc = createBreadcrumbSchema(crumbs)
  if (bc) setSchema(bc)
})

app.mount('#app')
