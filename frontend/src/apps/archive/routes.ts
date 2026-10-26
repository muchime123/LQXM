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
  // 默认落地页（登录后 / 直接打开根路径 `/`）由路由守卫按权限解析，**不在此静态 redirect**：
  // 2026-10-10 按 LQ 决定默认页为「生产跟单」(`/production-tracking`)；该页带读权限门槛，
  // 无权限者需回落「客户档案」(`/customers`)，而权限要等 `/api/auth/me` 返回才知道——
  // 静态 redirect 是同步的，拿不到权限，会把无权限的人直接送进 403。
  // 落点解析见 `router/index.ts` 的守卫 + `utils/defaultLanding.ts`（唯一来源）。

  // ---- 客户档案（氚云数据链）----
  // 2026-10-06：客户档案随侧栏归入「氚云数据管理」分组 —— 客户与产品库存同为氚云
  // 数据链的下游消费方（出库关联、提成核算都锚定客户 ID），与库存并列更顺。
  {
    path: '/customers',
    name: 'customers',
    component: () => import('@/apps/archive/views/customer/CustomerView.vue'),
    meta: { title: '客户档案', layout: 'workspace', group: 'h3', permission: 'customer.read' }
  },

  // ---- 产品中心 ----
  {
    path: '/products',
    name: 'products',
    component: () => import('@/apps/archive/views/product/ProductCatalogView.vue'),
    meta: { title: '产品管理', layout: 'workspace', group: 'product', permission: 'product.read' }
  },
  {
    path: '/device-videos',
    name: 'deviceVideos',
    component: () => import('@/apps/archive/views/product/DeviceVideoLibraryView.vue'),
    meta: { title: '设备视频库', layout: 'list', group: 'product', permission: 'product.read' }
  },
  {
    path: '/packaging',
    name: 'packaging',
    component: () => import('@/apps/archive/views/product/PackagingView.vue'),
    meta: { title: '包材管理', layout: 'workspace', group: 'product', permission: 'packaging.read' }
  },
  {
    path: '/molds',
    name: 'molds',
    component: () => import('@/apps/archive/views/product/mold/PackagingMoldView.vue'),
    meta: { title: '模具资产', layout: 'workspace', group: 'product', permission: 'mold.read' }
  },
  {
    path: '/certificates',
    name: 'certificates',
    component: () => import('@/apps/archive/views/product/CertificateView.vue'),
    meta: { title: '知识产权', layout: 'list', group: 'marketing', permission: 'certificate.read' }
  },
  {
    path: '/office-assets',
    name: 'officeAssets',
    component: () => import('@/apps/archive/views/product/OfficeAssetView.vue'),
    meta: { title: '物品资产', layout: 'list', group: 'marketing', permission: 'officeAsset.read' }
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
    meta: { title: '自媒体账号', layout: 'list', group: 'marketing', permission: 'media.read' }
  },
  {
    path: '/company',
    name: 'company',
    component: () => import('@/apps/archive/views/marketing/CompanyInfoView.vue'),
    meta: { title: '公司信息', layout: 'detail', group: 'marketing', permission: 'company.read' }
  },
  {
    path: '/company/certifications',
    name: 'companyCertifications',
    component: () => import('@/apps/archive/views/marketing/CompanyCertificationsView.vue'),
    meta: { title: '资质合规', layout: 'list', group: 'marketing', permission: 'company.read' }
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
    path: '/cashflow-journal',
    name: 'cashflowJournal',
    component: () => import('@/apps/archive/views/settlement/CashflowJournalView.vue'),
    meta: { title: '资金日记账', layout: 'list', permission: 'cashflow.read', group: 'h3' }
  },
  // P2-A：资金主体管理（归属认领 + 账户停用；读 cashflow.read / 管 cashflow.manage）
  {
    path: '/cashflow-entities',
    name: 'cashflowEntities',
    component: () => import('@/apps/archive/views/settlement/CashflowEntitiesView.vue'),
    meta: { title: '资金主体管理', layout: 'list', permission: 'cashflow.read', group: 'h3' }
  },
  {
    path: '/h3-sales-products',
    name: 'h3SalesProducts',
    component: () => import('@/apps/archive/views/governance/H3SalesProductAnalysisView.vue'),
    meta: { title: '销售出库产品分析', layout: 'list', permission: 'integration.h3', group: 'h3' }
  },
  // 2026-10-06 按 LQ 决定下线「销售出库关联」工作台（/h3-outbound-relations）：
  // 视图 H3OutboundRelationView.vue 与侧栏项已删，后端端点保留。留底 commit 23a0eda。
  // 2026-10-06 按 LQ 决定下线「车间执行 · 氚云生产任务」页面（/h3-production-execution），
  // 视图与 api/manufacturing/h3Execution.ts 已删；后端端点保留。留底于 commit 23a0eda。
  {
    path: '/product-inventory',
    name: 'productInventory',
    component: () => import('@/apps/archive/views/production/ProductInventoryView.vue'),
    meta: { title: '产品库存', layout: 'list', permission: 'product_inventory.read', group: 'h3' }
  },
  // ⭐ 本页自 2026-10-10 起同时是**登录默认落地页**（根路径 `/` 由守卫落到这里）。
  // 权限列表与 `utils/defaultLanding.ts` / 侧栏项三处必须一致，契约测试钉死。
  {
    path: '/production-tracking',
    name: 'productionTracking',
    component: () => import('@/apps/archive/views/production/ProductionTrackingView.vue'),
    meta: { title: '生产跟单', layout: 'list', permission: ['manufacturing.read.self', 'manufacturing.read.department', 'manufacturing.read.all'], group: 'h3' }
  },
  {
    path: '/production-tracking/analytics',
    name: 'productionTrackingAnalytics',
    redirect: { path: '/production-tracking', query: { analysis: '1' } }
  },
  // 「生产参考工期」（2026-10-23 重定位，原「生产产品与工期管理」）：
  // 维护「产品 + 车间」的节点参考工期与兜底通用参考，是生产跟单参考日期的取数底座，
  // 也是产品身份（可售库存口径）的锚点。不再与制程/工序概念绑定，也不再有工序库/制程模板。
  {
    path: '/manufacturing-task-configs',
    name: 'manufacturingTaskConfigs',
    component: () => import('@/apps/archive/views/production/ManufacturingTaskConfigView.vue'),
    meta: { title: '生产参考工期', layout: 'list', permission: 'manufacturing.template.manage', group: 'h3' }
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
