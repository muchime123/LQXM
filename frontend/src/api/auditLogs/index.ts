import { http } from '../http'

/**
 * 操作审计日志 API。
 * 后端前缀：/api/audit-logs（系统管理 → 操作日志）
 */

export interface AuditLogItem {
  id: number
  entityType: string
  entityId: number
  modelName: string | null
  action: string
  /** 变更摘要（JSON 或文字），批量操作可能很长 */
  changes: string | null
  createdAt: string
}

/** Spring Page 序列化结构 */
export interface AuditLogPage {
  content: AuditLogItem[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface AuditLogFacets {
  entityTypes: string[]
  actions: string[]
}

export interface AuditLogQuery {
  entityType?: string
  action?: string
  dateFrom?: string
  dateTo?: string
  keyword?: string
  /** 0-based 页码 */
  page: number
  size: number
}

export async function fetchAuditLogs(params: AuditLogQuery) {
  return (await http.get<AuditLogPage>('/audit-logs', { params })).data
}

export async function fetchAuditLogFacets() {
  return (await http.get<AuditLogFacets>('/audit-logs/facets')).data
}
