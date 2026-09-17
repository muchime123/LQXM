import { http } from '../http'

/**
 * 运费范围（运杂费扣减窗口）参数（G4，P2）：
 * PREVIOUS_CALENDAR_MONTH=结束日所在月的上一完整自然月（默认）/ PREVIOUS_EQUAL_PERIOD=核算范围前一个
 * 等长周期 / CUSTOM=自定义起止日期（结账/预览必须填 reason，审计留痕）。运费范围只作用于运杂费扣减窗口。
 */
export interface FreightRangeParams {
  periodMode?: 'PREVIOUS_CALENDAR_MONTH' | 'PREVIOUS_EQUAL_PERIOD' | 'CUSTOM' | string | null
  startDate?: string | null
  endDate?: string | null
  reason?: string | null
}

/** 把运费范围参数并入 GET query / POST body（仅携带非空项）。 */
export function withFreightRange(base: Record<string, unknown>, f?: FreightRangeParams | null): Record<string, unknown> {
  if (!f) return base
  const out: Record<string, unknown> = { ...base }
  if (f.periodMode) out.freightPeriodMode = f.periodMode
  if (f.startDate) out.freightStartDate = f.startDate
  if (f.endDate) out.freightEndDate = f.endDate
  if (f.reason) out.freightReason = f.reason
  return out
}

/**
 * 业务员月度提成汇总行（现行简化口径，2026-08 财务确认）：
 * 提成 = (经销商回款 − 上月经销商运杂费) × 经销商比例 + (零售回款 − 上月零售运杂费) × 零售比例。
 * 经销商判定 = 出库单经销商非空，或毛利率（source_gross_profit/sales_amount）低于 15%。
 */
export interface MonthlyCommissionRow {
  employeeId: number
  employeeName: string
  currentMonthSalesAmount: number
  dealerSalesAmount: number
  retailSalesAmount: number
  currentMonthReceiptAmount: number
  dealerReceiptAmount: number
  retailReceiptAmount: number
  /** 期间销售出库主单的氚云「已收款金额」合计。 */
  collectedAmount: number
  dealerCollectedAmount: number
  retailCollectedAmount: number
  /** 期间销售出库主单的氚云「未收款金额」合计。 */
  uncollectedAmount: number
  /** 收款占比 0~1（氚云已收 / (氚云已收 + 氚云未收)；无应收视为 1）。 */
  collectionRate: number
  previousMonthFreightAmount: number
  dealerFreightAmount: number
  retailFreightAmount: number
  commissionBase: number
  dealerRate: number
  retailRate: number
  dealerCommission: number
  retailCommission: number
  /** 正常提成（未结账 = 经销/零售拆分合计；已结账 = 版本快照 base_commission）。 */
  baseCommission: number
  /** 修正提成（已结账期间 = 版本快照 adj_commission；未结账 = 0）。 */
  adjCommission: number
  commissionAmount: number
}

/** 数据异常提示（只检查三类：回款无业务员 / 运杂费出库无业务员 / 回款缺收款日期）。 */
export interface MonthlyCommissionIssue {
  type: string
  message: string
  count: number
  amount: number
}

export interface MonthlyCommissionSummary {
  startDate: string
  endDate: string
  /** 期间显示标签（如 2026-08-01~2026-08-31）。 */
  period: string
  previousStartDate: string
  previousEndDate: string
  previousPeriod: string
  currentMonthSalesAmount: number
  dealerSalesAmount: number
  retailSalesAmount: number
  currentMonthReceiptAmount: number
  dealerReceiptAmount: number
  retailReceiptAmount: number
  /** 期间销售出库主单的氚云「已收款金额」合计。 */
  totalCollectedAmount: number
  /** 期间销售出库主单的氚云「未收款金额」合计。 */
  totalUncollectedAmount: number
  /** 收款占比 0~1（氚云已收 / (氚云已收 + 氚云未收)）。 */
  collectionRate: number
  previousMonthFreightAmount: number
  dealerFreightAmount: number
  retailFreightAmount: number
  commissionBase: number
  /** 正常提成合计（已结账期间 = 版本快照 Σ base_commission）。 */
  totalBaseCommission: number
  /** 修正提成合计（已结账期间 = 版本快照 Σ adj_commission；未结账 = 0）。 */
  totalAdjCommission: number
  commissionAmount: number
  employeeCount: number
  items: MonthlyCommissionRow[]
  issues: MonthlyCommissionIssue[]
  /** 本次查询实际采用的运费范围模式（G4）。 */
  freightPeriodMode?: string
  generatedAt: string
  /** 该期间是否已核算结账：为 true 时回款/运杂费/比例/提成均以核算版本快照为准，比例调整不回算。 */
  settled: boolean
  settledVersionId: number | null
  settledVersionNo: string | null
  settledAt: string | null
  /** 按当前查询窗口中的 ACTIVE 来源累计，不受日期范围重叠或补充版本影响。 */
  settlement: {
    versionCount: number
    sourceLineCount: number
    settledBaseCommission: number
    settledAdjustmentCommission: number
    settledCommission: number
    paidCommission: number
    unpaidCommission: number
    items: Array<{
      employeeId: number
      employeeName: string
      settledBaseCommission: number
      settledAdjustmentCommission: number
      settledCommission: number
      paidCommission: number
      unpaidCommission: number
    }>
  }
}

