import { http } from './http'

export interface CashflowOpening {
  openingId: number | null
  accountId: number
  accountName: string
  accountStatus: string
  fiscalYear: number
  amount: number | null
  source: string | null
  openingStatus: 'MISSING' | 'CANDIDATE' | 'ACTIVE'
  financeConfirmed: boolean | number | null
  confirmedBy: number | null
  confirmedAt: string | null
}

export interface OpeningConfirmation {
  id: number
  openingAmountSnapshot: number
  evidenceType: string
  balanceAsOfDate: string
  verifiedBalance: number
  evidenceRef: string
  note: string
  confirmedByName: string
  confirmedAt: string
}

export interface ConfirmOpeningRequest {
  evidenceType: 'BANK_STATEMENT' | 'CASH_COUNT' | 'OTHER'
  balanceAsOfDate: string
  verifiedBalance: string
  evidenceRef: string
  note: string
}

export async function fetchCashflowOpening(accountId: number, year: number): Promise<CashflowOpening> {
  const { data } = await http.get<CashflowOpening>(`/cashflow/accounts/${accountId}/opening/${year}`)
  return data
}

export async function fetchOpeningConfirmations(openingId: number): Promise<OpeningConfirmation[]> {
  const { data } = await http.get<OpeningConfirmation[]>(`/cashflow/accounts/opening/${openingId}/confirmations`)
  return data
}

export async function confirmCashflowOpening(openingId: number, request: ConfirmOpeningRequest, requestKey: string): Promise<void> {
  await http.post(`/cashflow/accounts/opening/${openingId}/finance-confirm`, request, {
    headers: { 'X-Request-Key': requestKey }
  })
}
