import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import OperationLogView from '@/apps/archive/views/system/OperationLogView.vue'

/**
 * B6-1 试点页面视觉验收入口。
 *
 * 只做最小装配：真实业务页面 + Element Plus + 项目样式。
 * 刻意不引入 router / store / 登录态，避免验收被引流到应用外壳。
 */
const app = createApp(OperationLogView)

app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn }).mount('#e2e-app')
