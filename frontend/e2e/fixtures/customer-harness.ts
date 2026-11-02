import { createApp, h } from 'vue'
import { RouterView, createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import CustomerView from '@/apps/archive/views/customer/CustomerView.vue'

/**
 * B7-B1b 客户档案页（CustomerView）搜索 / 筛选 / 分页联动验收入口。
 *
 * 最小装配：真实业务页面 + Element Plus + 项目样式 + 最小 router。
 * router 仅为满足 `useRoute()` 的注入契约，不引入任何守卫或应用外壳。
 */
const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', component: CustomerView }]
})

const app = createApp({ render: () => h(RouterView) })

app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn })
app.use(router)
app.mount('#e2e-app')

// 身份加载完成后权限分支自动重算（can() 读取的 user 是模块级 ref）
localStorage.setItem('lq.token', 'e2e-mock-token')
void useCurrentUser().load()
