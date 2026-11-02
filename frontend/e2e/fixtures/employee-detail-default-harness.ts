import { createApp, h, reactive } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import EmployeeDetailPanel from '@/components/employee/EmployeeDetailPanel.vue'

/**
 * B7-B2b-1：员工详情面板（EmployeeDetailPanel）权限 prop 默认值验收入口。
 *
 * 关键：这里**刻意不传** `can-manage`，用于暴露 prop 的默认值语义。
 * 修复前默认 `true`（fail-open：漏传即放行写操作）；修复后默认 `false`。
 *
 * 这不是人为构造的场景 —— 生产环境真实形态即是如此：
 * 唯一父级 `EmployeeRosterView`（:55-61）此前完全没有传该 prop。
 *
 * 另注：与 B7-B1c 的 `employee-detail-panel-harness.ts` 的区别是
 * 后者显式传 `canManage: true` 以复现校验态残留路径，两者互不干扰。
 */
const state = reactive({
  employeeId: 1 as number | null
})

const Wrapper = {
  setup() {
    ;(window as unknown as { __empDefault: typeof state }).__empDefault = state
    return () => h(EmployeeDetailPanel, { employeeId: state.employeeId })
  }
}

const app = createApp(Wrapper)

app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn })
app.mount('#e2e-app')