/** 本月收款明细行（来自销售出库收款子表，按收款日期归属本月；customerType=DEALER 经销商 / RETAIL 零售）。 */
export interface MonthlyReceiptRow {
  outboundId: number
  outboundNo: string
  customerName: string | null
  receiptDate: string | null
  amount: number
  customerType: 'DEALER' | 'RETAIL'
  rate: number
  commission: number
}

/** 上月运杂费明细行（来自销售出库 freight，按发货日期归属上月；customerType=DEALER 经销商 / RETAIL 零售）。 */
export interface MonthlyFreightRow {
  outboundId: number
  outboundNo: string
  customerName: string | null
  shipDate: string | null
  businessDate: string | null
  freight: number
  customerType: 'DEALER' | 'RETAIL'
}

export interface MonthlyCommissionDetail {
  employeeId: number
  employeeName: string
  startDate: string
  endDate: string
  /** 期间显示标签（如 2026-08-01~2026-08-31）。 */
  period: string
  previousStartDate: string
  previousEndDate: string
  previousPeriod: string
  /** 本次查询实际采用的运费范围模式（G4）。 */
  freightPeriodMode?: string
  currentMonthSalesAmount: number
  dealerSalesAmount: number
  retailSalesAmount: number
  currentMonthReceiptAmount: number
  dealerReceiptAmount: number
  retailReceiptAmount: number
  /** 期间销售出库主单的氚云「已收款金额」合计。 */
  collectedAmount: number
  dealerCollectedAmount: number
  retailCollectedAmount: number
  /** 期间销售出库主单的氚云「未收款金额」合计。 */
  uncollectedAmount: number
  /** 收款占比 0~1（氚云已收 / (氚云已收 + 氚云未收)）。 */
  collectionRate: number
  receipts: MonthlyReceiptRow[]
  previousMonthFreightAmount: number
  dealerFreightAmount: number
  retailFreightAmount: number
  freights: MonthlyFreightRow[]
  commissionBase: number
  dealerRate: number
  retailRate: number
  dealerCommission: number
  retailCommission: number
  commissionAmount: number
  /** 该期间是否已核算结账：为 true 时明细比例以核算版本快照为准。 */
  settled: boolean
  settledVersionNo: string | null
  settledAt: string | null
}

/** 提成比例配置行（employeeId 为 null 表示默认规则：未单独配置的业务员）。 */
export interface MonthlyCommissionRate {
  id: number
  employeeId: number | null
  employeeName: string
  dealerRate: number
  retailRate: number
  remark: string | null
  createdAt: string
  updatedAt: string
}

/** 核算汇总（日期范围 [startDate, endDate]，含两端）。 */
export async function fetchMonthlyCommissionSummary(startDate: string, endDate: string, freight?: FreightRangeParams | null) {
  return (await http.get<MonthlyCommissionSummary>('/monthly-commissions/summary',
    { params: withFreightRange({ startDate, endDate }, freight) })).data
}

/** 指定业务员某日期范围的核算明细（期间收款明细 + 上期运杂费明细）。 */
export async function fetchMonthlyCommissionDetail(employeeId: number, startDate: string, endDate: string, freight?: FreightRangeParams | null) {
  return (await http.get<MonthlyCommissionDetail>(`/monthly-commissions/${employeeId}`,
    { params: withFreightRange({ startDate, endDate }, freight) })).data
}

/** 提成比例配置列表（默认规则在前）。 */
export async function fetchMonthlyCommissionRates() {
  return (await http.get<MonthlyCommissionRate[]>('/monthly-commissions/rates')).data
}

