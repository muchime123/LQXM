import { http } from '../http'

export interface DingtalkStats {
  totalClaims: number
  thisMonthApproved: number
  totalRuns: number
  failedRuns: number
}

export interface DingtalkStatus {
  enabled: boolean
  configured: boolean
  /** 连通性：真实调用 /gettoken 成功才为 true（不再是仅配置齐全即"可连"） */
  connected: boolean
  /** 连通性失败原因；仅 connected=false 时非空，成功时为 null */
  connectError: string | null
  appKeyMasked: string
  lastSyncStatus: string | null
  lastSyncAt: string | null
  stats: DingtalkStats
  // ====== 诊断字段 ======
  processCodeMasked: string
  useV2Api: boolean
  accessTokenStatus: 'OK' | 'EMPTY' | 'FAIL' | 'N/A' | string
  accessTokenMasked: string | null
  apiVersionHint: string | null
}

export interface SyncRunRow {
  id: number
  syncType: string
  status: string
  triggeredBy: string
  triggeredByName: string | null
  startedAt: string
  finishedAt: string | null
  durationMs: number | null
  fetchedCount: number
  createdCount: number
  updatedCount: number
  unchangedCount: number
  failedCount: number
  mappingMissed: number
  deptMappingsCreated: number
  deptMappingsUpgraded: number
  errorSummary: string | null
  rangeDateFrom: string | null
  rangeDateTo: string | null
}

export interface SyncRunDetail {
  summary: SyncRunRow
  errorDetails: string | null
}

export interface SyncTriggered {
  runId: number
  message: string
}

// 同步运行状态（与 DingtalkSyncRun 枚举对齐）
export const SYNC_STATUS_OPTIONS: Record<string, { label: string; type: string }> = {
  RUNNING:   { label: '执行中', type: 'warning' },
  SUCCESS:   { label: '全部成功', type: 'success' },
  PARTIAL:   { label: '部分成功', type: 'warning' },
  FAILED:    { label: '失败', type: 'danger' },
  SKIPPED:   { label: '跳过', type: 'info' }
}

export const TRIGGER_OPTIONS: Record<string, string> = {
  MANUAL: '手动',
  SCHEDULED: '定时',
  API: '外部触发',
  EXCEL_IMPORT: 'Excel 导入'
}

// 状态卡片
export function fetchDingtalkStatus() {
  return http.get<DingtalkStatus>('/integrations/dingtalk/status').then(r => r.data)
}

// 触发同步（503 时由 http 拦截器或调用方 toast）；daysBack 指定本次回溯天数，为空用后端配置默认
export function triggerReimbursementSync(daysBack?: number) {
  return http.post<SyncTriggered>('/integrations/dingtalk/sync/reimbursements', null, {
    params: daysBack ? { daysBack } : undefined
  }).then(r => r.data)
}

// 同步记录分页
export function fetchSyncRuns(params?: { page?: number; size?: number }) {
  return http.get<{ items: SyncRunRow[]; total: number; page: number; size: number }>('/integrations/dingtalk/sync-runs', { params }).then(r => r.data)
}

// 单次同步详情（含完整错误明细）
export function fetchSyncRunDetail(id: number) {
  return http.get<SyncRunDetail>(`/integrations/dingtalk/sync-runs/${id}`).then(r => r.data)
}
