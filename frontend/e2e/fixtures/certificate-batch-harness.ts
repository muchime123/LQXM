import { createApp, h } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { confirmDialog, resolveConfirm } from '@/composables/ui/useConfirmDialog'
import CertificateView from '@/apps/archive/views/product/CertificateView.vue'
import LqConfirmDialog from '@/components/common/LqConfirmDialog.vue'

/**
 * B7-B2a：知识产权页（CertificateView）权限入口与批量选择验收入口。
 *
 * 最小装配：真实业务页面 + Element Plus + 项目样式 + 确认弹窗宿主。
 *
 * 为什么必须额外挂 `LqConfirmDialog`：页面的删除 / 批量删除走 `askConfirm`，
 * 它只改全局 reactive 状态，由 `App.vue` 里挂载的 `LqConfirmDialog` 负责渲染。
 * 而且该组件靠 props 接收弹窗内容，夹具必须把 `confirmDialog` 的字段逐个绑上去，
 * 否则弹窗永远不出现，用例会卡在等待上。
 *
 * 与 B7-B1b 的 `certificate` 夹具的区别就在这一点。
 *
 * 不引入 router、不引入真实登录态；权限通过「本地 token + /api/auth/me 拦截」注入。
 */
const Wrapper = {
  setup() {
    return () =>
      h('div', [
        h(CertificateView),
        h(LqConfirmDialog, {
          modelValue: confirmDialog.visible,
          title: confirmDialog.title,
          subtitle: confirmDialog.subtitle,
          type: confirmDialog.type,
          confirmText: confirmDialog.confirmText,
          cancelText: confirmDialog.cancelText,
          secondaryText: confirmDialog.secondaryText,
          loading: confirmDialog.loading,
          'onUpdate:modelValue': () => resolveConfirm(false),
          onConfirm: () => resolveConfirm(true),
          onCancel: () => resolveConfirm(false)
        })
      ])
  }
}

const app = createApp(Wrapper)

app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn })
app.mount('#e2e-app')

localStorage.setItem('lq.token', 'e2e-mock-token')
void useCurrentUser().load()
