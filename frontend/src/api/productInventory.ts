import { http } from './http'
import type { BatchProgress, LedgerImportKind, LedgerImportPreview, LedgerImportStageResult, LedgerConfirmResult, LedgerPage } from '@/types/h3Import'
import { waitH3ImportUntil } from '@/utils/h3Import'
// 台账批次导入类型单一来源在 @/types/h3Import（原 orders/productInventory 各自定义，R9 收敛）
export type {
  LedgerImportKind,
  LedgerRowAction,
  LedgerImportRow,
  LedgerImportStageResult,
  LedgerImportPreview,
  LedgerConfirmResult,
  LedgerPage,
  LedgerRow
} from '@/types/h3Import'
/**
 * 库存清单行 = 氚云「产品信息台账 × 仓库明细」合并结果。
 *
 * - warehouseId/warehouseName 为 null 表示该产品在仓库明细里没有任何记录（warehouseRecorded=0）
 * - codeConflict=1 表示该编号在两张台账里指向不同产品，采购价可能取自别的产品
 */
export interface ProductInventoryBalance {
  id: number | null; balanceId?: number | null; hasBalance?: number
  warehouseId: number | null; warehouseName: string | null; warehouseRecorded?: number
  salesProductId: number | null
  productCode: string; productName: string; specification?: string; unit: string
  quantity: number; purchasePrice?: number | null; stockAmount?: number | null
  category?: string; isCustomized?: boolean; codeConflict?: number; updatedAt: string | null
  /** 生命周期状态（V304，本地管理列，导入不覆盖）：只在产品信息台账有该编号时存在 */
  shelfStatus?: 'ON_SHELF' | 'OFF_SHELF' | null
  shelfUpdatedAt?: string | null
}
/** 库存汇总统计：基于完整筛选结果（与分页无关），供顶部统计卡片使用 */
export interface ProductInventorySummary { total: number; productCount: number; warehouseCount: number; zeroCount: number; totalAmount: number }
export interface ProductInventoryPage { items: ProductInventoryBalance[]; total: number; page: number; size: number; summary: ProductInventorySummary; categories: string[]; warehouses: Array<{ id: number; warehouseCode: string; warehouseName: string }> }
export const fetchProductInventory = async (params: Record<string, unknown>) => (await http.get<ProductInventoryPage>('/product-inventory/balances', { params })).data
export interface ConfiguredProductIdentity { id:number; productCode:string; skuKey:string; productName:string; specification?:string; unit:string; status:string; createdAt:string; outputCount:number }
export interface ConfiguredProductCandidate { id:number; productCode:string; productName:string; specification?:string; unit:string; status:string }
export interface ConfiguredOutputQueueItem { id:number;orderId:number;orderNo:string;productName:string;specification?:string;unit:string;skuKey:string;plannedQuantity:number;receivedQuantity:number;orderStatus:string }
export const fetchConfiguredProducts = async (params:Record<string,unknown>) =>
  (await http.get<{items:ConfiguredProductIdentity[];total:number;page:number;size:number;queuedOutputs:ConfiguredOutputQueueItem[];queuedTotal:number}>('/product-inventory/configured-products',{params})).data
export const fetchConfiguredProductCandidates = async (id:number,keyword:string) =>
  (await http.get<ConfiguredProductCandidate[]>(`/product-inventory/configured-products/${id}/candidates`,{params:{keyword}})).data
export const reviewConfiguredProduct = async (id:number,payload:{action:'CONFIRM_NEW'|'MERGE_EXISTING';targetProductId?:number}) =>
  (await http.post(`/product-inventory/configured-products/${id}/review`,payload)).data

// ===== 单行库存详情（2026-09-16）：本行快照 + 两份来源台账行 + 同编号跨仓库分布 =====
/**
 * 来源台账行（产品信息台账行 / 仓库明细行共用结构）。
 *
 * - `id` 是该行在来源台账表里的记录 id，用它可拉取「更新明细」
 *   （`GET /api/h3-imports/ledger/{product|stock}/{id}/history`）与打开台账单行详情；
 * - 产品信息行无 warehouse/quantity，仓库明细行两者都有；
 * - `purchasePrice` 在仓库明细里恒空，实际价格唯一来源是产品信息台账。
 */
