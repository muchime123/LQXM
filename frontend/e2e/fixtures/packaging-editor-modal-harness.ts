import { createApp, h, reactive } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import PackagingEditorModal from '@/components/packaging/PackagingEditorModal.vue'

/**
 * B7-B1c：包材新建 / 编辑弹窗（PackagingEditorModal）表单生命周期验收入口。
 *
 * 原父级用 `v-if` 每次重建本组件；夹具改为常驻挂载，
 * 以便观察「关闭后旧响应返回」与「A/B 乱序响应」两类竞态。
 */
const state = reactive({
  visible: true,
  modelId: 201 as number | null
})

const Wrapper = {
  setup() {
    ;(window as unknown as { __pkgEditor: typeof state }).__pkgEditor = state
    return () =>
      h(PackagingEditorModal, {
        visible: state.visible,
        modelId: state.modelId,
        onClose: () => (state.visible = false),
        onSaved: () => (state.visible = false)
      })
  }
}

const app = createApp(Wrapper)
app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn })
app.mount('#e2e-app')
