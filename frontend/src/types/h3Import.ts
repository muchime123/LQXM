/**
 * 氚云导入中心 · 批次导入共享类型（单一来源，2026-09 收敛）。
 *
 * 背景：`/api/h3-facts`（四类业务事实）与 `/api/product-inventory`（产品信息/仓库明细台账）
 * 共用同一张 h3_import_batches 批次表与同构的「登记→解析→预览→确认」管线，但两套 API 模块
 * 曾各自定义一批形状重复的类型（BatchProgress / LedgerPage / Stage…）。
 * 本文件作为唯一事实源；`api/business/orders.ts` 与 `api/productInventory.ts` 仅 re-export。
 */
export type PageResult<T> = { items: T[]; total: number; page: number; size: number }

// ===== 域与路由别名 =====
/** 业务事实（A 域 /api/h3-facts 的 {type} 路径段）；供应商为主数据但复用 A 域暂存管线 */
export type H3FactType =
  | 'production'
  | 'outbound'
  | 'receipt'
  | 'settlement'
  | 'supplier'
  | 'purchase'
  | 'otherIn'
  | 'otherOut'
  | 'transfer'
  | 'invoiceProfile'
  | 'invoiceRequest'
  | 'productionReport'
  | 'invoiceInput'
  | 'invoiceSales'
  | 'supplierPayable'
  | 'cashflowJournal'
  | 'cashierLog'
/** 产品台账批次导入（B 域 /api/product-inventory 的 {kind} 路径段） */
export type LedgerImportKind = 'catalog' | 'stock' | 'dealer'
/** 导入中心卡片统一 key：4 业务事实（type 直用）+ 产品/仓库/经销商台账卡 + 合并入库批次卡（仅列表展示） */
export type H3ImportKindKey =
  | 'outbound'
  | 'production'
  | 'receipt'
  | 'settlement'
  | 'supplier'
  | 'purchase'
  | 'otherIn'
  | 'otherOut'
  | 'transfer'
  | 'invoiceProfile'
  | 'invoiceRequest'
  | 'productionReport'
  | 'invoiceInput'
  | 'invoiceSales'
  | 'supplierPayable'
  | 'cashflowJournal'
  | 'cashierLog'
  | 'product'
  | 'stock'
  | 'dealer'
  | 'merge'
/** h3_import_batches.data_type 取值（INVENTORY_MERGE 非文件导入批次，仅在批次列表展示） */
export type H3ImportDbType =
  | 'OUTBOUND'
  | 'PRODUCTION'
  | 'RECEIPT'
  | 'SETTLEMENT'
  | 'SUPPLIER'
  | 'PURCHASE_IN'
  | 'OTHER_IN'
  | 'OTHER_OUT'
  | 'TRANSFER'
  | 'INVOICE_PROFILE'
  | 'INVOICE_REQUEST'
  | 'PRODUCTION_REPORT'
  | 'INVOICE_INPUT'
  | 'INVOICE_SALES'
  | 'SUPPLIER_PAYABLE'
  | 'CASHFLOW_JOURNAL'
  | 'CASHIER_LOG'
  | 'PRODUCT_INFO'
  | 'WAREHOUSE_STOCK'
  | 'DEALER'
  | 'INVENTORY_MERGE'
/** 数据源大类：businessFact（确认后触发后台编排）vs ledger（确认=写台账） */
export type H3ImportDomain = 'fact' | 'ledger'
/**
 * 导入类型分层组（展示层）：七类数据共用同一入口，但按用途分三组呈现。
 *
 * 注意：分组只决定「怎么摆」，不决定「做什么」——参与核算与否由
 * {@link H3ImportParticipation} 单独声明，两者不要混用。
 */
export type H3ImportGroup =
  /** 核心业务：销售出库，唯一进入经营核算的来源 */
  | 'core'
  /** 基础数据：产品信息 / 仓库明细 / 经销商信息，更新主档台账 */
  | 'base'
  /** 参考归档：生产工单 / 收款记录 / 多订单结清，仅归档留存 */
  | 'archive'
