import { createRouter, createWebHistory } from 'vue-router'

/**
 * 路由配置。
 * - /about          关于我们
 * - /packaging      包装耗材
 * - /molds          模具中心（独立页）
 */
/**
 * 主导航 key：用于当前一级页高亮。
 * 规则：所有路由按内容归属映射到一个一级导航名；
 * 产品详情高亮 products；资质页（原一级）已降为关于利强下属分区，高亮 about。
 */
export const NAV_KEYS = {
  HOME: 'home',
  PRODUCTS: 'products',
  SOLUTIONS: 'solutions',
  SUPPLIES: 'supplies',
  ABOUT: 'about',
}

const routes = [
  {
    path: '/',
    name: 'home',
    component: () => import('../views/Home.vue'),
    meta: {
      navKey: NAV_KEYS.HOME,
      seo: {
        title: '利强包装｜食品包装设备与一站式包装解决方案',
        description: '浙江利强包装科技有限公司专注食品包装设备研发制造，提供封口机、气调包装机、真空包装机、自动化包装产线及包装耗材模具，覆盖生鲜、熟食、预制菜、烘焙等场景。',
      },
    },
  },
  {
    path: '/products',
    name: 'products',
    component: () => import('../views/Products.vue'),
    meta: {
      navKey: NAV_KEYS.PRODUCTS,
      seo: {
        title: '产品中心｜封口机、气调包装机、自动化包装设备',
        description: '利强包装产品中心：覆盖手动封口机、气动封口机、连续式封口机、气调包装机、全自动包装产线等食品包装设备，支持按设备分类与关键词筛选。',
      },
    },
  },
  {
    path: '/products/:slug',
    name: 'product-detail',
    component: () => import('../views/ProductDetail.vue'),
    // 产品详情页 SEO 在组件内动态设置（基于产品名称和型号）
    meta: {
      navKey: NAV_KEYS.PRODUCTS,
      seo: {
        title: '产品详情',
        description: '利强包装设备产品详情：包含产品图库、技术参数、核心优势、配置规格与适用行业。',
      },
    },
  },
  {
    path: '/solutions',
    name: 'solutions',
    component: () => import('../views/Solutions.vue'),
    meta: {
      navKey: NAV_KEYS.SOLUTIONS,
      seo: {
        title: '包装解决方案｜气调锁鲜、封口包装、自动化产线',
        description: '利强包装解决方案：针对生鲜肉类、熟食、预制菜、烘焙、中央厨房等场景，提供气调锁鲜、封口包装、自动化产线与耗材模具的整体包装方案。',
      },
    },
  },
  {
    path: '/solutions/:slug',
    name: 'solution-article',
    component: () => import('../views/SolutionArticle.vue'),
    meta: {
      navKey: NAV_KEYS.SOLUTIONS,
      seo: {
        title: '包装方案参考｜利强包装',
        description: '围绕包装生产场景、常见问题与设备选型整理的实用方案参考。',
      },
    },
  },
  {
    path: '/credentials',
    name: 'credentials',
    redirect: { path: '/about', hash: '#credentials' },
    /* 旧地址保留兼容，前台内容已经整合进关于利强。 */
    meta: {
      navKey: NAV_KEYS.ABOUT,
      seo: {
        title: '企业资质｜ISO、CE、专利与检测报告',
        description: '浙江利强包装科技企业资质：ISO9001 质量体系认证、CE 认证、实用新型专利、第三方检测报告等，证明设备品质与制造能力。',
      },
    },
  },
  {
    path: '/patents',
    name: 'patents',
    redirect: { path: '/about', hash: '#patents' },
    /* 旧地址保留兼容，前台内容已经整合进关于利强。 */
    meta: {
      navKey: NAV_KEYS.ABOUT,
      seo: {
        title: '专利与知识产权｜发明专利、实用新型、外观设计',
        description: '浙江利强包装科技专利与知识产权档案：覆盖食品包装设备、模具与工艺的发明专利、实用新型专利、外观设计及软件著作权，展示企业研发创新成果。',
      },
    },
  },
  {
    path: '/about',
    name: 'about',
    component: () => import('../views/About.vue'),
    meta: {
      navKey: NAV_KEYS.ABOUT,
      seo: {
        title: '关于浙江利强包装科技有限公司',
        description: '浙江利强包装科技有限公司成立于 2013 年，位于温州，专注包装设备研发制造，业务覆盖封口机、气调包装机、包装容器、封口膜、模具及配件，提供样品试封与非标定制服务。',
      },
    },
  },
  {
    path: '/contact',
    name: 'contact',
    redirect: { path: '/about', hash: '#contact' },
    meta: {
      navKey: NAV_KEYS.ABOUT,
      seo: {
        title: '获取包装方案｜设备选型与样品试封',
        description: '告诉我们您的产品、容器尺寸和目标产能，利强包装的工程师将为您推荐最合适的设备配置，并提供样品试封测试。',
      },
    },
  },
  {
    /* /supplies 中间页已下线，旧链接兼容至 /packaging。
       Header 一级导航“耗材与模具”改为纯下拉触发器（包材耗材 / 模具中心）。 */
    path: '/supplies',
    name: 'supplies',
    redirect: { path: '/packaging' },
  },
  {
    path: '/packaging',
    name: 'packaging',
    component: () => import('../views/Packaging.vue'),
    meta: {
      navKey: NAV_KEYS.SUPPLIES,
      seo: {
        title: '包装耗材｜封口膜 包装容器 一站式配套',
        description: '利强包装提供封口膜、卷膜、托盒、杯罐等包装耗材，与利强设备同步匹配，减少采购方与兼容问题。',
      },
    },
  },
  {
    path: '/molds',
    name: 'molds',
    component: () => import('../views/Molds.vue'),
    meta: {
      navKey: NAV_KEYS.SUPPLIES,
      seo: {
        title: '模具中心｜方盒 圆杯 碗 桶 盖 全品类自研模具',
        description: '利强包装自研模具设计制造能力，覆盖方盒、圆杯、碗、桶、盖等全品类规格，支持定制化模具开发，公差±0.01mm，模具寿命100万+次。',
      },
    },
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('../views/NotFound.vue'),
    meta: {
      seo: {
        title: '页面未找到｜404',
        description: '您访问的页面不存在或已下架，请返回利强包装官网首页继续浏览。',
      },
    },
  },
]