/** 新增业务员提成比例配置。 */
export async function createMonthlyCommissionRate(payload: { employeeId: number; dealerRate: number; retailRate: number; remark?: string | null }) {
  return (await http.post<MonthlyCommissionRate>('/monthly-commissions/rates', payload)).data
}

/** 修改提成比例配置（含默认规则行）。 */
export async function updateMonthlyCommissionRate(id: number, payload: { dealerRate: number; retailRate: number; remark?: string | null }) {
  return (await http.put<MonthlyCommissionRate>(`/monthly-commissions/rates/${id}`, payload)).data
}

/** 删除业务员提成比例配置（默认规则行不可删除）。 */
export async function deleteMonthlyCommissionRate(id: number) {
  return (await http.delete<void>(`/monthly-commissions/rates/${id}`)).data
}

// ==================== 核算版本（2026-08-31 财务口径：单据级变化跟踪 + 手动核算结账） ====================

/** 核算版本行（财务手动「核算结账」生成：期间正常提成 + 修正提成）。 */
export interface CommissionCalculationVersion {
  id: number
  /** 期间显示标签（如 2026-08-01~2026-08-31）。 */
  period: string
  startDate: string | null
  endDate: string | null
  versionNo: string
  remark: string | null
  status: string
  calculatedAt: string
  createdByName: string | null
  /** 结算类型：NORMAL=正常核算 / SUPPLEMENT=补充核算 / RECALCULATION=作废后重新核算。 */
  settlementType?: 'NORMAL' | 'SUPPLEMENT' | 'RECALCULATION' | string | null
  /** 本次实际采用的运费窗口（含端点）。 */
  freightStartDate?: string | null
  freightEndDate?: string | null
  freightPeriodMode?: 'PREVIOUS_CALENDAR_MONTH' | 'PREVIOUS_EQUAL_PERIOD' | 'CUSTOM' | string | null
  /** 发放状态：PAID=已发放（不可作废）。 */
  paidFlag?: 'UNPAID' | 'PAID' | string | null
  paidBy?: number | null
  paidAt?: string | null
  voidReason?: string | null
  voidBy?: number | null
  voidAt?: string | null
  employeeCount: number
  totalBaseCommission: number
  totalAdjCommission: number
  totalCommission: number
  /** 是否可作废：FINAL 且未发放版本可作废（释放其独占的来源明细）。 */
  voidable?: boolean
}

/** 核算版本明细行（每业务员一行：正常提成 + 修正提成 + 应发合计）。 */
export interface CommissionCalculationItem {
  versionId: number
  employeeId: number
  employeeName: string
  dealerRate: number
  retailRate: number
  baseReceiptDealer: number
  baseReceiptRetail: number
  baseFreightDealer: number
  baseFreightRetail: number
  baseCommission: number
  adjReceiptDealer: number
  adjReceiptRetail: number
  adjFreightDealer: number
  adjFreightRetail: number
  adjCommission: number
  totalCommission: number
  /** 结账时冻结的氚云销售出库考核指标。 */
  salesAmountSnapshot: number
  receivedAmountSnapshot: number
  outstandingAmountSnapshot: number
  collectionRateSnapshot: number
  /** G7：该业务员本批来源是否跨比例段（>1 段时 true），rateSegments 为逐段明细（仅版本详情返回）。 */
  multiRateSegments?: boolean
  rateSegments?: Array<{
    effectiveFrom: string
    effectiveTo: string | null
    dealerRate: number | null
    retailRate: number | null
    sourceLineCount: number
  }>
}

export interface CommissionCalculationSourceLine {
  id: number
  employeeId: number
  employeeName: string
  /** RECEIPT=收款明细 / FREIGHT=运杂费明细 / ADJUSTMENT=修正变化（独立并入部分）。 */
  lineType: 'RECEIPT' | 'FREIGHT' | 'ADJUSTMENT'
  sourceId: number
  outboundId: number
  outboundNo: string | null
  customerName: string | null
  occurrenceDate: string
  channelType: 'DEALER' | 'RETAIL'
  /** 渠道判定来源：DEALER_FIELD=填了经销商 / MARGIN_FALLBACK=毛利兜底 / DEFAULT_RETAIL=默认终端；ADJUSTMENT 行为空。 */
  channelSource?: 'DEALER_FIELD' | 'MARGIN_FALLBACK' | 'DEFAULT_RETAIL' | null
  amount: number
  appliedRate: number
  commissionContribution: number
  status: 'ACTIVE' | 'RELEASED'
}

