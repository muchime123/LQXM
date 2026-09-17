/**
 * 氚云导入中心 · 展示元数据与文案映射（单一来源，2026-09 收敛）。
 *
 * - 七张导入卡的展示数据（原散落在 H3ImportCenterView 内的 importTypes 数组，已收口于此）；
 * - 批次状态/进度阶段/动作/候选状态 → 中文文案与 el-tag 类型；
 * - NORMAL→OUTBOUND 历史数据归一统一收口（原多处内联三元表达式）。
 */
import type {
  H3ImportDbType,
  H3ImportDomain,
  H3ImportEntryStage,
  H3ImportGroup,
  H3ImportKindKey,
  H3ImportParticipation,
  H3LedgerKind,
  LedgerImportKind
} from '@/types/h3Import'

export type H3ElTagType = 'success' | 'warning' | 'info' | 'danger' | 'primary'

// ===== NORMAL→OUTBOUND 归一（V135 已迁移，保留防守性归一，只此一处） =====
export function normalizeH3DbType(dbType?: string | null): string {
  return !dbType ? '' : dbType === 'NORMAL' ? 'OUTBOUND' : dbType
}

/** 台账类批次（产品信息/仓库明细/经销商）：区别于四类业务事实 */
export function isH3LedgerDbType(dbType?: string | null): boolean {
  const t = normalizeH3DbType(dbType)
  return t === 'PRODUCT_INFO' || t === 'WAREHOUSE_STOCK' || t === 'DEALER'
}
/** 收款类批次（含多订单结清） */
export function isH3ReceiptDbType(dbType?: string | null): boolean {
  const t = normalizeH3DbType(dbType)
  return t === 'RECEIPT' || t === 'SETTLEMENT'
}

// ===== 批次状态 =====
export const H3_BATCH_STATUS_META: Record<string, { label: string; tag: H3ElTagType }> = {
  UPLOADED: { label: '等待解析', tag: 'info' },
  PARSING: { label: '解析中', tag: 'info' },
  PREVIEW_READY: { label: '待确认', tag: 'primary' },
  CONFIRMING: { label: '导入中', tag: 'info' },
  COMPLETED: { label: '已完成', tag: 'success' },
  PARTIAL_FAILED: { label: '部分失败', tag: 'warning' },
  FAILED: { label: '失败', tag: 'danger' }
}
export function h3BatchStatusLabel(status: string): string {
  return H3_BATCH_STATUS_META[status]?.label ?? status
}
export function h3BatchStatusTagType(status: string): H3ElTagType {
  return H3_BATCH_STATUS_META[status]?.tag ?? 'info'
}

// ===== 进度阶段 =====
export const H3_PROGRESS_STAGE_LABEL: Record<string, string> = {
  UPLOAD: '上传登记',
  PARSING: '解析 Excel',
  STAGING: '落暂存表',
  PREVIEW: '生成预览',
  WRITING: '写入事实表',
  ORCHESTRATING: '刷新收款状态与利润结果',
  DONE: '完成'
}
export function h3ProgressStageLabel(stage?: string): string {
  return stage ? (H3_PROGRESS_STAGE_LABEL[stage] ?? stage) : '排队中'
}
/** el-progress 状态：success/exception/warning/'' */
export function h3ProgressElStatus(status: string): '' | 'success' | 'warning' | 'exception' {
  switch (status) {
    case 'PREVIEW_READY':
    case 'COMPLETED':
      return 'success'
    case 'FAILED':
      return 'exception'
    case 'PARTIAL_FAILED':
      return 'warning'
    default:
      return ''
  }
}

// ===== 行动作 / 候选状态 =====
export function h3ActionLabel(action: string): string {
  return action === 'CREATE' ? '新增' : action === 'UPDATE' ? '更新' : '无变化'
}
export const H3_CANDIDATE_STATUS_META: Record<string, { label: string; tag: H3ElTagType }> = {
  PENDING: { label: '待处理', tag: 'warning' },
  CONFIRMED: { label: '已确认', tag: 'success' },
  REJECTED: { label: '已驳回', tag: 'danger' },
  SUPERSEDED: { label: '已替代', tag: 'info' }
}
export function h3CandidateStatusLabel(status: string): string {
  return H3_CANDIDATE_STATUS_META[status]?.label ?? status
}
export function h3CandidateTagType(status: string): H3ElTagType {
  return H3_CANDIDATE_STATUS_META[status]?.tag ?? 'warning'
}