export interface InventorySourceRow {
  id: number
  batchId?: string | null
  productCode: string
  productName?: string | null
  category?: string | null
  specification?: string | null
  unit?: string | null
  /** 仓库明细行专有（氚云原始仓库名） */
  warehouse?: string | null
  /** 仓库明细行专有 */
  quantity?: number | null
  purchasePrice?: number | null
  createdAt?: string | null
  /** 「导入与系统信息」：氚云侧系统列（2026-09-16 起随台账落库，老批次重导前为空） */
  sourceObjectId?: string | null
  sourceTitle?: string | null
  createdByName?: string | null
  ownerName?: string | null
  departmentName?: string | null
  dataStatus?: string | null
  sourceCreatedAt?: string | null
  sourceModifiedAt?: string | null
  lastSyncedAt?: string | null
  updatedAt?: string | null
}
/** 同编号在某仓库的库存分布行 */
export interface InventorySpreadRow {
  warehouseId: number | null
  warehouseName: string | null
  unit: string
  quantity: number
  purchasePrice?: number | null
  stockAmount?: number | null
}
export interface InventoryRowDetail {
  row: ProductInventoryBalance
  /** 产品信息台账来源行；null = 该编号不在产品信息台账（缺产品，库存金额无法计算） */
  catalogSource: InventorySourceRow | null
  /** 仓库明细来源行；null = 该行只在产品信息台账（仓库「未记录」） */
  stockSource: InventorySourceRow | null
  /** 同编号跨仓库分布（仅数量非 0 的行，含本行） */
  spread: InventorySpreadRow[]
  /** 分布计数与金额合计（数量跨单位相加无意义，故不合计数量） */
  spreadTotal: { rowCount: number; warehouseCount: number; totalAmount: number }
}
/** 定位键为 (仓库, 编号, 单位)：仓库为 null 表示「产品信息台账独有、仓库明细无此产品」的行 */
export const fetchProductInventoryRowDetail = async (params: {
  warehouseId?: number | null
  productCode: string
  unit?: string | null
}) => (await http.get<InventoryRowDetail>('/product-inventory/detail', { params })).data

// ===== 快照元信息与待处理异常（2026-09-16 第二阶段；处置能力同日第三阶段） =====
export interface SnapshotBatchMeta { id?: number; fileName?: string; createdAt?: string; completedAt?: string }
/** 异常类型：缺产品信息 / 缺采购价（两者互斥） */
export type InventoryIssueType = 'MISSING_PRODUCT' | 'MISSING_PRICE'
/**
 * 处置结论：
 * - PENDING_H3 氚云待补：已确认是真缺，待回氚云补产品/补采购价后重导
 * - DATA_RESIDUE 历史残留：氚云产品信息表已无此产品，仓库明细保留历史行，无需处理
 * - NO_PRICE_NEEDED 无需计价：设备/配件/模具类不按库存金额计价，接受价格为空
 */
export type InventoryIssueStatus = 'PENDING_H3' | 'DATA_RESIDUE' | 'NO_PRICE_NEEDED'
export const INVENTORY_ISSUE_STATUS_TEXT: Record<InventoryIssueStatus, string> = {
  PENDING_H3: '氚云待补',
  DATA_RESIDUE: '历史残留',
  NO_PRICE_NEEDED: '无需计价'
}
export const INVENTORY_ISSUE_TYPE_TEXT: Record<InventoryIssueType, string> = {
  MISSING_PRODUCT: '缺产品信息',
  MISSING_PRICE: '缺采购价'
}
/**
 * 异常明细行（待处理 / 已处置共用结构）。
 *
 * 定位键 = 仓库 + 编号 + 单位（与 h3_warehouse_stock 唯一键一致）；处置标记按同一键持久化，
 * 因此重导台账后标记依然生效。`warehouseName` 为氚云原始仓库名。
 */