/**
 * 确认导入后对经营核算的参与方式（单一来源，驱动批次行「处理结果」标签与提示文案）。
 *
 * - in_accounting：写入事实表并进入客户/订单/回款/利润/提成核算
 * - updates_master：只更新基础台账，不进入核算
 * - archive_only：仅归档留存，不参与客户、回款、利润与提成计算
 */
export type H3ImportParticipation = 'in_accounting' | 'updates_master' | 'archive_only'
/** 入口阶段：daily=日常入口直接可见；advanced=收进「历史数据导入」折叠区（低频/归档类） */
export type H3ImportEntryStage = 'daily' | 'advanced'
/** 历史台账 Tab（19 值：五类事实镜像 + 产品信息/仓库明细/经销商/供应商 + 其他入/出库/调拨 + 开票资料/开票申请 + 生产报工 + 进项/销项发票 + 供应商应付 + 流水日记账/收银通） */
export type H3LedgerKind =
  | 'outbound' | 'production' | 'receipt' | 'settlement' | 'purchase'
  | 'product' | 'stock' | 'dealer' | 'supplier'
  | 'otherIn' | 'otherOut' | 'transfer'
  | 'invoiceProfile' | 'invoiceRequest'
  | 'productionReport'
  | 'invoiceInput' | 'invoiceSales' | 'supplierPayable'
  | 'cashflowJournal' | 'cashierLog'

// ===== A 域 / B 域批次导入契约 =====
export type LedgerRowAction = 'CREATE' | 'UPDATE' | 'UNCHANGED'
/** 「这个文件以前导过」提示：仅提示，绝不拦截（氚云无法实时同步，一天多次全量重导是常态） */
export interface DuplicateImportHint {
  batchId: number
  fileName: string
  /** 该批次完成时间（本地时间 yyyy-MM-ddTHH:mm:ss），可能为空 */
  completedAt: string | null
}
/** 上传登记批次（A/B 两域同形） */
export interface StageAccepted {
  batchId: number
  dataType: string
  fileName: string
  hashHit: boolean
  status: string
  message: string
  duplicateHint?: DuplicateImportHint | null
}
/** B 域台账批次 stage 返回（与 StageAccepted 形状一致，字段可选以兼容历史实现） */
export interface LedgerImportStageResult {
  batchId: number
  dataType: string
  fileName: string
  hashHit: boolean
  status?: string
  message?: string
  duplicateHint?: DuplicateImportHint | null
}
/** 批次进度（两域同形；status ∈ UPLOADED/PARSING/PREVIEW_READY/CONFIRMING/COMPLETED/PARTIAL_FAILED/FAILED） */
export interface BatchProgress {
  batchId: number
  status: 'UPLOADED' | 'PARSING' | 'PREVIEW_READY' | 'CONFIRMING' | 'COMPLETED' | 'PARTIAL_FAILED' | 'FAILED' | string
  progressPct: number
  progressStage?: string
  errorMessage?: string
  totalCount?: number
  createCount?: number
  updateCount?: number
  unchangedCount?: number
  completedAt?: string
}
/** 批次列表行 */
export interface ImportBatch {
  id: number
  fileName: string
  dataType: string
  status: string
  progressPct?: number
  progressStage?: string
  errorMessage?: string
  errorDetail?: string
  totalCount: number
  createdCount: number
  updatedCount: number
  unchangedCount: number
  /** 校验告警计数（确认导入时写入）：往来单位未解析单数 */
  missingCustomerCount?: number | null
  /** 校验告警计数：缺关键日期单数 */
  missingDateCount?: number | null
  /** 校验告警计数：Σ明细≠主表 / 缺价 / 仓库未匹配 等勾稽告警单数 */
  missingQuantityCount?: number | null
  createdAt: string
  completedAt?: string
}

// ===== 批次告警明细（2026-10-20） =====
/**
 * 告警分类码（后端 H3FactImportService#warningCode 单一来源）。
 * 用途：导入后按类筛选/统计；未命中映射时后端返回 OTHER，前端回落到「其他」。
 */
