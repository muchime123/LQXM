import type { NavItem } from '@/apps/types'

/**
 * ERP 应用的导航根与权限组。
 *
 * 主轴（L2 · 交易单据）：
 *   销售订单 → 履约评审 → 生产任务 / 采购订单 → 完工入库 → 销售出库
 *   （完工入库仍归 ManufacturingExecutionView 内次级 Tab，不独立成页）
 *
 * 2026-09-30 下线：工作台 / 经营总览 / 生产·采购·仓储协同 / 主数据 / 收付款规划 /
 *   可售库存 / 制程与任务单配置。这些聚合页掩盖了主轴 5 节点，已删视图并保留
 *   路由 redirect 到主轴对应节点，旧书签不破。
 *
 * 同步下线 ErpAreaNav 顶部页签组件：主轴 5 节点已在侧栏导航里，顶部页签与
 * 流程步骤条 / 页面内次级 Tab 重复且顺序不一致，已删。
 *
 * 2026-09-30 客户档案迁出 ERP 应用；2026-10-06 侧栏合并为一份后，它挂在
 * 「氚云数据管理」分组（客户 ID 是出库关联与提成核算的锚点）。销售订单抽屉内
 * 仍可就地建档。
 */

export const salesRead = ['sales_order.read.self', 'sales_order.read.department', 'sales_order.read.all']
export const purchaseRead = ['purchase_order.read.self', 'purchase_order.read.department', 'purchase_order.read.all']
export const manufacturingRead = ['manufacturing.read.self', 'manufacturing.read.department', 'manufacturing.read.all']

export const productionAccess = [
  ...manufacturingRead,
  'manufacturing.manage',
  'manufacturing.inspect',
  'manufacturing.report',
  'manufacturing.report.override',
  'manufacturing.receive'
]

export const warehouseAccess = [...salesRead, ...purchaseRead, ...manufacturingRead, 'manufacturing.inspect']

/** 统一图标构造：与既有导航保持同一套线条参数（24 视框 / stroke 2 / 18px）。 */
const icon = (d: string): string =>
  `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" width="18" height="18">${d}</svg>`

/**
 * ERP 应用的一级导航根：单个「ERP」父节点 + 主轴 5 项子节点。
 *
 * 形状保持「父节点 + children」：本项目侧栏的既有契约（见 `e2e/erp-v3.spec.ts`）。
 * 客户档案已迁出（现挂侧栏「氚云数据管理」分组），不属 ERP 主轴。
 */
export const erpNavItems: NavItem[] = [
  {
    to: '/sales-orders',
    label: 'ERP',
    description: '销售订单 → 履约评审 → 生产 / 采购 → 销售出库。',
    icon: icon('<rect x="3" y="3" width="7" height="9"/><rect x="14" y="3" width="7" height="5"/><rect x="14" y="12" width="7" height="9"/><rect x="3" y="16" width="7" height="5"/>'),
    group: 'erp',
    children: [
      { to: '/sales-orders', label: '销售订单', icon: '', description: '主轴 1：客户接单与订单主档。' },
      { to: '/sales-fulfillment-reviews', label: '履约评审', icon: '', permission: ['manufacturing.manage', 'purchase_order.manage'], description: '主轴 2：销售需求的履约评审（生产/采购分流）。' },
      {
        to: '/local-manufacturing-execution',
        label: '生产任务',
        icon: '',
        activePaths: ['/local-manufacturing-execution', '/production-demands'],
        permission: productionAccess,
        description: '主轴 3a：车间任务、报工、返工、质检与完工交仓。'
      },
      {
        to: '/purchase-orders',
        label: '采购订单',
        icon: '',
        permission: [...purchaseRead, 'purchase_order.manage'],
        description: '主轴 3b：采购订单、到货入库与代发直发。'
      },
      {
        to: '/sales-outbounds',
        label: '销售出库',
        icon: '',
        permission: salesRead,
        description: '主轴 5：ERP 正式销售出库单（利润/提成/结算在月度提成页）。'
      }
    ]
  }
]
