import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import App from './App.vue'
import { router } from './router/index'
import './styles/index.css'
import 'chart.js/auto'
import { captureError } from './composables/ui/useErrorBoundary'

const app = createApp(App)

app.config.errorHandler = (err, _instance, _info) => {
  captureError(err, 'global')
}

app.config.warnHandler = (msg, _instance, _trace) => {
  console.warn('[Vue warn]', msg)
}

window.addEventListener('unhandledrejection', event => {
  if (event.reason && !(event.reason as any).__handled) {
    captureError(event.reason, 'async')
  }
})

app.use(router).use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn }).mount('#app')
