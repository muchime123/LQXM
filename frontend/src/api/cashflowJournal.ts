import { http } from './http'

export interface CashflowAccount {
  id: number
  accountName: string
  currency: string
  openingBalance: number | null
  income: number
  expense: number
  netFlow: number
  closingBalance: number | null
  entryCount: number
  /** 期间余额核对：只回答「期初+收支」能否算出可信期末，是账户级结论。 */
  closingStatus: 'OK' | 'OPENING_MISSING'
  closingStatusLabel: string
  /** 逐笔来源结存核对：明细级结论，有异常不代表期间余额不可信。 */
  sourceBalanceStatus: 'DETAIL_OK' | 'DETAIL_EXCEPTIONS'
  sourceBalanceExceptionCount: number
  /** 期间内参与核对的相邻链接数（0 断点账户也有值）。 */
  sourceBalanceCheckedLinks: number
  sourceBalanceReviewStart: string | null
  sourceBalanceReviewEnd: string | null
  /** 人工对账维度：P1 恒为 NOT_STARTED（未人工对账），与上面两个计算维度互相独立。 */
  manualReconcileStatus: 'NOT_STARTED' | string
  manualReconcileStatusLabel: string
  /** 结论陈述：只说「能否计算 / 是否衔接」，不代表财务已核对。 */
  reconcileSummary: string
}

export interface CashflowReportSummary {
  closingComputableCount: number
  closingBlockedCount: number
  sourceBalanceExceptionCount: number
  sourceBalanceCheckedLinks: number
  sourceBalanceAffectedAccounts: number
  /** 已完成人工对账的账户数。P1 恒为 0。 */
  manualReconciledCount: number
  /** 尚未人工对账的账户数。 */
  manualReconcilePendingCount: number
}

export interface CashflowReport {
  items: CashflowAccount[]
  totals: CashflowCurrencyTotal[]
  start: string
  end: string
  summary: CashflowReportSummary
}

export interface CashflowCurrencyTotal {
  currency: string
  count: number
  income: number
  expense: number
  closing: number
  unknownOpening: boolean
}

export interface CashflowMonthlyRow {
  month: number
  openingBalance: number | null
  income: number
  expense: number
  netFlow: number
  closingBalance: number | null
  entryCount: number
}

export interface CashflowMonthlyReport {
  accountId: number
  accountName: string
  currency: string
  items: CashflowMonthlyRow[]
}

export interface CashflowEntry {
  id: number
  account_name: string
  entry_date: string
  summary: string | null
  supplier_raw: string | null
  counterparty_raw: string | null
  reimbursed_by: string | null
  invoiced: string | null
  income: number | null
  expense: number | null
  balance: number | null
  fx_rate: number | null
}

export interface CashflowIssue {
  /** 前端列表行键（后端不返回，由页面拼接；同一 entry 可能命中多类异常）。 */
  issueKey?: string
  accountId: number
  accountName: string
  entryId: number | null
  entryDate: string | null
  issueCode: string
  detail: string
  /** SOURCE_BALANCE_BREAK 才有：断点期望值 / 实际值 / 差额 */
  expectedBalance?: string | null
  actualBalance?: string | null
  gap?: string | null
  rangeStart?: string | null
  rangeEnd?: string | null
  severity?: 'REVIEW' | string
}

export interface CashflowReviewBatch {
  id: number
  fileName: string
  status: string
  totalCount: number
  createdAt: string
}

export interface CashflowReviewRow {
  kind: 'OLD_ONLY' | 'NEW_ONLY'
  key: string
  accountName: string
  sourceRowNumber: string | null
  entryDate: string | null
  summary: string | null
  income: string | number | null
  expense: string | number | null
  balance: string | number | null
}

export interface CashflowReviewDiff {
  batchId: number
  status: string
  items: CashflowReviewRow[]
  total: number
  oldOnlyCount: number
  newOnlyCount: number
  accounts: string[]
  stagingAvailable: boolean
}



export async function fetchCashflowReport(start: string, end: string) {
  return (await http.get<CashflowReport>('/cashflow-journal/accounts', { params: { start, end } })).data
}

export async function fetchCashflowMonthly(accountId: number) {
  return (await http.get<CashflowMonthlyReport>(`/cashflow-journal/accounts/${accountId}/monthly`)).data
}

export async function fetchCashflowEntries(accountId: number | null, start: string, end: string, page: number, size = 30, keyword = '') {
  return (await http.get<{ items: CashflowEntry[]; total: number }>('/cashflow-journal/entries', {
    params: { accountId: accountId ?? undefined, start, end, page, size, keyword: keyword || undefined }
  })).data
}

export async function fetchCashflowIssues(start: string, end: string, accountId: number | null = null) {
  return (await http.get<{ items: CashflowIssue[]; total: number; detailCheckedLinks: number }>('/cashflow-journal/issues', {
    params: { start, end, accountId: accountId ?? undefined }
  })).data
}

export async function fetchCashflowReviewBatches() {
  return (await http.get<{ items: CashflowReviewBatch[] }>('/cashflow-journal/review-batches')).data
}

export async function fetchCashflowReviewDiff(batchId: number, account?: string, page = 1, size = 50) {
  return (await http.get<CashflowReviewDiff>(`/cashflow-journal/review-batches/${batchId}/diff`, {
    params: { account: account || undefined, page, size }
  })).data
}


