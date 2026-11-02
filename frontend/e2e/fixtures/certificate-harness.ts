import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import CertificateView from '@/apps/archive/views/product/CertificateView.vue'

/**
 * B7-B1b 知识产权页（CertificateView）搜索 / 筛选 / 分页联动验收入口。
 *
 * 最小装配：真实业务页面 + Element Plus + 项目样式。
 * 不引入 router / store / 真实登录态；权限通过「本地 token + /api/auth/me 拦截」注入。
 */
const app = createApp(CertificateView)

app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn })
app.mount('#e2e-app')

// 身份加载完成后权限分支自动重算（can() 读取的 user 是模块级 ref）
localStorage.setItem('lq.token', 'e2e-mock-token')
void useCurrentUser().load()
