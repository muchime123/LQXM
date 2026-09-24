import { createRouter, createWebHashHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { isDirty, askConfirm } from '../composables'
import { startProgress, doneProgress, failProgress } from '../composables/ui/useRouteProgress'
import { addTabFromRoute } from '../composables/common/useMultiTabs'
import { useCurrentUser } from '../composables/auth/useCurrentUser'

const routes: RouteRecordRaw[] = [
  // P7：正式登录页（独立全屏布局，不套 DefaultLayout）
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/auth/LoginView.vue'),
    meta: { title: '登录', layout: 'auth' }
  },
  // P5.2a：系统定位升级，登录后默认进入「客户档案」
  { path: '/', redirect: '/customers' },
  {
    path: '/products',
    name: 'products',
    component: () => import('../views/product/ProductCatalogView.vue'),
    meta: { title: '产品管理', layout: 'workspace', group: 'product' }
  },
  {
    path: '/device-videos',
    name: 'deviceVideos',
    component: () => import('../views/product/DeviceVideoLibraryView.vue'),
    meta: { title: '设备视频库', layout: 'list', group: 'product' }
  },
  {
    path: '/website/dashboard',
    name: 'websiteDashboard',
    component: () => import('../views/marketing/WebsiteDashboardView.vue'),
    meta: { title: '官网工作台', layout: 'list', permission: 'website.manage', group: 'marketing' }
  },
  {
    path: '/website/visual-editor',
    name: 'websiteVisualEditor',
    component: () => import('../views/marketing/WebsiteVisualEditorView.vue'),
    meta: { title: '可视化编辑器', layout: 'workspace', permission: 'website.manage', group: 'marketing' }
  },
  {
    path: '/website/inquiries',
    name: 'websiteInquiries',
    component: () => import('../views/marketing/WebsiteInquiryView.vue'),
    meta: { title: '询盘管理', layout: 'list', permission: 'website.manage', group: 'marketing' }
  },
  {
    path: '/website/solutions',
    name: 'websiteSolutions',
    component: () => import('../views/marketing/WebsiteSolutionsView.vue'),
    meta: { title: '方案管理', layout: 'list', permission: 'website.manage', group: 'marketing' }
  },
  {
    path: '/website/banners',
    name: 'websiteBanners',
    component: () => import('../views/marketing/WebsiteBannerView.vue'),
    meta: { title: 'Banner管理', layout: 'list', permission: 'website.manage', group: 'marketing' }
  },
  {
    path: '/packaging',
    name: 'packaging',
    component: () => import('../views/product/PackagingView.vue'),
    meta: { title: '包材管理', layout: 'workspace', group: 'product' }
  },
  {
    path: '/molds',
    name: 'molds',
    component: () => import('../views/product/mold/PackagingMoldView.vue'),
    meta: { title: '模具资产', layout: 'workspace', group: 'product' }
  },
  {
    path: '/media',
    name: 'media',
    component: () => import('../views/marketing/MediaAccountView.vue'),
    meta: { title: '自媒体账号', layout: 'list', group: 'marketing' }
  },
  {
    path: '/certificates',
    name: 'certificates',
    component: () => import('../views/product/CertificateView.vue'),
    meta: { title: '知识产权', layout: 'list', group: 'product' }
  },
  {
    path: '/company',
    name: 'company',
    component: () => import('../views/marketing/CompanyInfoView.vue'),
    meta: { title: '公司信息', layout: 'detail', group: 'marketing' }
  },
  {
    path: '/company/certifications',
    name: 'companyCertifications',
    component: () => import('../views/marketing/CompanyCertificationsView.vue'),
    meta: { title: '资质合规', layout: 'list', group: 'marketing' }
  },
  {
    path: '/office-assets',
    name: 'officeAssets',
    component: () => import('../views/product/OfficeAssetView.vue'),
    meta: { title: '物品资产', layout: 'list', group: 'product' }
  },
  {
    path: '/customers',
    name: 'customers',
    component: () => import('../views/sales/CustomerView.vue'),
    meta: { title: '客户档案', layout: 'workspace', group: 'h3' }
  },
  // ERP 正式销售出库单（P2）：把「有货直接出库」这条主线在 ERP 里跑通。
  // 与 /h3-outbound-relations（氚云台账关联工作台）不是同一批数据：那个查历史台账，这个录 ERP 正式单。
  {
    path: '/sales-outbounds',
    name: 'salesOutbounds',
    component: () => import('../views/sales/SalesOutboundView.vue'),
    meta: {
      title: '销售出库',
      layout: 'list',
      permission: ['sales_order.read.self', 'sales_order.read.department', 'sales_order.read.all'],
      group: 'sales'
    }
  },
  {
    path: '/sales-orders',
    name: 'salesOrders',
    component: () => import('../views/sales/SalesOrderView.vue'),
    meta: {
      title: '销售订单',
      layout: 'list',
      permission: ['sales_order.read.self', 'sales_order.read.department', 'sales_order.read.all'],
      group: 'sales'
    }
  },
  {
    path: '/production-demands',
    name: 'productionDemands',
    component: () => import('../views/production/ProductionDemandView.vue'),
    meta: {
      title: '待接生产需求',
      layout: 'list',
      permission: 'manufacturing.manage',
      group: 'sales'
    }
  },
  // 外采采购订单：销售订单里供货方式为「外采」的明细在这里落地。
  // 入库型到货写本地库存账（与自产完工入库同套余额表）；代发型供应商直发客户、不进本地仓。
  {
    path: '/purchase-orders',
    name: 'purchaseOrders',
    component: () => import('../views/procurement/PurchaseOrderView.vue'),
    meta: {
      title: '采购订单',
      layout: 'list',
      permission: ['purchase_order.read.self', 'purchase_order.read.department', 'purchase_order.read.all'],
      group: 'sales'
    }
  },
  // P1-1：氚云导入中心（原 H3Business import stage 独立）
  {
    path: '/h3-imports',
    name: 'h3Imports',
    component: () => import('../views/governance/H3ImportCenterView.vue'),
    meta: { title: '氚云导入中心', layout: 'list', permission: 'integration.h3', group: 'h3' }
  },
  {
    path: '/h3-outbound-relations',
    name: 'h3OutboundRelations',
    component: () => import('../views/governance/H3OutboundRelationView.vue'),
    meta: { title: '销售出库关联工作台', layout: 'list', permission: 'integration.h3', group: 'h3' }
  },
  {
    path: '/h3-production-execution',
    name: 'h3ProductionExecution',
    component: () => import('../views/production/H3ProductionExecutionView.vue'),
    meta: { title: '车间执行 · 氚云生产任务', layout: 'list', group: 'h3',
      permission: ['manufacturing.read.self', 'manufacturing.read.department', 'manufacturing.read.all', 'manufacturing.report', 'manufacturing.report.override', 'manufacturing.inspect', 'manufacturing.followup'] }
  },
  // 旧的车间执行地址保留本地生产任务入口，避免原先的「新建生产任务单」书签失效。
  {
    path: '/manufacturing-execution',
    name: 'manufacturingExecution',
    redirect: { path: '/local-manufacturing-execution' }
  },
  // 本地任务为正式入口：展会、展厅、打样等内部需求可不经过销售订单直接建单。
  {
    path: '/local-manufacturing-execution',
    name: 'localManufacturingExecution',
    component: () => import('../views/production/ManufacturingExecutionView.vue'),
    meta: {
      title: '本地生产任务 · 车间执行',
      layout: 'list',
      permission: [
        'manufacturing.manage',
        'manufacturing.report',
        'manufacturing.inspect',
        'manufacturing.report.override',
        'manufacturing.receive',
        'manufacturing.read.self',
        'manufacturing.read.department',
        'manufacturing.read.all'
      ],
      group: 'sales'
    }
  },
  {
    path: '/manufacturing-process-management',
    name: 'manufacturingProcessManagement',
    component: () => import('../views/production/ManufacturingProcessManagementView.vue'),
    meta: { title: '制程与工序管理', layout: 'list', permission: 'manufacturing.template.manage', group: 'h3' }
  },
  // 「任务单产品与配置」原为制程管理页内的抽屉，2026-09-24 独立成页：
  // 它是新建任务单的联动数据源，也是产品身份（可售库存口径）的锚点，需要独立演进。
  {
    path: '/manufacturing-task-configs',
    name: 'manufacturingTaskConfigs',
    component: () => import('../views/production/ManufacturingTaskConfigView.vue'),
    meta: { title: '任务单产品与配置', layout: 'list', permission: 'manufacturing.template.manage', group: 'h3' }
  },
  // 本地新建任务不是独立页面：入口在本地任务执行页右上角。
  // （组件 components/production/ProductionTaskOrderDrawer.vue）。
  // 旧路径保留为跳转，避免历史书签 404。
  {
    path: '/production-task-orders',
    name: 'productionTaskOrders',
    redirect: { path: '/local-manufacturing-execution', query: {} }
  },
  {
    path: '/product-inventory',
    name: 'productInventory',
    component: () => import('../views/production/ProductInventoryView.vue'),
    meta: { title: '产品库存', layout: 'list', permission: 'product_inventory.read', group: 'h3' }
  },
  // 月度提成核算（现行简化口径，独立只读视图）：本月实际回款 − 上月运杂费，不依赖旧提成体系
  {
    path: '/monthly-commission',
    name: 'monthlyCommission',
    component: () => import('../views/settlement/commission/MonthlyCommissionView.vue'),
    meta: {
      title: '月度提成核算',
      layout: 'list',
      permission: ['monthly_commission.read.self', 'monthly_commission.read.department', 'monthly_commission.read.all'],
      group: 'h3'
    }
  },
  // ========== 系统管理 ==========
  // P5.1 统一员工体系
  {
    path: '/system/employees',
    name: 'systemEmployees',
    component: () => import('../views/system/EmployeeRosterView.vue'),
    meta: { title: '员工名册', layout: 'list', permission: 'system.employee.manage', group: 'system' }
  },
  {
    path: '/system/departments',
    name: 'systemDepartments',
    component: () => import('../views/system/DepartmentManagementView.vue'),
    meta: { title: '部门主数据', layout: 'list', permission: 'system.employee.manage', group: 'system' }
  },
  // 角色与权限管理（角色 CRUD + 权限勾选）
  {
    path: '/system/roles',
    name: 'systemRoles',
    component: () => import('../views/system/RolePermissionView.vue'),
    meta: { title: '角色权限', layout: 'list', permission: 'system.employee.manage', group: 'system' }
  },
  // 日常报销（钉钉同步 + 报销台账整合单入口，2026-09）：钉钉「日常报销」数据只读留档 + 同步/导入入口，
  // 页内 Tab 按各自权限可见；原 /system/dingtalk 与 /system/reimbursement-ledger 仅作兼容重定向
  {
    path: '/system/reimbursement',
    name: 'reimbursementCenter',
    component: () => import('../views/system/ReimbursementCenterView.vue'),
    meta: {
      title: '日常报销',
      layout: 'list',
      permission: ['expense.read.self', 'expense.manage', 'integration.dingtalk'],
      group: 'system'
    }
  },
  {
    path: '/system/dingtalk',
    redirect: '/system/reimbursement?tab=sync'
  },
  {
    path: '/system/reimbursement-ledger',
    redirect: '/system/reimbursement'
  },
  // 系统管理 → 操作日志（审计记录只读查询）
  {
    path: '/system/operation-logs',
    name: 'operationLogs',
    component: () => import('../views/system/OperationLogView.vue'),
    meta: { title: '操作日志', layout: 'list', permission: 'system.employee.manage', group: 'system' }
  }
]

