import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import OfficeAssetView from '@/apps/archive/views/product/OfficeAssetView.vue'

/**
 * B6-2 综合试点页面（OfficeAssetView）视觉与交互验收入口。
 *
 * 最小装配：真实业务页面 + Element Plus + 项目样式。
 * 不引入 router / store / 真实登录态；权限通过「本地 token + /api/auth/me 拦截」注入，
 * 只授予 officeAsset.manage，用来验证该权限控制的新增 / 编辑 / 登记 / 打印 / 批量交互。
 */
const app = createApp(OfficeAssetView)

app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn })
app.mount('#e2e-app')

// 身份加载完成后权限分支自动重算（can() 读取的 user 是模块级 ref）
localStorage.setItem('lq.token', 'e2e-mock-token')
void useCurrentUser().load()