export interface SnapshotIssueRow {
  productCode: string
  productName: string | null
  warehouseName: string
  warehouseId: number | null
  specification?: string | null
  unit: string
  quantity: number
  issueType: InventoryIssueType
  dispositionStatus?: InventoryIssueStatus | null
  dispositionRemark?: string | null
  dispositionBy?: string | null
  dispositionAt?: string | null
}
/**
 * 库存快照元信息：两份来源批次 + 导出时间差 + 异常统计。
 *
 * 异常口径三层收敛（2026-09-16 第三阶段）：
 * 1. 与清单同口径——只看页面清单里看得见的行（仓库映射得上、非费用项）；
 * 2. 按是否影响金额分层——数量为 0 的行乘任何价都是 0，归「仅台账残留」；
 * 3. 扣除已处置——标记过结论的行不再计入待处理，因此异常数能真正收敛到 0。
 * 前端口径条主数字用 `affectedCount`，不要再用两个 count 相加（那是历史虚高口径）。
 */
export interface ProductInventorySnapshotInfo {
  catalogBatch: SnapshotBatchMeta
  stockBatch: SnapshotBatchMeta
  /** 两份台账完成导入时间的差（秒）；任一缺失为 null */
  gapSeconds: number | null
  /** 待处理·缺产品 行数（已排除费用项、已扣除已处置） */
  missingProductCount: number
  missingProductCodes: number
  /** 待处理·缺产品里数量非 0 的行数（真正影响库存金额） */
  missingProductInStockCount: number
  /** 待处理·缺价 行数（缺价天然要求数量非 0） */
  missingPriceCount: number
  missingPriceCodes: number
  missingPriceInStockCount: number
  /** 已处置行数（两类合计） */
  disposedCount: number
  /** 真正影响库存金额的待处理行数 = 缺产品有量 + 缺价（口径条主数字） */
  affectedCount: number
  /** 待处理合计（含零库存残留，用于说明「为什么还有个更大的数」） */
  pendingCount: number
  /** 被费用项过滤挡掉的行数：历史口径多报的部分，让口径变化有据可查 */
  feeExcludedCount: number
  missingProductRows: SnapshotIssueRow[]
  missingPriceRows: SnapshotIssueRow[]
  disposedRows: SnapshotIssueRow[]
  // ===== 导入覆盖报告（V304）：30 天未被任何导入批次覆盖、且仍「在用」的行 =====
  /** 阈值（天），文案随它显示 */
  staleDays: number
  /** 产品信息台账：长期未被覆盖的行（可能氚云已不再有此产品，人工判断后可标记下架） */
  staleCatalogCount: number
  staleCatalogRows: StaleCatalogRow[]
  /** 仓库明细台账：长期未被覆盖的行（数量仍在清单里，需核对导出范围或记处置结论） */
  staleStockCount: number
  staleStockRows: StaleStockRow[]
  /** 后端保留的对账偏离字段（正式库存账冻结期前端不消费，仅为 API 契约完整） */
  balanceVanished: number; balanceDrifted: number
}
/** 覆盖报告·产品信息台账行 */
export interface StaleCatalogRow {
  productCode: string; productName: string | null; category: string | null
  unit: string | null; purchasePrice: number | null; lastSeenAt: string | null
}
/** 覆盖报告·仓库明细行 */
export interface StaleStockRow {
  productCode: string; productName: string | null; warehouse: string | null
  specification: string | null; unit: string | null; quantity: number; lastSeenAt: string | null
}
export const fetchProductInventorySnapshotInfo = async () => (await http.get<ProductInventorySnapshotInfo>('/product-inventory/snapshot-info')).data

// ===== 产品生命周期状态（V304：产品永不物理删除，「删除/停用」表达为 在用/下架） =====
export const SHELF_STATUS_TEXT: Record<string, string> = { ON_SHELF: '在用', OFF_SHELF: '下架' }
/** 批量设置产品生命周期状态（按编号）。下架是标注不是删除：库存金额照算，覆盖报告不再提示。 */
export const setProductShelfStatus = async (productCodes: string[], status: 'ON_SHELF' | 'OFF_SHELF') =>
  (await http.post<{ updated: number }>('/product-inventory/catalog/shelf-status', { productCodes, status })).data

