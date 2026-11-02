import { createApp, h, reactive } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/styles/index.css'
import EmployeeDetailPanel from '@/components/employee/EmployeeDetailPanel.vue'

/**
 * B7-B1c：员工详情面板（EmployeeDetailPanel）身份映射弹窗校验态验收入口。
 *
 * 原父级用 `:key` 在「切换员工」时重建面板；但**同一员工内**反复开关弹窗
 * 不会重建面板，因此 `el-form` 实例会被复用 —— 这正是校验态残留的暴露窗口，
 * 夹具保持常驻挂载以复现该路径。
 */
const state = reactive({
  employeeId: 1 as number | null,
  canManage: true
})

const Wrapper = {
  setup() {
    ;(window as unknown as { __empPanel: typeof state }).__empPanel = state
    return () =>
      h(EmployeeDetailPanel, {
        employeeId: state.employeeId,
        canManage: state.canManage,
        filterOptions: {
          departments: ['行政部', '生产部'],
          companies: [{ id: 1, name: '示例公司', parentId: null, parentName: null }],
          statuses: ['在职', '离职'],
          sourceSystems: ['氚云', '钉钉', '系统账号'],
          totalCount: 2,
          activeCount: 2,
          salespersonCount: 1
        }
      })
  }
}

const app = createApp(Wrapper)
app.use(ElementPlus, { size: 'default', zIndex: 2000, locale: zhCn })
app.mount('#e2e-app')