// ===== 导入卡元数据 =====
export interface H3ImportNextAction {
  label: string
  description: string
  path: string
  query?: Record<string, string>
  /** 存在时直接切到本页「历史台账」对应 Tab */
  ledger?: H3LedgerKind
}
/** 导入卡（domain=fact 走 A 域 stage；domain=ledger 走 B 域 stage） */
export interface H3ImportKindCard {
  type: H3ImportKindKey
  dbType: H3ImportDbType
  domain: H3ImportDomain
  /** 分层组：决定下拉/台账的摆放位置（见 H3_IMPORT_GROUPS） */
  group: H3ImportGroup
  /** 确认后是否进入经营核算（决定批次行「处理结果」标签与提示文案） */
  participation: H3ImportParticipation
  /** daily=日常入口；advanced=收进「历史数据导入」折叠区 */
  entryStage: H3ImportEntryStage
  short: string
  /** 通用名称：批次下拉筛选、批次表数据类型标签、导入弹窗副标题 */
  title: string
  description: string
  hint: string
  /** 卡面标题/正文（默认回退 title/description/hint；台账卡可用更具体的导入话术） */
  cardTitle?: string
  cardDescription?: string
  cardHint?: string
  color: string
  /** 图标文字颜色（缺省走 .import-card__icon 默认色 #24415d） */
  fg?: string
  /** domain=ledger 时的 B 域 {kind} 路径段 */
  ledgerKind?: LedgerImportKind
  /** 上传按钮文案 */
  uploadLabel: string
  /** COMPLETED 后「建议下一步」动作 */
  next: H3ImportNextAction
}
export const H3_IMPORT_KINDS: H3ImportKindCard[] = [
  {
    type: 'outbound',
    dbType: 'OUTBOUND',
    domain: 'fact',
    group: 'core',
    participation: 'in_accounting',
    entryStage: 'daily',
    short: '出',
    title: '销售出库',
    description: '出库主单与产品明细',
    hint: '识别销售出库编号',
    color: '#e7f7f0',
    uploadLabel: '选择 Excel',
    next: { label: '查看销售出库台账', description: '销售出库已按源状态入库，可在本页「历史台账 · 销售出库」核对原始数据。', path: '/h3-imports', ledger: 'outbound' }
  },
  {
    type: 'production',
    dbType: 'PRODUCTION',
    domain: 'fact',
    group: 'archive',
    participation: 'archive_only',
    entryStage: 'advanced',
    short: '产',
    title: '生产任务工单',
    description: '生产工单主单与零部件制程',
    hint: '识别生产工单流水号',
    color: '#eef2ff',
    uploadLabel: '选择 Excel',
    next: { label: '查看生产工单台账', description: '生产任务工单已写入事实表，可在本页「历史台账 · 生产任务工单」核对原始数据。', path: '/h3-imports', ledger: 'production' }
  },
  {
    type: 'receipt',
    dbType: 'RECEIPT',
    domain: 'fact',
    group: 'archive',
    participation: 'archive_only',
    entryStage: 'advanced',
    short: '收',
    title: '收款记录',
    description: '收款主单（单订单结清）',
    hint: '识别流水号',
    color: '#fef3c7',
    uploadLabel: '选择 Excel',
    next: { label: '查看收款记录台账', description: '收款记录已写入 receipts（SINGLE_OUTBOUND），可在本页「历史台账 · 收款记录」核对原始数据。', path: '/h3-imports', ledger: 'receipt' }
  },
  {
    type: 'settlement',
    dbType: 'SETTLEMENT',
    domain: 'fact',
    group: 'archive',
    participation: 'archive_only',
    entryStage: 'advanced',
    short: '结',
    title: '多订单结清',
    description: '多订单结清收款主单',
    hint: '识别流水号',
    color: '#fce7f3',
    uploadLabel: '选择 Excel',
    next: { label: '查看结清台账', description: '多订单结清已写入 receipts（MULTI_OUTBOUND），可在本页「历史台账 · 多订单结清」核对原始数据。', path: '/h3-imports', ledger: 'settlement' }
  },
  {
    type: 'purchase',
    dbType: 'PURCHASE_IN',
    domain: 'fact',
    group: 'core',
    participation: 'in_accounting',
    entryStage: 'daily',
    short: '采',
    title: '采购入库',
    description: '采购单主表与产品明细（采购价权威来源）',
    hint: '识别采购入库编号',
    color: '#ffedd5',
    fg: '#9a3412',
    uploadLabel: '选择 Excel',
    next: { label: '查看采购入库台账', description: '采购入库已按单号入库（事实层，不动库存余额）。预览中的 Σ差异/缺价/供应商未解析告警可在台账逐单核对。', path: '/h3-imports', ledger: 'purchase' }
  },
  {
    type: 'supplier',
    dbType: 'SUPPLIER',
    domain: 'fact',
    group: 'base',
    participation: 'updates_master',
    entryStage: 'daily',
    short: '供',
    title: '供应商信息',
    description: '供应商主数据（无编号，按数据ID幂等）',
    hint: '识别供应商名称',
    color: '#d1fae5',
    fg: '#047857',
    uploadLabel: '选择 Excel',
    next: { label: '查看供应商台账', description: '供应商主档已按数据ID写入；内部车间/串名脏值会打标记，采购入库将按归一化名称自动关联。', path: '/h3-imports', ledger: 'supplier' }
  },
  {
    type: 'otherIn',
    dbType: 'OTHER_IN',
    domain: 'fact',
    group: 'archive',
    participation: 'archive_only',
    entryStage: 'advanced',
    short: '他入',
    title: '其他入库',
    description: '其他入库主单与产品明细（补录/调整库存）',
    hint: '识别其他入库流水号',
    color: '#ecfeff',
    fg: '#155e75',
    uploadLabel: '选择 Excel',
    next: { label: '查看其他入库台账', description: '其他入库已按单号入库（事实层，不动库存余额）。预览中的仓库未匹配 / Σ数量差异 / 缺价行告警可在台账逐单核对。', path: '/h3-imports', ledger: 'otherIn' }
  },
  {
    type: 'otherOut',
    dbType: 'OTHER_OUT',
    domain: 'fact',
    group: 'archive',
    participation: 'archive_only',
    entryStage: 'advanced',
    short: '他出',
    title: '其他出库',
    description: '其他出库主单与产品明细（设备/模具/片膜等非销售出库）',
    hint: '识别其他出库编号',
    color: '#fff1f2',
    fg: '#9f1239',
    uploadLabel: '选择 Excel',
    next: { label: '查看其他出库台账', description: '其他出库已按单号入库（事实层，不动库存余额）。预览中的仓库未匹配 / Σ数量差异 / 缺价行告警可在台账逐单核对。', path: '/h3-imports', ledger: 'otherOut' }
  },
  {
    type: 'transfer',
    dbType: 'TRANSFER',
    domain: 'fact',
    group: 'archive',
    participation: 'archive_only',
    entryStage: 'advanced',
    short: '调',
    title: '库存调拨',
    description: '仓间调拨单主表与产品明细（安徽仓 ↔ 温州仓，含调出库存快照）',
    hint: '识别调拨流水号',
    color: '#eef2ff',
    fg: '#3730a3',
    uploadLabel: '选择 Excel',
    next: { label: '查看库存调拨台账', description: '库存调拨已按单号入库（事实层，不动库存余额）。预览中的调出仓=调入仓 / 超调出库存快照 / 非已生效状态告警可在台账逐单核对。', path: '/h3-imports', ledger: 'transfer' }
  },
  {
    type: 'invoiceProfile',
    dbType: 'INVOICE_PROFILE',
    domain: 'fact',
    group: 'base',
    participation: 'updates_master',
    entryStage: 'daily',
    short: '票档',
    title: '开票资料',
    description: '客户开票主数据（户名/税号/开户行，按数据ID幂等；44 组同名户名是合法事实）',
    hint: '识别开票资料表单',
    color: '#fefce8',
    fg: '#a16207',
    uploadLabel: '选择 Excel',
    next: { label: '查看开票资料台账', description: '开票资料已按数据ID写入；税号→customers.credit_code 等值唯一命中才关联，未命中只标记不进确认队列（外协/一次性户名属正常）。', path: '/h3-imports', ledger: 'invoiceProfile' }
  },
  {
    type: 'invoiceRequest',
    dbType: 'INVOICE_REQUEST',
    domain: 'fact',
    group: 'core',
    participation: 'in_accounting',
    entryStage: 'daily',
    short: '开票',
    title: '开票申请',
    description: '开票申请主表 + 开票内容 + 收款情况引用（本系统唯一开票口径来源）',
    hint: '识别开票申请流水号',
    color: '#f5f3ff',
    fg: '#6d28d9',
    uploadLabel: '选择 Excel',
    next: { label: '查看开票申请台账', description: '开票申请已按数据ID入库（18 单缺流水号以数据ID兜底）。Σ内容金额≠申请 / 收付不平告警可在台账逐单核对；出库匹配自由文本只存原文不自动勾稽。', path: '/h3-imports', ledger: 'invoiceRequest' }
  },
  {
    type: 'productionReport',
    dbType: 'PRODUCTION_REPORT',
    domain: 'fact',
    group: 'archive',
    participation: 'archive_only',
    entryStage: 'advanced',
    short: '报工',
    title: '生产报工',
    description: '工序完工报工：工单×零部件制程粒度（按数据ID幂等；非材料领料——氚云从未启用零部件库存调用）',
    hint: '识别报工表单',
    color: '#ecfdf5',
    fg: '#047857',
    uploadLabel: '选择 Excel',
    next: { label: '查看生产报工台账', description: '报工已按数据ID入库。生产工单编号关联生产工单（实测 87.8% 工单有报工）；零部件流水号全表 10 值=工序模板；数据状态全「已生效」、氚云无撤销原语，误报需 ERP 侧冲销。', path: '/h3-imports', ledger: 'productionReport' }
  },
  {
    type: 'product',
    dbType: 'PRODUCT_INFO',
    domain: 'ledger',
    group: 'base',
    participation: 'updates_master',
    entryStage: 'daily',
    ledgerKind: 'catalog',
    short: '品',
    title: '产品信息',
    description: '产品信息台账（逐行新增/更新，不覆盖历史）',
    hint: '比对产品编号',
    cardTitle: '产品信息台账',
    cardDescription: '上传氚云产品信息 Excel，先比对再确认写入台账',
    cardHint: '类目/编号/规格/单位/采购价 · 只增改，不覆盖历史行',
    color: '#e0f2fe',
    fg: '#0369a1',
    uploadLabel: '上传并比对',
    next: { label: '查看产品信息台账', description: '产品信息已按批次写入台账（新增/更新），可在本页「历史台账 · 产品信息」核对。', path: '/h3-imports', ledger: 'product' }
  },
  {
    type: 'stock',
    dbType: 'WAREHOUSE_STOCK',
    domain: 'ledger',
    group: 'base',
    participation: 'updates_master',
    entryStage: 'daily',
    ledgerKind: 'stock',
    short: '库',
    title: '仓库明细',
    description: '仓库明细台账（逐行新增/更新，不覆盖历史）',
    hint: '比对仓库+编号',
    cardTitle: '仓库明细台账',
    cardDescription: '上传氚云仓库明细 Excel，先比对再确认写入台账',
    cardHint: '仓库/编号/库存数量/采购价 · 只增改，不覆盖历史行',
    color: '#fef3c7',
    fg: '#92400e',
    uploadLabel: '上传并比对',
    next: { label: '查看仓库明细台账', description: '仓库明细已按批次写入台账（新增/更新），可在本页「历史台账 · 仓库明细」核对。', path: '/h3-imports', ledger: 'stock' }
  },
  {
    type: 'dealer',
    dbType: 'DEALER',
    domain: 'ledger',
    group: 'base',
    participation: 'updates_master',
    entryStage: 'daily',
    ledgerKind: 'dealer',
    short: '经',
    title: '经销商信息',
    description: '经销商主数据台账（逐行新增/更新，不覆盖历史）',
    hint: '比对经销商编号',
    cardTitle: '经销商信息台账',
    cardDescription: '上传氚云经销商信息 Excel，先比对再确认写入台账',
    cardHint: '编号/名称/联系方式/地址/归属业务员 · 只增改，不覆盖历史行',
    color: '#ede9fe',
    fg: '#6d28d9',
    uploadLabel: '上传并比对',
    next: { label: '查看经销商台账', description: '经销商信息已按批次写入台账（新增/更新），可在本页「历史台账 · 经销商」核对。', path: '/h3-imports', ledger: 'dealer' }
  }
]

