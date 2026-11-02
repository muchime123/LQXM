import { createApp, h } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import MediaAccountView from '@/apps/archive/views/marketing/MediaAccountView.vue'
import LqConfirmDialog from '@/components/common/LqConfirmDialog.vue'

/**
 * B7-B2a：自媒体账号页（MediaAccountView）权限入口与批量选择验收入口。
 *
 * 最小装配：真实业务页面 + Element Plus + 项目样式 + 确认弹窗宿主。
 *
 * 为什么必须额外挂 `LqConfirmDialog`：页面的删除 / 批量删除走
 * `askConfirm`（`composables/ui/useConfirmDialog.ts`），它只改全局 reactive 状态，
 * 由 `App.vue` 里挂载的 `LqConfirmDialog` 负责渲染。夹具不挂它，
 * 弹窗永远不会出现，用例会卡在等待上。
 *
 * 不引入 router（该页不使用 `useRoute`），权限通过「本地 token + /api/auth/me 拦截」注入。
 */
const Wrapper = {
  setup() {
    return () => h('div', [h(MediaAccountView), h(LqConfirmDialog)])
  }
}

const app = createApp(Wrapper)

app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn })
app.mount('#e2e-app')

localStorage.setItem('lq.token', 'e2e-mock-token')
void useCurrentUser().load()