/* 固定 header 高度：与 .site-header (88px) 对齐，滚动时避免锚点被遮挡 */
const HEADER_OFFSET = 100

/**
 * 尝试滚动到锚点：兼容跨页异步渲染组件（Solutions 的 :id 等）。
 * 最多重试若干次，确保 DOM 挂载后再滚。
 */
function scrollToHash(hash) {
  return new Promise((resolve) => {
    let attempts = 0
    const tryScroll = () => {
      const el = document.querySelector(hash)
      if (el) {
        const y = el.getBoundingClientRect().top + window.pageYOffset - HEADER_OFFSET
        window.scrollTo({ top: y, behavior: attempts > 0 ? 'auto' : 'smooth' })
        resolve()
      } else if (attempts < 12) {
        attempts++
        setTimeout(tryScroll, 60)
      } else {
        /* 未找到锚点元素，回滚到顶部 */
        window.scrollTo({ top: 0 })
        resolve()
      }
    }
    /* 等待 Vue 完成 DOM 更新（跨页时组件尚未挂载） */
    setTimeout(tryScroll, 0)
  })
}

const router = createRouter({
  history: createWebHistory(),
  routes,
  async scrollBehavior(to, from, savedPosition) {
    /* 浏览器前进/后退优先恢复原位置 */
    if (savedPosition) return savedPosition

    if (to.hash) {
      await scrollToHash(to.hash)
      /* 手动处理，避免返回默认值造成二次跳动 */
      return { top: window.pageYOffset, left: 0, behavior: 'auto' }
    }

    /* 同一路径切换（如产品筛选）不强制回顶，仅真正路由切换时回顶 */
    if (to.path !== from.path) {
      return { top: 0, behavior: 'auto' }
    }
    return null
  },
})

export default router