/**
 * 历史 INVENTORY_MERGE 批次元数据：入口已于 2026-09-16 下线（正式库存账冻结，
 * 见 docs/氚云导入中心与库存快照重设计-20260916.md），仅保留此卡供批次列表展示库中
 * 的历史 INVENTORY_MERGE 批次；不出现在「导入氚云 Excel」上传下拉里。
 */
export const H3_BATCH_MERGE_KIND: H3ImportKindCard = {
  type: 'merge',
  dbType: 'INVENTORY_MERGE',
  domain: 'ledger',
  group: 'base',
  participation: 'updates_master',
  entryStage: 'advanced',
  short: '合',
  title: '库存对账（已下线）',
  description: '历史合并批次：入口已下线，正式库存账冻结期间不再产生新批次',
  hint: '仅历史批次展示',
  color: '#dcfce7',
  fg: '#166534',
  uploadLabel: '开始合并',
  next: {
    label: '查看产品库存',
    description: '产品库存页是产品信息 × 仓库明细两张台账的实时快照，导入完成即生效。',
    path: '/product-inventory'
  }
}

/**
 * 批次列表展示用种类集合 = 七类文件导入卡 + 历史 INVENTORY_MERGE 批次卡（仅历史展示）。
 * 用于「最近导入」类型筛选下拉与「建议下一步」按钮查找；
 * 上传下拉只用 H3_IMPORT_KINDS（H3ImportCenterView）。
 */
