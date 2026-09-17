import { http } from '../http'

// ================================================================================
// 销售出库订单利润列表 API（客户档案「业务」列表使用）
// 对应后端：SalesOutboundProfitController 列表端点
// 方案：docs/current-sales-profit-design.md §12
//
// 2026-09-07 迁移：出库单「详情」统一改为氚云销售出库台账详情
// （H3LedgerDetailDrawer + api/business/orders#fetchLedgerDetail('outbound', id)），
// 原订单利润详情抽屉 / 客户档案订单利润抽屉及其独有 API（详情/重算/调整/
// 费用归单确认）已随组件下线删除；费用归单确认后端 ExpenseController 保留。
// ================================================================================

export interface OrderProfitItem {
  orderNature?: 'SALE' | 'NEGATIVE' | 'ZERO_VALUE' | 'MIXED' | 'RETURN_LINES'
  outboundStatus?: string | null
  id: number
  outboundNo: string
  customerId?: number | null
  employeeId?: number | null
  shipDate?: string
  customerName?: string
  salespersonName?: string
  dealerName?: string
  businessOwner?: string
  /** 地区（出库台账自带），2026-09 业务列表透出 */
  region?: string
  /** 联系电话（出库台账自带） */
  contactPhone?: string
  /** 客户详细地址（出库台账自带） */
  customerAddress?: string
  salesAmount: number
  purchaseAmount: number
  receivedAmount?: number
  outstandingAmount?: number
  profitId?: number
  productGrossProfit?: number
  confirmedExpenseAmount?: number
  adjustmentAmount?: number
  accountingProfit?: number
  commissionableProfit?: number
  theoreticalCommission?: number
  availableCommission?: number
  calculationStatus?: string
  costAbnormal?: string
  calculatedAt?: string
  readinessStatus?: 'READY' | 'SPECIAL_REVIEW' | 'EMPLOYEE_UNMATCHED' | 'SALES_AMOUNT_ABNORMAL' | 'PURCHASE_COST_ABNORMAL' | 'RECEIPT_AMOUNT_ABNORMAL'
}

export interface PageResult<T> { items: T[]; total: number; page?: number; size?: number }

/** 销售出库利润列表（客户档案「业务」列表等场景）。 */
export async function fetchOrderProfits(params: Record<string, unknown>) {
  return (await http.get<PageResult<OrderProfitItem>>('/sales-outbound-profits', { params })).data
}
