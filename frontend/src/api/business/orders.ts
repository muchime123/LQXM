import { http } from '../http'
import type { BatchProgress, H3FactType, H3LedgerKind, ImportResult, PageResult, ImportBatch, StagingPreview, LedgerPage, StageAccepted, LedgerDetailResult, BatchWarningsPage } from '@/types/h3Import'
import { waitH3ImportUntil } from '@/utils/h3Import'
// 氚云导入共享类型单一来源在 @/types/h3Import，此处 re-export 保持调用方兼容
export type {
  PageResult,
  H3FactType,
  H3LedgerKind,
  ImportRowPreview,
  ImportPreview,
  ImportResult,
  ImportBatch,
  StageAccepted,
  BatchProgress,
  StagingPreview,
  LedgerRow,
  LedgerPage,
  LedgerDetailResult,
  BatchWarningRow,
  BatchWarningsPage,
  H3WarningCode
} from '@/types/h3Import'
// P0-C：预览行扩展金额/客户/业务员 3 字段，便于用户在确认前判断业务含义
function form(file: File) { const data = new FormData(); data.append('file', file); return data }
/** 导入文件可达数万行（解析+哈希比对+落库+自动编排耗时以分钟计），放宽超时到 10 分钟。 */
const IMPORT_TIMEOUT = 600000
// ---- 新异步管线（推荐）：Excel 只解析一次，4 步走 ----
/** Step 1：上传 → 返回 batchId + 后台开始异步解析 */
export async function stageH3Fact(type: H3FactType, file: File) { return (await http.post<StageAccepted>(`/h3-facts/${type}/stage`, form(file), { timeout: Math.min(30000, IMPORT_TIMEOUT) })).data }
/** Step 2：轮询批次解析/导入进度 */
export async function fetchH3BatchProgress(batchId: number) { return (await http.get<BatchProgress>(`/h3-imports/batches/${batchId}/progress`)).data }
/** Step 3：解析完成后读取预览结果（从 DB 暂存读，不再解析 Excel） */
export async function fetchH3StagingPreview(batchId: number) { return (await http.get<StagingPreview>(`/h3-imports/batches/${batchId}/preview`)).data }
/** 统一批次预览原始负载：用于完成后查看 UPDATE 新旧值（事实域为 {preview}，台账域为直接预览）。 */
export async function fetchH3BatchPreview(batchId: number) {
  return (await http.get<StagingPreview | import('@/types/h3Import').LedgerImportPreview>(`/h3-imports/batches/${batchId}/preview`)).data
}
/** Step 4：确认导入 → 从暂存写入事实表（仍可能数分钟，但可继续用 progress 轮询） */
export async function confirmH3Staging(batchId: number) { return (await http.post<ImportResult>(`/h3-facts/import-batches/${batchId}/confirm`, {}, { timeout: IMPORT_TIMEOUT })).data }
/** 工具：直到 status ∈ doneSet 或 FAILED / 超时，轮询 fetchH3BatchProgress。返回最终 progress。 */
export async function waitH3BatchUntil(batchId: number, doneStatuses: string[], opts?: { intervalMs?: number; timeoutMs?: number; onProgress?: (p: BatchProgress) => void }): Promise<BatchProgress> {
  return waitH3ImportUntil(fetchH3BatchProgress, batchId, doneStatuses, opts)
}
export async function fetchImportBatches(params: Record<string, unknown>) { return (await http.get<PageResult<ImportBatch>>('/h3-imports/batches', { params })).data }
/**
 * 批次告警明细（GET /api/h3-imports/batches/{id}/warnings）。
 * 后端按 h3_import_rows.raw_data 重算每行告警（只读），与预览期 warnings 同口径；历史批次同样可查。
 */