routes.push({
  path: '/403',
  name: 'forbidden',
  component: () => import('../views/error/ForbiddenView.vue'),
  meta: { title: '无权限', layout: 'default' }
})

routes.push({
  path: '/:pathMatch(.*)*',
  name: 'notFound',
  component: () => import('../views/error/NotFoundView.vue'),
  meta: { title: '页面不存在', layout: 'default' }
})

export const router = createRouter({
  history: createWebHashHistory(),
  routes
})

// 路由守卫：P7 登录校验 + 编辑未保存时离开确认 + 启动顶部进度条
router.beforeEach(async (to, from) => {
  if (to.path !== from.path) startProgress()
  const auth = useCurrentUser()
  const isLoginPage = to.path === '/login'
  const isAuthPage = isLoginPage

  // P7 登录守卫：已登录访问登录页 → 回到目标页；未登录访问业务页 → 跳登录页
  if (isLoginPage) {
    if (auth.user.value && auth.hasToken()) {
      const redirect = to.query.redirect
      const target = typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/customers'
      return { path: target }
    }
  } else {
    const hasAnyIdentity = auth.hasToken() || !!localStorage.getItem('lq.currentEmployeeId')
    await auth.load()
    if (!auth.user.value && !hasAnyIdentity) {
      return {
        path: '/login',
        query: to.fullPath && to.fullPath !== '/' ? { redirect: to.fullPath } : undefined
      }
    }
  }

  const required = to.meta?.permission as string | string[] | undefined
  if (required && !isAuthPage) {
    if (!auth.user.value) await auth.load()
    if (!auth.can(required)) return { path: '/403', query: { from: to.fullPath } }
  }
  if (isDirty() && from.name) {
    const ok = await askConfirm({
      title: '未保存的修改',
      message: '当前有未保存的修改，确定要离开吗？离开后修改将丢失。',
      confirmText: '离开',
      cancelText: '留下',
      danger: true
    })
    if (!ok) {
      // 用户取消跳转：结束进度条
      doneProgress()
      return false
    }
  }
})

// 路由后置守卫：同步浏览器标签页标题 + 结束顶部进度条 + 多标签页同步
// P5.2a：系统定位从「设备资料管理系统」升级为「利强内部业务管理系统」
const APP_TITLE = '利强包装科技内部业务系统'
router.afterEach(to => {
  const pageTitle = to.meta?.title as string | undefined
  document.title = pageTitle ? `${pageTitle} - ${APP_TITLE}` : APP_TITLE
  addTabFromRoute(to)
  doneProgress()
})

// 路由错误：进度条变红并淡出
router.onError(() => {
  failProgress()
})
