import { createApp, h, reactive } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import MoldFormDialog from '@/components/mold/MoldFormDialog.vue'

/**
 * B7-B1c：模具新建 / 编辑弹窗（MoldFormDialog）表单生命周期验收入口。
 *
 * 最小装配：真实业务组件 + Element Plus + 项目样式。
 * 不引入 router / store / 真实登录态。
 *
 * 通过 `window.__moldDialog` 暴露可写状态，让用例能精确驱动
 * 「关闭」「切换到另一个模具」这两个会产生竞态的入口。
 */
const state = reactive({
  visible: true,
  moldId: 101 as number | null
})

const Wrapper = {
  setup() {
    ;(window as unknown as { __moldDialog: typeof state }).__moldDialog = state
    return () =>
      h(MoldFormDialog, {
        visible: state.visible,
        moldId: state.moldId,
        'onUpdate:visible': (value: boolean) => (state.visible = value)
      })
  }
}

const app = createApp(Wrapper)
app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn })
app.mount('#e2e-app')