/** 核算版本明细 + 版本汇总。 */
export interface CommissionCalculationVersionDetail extends CommissionCalculationVersion {
  items: CommissionCalculationItem[]
  sourceLines: CommissionCalculationSourceLine[]
  sourceLineCount: number
  assessmentLines: Array<{
    outboundId: number
    outboundNo: string | null
    customerName: string | null
    employeeId: number
    employeeName: string
    attributionDate: string
    salesAmount: number
    receivedAmount: number
    outstandingAmount: number
    paymentStatus: string | null
    channelType: 'DEALER' | 'RETAIL'
  }>
  assessmentLineCount: number
}

/** 核算结账结果（POST /versions）。 */
export interface CommissionCalculationResult {
  versionId: number
  versionNo: string
  period: string
  startDate: string
  endDate: string
  calculatedAt: string
  employeeCount: number
  totalBaseCommission: number
  totalAdjCommission: number
  totalCommission: number
  settlementType?: string
  freightPeriodMode?: string
  freightStartDate?: string
  freightEndDate?: string
  freightReason?: string | null
  processedChanges: number
  settledSourceLines: number
  settledAdjustmentLines?: number
  settledReceiptLines: number
  settledFreightLines: number
}

/** 结账前检查项（P3 检查引擎）：blocking=阻断（任一中招后端拒绝核算），warnings=仅提示不阻断。 */
export interface CommissionSettlementCheck {
  code: string
  message: string
  count: number
  amount: number
}

export interface CommissionSettlementPreview {
  startDate: string
  endDate: string
  freightStartDate: string
  freightEndDate: string
  freightPeriodMode?: string
  freightReason?: string | null
  totalReceiptLines: number
  settledReceiptLines: number
  pendingReceiptLines: number
  totalReceiptAmount: number
  pendingReceiptAmount: number
  totalFreightLines: number
  settledFreightLines: number
  pendingFreightLines: number
  totalFreightAmount: number
  pendingFreightAmount: number
  pendingBaseCommission: number
  pendingAdjustmentCommission: number
  pendingCommission: number
  salesAmountSnapshot: number
  receivedAmountSnapshot: number
  outstandingAmountSnapshot: number
  employeeCount: number
  /** 按业务员汇总的本次待核算来源。 */
  items?: Array<{
    employeeId: number
    employeeName: string
    baseCommission: number
    adjustmentCommission: number
    commissionAmount: number
    salesAmountSnapshot?: number
    receivedAmountSnapshot?: number
    outstandingAmountSnapshot?: number
    collectionRateSnapshot?: number
  }>
  /** P3 阻断清单（结账确认前必须清零，否则确认按钮禁用且后端再次拒绝）。 */
  blocking?: CommissionSettlementCheck[]
  /** P3 警告清单（仅提示，可展开明细）。 */
  warnings?: CommissionSettlementCheck[]
}

/** 已生效出库单变化明细行（单据级：哪张单被修正、前后值、Δ收款/Δ运杂费、Δ提成）。 */
export interface CommissionOutboundChange {
  id: number
  batchId: number
  outboundId: number
  outboundNo: string
  customerName: string | null
  /** 回款变化归属的收款日期（该单最近收款日期，结账期间过滤用）。 */
  receiptDate: string | null
  /** 运杂费变化归属的发货日期（上期运杂费窗口过滤用）。 */
  shipDate: string | null
  employeeId: number
  employeeName: string
  /** 业务员变更前归属（换人修正审计；追回/补给记录时非空）。 */
  prevEmployeeId: number | null
  prevEmployeeName: string | null
  recordedAt: string
  source: string
  beforeReceiptDealer: number
  afterReceiptDealer: number
  beforeReceiptRetail: number
  afterReceiptRetail: number
  beforeFreightDealer: number
  afterFreightDealer: number
  beforeFreightRetail: number
  afterFreightRetail: number
  dealerRate: number
  retailRate: number
  changeCommission: number
  remark: string | null
  status: 'PENDING' | 'PROCESSED'
  versionId: number | null
}

