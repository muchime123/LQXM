import type { RouteRecordRaw } from 'vue-router'

/**
 * ERP 应用路由表。
 *
 * 与 `apps/archive/routes.ts` 分开维护：应用边界必须同时落在「路由表 + 导航根 +
 * 视图目录」上才算数，只在侧栏加一个分组只是 UI 层的假边界。
 *
 * ⚠️ 本次只搬位置，**path / name / meta / permission 一字未改**，现有 URL 与书签全部不变。
 * 路由模式仍是 hash（`createWebHashHistory`），改 history 属于下一步（需要服务端 fallback）。
 */
export const erpRoutes: RouteRecordRaw[] = [
  // ---- 兼容旧地址 ----
  // 原「ERP 销售」入口已并入销售订单列表
  { path: '/erp-sales', redirect: to => ({ path: '/sales-orders', query: to.query }) },
  // 旧的车间执行地址保留本地生产任务入口，避免原先的「新建生产任务单」书签失效。
  { path: '/manufacturing-execution', name: 'manufacturingExecution', redirect: { path: '/local-manufacturing-execution' } },
  // 本地新建任务不是独立页面：入口在本地任务执行页右上角（components/production/ProductionTaskOrderDrawer.vue）。
  // 旧路径保留为跳转，避免历史书签 404。
  { path: '/production-task-orders', name: 'productionTaskOrders', redirect: { path: '/local-manufacturing-execution', query: {} } },

  // ---- 已下线工作台/聚合页：旧 URL 重定向到主轴首项销售订单，避免历史书签 404 ----
  // 删除清单：ErpWorkbenchView / ErpOperationsView(生产/采购/仓储协同) / ErpDomainView(主数据/收付款) / ErpInventoryView(可售库存) / ErpOverviewView(经营总览)
  { path: '/erp-workbench', redirect: { path: '/sales-orders' } },
  { path: '/erp-production', redirect: { path: '/local-manufacturing-execution' } },
  { path: '/erp-purchase', redirect: { path: '/purchase-orders' } },
  { path: '/erp-warehouse', redirect: { path: '/sales-outbounds' } },
  { path: '/erp-inventory', redirect: { path: '/sales-outbounds' } },
  { path: '/erp-master', redirect: { path: '/sales-orders' } },
  { path: '/erp-finance', redirect: { path: '/sales-orders' } },
  { path: '/erp-overview', redirect: { path: '/sales-orders' } },
  { path: '/manufacturing-process-management', redirect: { path: '/local-manufacturing-execution' } },
  { path: '/manufacturing-task-configs', redirect: { path: '/local-manufacturing-execution' } },

  // ---- 订单协作（订单主档详情）----
  { path: '/sales-orders/:id', name: 'erpOrderWorkspace', component: () => import('@/apps/erp/views/ErpOrderWorkspaceView.vue'), meta: { title: '订单协作', layout: 'detail', group: 'erp', pageOwnsTitle: true, permission: ['sales_order.read.self', 'sales_order.read.department', 'sales_order.read.all'] } },

  // ---- 销售链路 ----
  { path: '/sales-orders', name: 'salesOrders', component: () => import('@/apps/erp/views/sales/SalesOrderView.vue'), meta: { title: '销售订单', layout: 'list', permission: ['sales_order.read.self', 'sales_order.read.department', 'sales_order.read.all'], group: 'erp' } },
  // 客户档案已迁出 ERP（/customers 现挂侧栏「氚云数据管理」分组）：
  // 客户主档属主数据，非 ERP 交易节点；销售订单抽屉内仍可就地建档，登录默认落地页仍是 /customers。
  {
    path: '/sales-fulfillment-reviews',
    name: 'salesFulfillmentReviews',
    component: () => import('@/apps/erp/views/sales/SalesFulfillmentReviewView.vue'),
    meta: { title: '销售需求履约评审', layout: 'list', permission: ['manufacturing.manage', 'purchase_order.manage'], group: 'erp' }
  },
  // ---- 交付链路 ----
  {
    path: '/production-demands',
    name: 'productionDemands',
    component: () => import('@/apps/erp/views/production/ProductionDemandView.vue'),
    meta: { title: '待下达生产需求', layout: 'list', permission: 'manufacturing.manage', group: 'erp' }
  },
  // 本地任务为正式入口：展会、展厅、打样等内部需求可不经过销售订单直接建单。
  {
    path: '/local-manufacturing-execution',
    name: 'localManufacturingExecution',
    component: () => import('@/apps/erp/views/production/ManufacturingExecutionView.vue'),
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
      group: 'erp'
    }
  },
  // ERP 正式销售出库单（P2）：把「有货直接出库」这条主线在 ERP 里跑通。
  // 与 /h3-outbound-relations（氚云台账关联工作台）不是同一批数据：那个查历史台账，这个录 ERP 正式单。
  {
    path: '/sales-outbounds',
    name: 'salesOutbounds',
    component: () => import('@/apps/erp/views/sales/SalesOutboundView.vue'),
    meta: { title: '销售出库', layout: 'list', permission: ['sales_order.read.self', 'sales_order.read.department', 'sales_order.read.all'], group: 'erp' }
  },
  // ---- 外采 ----
  // 销售订单里供货方式为「外采」的明细在这里落地。
  // 入库型到货写本地库存账（与自产完工入库同套余额表）；代发型供应商直发客户、不进本地仓。
  {
    path: '/purchase-orders',
    name: 'purchaseOrders',
    component: () => import('@/apps/erp/views/procurement/PurchaseOrderView.vue'),
    meta: { title: '采购订单', layout: 'list', permission: ['purchase_order.read.self', 'purchase_order.read.department', 'purchase_order.read.all'], group: 'erp' }
  }
]