export const H3_BATCH_KINDS: H3ImportKindCard[] = [...H3_IMPORT_KINDS, H3_BATCH_MERGE_KIND]

// ===== 分层组与核算参与方式（展示层单一来源） =====
/**
 * 三组分层的展示元数据。
 *
 * 分工刻意分开：`group` 决定「摆在哪」，`participation` 决定「导入后会发生什么」。
 * 把归档类数据收进折叠区只是降低噪音；"不参与核算"必须靠 participation 声明来表达，
 * 不能只靠视觉降权——否则将来启用某类数据时，只能回头翻页面找散落的判断。
 */
export const H3_IMPORT_GROUPS: Array<{
  key: H3ImportGroup
  label: string
  hint: string
  /** 下拉里默认收起（低频/归档类） */
  collapsed: boolean
}> = [
  { key: 'core', label: '核心业务', hint: '确认后进入经营核算', collapsed: false },
  { key: 'base', label: '基础数据', hint: '更新主档台账，不进入核算', collapsed: false },
  { key: 'archive', label: '历史数据导入', hint: '仅归档留存，不参与核算', collapsed: true }
]

/** participation → 批次行「处理结果」标签（语义固定，不由各页面自行拼文案） */
export const H3_PARTICIPATION_META: Record<
  H3ImportParticipation,
  { label: string; tag: H3ElTagType; hint: string }