/** 核算版本对比行（两版金额 + 差额 diff = 新版 − 旧版）。 */
export interface CommissionVersionCompareItem {
  employeeId: number
  employeeName: string
  baseCommissionNew: number
  baseCommissionOld: number
  baseCommissionDiff: number
  adjCommissionNew: number
  adjCommissionOld: number
  adjCommissionDiff: number
  totalCommissionNew: number
  totalCommissionOld: number
  totalCommissionDiff: number
  adjReceiptNew: number
  adjReceiptOld: number
  adjReceiptDiff: number
  adjFreightNew: number
  adjFreightOld: number
  adjFreightDiff: number
}

/** 核算版本对比结果。 */
export interface CommissionVersionCompare {
  newerVersion: { id: number; period: string; startDate: string | null; endDate: string | null; versionNo: string; calculatedAt: string }
  olderVersion: { id: number; period: string; startDate: string | null; endDate: string | null; versionNo: string; calculatedAt: string }
  totalBaseCommissionDiff: number
  totalAdjCommissionDiff: number
  totalCommissionDiff: number
  employeeCount: number
  items: CommissionVersionCompareItem[]
}

/** 核算版本列表（全部版本，按核算时间倒序）。 */
export async function fetchCommissionCalculationVersions() {
  return (await http.get<CommissionCalculationVersion[]>('/monthly-commissions/versions')).data
}

export async function fetchCommissionSettlementPreview(startDate: string, endDate: string, freight?: FreightRangeParams | null) {
  return (await http.get<CommissionSettlementPreview>('/monthly-commissions/versions/preview', {
    params: withFreightRange({ startDate, endDate }, freight),
  })).data
}

/** 核算版本明细（业务员行 + 版本汇总）。 */
export async function fetchCommissionCalculationVersionDetail(versionId: number) {
  return (await http.get<CommissionCalculationVersionDetail>(`/monthly-commissions/versions/${versionId}`)).data
}

/** 财务手动「核算结账」：按所选日期范围生成核算版本 = 期间正常提成 + 未处理修正变化（可带运费范围参数）。 */
export async function calculateCommissionVersion(
  startDate: string,
  endDate: string,
  remark?: string | null,
  intent?: string,
  freight?: FreightRangeParams | null
) {
  return (await http.post<CommissionCalculationResult>(
    '/monthly-commissions/versions',
    withFreightRange({ startDate, endDate, remark, intent }, freight)
  )).data
}

/** 两个核算版本逐业务员对比（newer 新版本在前，diff = newer − older）。 */
export async function compareCommissionVersions(newer: number, older: number) {
  return (await http.get<CommissionVersionCompare>('/monthly-commissions/versions/compare', { params: { newer, older } })).data
}

/** 作废核算版本（原因必填；已发放版本不可作废；作废后其并入的修正变化回滚为待核算，可重新结账）。 */
export async function voidCommissionCalculationVersion(versionId: number, reason: string) {
  return (await http.post<{ versionId: number; versionNo: string; status: string; voidReason: string; rolledBackChanges: number; releasedSourceLines: number }>(
    `/monthly-commissions/versions/${versionId}/void`, { reason }
  )).data
}

/** 标记核算版本提成已发放：发放后该版本不可作废（审计留痕）。 */
export async function markCommissionVersionPaid(versionId: number) {
  return (await http.post<{ versionId: number; versionNo: string; paidFlag: 'PAID' }>(
    `/monthly-commissions/versions/${versionId}/mark-paid`
  )).data
}

/** 核算版本 CSV 导出（P4 G8）：items=提成发放汇总 / lines=核算来源明细 / adjustments=修正调整明细（Excel 兼容 UTF-8 BOM）。 */
export async function exportCommissionVersionCsv(versionId: number, kind: 'items' | 'lines' | 'adjustments') {
  return (await http.get<Blob>(`/monthly-commissions/versions/${versionId}/export/${kind}.csv`, { responseType: 'blob' })).data
}

/** 已生效出库单变化明细（可选按状态 PENDING/PROCESSED / 导入批次过滤，支持 limit/offset 分页），单据级。 */
export async function fetchCommissionOutboundChanges(status?: string, batchId?: number, limit?: number, offset?: number) {
  return (await http.get<CommissionOutboundChange[]>('/monthly-commissions/changes', { params: { status, batchId, limit, offset } })).data
}

/** 变化数量（可选按状态 PENDING/PROCESSED 过滤）：待核算徽标用，避免拉全量明细。 */
export async function fetchCommissionOutboundChangeCount(status?: string) {
  return (await http.get<number>('/monthly-commissions/changes/count', { params: { status } })).data
}
