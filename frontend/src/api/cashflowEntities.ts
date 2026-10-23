// 资金归属主体与账户管理（P2-A，2026-10-23）
import { http } from './http'

export type EntityType = 'COMPANY' | 'INDIVIDUAL' | 'OTHER'
export type AccountStatus = 'ACTIVE' | 'INACTIVE'

export interface CashflowEntity {
  id: number
  name: string
  entityType: EntityType
  status: 'ACTIVE' | 'INACTIVE'
  note: string | null
  createdAt?: string
  updatedAt?: string
  accountCount: number
}

export interface EntitiesResponse {
  items: CashflowEntity[]
  unassignedCount: number
  totalAccounts: number
}

export interface SuggestItem {
  accountName: string
  suggestedEntityName: string | null
  groupSize: number
}

export interface SuggestResponse {
  items: SuggestItem[]
  suggestedCount: number
  unrecognizedCount: number
}

export async function fetchCashflowEntities() {
  return (await http.get<EntitiesResponse>('/cashflow/entities')).data
}

export async function createCashflowEntity(data: { name: string; entityType: EntityType; note?: string }) {
  return (await http.post<CashflowEntity>('/cashflow/entities', data)).data
}

export async function updateCashflowEntity(
  id: number,
  data: { name?: string; entityType?: EntityType; status?: 'ACTIVE' | 'INACTIVE'; note?: string },
) {
  return (await http.put<{ updated: number }>(`/cashflow/entities/${id}`, data)).data
}

export interface CashflowAccountLite {
  id: number
  accountName: string
  currency: string
  entityId: number | null
  entityName: string | null
  status: AccountStatus
}

export async function fetchCashflowAccounts() {
  return (await http.get<CashflowAccountLite[]>('/cashflow/accounts')).data
}

export async function fetchCashflowEntitySuggestions() {
  return (await http.get<SuggestResponse>('/cashflow/entities/suggest')).data
}

export async function assignCashflowEntities(assignments: { accountId: number; entityId: number | null }[]) {
  return (await http.post<{ assigned: number; cleared: number }>('/cashflow/accounts/assign-entity', { assignments })).data
}

export async function setCashflowAccountStatus(id: number, status: AccountStatus) {
  return (await http.put<{ updated: number; status: AccountStatus }>(`/cashflow/accounts/${id}/status`, { status })).data
}

export async function deleteCashflowEntity(id: number) {
  return (await http.delete<{ deleted: number }>(`/cashflow/entities/${id}`)).data
}