export type H3WarningCode =
  | 'SUM_MISMATCH'
  | 'MISSING_PRICE'
  | 'SUPPLIER_UNRESOLVED'
  | 'WAREHOUSE_UNMATCHED'
  | 'INVALID_STATUS'
  | 'INVALID_TYPE'
  | 'BAD_FIELD'
  | 'MISSING_FIELD'
  | 'RED_CHAIN'
  | 'DUPLICATE'
  | 'OTHER'
/** 批次告警单行：来自 h3_import_rows.raw_data 重算，与预览行 warnings 同口径 */
export interface BatchWarningRow {
  rowId: number
  /** 落库后的事实行 id（销售出库/采购入库/…主表 id）；为 null 时无法跳台账详情 */
  businessId: number | null
  externalId: string
  title: string
  action: 'CREATE' | 'UPDATE' | 'UNCHANGED' | string
  /** 中文告警，多条以「；」连接 */
  warnings: string
  warningCodes: H3WarningCode[]
}
export interface BatchWarningsPage {
  batchId: number
  dataType: string
  /** 批次全量行数 */
  total: number
  /** 含告警行数（不受分页影响） */
  totalWarningRows: number
  page: number
  size: number
  items: BatchWarningRow[]
}

// ===== 预览 =====
/** P0-C：业务事实预览行（扩展金额/客户/业务员，便于确认前判断业务含义） */
export interface ImportRowPreview {
  businessKey: string
  externalId: string
  title?: string
  action: 'CREATE' | 'UPDATE' | 'UNCHANGED'
  childCount: number
  amount?: string | null
  customerName?: string | null
  salespersonName?: string | null
  /** 导入校验告警（Σ明细≠合计、缺价、供应商未解析），多条以「；」连接；无告警为空 */
  warnings?: string | null
  changes?: Array<{ path: string; oldValue: string; newValue: string }>
}
export interface ImportPreview {
  dataType: string
  fileName: string
  totalCount: number
  createCount: number
  updateCount: number
  unchangedCount: number
  rows: ImportRowPreview[]
}
export interface StagingPreview {
  batchId: number
  preview: ImportPreview
}
/** B 域台账比对预览行（产品信息/仓库明细/经销商三态共用，按 ledgerKind 取用字段） */
export interface LedgerImportRow {
  action: LedgerRowAction
  productCode?: string
  productName?: string
  category?: string
  specification?: string
  unit?: string
  warehouse?: string
  quantity?: number
  purchasePrice?: number | null
  /** 经销商台账（ledgerKind=dealer） */
  dealerCode?: string
  dealerName?: string
  contact?: string
  address?: string
  remark?: string
  ownerSalesperson?: string
  dataStatus?: string
  changes?: string
}
export interface LedgerImportPreview {
  batchId: number
  dataType: string
  fileName: string
  totalCount: number
  createCount: number
  updateCount: number
  unchangedCount: number
  rows: LedgerImportRow[]
}

// ===== 确认结果 =====
export interface ImportResult {
  batchId: number
  dataType: string
  totalCount: number
  createdCount: number
  updatedCount: number
  unchangedCount: number
  childCount: number
}
export interface LedgerConfirmResult {
  batchId: number
  dataType: string
  totalCount: number
  createdCount: number
  updatedCount: number
  unchangedCount: number
}

/** 客户档案回填结果 */
export interface CustomerArchiveBackfillResult {
  startedAt?: string
  completedAt?: string
  durationMs?: number
  totalDiscoveredCustomers: number
  customersCreated: number
  customersSkippedExisting: number
  customersPatched: number
  contactsCreated: number
  salespersonRelationsCreated: number
  outboundCustomerIdsLinked: number
  receiptCustomerIdsLinked: number
  productionCustomerIdsLinked: number
}
// ===== 历史台账查询（A 域 4 类 + B 域 2 类共用行/页结构） =====
export type LedgerRow = Record<string, any>
export interface LedgerPage {
  items: LedgerRow[]
  total: number
  page: number
  size: number
}
/** 台账单行详情（GET /api/h3-imports/ledger/{kind}/{id}）：row=主记录全字段，rows=子表明细（仅存在子表的类型返回） */
export interface LedgerDetailResult {
  kind: H3LedgerKind
  row: LedgerRow
  rows?: LedgerRow[]
  rows2?: LedgerRow[]
}
