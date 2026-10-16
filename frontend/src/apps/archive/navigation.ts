import type { NavItem } from '@/apps/types'

/**
 * 档案与运营应用的一级导航根。
 *
 * 原先它与 ERP 导航同住 `constants/navigation.ts`（ERP 只是其中一个 children 分组）。
 * 拆应用后这里只剩档案侧：氚云数据管理 / 产品中心 / 官网运营 / 公司管理 / 系统管理。
 *
 * 氚云导入与历史台账独立于自建订单履约；销售订单至出库归 ERP 应用。
 * 氚云导入的历史台账已并入氚云导入中心页内 Tab（/h3-imports?tab=ledger&ledger=xxx），
 * 旧路径全部在 router 中保留 redirect，兼容历史书签和已开 Tab。
 */
export const archiveNavItems: NavItem[] = [
  // ==================== 氚云数据管理（统一数据链入口） ====================
  {
    to: '/h3-imports',
    label: '氚云数据管理',
    description: '统一管理氚云源数据、客户档案、库存、生产主数据和月度核算。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18"><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v7c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 12v7c0 1.7 3.6 3 8 3s8-1.3 8-3v-7"/></svg>',
    group: 'h3',
    children: [
      {
        to: '/h3-imports',
        permission: 'integration.h3',
        label: '氚云导入中心',
        description: '导入六类氚云数据，查看批次与原始台账，并做重导准备。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M12 3v12M7 8l5-5 5 5"/></svg>',
        group: 'h3'
      },
      {
        to: '/h3-sales-products',
        permission: 'integration.h3',
        label: '销售出库产品分析',
        description: '按已导入的氚云出库统计常卖产品，并标出编号和名称待核项。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><path d="M3 3v18h18"/><path d="m6 15 5-5 4 3 5-7"/></svg>',
        group: 'h3'
      },
      // 2026-10-06 按 LQ 决定下线「销售出库关联」工作台（/h3-outbound-relations）：
      // 身份/关联治理能力仍由导入流程内部自动完成，不再单独占用侧栏入口。
      // 视图、路由与侧栏项已删；后端 /api/h3-imports/outbound-relations* 端点保留。
      {
        to: '/customers',
        permission: 'customer.read',
        label: '客户档案',
        description: '客户主档：销售订单锚定的客户 ID 与联系人地址。聚合关联的销售出库、合并历史与案例素材。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
        group: 'h3'
      },
      {
        to: '/product-inventory',
        permission: 'product_inventory.read',
        label: '产品库存',
        description: '氚云时点快照（产品信息台账 × 仓库明细的组合视图），导入完成即生效，不反映 ERP 出入库。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><path d="M4 7l8-4 8 4-8 4z"/><path d="M4 7v10l8 4 8-4V7"/><path d="M12 11v10"/></svg>',
        group: 'h3'
      },
      {
        to: '/production-tracking',
        permission: ['manufacturing.read.self', 'manufacturing.read.department', 'manufacturing.read.all'],
        label: '生产跟单',
        description: '按预计完工查看生产任务，推进车间节点；预计完工来不及完成时登记延期至的日期。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 8h8M8 12h8M8 16h5"/></svg>',
        group: 'h3'
      },
      // 生产主数据（2026-10-06 恢复）：09-30 那次「聚合页下线」把它们一并摘了，但它们不是
      // ERP 交易页，而是生产任务单的取数底座（后端 /api/manufacturing/master/* 从未删），
      // 属「入口丢」而非功能下线。ERP 前端下线不涉及这两页。
      {
        to: '/manufacturing-process-management',
        permission: 'manufacturing.template.manage',
        label: '制程与工序管理',
        description: '维护车间工序主数据与制程模板（生产任务单的联动数据源）；模板修改不影响已下达任务，任务工序为快照。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><circle cx="12" cy="12" r="3"/><path d="M12 2v4M12 18v4M2 12h4M18 12h4M5 5l3 3M16 16l3 3M19 5l-3 3M8 16l-3 3"/></svg>',
        group: 'h3'
      },
      {
        to: '/manufacturing-task-configs',
        permission: 'manufacturing.template.manage',
        label: '任务单产品与配置',
        description: '维护各产线可下单的产品名称与可选配置，是新建任务单的联动数据源与产品身份锚点。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><rect x="3" y="4" width="18" height="7" rx="1.5"/><rect x="3" y="15" width="18" height="5" rx="1.5"/><circle cx="8" cy="7.5" r="1.2"/></svg>',
        group: 'h3'
      },
      // 2026-10-06 按 LQ 决定下线「车间执行」导航项与页面（/h3-production-execution）。留底 commit 23a0eda。
      {
        to: '/monthly-commission',
        permission: ['monthly_commission.read.self', 'monthly_commission.read.department', 'monthly_commission.read.all'],
        label: '月度提成核算',
        description: '以氚云销售出库及其中经财务确认的收款数据为唯一核算来源。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><circle cx="12" cy="12" r="10"/><path d="M12 6v12"/><path d="M15 9.5c0-1.4-1.3-2.5-3-2.5s-3 1.1-3 2.5 1.3 2 3 2.5 3 1.1 3 2.5-1.3 2.5-3 2.5-3-1.1-3-2.5"/></svg>',
        group: 'h3'
      }
    ]
  },

  // ==================== 产品中心 ====================
  {
    to: '/products',
    label: '产品中心',
    description: '产品、包材、模具、设备视频等交付物资产。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="18" height="18"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>',
    group: 'product',
    children: [
      {
        to: '/products',
        permission: 'product.read',
        label: '产品管理',
        description: '产品档案、技术资料、定价、营销资产与官网发布。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M20 7h-7l-2-2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2z"/></svg>',
        group: 'product'
      },
      {
        to: '/device-videos',
        permission: 'product.read',
        label: '设备视频库',
        description: '跨产品按用途、机型和展示参数查找设备演示视频。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><rect x="3" y="5" width="15" height="14" rx="2"/><path d="M18 10l4-3v10l-4-3z"/><path d="M9 9l4 3-4 3z"/></svg>',
        group: 'product'
      },
      {
        to: '/packaging',
        permission: 'packaging.read',
        label: '包材管理',
        description: '包材系列、规格卡片、质量信息与素材资产。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><path d="M7.5 4.21 12 6.81l4.5-2.6"/><path d="M7.5 19.79V14.6L3 12"/><path d="M21 12l-4.5 2.6v5.19"/><path d="M3.27 6.96 12 12.01l8.73-5.05"/><line x1="12" y1="22.08" x2="12" y2="12"/></svg>',
        group: 'product'
      },
      {
        to: '/molds',
        permission: 'mold.read',
        label: '模具资产',
        description: '包材模具编号、出模数、刻字版本与配件台账。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M12 2l8 4v6c0 5-3.5 9-8 10-4.5-1-8-5-8-10V6l8-4z"/><path d="M9 7h6M9 11h6M10 15h4"/></svg>',
        group: 'product'
      },
      {
        to: '/certificates',
        permission: 'certificate.read',
        label: '知识产权',
        description: '专利、商标、软著档案，管理缴费状态与证书文件。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="9" y1="13" x2="15" y2="13"/><line x1="9" y1="17" x2="15" y2="17"/></svg>',
        group: 'product'
      },
      {
        to: '/office-assets',
        permission: 'officeAsset.read',
        label: '物品资产',
        description: '统一管理办公资产档案、扫码流转与标签打印。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M7 8h10M7 12h4"/><rect x="14" y="11" width="4" height="4"/></svg>',
        group: 'product'
      }
    ]
  },

  // ==================== 官网运营 ====================
  {
    to: '/website/dashboard',
    label: '官网运营',
    description: '在真实官网中编辑内容，管理发布、询盘与运营数据。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="18" height="18"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>',
    group: 'marketing',
    children: [
      {
        to: '/website/dashboard',
        permission: 'website.manage',
        label: '官网工作台',
        description: '官网发布总览与内容健康度检查。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><rect x="3" y="3" width="7" height="9"/><rect x="14" y="3" width="7" height="5"/><rect x="14" y="12" width="7" height="9"/><rect x="3" y="16" width="7" height="5"/></svg>',
        group: 'marketing'
      },
      {
        to: '/website/visual-editor',
        permission: 'website.manage',
        label: '可视化编辑器',
        description: '在真实官网画面中点击区块、实时预览并保存内容。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16"><rect x="3" y="3" width="18" height="14" rx="2"/><path d="M8 21h8M12 17v4"/><path d="M8 8h8M8 12h5"/></svg>',
        group: 'marketing'
      },
      {
        to: '/website/solutions',
        permission: 'website.manage',
        label: '方案管理',
        description: '管理官网 Solutions 页面的应用场景与方案文章。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>',
        group: 'marketing'
      },
      {
        to: '/website/inquiries',
        permission: 'website.manage',
        label: '询盘管理',
        description: '处理官网提交的询盘，支持状态流转与跟进记录。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/></svg>',
        group: 'marketing'
      },
      {
        to: '/website/banners',
        permission: 'website.manage',
        label: 'Banner管理',
        description: '管理官网首页 Banner 轮播图。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><rect x="2" y="3" width="20" height="14" rx="2"/><path d="M8 21h8M12 17v4"/></svg>',
        group: 'marketing'
      }
    ]
  },

  // ==================== 公司管理（主数据） ====================
  // 客户档案已于 2026-10-06 迁入「氚云数据管理」分组（客户与库存同属氚云数据链）；
  // 本组只保留公司主体与市场侧主数据。
  {
    to: '/company',
    label: '公司管理',
    description: '公司主体主数据：公司信息、资质合规与自媒体账号。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="18" height="18"><path d="M4 21V10.5l8-5 8 5V21"/><path d="M9 21V14h6v7"/><path d="M2 21h20"/></svg>',
    group: 'marketing',
    children: [
      {
        to: '/company',
        permission: 'company.read',
        label: '公司信息',
        description: '维护公司概览、品牌资产、账户和组织资料。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/></svg>',
        group: 'marketing'
      },
      {
        to: '/company/certifications',
        permission: 'company.read',
        label: '资质合规',
        description: '检测报告、营业执照、ISO 认证等时效性合规文件。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M12 2l8 4v6c0 5-3.5 9-8 10-4.5-1-8-5-8-10V6l8-4z"/><path d="M9 12l2 2 4-4"/></svg>',
        group: 'marketing'
      },
      {
        to: '/media',
        permission: 'media.read',
        label: '自媒体账号',
        description: '维护账号资料、运营状态和发布数据。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M16 8a6 6 0 0 1 6 6v7h-4v-7a2 2 0 0 0-2-2 2 2 0 0 0-2 2v7h-4v-7a6 6 0 0 1 6-6z"/><rect x="2" y="9" width="4" height="12"/><circle cx="4" cy="4" r="2"/></svg>',
        group: 'marketing'
      }
    ]
  },

  // ==================== 系统管理 ====================
  {
    to: '/system/employees',
    label: '系统管理',
    description: '统一员工身份、多系统数据集成与权限。',
    icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="18" height="18"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>',
    group: 'system',
    children: [
      {
        to: '/system/employees',
        permission: 'system.employee.manage',
        label: '员工名册',
        description: '统一员工身份，配置氚云/钉钉/系统账号的多系统映射，管理在职状态、业务员身份与角色分配。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>',
        group: 'system'
      },
      {
        to: '/system/departments',
        permission: 'system.employee.manage',
        label: '部门主数据',
        description: '统一部门编码、层级、员工当前归属与费用发生时归属。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M3 21h18"/><path d="M6 21V7l6-4 6 4v14"/><path d="M9 10h1"/><path d="M14 10h1"/><path d="M9 14h1"/><path d="M14 14h1"/></svg>',
        group: 'system'
      },
      {
        to: '/system/roles',
        permission: 'system.employee.manage',
        label: '角色权限',
        description: '管理登录角色：新建/编辑角色、勾选权限范围、删除自定义角色。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M12 2l8 4v6c0 5-3.5 9-8 10-4.5-1-8-5-8-10V6l8-4z"/><path d="M9 12l2 2 4-4"/></svg>',
        group: 'system'
      },
      {
        to: '/system/reimbursement',
        permission: ['expense.read.self', 'expense.manage', 'integration.dingtalk'],
        label: '日常报销',
        description: '钉钉「日常报销」数据全流程：报销台账只读检索与核对，钉钉同步页签负责 API 拉取 / Excel 导入留档。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M6 2h9l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"/><path d="M14 2v6h6"/><path d="M9 13h6M9 17h4"/></svg>',
        group: 'system'
      },
      {
        to: '/system/operation-logs',
        permission: 'system.employee.manage',
        label: '操作日志',
        description: '【审计】关键增删改操作的留痕记录，支持按实体类型、动作与时间检索。',
        icon: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="16" height="16"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>',
        group: 'system'
      }
    ]
  }
]