export async function fetchBatchWarnings(batchId: number, page = 1, size = 50) {
  return (await http.get<BatchWarningsPage>(`/h3-imports/batches/${batchId}/warnings`, { params: { page, size } })).data
}
export interface H3ResetPreviewRow {
  code: string
  label: string
  count: number
  rebuildable: boolean
  handling: string
}
export interface H3ResetBlocker { code: string; count: number; message: string }
export interface H3ResetPreview {
  generatedAt: string
  recommendedMode: 'SOURCE_REBUILD'
  executionAllowed: boolean
  sourceData: H3ResetPreviewRow[]
  downstreamImpacts: H3ResetPreviewRow[]
  blockers: H3ResetBlocker[]
  message: string
  identityRuleCount?: number
  referencedCommissionVersions?: number
}
export type H3ResetIdentityMode = 'PRESERVE_IDENTITIES' | 'REASSESS_IDENTITIES'
export async function fetchH3ResetPreview() {
  return (await http.get<H3ResetPreview>('/h3-imports/reset/preview')).data
}
export interface H3ResetPreparation {
  runId: number; resetToken: string; status: 'PREPARED'; identityCount: number
  outboundNatureCount: number; ruleSnapshotCount: number; mode: H3ResetIdentityMode; message: string
}
export async function prepareH3Reset(mode: H3ResetIdentityMode) {
  return (await http.post<H3ResetPreparation>('/h3-imports/reset/prepare', { mode })).data
}
export interface H3ResetExecution {
  runId: number
  resetToken: string
  status: 'EXECUTED'
  outboundCount: number
  productionCount: number
  receiptCount: number
  productCount: number
  stockCount: number
  dealerCount: number
  rawRowCount: number
  stagingRowCount: number
  batchCount: number
  mode: H3ResetIdentityMode
  clearedRuleCount: number
  message: string
}
export async function executeH3Reset(resetToken: string) {
  return (await http.post<H3ResetExecution>('/h3-imports/reset/execute', { resetToken })).data
}
export async function cancelH3Reset(resetToken: string) {
  return (await http.post<{ runId: number; status: 'CANCELLED'; message: string }>('/h3-imports/reset/cancel', { resetToken })).data
}
/** 重新打开已确认的客户身份（客户档案详情页使用）。 */
export async function reopenCustomerIdentity(id: number) {
  return (await http.put<{ id: number; unlinkedFacts: number; status: string }>(`/h3-imports/identities/${id}/reopen`)).data
}
export interface IdentityReassignPreview {
  id: number
  entityType: 'CUSTOMER' | 'EMPLOYEE'
  sourceName: string
  oldTargetId: number
  newTargetId: number
  affected: Record<string, number>
  finalCommissionVersions: number
  allowed: boolean
}
export async function fetchIdentityReassignPreview(id: number, targetId: number) {
  return (await http.get<IdentityReassignPreview>(`/h3-imports/identities/${id}/reassign-preview`, { params: { targetId } })).data
}
export async function reassignSourceIdentity(id: number, targetId: number, note?: string) {
  return (await http.put<{ id: number; oldTargetId: number; newTargetId: number; affected: Record<string, number> }>(
    `/h3-imports/identities/${id}/reassign`, { targetId, note }
  )).data
}
export interface ReassignIdentityRow {
  id: number; entityType: string; sourceName: string; targetId: number | null
  targetName: string | null; resolutionStatus: string; resolutionNote: string | null
}
export async function fetchReassignableIdentities(params: { entityType: 'CUSTOMER' | 'EMPLOYEE'; keyword?: string; page?: number; size?: number }) {
  return (await http.get<{ items: ReassignIdentityRow[]; total: number }>('/h3-imports/identities', { params })).data
}
export async function fetchReassignTargets(entityType: 'CUSTOMER' | 'EMPLOYEE', keyword: string) {
  return (await http.get<{ items: Array<{ id: number; name: string }> }>('/h3-imports/identities/targets', { params: { entityType, keyword } })).data
}

/**
 * 台账排序参数（2026-10-08）：`sort` ∈ time/code/amount/name（各台账可排序键不同），
 * 缺省或非白名单键由服务端静默回落该台账默认序；`direction` 仅 'asc' 为升序，缺省降序。
 * 键名与后端 `LedgerSort` 常量一致（time=业务时间、code=单号/编号、amount=金额、name=往来单位）。
 */
export interface LedgerSortParams { sort?: string; direction?: 'asc' | 'desc' }

