import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import WebsiteInquiryView from '@/apps/archive/views/marketing/WebsiteInquiryView.vue'

/**
 * B7-B1b 询盘管理页（WebsiteInquiryView）搜索 / 筛选 / 分页联动验收入口。
 *
 * 最小装配：真实业务页面 + Element Plus + 项目样式。
 * 不引入 router / store / 真实登录态（本页无权限分支）。
 */
const app = createApp(WebsiteInquiryView)

app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn })
app.mount('#e2e-app')
