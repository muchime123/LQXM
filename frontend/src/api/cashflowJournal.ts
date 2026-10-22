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
  accountId: number
  accountName: string
  entryId: number | null
  entryDate: string | null
  issueCode: string
  detail: string
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
  return (await http.get<{ items: CashflowAccount[]; totals: CashflowCurrencyTotal[] }>('/cashflow-journal/accounts', { params: { start, end } })).data
}

export async function fetchCashflowMonthly(accountId: number) {
  return (await http.get<CashflowMonthlyReport>(`/cashflow-journal/accounts/${accountId}/monthly`)).data
}

export async function fetchCashflowEntries(accountId: number, start: string, end: string, page: number, size = 30) {
  return (await http.get<{ items: CashflowEntry[]; total: number }>('/cashflow-journal/entries', {
    params: { accountId, start, end, page, size }
  })).data
}

export async function fetchCashflowIssues(start: string, end: string) {
  return (await http.get<{ items: CashflowIssue[]; total: number }>('/cashflow-journal/issues', {
    params: { start, end }
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


