import type { RouteRecordRaw } from 'vue-router'

/**
 * 档案与运营应用路由表。
 *
 * 视图已物理搬迁至 `apps/archive/views/`（product/marketing/governance/production/
 * settlement/system 六域，24 个业务视图）。登录页与 403/404 属外壳级，放 `@/shell/`。
 *
 * ⚠️ path / name / meta / permission 与原 `router/index.ts` 完全一致。
 */
export const archiveRoutes: RouteRecordRaw[] = [
  // P7：正式登录页（独立全屏布局，不套 DefaultLayout）
  {
    path: '/login',
    name: 'login',
    component: () => import('@/shell/LoginView.vue'),
    meta: { title: '登录', layout: 'auth' }
  },
  // P5.2a：登录后默认进入「客户档案」。
  // 客户档案归侧栏「氚云数据管理」分组（客户 ID 是出库关联与提成核算的锚点，属氚云数据链）。
  // 销售订单抽屉内仍可就地建档，登录默认落地页仍是 /customers。
  { path: '/', redirect: '/customers' },

  // ---- 客户档案（氚云数据链）----
  // 2026-10-06：客户档案随侧栏归入「氚云数据管理」分组 —— 客户与产品库存同为氚云
  // 数据链的下游消费方（出库关联、提成核算都锚定客户 ID），与库存并列更顺。
  {
    path: '/customers',
    name: 'customers',
    component: () => import('@/apps/archive/views/customer/CustomerView.vue'),
    meta: { title: '客户档案', layout: 'workspace', group: 'h3' }
  },

  // ---- 产品中心 ----
  {
    path: '/products',
    name: 'products',
    component: () => import('@/apps/archive/views/product/ProductCatalogView.vue'),
    meta: { title: '产品管理', layout: 'workspace', group: 'product' }
  },
  {
    path: '/device-videos',
    name: 'deviceVideos',
    component: () => import('@/apps/archive/views/product/DeviceVideoLibraryView.vue'),
    meta: { title: '设备视频库', layout: 'list', group: 'product' }
  },
  {
    path: '/packaging',
    name: 'packaging',
    component: () => import('@/apps/archive/views/product/PackagingView.vue'),
    meta: { title: '包材管理', layout: 'workspace', group: 'product' }
  },
  {
    path: '/molds',
    name: 'molds',
    component: () => import('@/apps/archive/views/product/mold/PackagingMoldView.vue'),
    meta: { title: '模具资产', layout: 'workspace', group: 'product' }
  },
  {
    path: '/certificates',
    name: 'certificates',
    component: () => import('@/apps/archive/views/product/CertificateView.vue'),
    meta: { title: '知识产权', layout: 'list', group: 'product' }
  },
  {
    path: '/office-assets',
    name: 'officeAssets',
    component: () => import('@/apps/archive/views/product/OfficeAssetView.vue'),
    meta: { title: '物品资产', layout: 'list', group: 'product' }
  },

  // ---- 官网运营 ----
  {
    path: '/website/dashboard',
    name: 'websiteDashboard',
    component: () => import('@/apps/archive/views/marketing/WebsiteDashboardView.vue'),
    meta: { title: '官网工作台', layout: 'list', permission: 'website.manage', group: 'marketing' }
  },
  {
    path: '/website/visual-editor',
    name: 'websiteVisualEditor',
    component: () => import('@/apps/archive/views/marketing/WebsiteVisualEditorView.vue'),
    meta: { title: '可视化编辑器', layout: 'workspace', permission: 'website.manage', group: 'marketing' }
  },
  {
    path: '/website/inquiries',
    name: 'websiteInquiries',
    component: () => import('@/apps/archive/views/marketing/WebsiteInquiryView.vue'),
    meta: { title: '询盘管理', layout: 'list', permission: 'website.manage', group: 'marketing' }
  },
  {
    path: '/website/solutions',
    name: 'websiteSolutions',
    component: () => import('@/apps/archive/views/marketing/WebsiteSolutionsView.vue'),
    meta: { title: '方案管理', layout: 'list', permission: 'website.manage', group: 'marketing' }
  },
  {
    path: '/website/banners',
    name: 'websiteBanners',
    component: () => import('@/apps/archive/views/marketing/WebsiteBannerView.vue'),
    meta: { title: 'Banner管理', layout: 'list', permission: 'website.manage', group: 'marketing' }
  },

  // ---- 公司管理 ----
  {
    path: '/media',
    name: 'media',
    component: () => import('@/apps/archive/views/marketing/MediaAccountView.vue'),
    meta: { title: '自媒体账号', layout: 'list', group: 'marketing' }
  },
  {
    path: '/company',
    name: 'company',
    component: () => import('@/apps/archive/views/marketing/CompanyInfoView.vue'),
    meta: { title: '公司信息', layout: 'detail', group: 'marketing' }
  },
  {
    path: '/company/certifications',
    name: 'companyCertifications',
    component: () => import('@/apps/archive/views/marketing/CompanyCertificationsView.vue'),
    meta: { title: '资质合规', layout: 'list', group: 'marketing' }
  },

  // ---- 氚云数据管理（迁移期只读对照 + 期初来源）----
  // P1-1：氚云导入中心（原 H3Business import stage 独立）
  {
    path: '/h3-imports',
    name: 'h3Imports',
    component: () => import('@/apps/archive/views/governance/H3ImportCenterView.vue'),
    meta: { title: '氚云导入中心', layout: 'list', permission: 'integration.h3', group: 'h3' }
  },
  {
    path: '/h3-outbound-relations',
    name: 'h3OutboundRelations',
    component: () => import('@/apps/archive/views/governance/H3OutboundRelationView.vue'),
    meta: { title: '销售出库关联工作台', layout: 'list', permission: 'integration.h3', group: 'h3' }
  },
  {
    path: '/h3-production-execution',
    name: 'h3ProductionExecution',
    component: () => import('@/apps/archive/views/production/H3ProductionExecutionView.vue'),
    meta: {
      title: '车间执行 · 氚云生产任务',
      layout: 'list',
      group: 'h3',
      permission: ['manufacturing.read.self', 'manufacturing.read.department', 'manufacturing.read.all', 'manufacturing.report', 'manufacturing.report.override', 'manufacturing.inspect', 'manufacturing.followup']
    }
  },
  {
    path: '/product-inventory',
    name: 'productInventory',
    component: () => import('@/apps/archive/views/production/ProductInventoryView.vue'),
    meta: { title: '产品库存', layout: 'list', permission: 'product_inventory.read', group: 'h3' }
  },
  // 月度提成核算（现行简化口径，独立只读视图）：本月实际回款 − 上月运杂费，不依赖旧提成体系
  {
    path: '/monthly-commission',
    name: 'monthlyCommission',
    component: () => import('@/apps/archive/views/settlement/commission/MonthlyCommissionView.vue'),
    meta: {
      title: '月度提成核算',
      layout: 'list',
      permission: ['monthly_commission.read.self', 'monthly_commission.read.department', 'monthly_commission.read.all'],
      group: 'h3'
    }
  },

  // ---- 系统管理 ----
  // P5.1 统一员工体系
  {
    path: '/system/employees',
    name: 'systemEmployees',
    component: () => import('@/apps/archive/views/system/EmployeeRosterView.vue'),
    meta: { title: '员工名册', layout: 'list', permission: 'system.employee.manage', group: 'system' }
  },
  {
    path: '/system/departments',
    name: 'systemDepartments',
    component: () => import('@/apps/archive/views/system/DepartmentManagementView.vue'),
    meta: { title: '部门主数据', layout: 'list', permission: 'system.employee.manage', group: 'system' }
  },
  // 角色与权限管理（角色 CRUD + 权限勾选）
  {
    path: '/system/roles',
    name: 'systemRoles',
    component: () => import('@/apps/archive/views/system/RolePermissionView.vue'),
    meta: { title: '角色权限', layout: 'list', permission: 'system.employee.manage', group: 'system' }
  },
  // 日常报销（钉钉同步 + 报销台账整合单入口，2026-09）：钉钉「日常报销」数据只读留档 + 同步/导入入口，
  // 页内 Tab 按各自权限可见；原 /system/dingtalk 与 /system/reimbursement-ledger 仅作兼容重定向
  {
    path: '/system/reimbursement',
    name: 'reimbursementCenter',
    component: () => import('@/apps/archive/views/system/ReimbursementCenterView.vue'),
    meta: {
      title: '日常报销',
      layout: 'list',
      permission: ['expense.read.self', 'expense.manage', 'integration.dingtalk'],
      group: 'system'
    }
  },
  { path: '/system/dingtalk', redirect: '/system/reimbursement?tab=sync' },
  { path: '/system/reimbursement-ledger', redirect: '/system/reimbursement' },
  // 系统管理 → 操作日志（审计记录只读查询）
  {
    path: '/system/operation-logs',
    name: 'operationLogs',
    component: () => import('@/apps/archive/views/system/OperationLogView.vue'),
    meta: { title: '操作日志', layout: 'list', permission: 'system.employee.manage', group: 'system' }
  }
]