> = {
  in_accounting: { label: '已进入核算', tag: 'success', hint: '客户 / 订单 / 回款 / 利润 / 提成' },
  updates_master: { label: '已更新基础台账', tag: 'primary', hint: '只更新台账，不进入核算' },
  archive_only: { label: '仅归档', tag: 'info', hint: '不参与客户、回款、利润与提成计算' }
}
/** 按 data_type 取核算参与方式；未知类型返回 null，由调用方决定兜底展示 */
export function h3ParticipationOf(dbType?: string | null): H3ImportParticipation | null {
  const t = normalizeH3DbType(dbType)
  return H3_BATCH_KINDS.find(k => k.dbType === t)?.participation ?? null
}
/** 按 data_type 取该批次的「处理结果」标签元数据；未知类型返回 null */
export function h3ParticipationMeta(
  dbType?: string | null
): { label: string; tag: H3ElTagType; hint: string } | null {
  const p = h3ParticipationOf(dbType)
  return p ? H3_PARTICIPATION_META[p] : null
}

/** 未知数据类型的兜底下一步 */
export const H3_BATCH_FALLBACK_ACTION: H3ImportNextAction = {
  label: '查看导入记录',
  description: '等待系统处理结果。',
  path: '/h3-imports'
}

/** data_type → 中文标题（销售出库/生产任务工单/…/仓库明细/库存合并入库）；未知值回显原文 */
export function h3DbTypeLabel(dbType?: string | null): string {
  const t = normalizeH3DbType(dbType)
  return H3_BATCH_KINDS.find(kind => kind.dbType === t)?.title || t
}
