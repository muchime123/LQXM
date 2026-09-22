import { http } from '../http'

export interface FollowUpState {
  plannedStartDate: string | null
  expectedFinishDate: string | null
  delayReason: string
  completedDate: string | null
  warehousedDate: string | null
  shippedDate: string | null
  remark: string
  stages: Record<string, string | null>
}
export interface FollowUpOrder {
  id: number; workOrderNo: string; productName: string; moldLayout?: string; boxSize?: string
  requiredQuantity?: number; productionLine?: string; salespersonName?: string; customerName?: string
  status?: string; requiredCompletionDate?: string; actualCompletionDate?: string
  sourceRequiredCompletionDate?: string
  completedQuantity?: number; warehousedQuantity?: number; productionProgress?: string; warehousingProgress?: string
  applicationDate?: string; lastSyncedAt?: string
  productionRequirement?: string; productionCompleted?: boolean; warehousingCompleted?: boolean
  followupId?: number; version: number; updatedByName?: string; updatedAt?: string
  state: FollowUpState; stageNames: string[]
}
export interface FollowUpLog {
  id: number; version: number; content: string; before: FollowUpState; after: FollowUpState
  createdByName: string; createdAt: string
}
export interface SourceComponent {
  sequenceNo?: number; componentName?: string; requiredQuantity?: number; completedQuantity?: number
  completionRatio?: string; completedAt?: string; completed?: boolean
}
export interface FollowUpDetail { order: FollowUpOrder; logs: FollowUpLog[]; logCount: number; sourceComponents?: SourceComponent[] }
export const fetchFollowUps = async (params: Record<string, unknown>) =>
  (await http.get<{ items: FollowUpOrder[]; total: number }>('/production-followups', { params })).data
export const fetchFollowUpWorkshops = async () => (await http.get<string[]>('/production-followups/workshops')).data
export const fetchFollowUp = async (id: number) => (await http.get<FollowUpDetail>(`/production-followups/${id}`)).data
export const saveFollowUp = async (id: number, payload: { version: number; state: FollowUpState; content: string }, key: string) =>
  (await http.put<{ version: number }>(`/production-followups/${id}`, payload, { headers: { 'X-Request-Key': key } })).data