// ===== 异常处置（只写结论，不改台账；采购价唯一来源仍是氚云） =====
/** 异常全量明细（未处置），供导出：抽屉每类只回显前 200 行，几百行人工核不完 */
export const fetchInventoryIssues = async (issueType?: InventoryIssueType) =>
  (await http.get<SnapshotIssueRow[]>('/product-inventory/issues', { params: issueType ? { issueType } : {} })).data
/** 标记 / 更新处置结论（按 仓库+编号+单位 幂等 upsert，重复标记即改判） */
export const disposeInventoryIssue = async (payload: {
  warehouseName: string; productCode: string; unit: string
  issueType: InventoryIssueType; status: InventoryIssueStatus; remark?: string
}) => (await http.post('/product-inventory/issues/dispositions', payload)).data
/** 撤销处置结论：判错了，或氚云补完数据后该行已不再是异常 */
export const undoInventoryIssueDisposition = async (params: { warehouseName: string; productCode: string; unit: string }) =>
  (await http.delete('/product-inventory/issues/dispositions', { params })).data

// ===== 台账批次化导入（产品信息 / 仓库明细，与销售出库一致：上传登记批次→后台解析轮询→比对预览→确认写入） =====
/** Stage 只登记批次并返回（解析在后台异步执行），进度通过 waitLedgerBatchUntil 轮询 */
export const stageLedgerImport = async (kind: LedgerImportKind, file: File) => {
  const form = new FormData(); form.append('file', file)
  return (await http.post<LedgerImportStageResult>(`/product-inventory/${kind}/stage`, form)).data
}
/** 后台解析进度轮询（PARSING → STAGING → PREVIEW_READY / FAILED）；与 A 域共用统一批次端点 */
export const fetchLedgerBatchProgress = async (batchId: number) => (await http.get<BatchProgress>(`/h3-imports/batches/${batchId}/progress`)).data
/** 直到批次进入 doneSet 或 FAILED（超时抛错），轮询 fetchLedgerBatchProgress，每步回调 onProgress（薄包装：复用统一 waitH3ImportUntil） */
export async function waitLedgerBatchUntil(batchId: number, doneStatuses: string[], opts?: { intervalMs?: number; timeoutMs?: number; onProgress?: (p: BatchProgress) => void }): Promise<BatchProgress> {
  return waitH3ImportUntil(fetchLedgerBatchProgress, batchId, doneStatuses, opts)
}
export const fetchLedgerBatchPreview = async (batchId: number) => (await http.get<LedgerImportPreview>(`/h3-imports/batches/${batchId}/preview`)).data
export const confirmLedgerBatch = async (batchId: number) => (await http.post<LedgerConfirmResult>(`/product-inventory/ledger-batches/${batchId}/confirm`, {}, { timeout: 600000 })).data

// ===== 台账查询（统一读端点 /api/h3-imports/ledger/{kind}，阶段 3 收口） =====
export const fetchProductCatalogLedger = async (params: Record<string, unknown>) => (await http.get<LedgerPage>('/h3-imports/ledger/product', { params })).data
export const fetchWarehouseStockLedger = async (params: Record<string, unknown>) => (await http.get<LedgerPage>('/h3-imports/ledger/stock', { params })).data
/** 经销商信息台账（氚云经销商信息 Excel 按批次增量导入 h3_dealer_catalog） */
export const fetchDealerLedger = async (params: Record<string, unknown>) => (await http.get<LedgerPage>('/h3-imports/ledger/dealer', { params })).data
// 注：「合并入库」前端链路（mergeInventoryPreview/Confirm 及类型）已随入口下线移除（2026-09-16）；
// 后端 /merge/** 端点保留，恢复入口时从 git 历史找回即可。
