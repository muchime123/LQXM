import { http } from '../http'

/**
 * ERP 行级变更日志 API。
 * 后端前缀：/api/erp-change-logs（系统管理 → 操作日志 → ERP 变更）
 *
 * 与 auditLogs 的区别：这里以「被改动的表」作身份（targetTable，schema 限定表名），
 * 且每条都带操作人、幂等请求键与变更原因 —— 而 audit_logs 只有自由字符串 entityType。
 * ERP 业务（销售出库、制造域）自 2026-09-21 起统一只写这里。
 */

export interface ErpChangeLogItem {
  id: number
  /** 被改动的表，schema 限定名，如 product_archive.erp_sales_outbounds */
  targetTable: string
  targetId: number
  /** INSERT / UPDATE / DELETE */
  action: string
  /** 改前改后（JSON 文本） */
  changedFields: string | null
  /** 结构化原因码，受控流程如调整/冲销必填 */
  reasonCode: string | null
  /** 自然语言原因 */
  changeReason: string | null
  operatorId: number | null
  operatorName: string | null
  /** 幂等请求键 */
  requestKey: string | null
  traceId: string | null
  changedAt: string | null
}

/** 与后端手写分页结构对齐（非 Spring Page 完整结构） */
export interface ErpChangeLogPage {
  content: ErpChangeLogItem[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface ErpChangeLogFacets {
  targetTables: string[]
  actions: string[]
  operatorNames: string[]
}

export interface ErpChangeLogQuery {
  targetTable?: string
  action?: string
  operatorName?: string
  keyword?: string
  dateFrom?: string
  dateTo?: string
  /** 0-based 页码 */
  page: number
  size: number
}

export async function fetchErpChangeLogs(params: ErpChangeLogQuery) {
  return (await http.get<ErpChangeLogPage>('/erp-change-logs', { params })).data
}

export async function fetchErpChangeLogFacets() {
  return (await http.get<ErpChangeLogFacets>('/erp-change-logs/facets')).data
}
