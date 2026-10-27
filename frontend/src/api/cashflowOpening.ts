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

export interface OpeningCorrectionPreview {
  openingId: number
  accountId: number
  accountName: string
  currency: string
  fiscalYear: number
  oldAmount: string
  newAmount: string
  balanceDelta: string
  affectedFrom: string
  affectedThrough: string
  wouldResetFinanceConfirmation: boolean
  applied: false
}

export interface OpeningCorrection {
  id: number
  openingId: number
  oldAmount: string
  newAmount: string
  originalFinanceConfirmed: boolean | number
  originalConfirmationId: number | null
  reason: string
  evidenceRef: string
  requestedByName: string
  requestedAt: string
  appliedByName: string | null
  appliedAt: string | null
  status: 'PENDING' | 'APPLIED'
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

export async function fetchOpeningCorrectionPreview(openingId: number, newAmount: string): Promise<OpeningCorrectionPreview> {
  const { data } = await http.get<OpeningCorrectionPreview>(`/cashflow/accounts/opening/${openingId}/correction-preview`, {
    params: { newAmount }
  })
  return data
}

export async function fetchOpeningCorrections(openingId: number): Promise<OpeningCorrection[]> {
  const { data } = await http.get<OpeningCorrection[]>(`/cashflow/accounts/opening/${openingId}/corrections`)
  return data
}

export async function requestOpeningCorrection(openingId: number, body: {
  expectedOldAmount: string; newAmount: string; reason: string; evidenceRef: string
}, requestKey: string): Promise<OpeningCorrection> {
  const { data } = await http.post<OpeningCorrection>(`/cashflow/accounts/opening/${openingId}/corrections`, body,
    { headers: { 'X-Request-Key': requestKey } })
  return data
}

export async function applyOpeningCorrection(correctionId: number, requestKey: string): Promise<OpeningCorrection> {
  const { data } = await http.post<OpeningCorrection>(`/cashflow/accounts/opening/corrections/${correctionId}/apply`, {},
    { headers: { 'X-Request-Key': requestKey } })
  return data
}