// ---- [历史台账] 统一读端点 /api/h3-imports/ledger/{kind}（阶段 3 收口，A/B 域同前缀） ----
/** 销售出库台账 */
export async function fetchOutboundLedger(params: { keyword?: string; productCode?: string; category?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/outbound', { params })).data
}
/** 生产任务工单台账：filter ∈ all/available/linked，响应额外含 counts{available,linked}（用于台账内接入氚云工单） */
export async function fetchProductionLedger(params: { keyword?: string; filter?: 'all' | 'available' | 'linked'; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/production', { params })).data
}
/** 收款记录台账 */
export async function fetchReceiptLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/receipt', { params })).data
}
/** 多订单结清台账 */
export async function fetchSettlementLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/settlement', { params })).data
}
/** 采购入库台账（V305 事实层；supplier_id 为空 = 供应商解析失败待人工核对） */
export async function fetchPurchaseLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/purchase', { params })).data
}
/** 供应商主数据台账（含别名引用数 alias_count 与内部/脏值标记） */
export async function fetchSupplierLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/supplier', { params })).data
}
/** 其他入库台账（V306 单表 + direction=IN；草稿单仅存档不进正式口径） */
export async function fetchOtherInLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/otherIn', { params })).data
}
/** 其他出库台账（V306 单表 + direction=OUT） */
export async function fetchOtherOutLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/otherOut', { params })).data
}
/** 库存调拨台账（V307；双仓库/双仓管员，草稿与已取消仅标记保留） */
export async function fetchTransferLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/transfer', { params })).data
}
/** 开票资料台账（V309；44 组同名户名是合法事实，未关联 customers 仅标记） */
export async function fetchInvoiceProfileLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/invoiceProfile', { params })).data
}
/** 开票申请台账（V309；本系统唯一开票口径来源，收款情况只建引用不写 receipts） */
export async function fetchInvoiceRequestLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/invoiceRequest', { params })).data
}
/** 生产报工台账（V311；工序完工报工，工单×零部件制程粒度，非材料领料） */
export async function fetchProductionReportLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/productionReport', { params })).data
}
/** 进项发票台账（V312 第 16 类；税务平台全量导出，实开口径权威源·进项侧） */
export async function fetchInvoiceInputLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/invoiceInput', { params })).data
}
/** 销项发票台账（V312 第 16 类；含多次收款登记子行，收款字段为校验源） */
export async function fetchInvoiceSalesLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/invoiceSales', { params })).data
}
/** 供应商应付台账（V313 第 17 类；multiSheet 聚合人工月度台账，补库内应付侧残缺）。supplier=sheet 维度精确筛选 */
export async function fetchSupplierPayableLedger(params: { keyword?: string; supplier?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/supplierPayable', { params })).data
}
/** 流水日记账台账（V315 第 18 类；35 张账户 sheet 行级聚合 7 588 行分录，校验源·只核对不回写）。account=sheet 维度精确筛选 */
export async function fetchCashflowJournalLedger(params: { keyword?: string; account?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/cashflowJournal', { params })).data
}

/** 流水日记账账户汇总行（sheet 维度，源文件每账户一个 sheet；期末=期初+收入−支出，外币账户为外币原值） */
export interface CashAccountSummary {
  id: number
  accountName: string
  currency: string
  openingBalance: number | null
  income: number
  expense: number
  closingBalance: number
  entryCount: number
  lastEntryDate: string | null
}
/** 供应商应付供应商汇总行（sheet 维度，supplier_name=源 sheet 名） */
export interface PayableSupplierSummary {
  supplierName: string
  resolvedSupplierName: string | null
  monthCount: number
  payableAmount: number
  settledAmount: number
  allowance: number
  payAmount: number
  remainingAmount: number
}
/** 流水日记账账户汇总（GET /h3-imports/ledger/cashflowJournal/accounts）：台账汇总条数据源 */
export async function fetchCashflowAccounts() {
  return (await http.get<{ items: CashAccountSummary[] }>('/h3-imports/ledger/cashflowJournal/accounts')).data
}
/** 供应商应付供应商汇总（GET /h3-imports/ledger/supplierPayable/suppliers）：台账汇总条数据源 */
export async function fetchPayableSuppliers() {
  return (await http.get<{ items: PayableSupplierSummary[] }>('/h3-imports/ledger/supplierPayable/suppliers')).data
}
/** 收银通收单流水台账（V316 第 19 类；微信收单 6088 行，校验源·只核对不回写） */
export async function fetchCashierLogLedger(params: { keyword?: string; page?: number; size?: number } & LedgerSortParams) {
  return (await http.get<LedgerPage>('/h3-imports/ledger/cashierLog', { params })).data
}
/**
 * 台账单行详情（六类通用，GET /api/h3-imports/ledger/{kind}/{id}）：
 * 主记录全字段 + 子表明细（outbound→产品明细、production→制程明细、settlement→覆盖出库单）。
 */
export async function fetchLedgerDetail(kind: H3LedgerKind, id: number) {
  return (await http.get<LedgerDetailResult>(`/h3-imports/ledger/${kind}/${id}`)).data
}

/** 更新历史字段级变化（A 域 preview 的 changes 数组项） */
export interface LedgerRowHistoryChange { path: string; oldValue: string | null; newValue: string | null }
/** 更新历史单条：一次批次中该业务键的新增/更新记录（数据源=批次 preview_json） */
export interface LedgerRowHistoryEntry {
  batchId: number
  fileName?: string | null
  batchTime?: string | null
  action: 'CREATE' | 'UPDATE'
  /** B 域（产品/仓库/经销商台账）的文案串差异 */
  changes?: string | null
  /** A 域（出库/生产/收款/结清）的字段级差异 */
  changeItems?: LedgerRowHistoryChange[] | null
}
export interface LedgerRowHistoryResult { kind: H3LedgerKind; businessKey: string; title: string; entries: LedgerRowHistoryEntry[] }
/** 台账行级更新历史（GET /api/h3-imports/ledger/{kind}/{id}/history）：按业务键回放历次批次的 CREATE/UPDATE */
export async function fetchLedgerRowHistory(kind: H3LedgerKind, id: number) {
  return (await http.get<LedgerRowHistoryResult>(`/h3-imports/ledger/${kind}/${id}/history`)).data
}

export interface OutboundOriginalCandidate {
  id: number
  outboundNo: string
  shipDate?: string | null
  customerName?: string | null
  salesAmount?: number | null
}

export async function fetchOutboundOriginalCandidates(id: number) {
  return (await http.get<OutboundOriginalCandidate[]>(`/h3-imports/ledger/outbound/${id}/original-candidates`)).data
}

export async function confirmOutboundBusinessType(
  id: number,
  businessType: 'SALE' | 'RETURN' | 'EXCHANGE',
  originalOutboundId?: number | null
) {
  return (await http.put(`/h3-imports/ledger/outbound/${id}/business-type`, { businessType, originalOutboundId })).data
}
