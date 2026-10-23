import { http } from './http'
import { createRequestKey } from '@/utils/requestKey'
import type { LedgerDetailResult } from '@/types/h3Import'

export type TrackingMilestoneStatus = 'PENDING' | 'IN_PROGRESS' | 'DONE'

/**
 * 跟单待办提醒代码（派生，不落库）：把「业务事实已经走到前面、跟单记录还没跟上」的单挑出来。
 * 一单只出一个代码，按严重度取第一条命中：已完工待跟单 > 已入库待跟单 > 已排产待跟单
 * > 已入库待登记（已在跟单但入库节点漏登记）> 7 天未更新。
 */
export type TrackingActionCode =
  | 'FINISHED_PENDING'
  | 'INBOUND_PENDING'
  | 'SCHEDULED_PENDING'
  | 'INBOUND_UNLOGGED'
  | 'STALE_FOLLOWUP'
  | 'SCHEDULE_DUE'
  | 'DELIVERY_PRESSURE'

export interface TrackingStageProgress {
  code: string
  label: string
  status: TrackingMilestoneStatus
  /** 节点 DONE 时的完成日期（未完成为 null）；导出按节点展开成「各阶段完成日期」列。 */
  completedDate?: string | null
  /**
   * 该节点的备注（跟单员报进度时手写的说明，10-15 起导出可带上）。
   * ⚠️ 详情的 `TrackingMilestone.note` 是同一份数据的详情口径；这里给列表/导出用。
   */
  note?: string | null
  /** 按需工序（封口机的氧化/喷特氟龙）：不是每单都走。 */
  optional?: boolean
  /** optional 为真时才有意义：本单是否真的走这道工序（有里程碑行）。 */
  applicable?: boolean
}

export interface TrackingOrder {
  salesTraceReceivedQuantity?: number | null
  salesTraceConfirmedQuantity?: number | null
  salesTraceStale?: boolean | number | null
  salesTraceMixedUnits?: boolean | number | null
  salesTraceCoverageThrough?: string | null
  salesTraceBeyondCoverage?: boolean | number | null
  id: number
  trackingMonth?: string | null
  orderNo: string
  sourceStatus?: string | null
  trackingStatus?: 'pending' | 'in_progress' | 'completed' | 'stopped'
  /** 本地跟单停止处置；氚云生产任务单状态不变。 */
  stoppedAt?: string | null
  stoppedReason?: string | null
  stoppedByName?: string | null
  /**
   * 跟单结案确认：跟单员主动确认结束。设置后状态按「结案优先」展示为「跟单已完成」，
   * 节点是否齐全与状态解耦——不再为了凑齐节点而补录虚假生产记录。
   * 互斥规则：已停止跟单的任务不能结案，已结案的任务不能再停止。
   */
  closedAt?: string | null
  closedByName?: string | null
  closedNote?: string | null
  /**
   * 跟单待办提醒（派生，不落库）：一单只出一个代码，按严重度取第一条命中。
   * 用于回答「这单现在该我做什么、为什么轮到我了」，筛选入参同名 `action`。
   */
  trackingAction?: TrackingActionCode | null
  latestStartDate?: string | null
  predictedFinishDate?: string | null
  scheduleRisk?: string | null
  /** ⚠️ 前端已不再展示/筛选（字段设计有问题，2026-10-10 从跟单列表整列移除）；后端仍计算，保留契约。 */
  reminder?: 'none' | 'overdue' | 'due' | 'delayed' | 'near'
  externalSource?: string | null
  externalId?: string | null
  productNameSnapshot: string
  specificationSnapshot?: string | null
  /** 氚云生产任务单“模具排列”原值，如“一出四”。 */
  moldLayout?: string | null
  plannedQuantity: number
  completedQuantity?: number
  unit: string
  workshopType?: string | null
  customerNameSnapshot: string
  salespersonNameSnapshot?: string | null
  dueDate?: string | null
  /** 氚云生产任务单的申请日期（production_orders.application_date）。 */
  applicationDate?: string | null
  sourceCreatedAt?: string | null
  expectedFinishDate?: string | null
  actualFinishDate?: string | null
  /**
   * 采购入库时间（首次入库日期）：氚云生产任务单自身没有入库时间，
   * 由采购入库明细「选择生产任务单」关联回工单，是设备真正进仓库的日期。
   */
  inboundDate?: string | null
  /** 多次分批发货时的末次入库日期；与 inboundDate 相同表示只入库一次。 */
  inboundLastDate?: string | null
  /** 关联到的采购入库单数；0 表示该工单尚无采购入库记录。 */
  inboundCount?: number | null
  /** 报工完工 → 首次入库 的自然日间隔；为负表示入库早于报工（现场补录顺序）。 */
  reportToInboundDays?: number | null
  /**
   * 入库进度对照日期：跟单员「入库」节点优先，否则为采购首次入库。
   * 首次入库可能只是部分数量，不能据此认定整单完工；分析页使用独立的整单判据。
   */
  deliveredDate?: string | null
  /**
   * 交付基准（派生，不落库）= 跟单员确认后的车间预计完成日
   * （COALESCE(forecastFinishDate, expectedFinishDate, 有效的 dueDate)）。车间申请延期后的新日期只落在 forecastFinishDate，
   * 氚云工单原值 expectedFinishDate 不会回写，故基准必须取这一列。
   */
  deliveryBaseline?: string | null
  /**
   * 生产交付结果（派生，不落库）：以交付基准（deliveryBaseline）比较实际交付日期（跟单入库确认 / 采购首次入库）。
   * PENDING=未交付 / ON_TIME=按期交付 / OVERDUE=逾期交付（天数见 deliveryOverdueDays）/ NO_BASELINE=已交付（无基准）。
   * ⚠️ 用词：这里是「逾期」（实际交付晚于基准这一既成事实）；「延期」另有所指——车间预计完成实现不了时
   * 与业务员协商另订新完成日期的主动动作（state.forecastFinishDate / delayReason），两者不可混用。
   * ⚠️ 与下面 state 里的 deliveryResult（跟单员手工登记的交付结果）不是一份数据。
   */
  deliveryOutcome?: 'PENDING' | 'ON_TIME' | 'OVERDUE' | 'NO_BASELINE' | null
  /** 逾期天数 = 实际交付日期 − 交付基准；按期或无从比较时为 null。 */
  deliveryOverdueDays?: number | null
  timeReviews?: TrackingTimeReview[]
  scheduleTime?: string | null
  finishDelayDays?: number | null
  /** 氚云回填的入库完成标记（tinyint 可能是 0/1 也可能是布尔）；与 actualFinishDate 一起构成「已完工入库」。 */
  warehousingCompleted?: boolean | number | null
  productionCompleted?: boolean | number | null
  /** 业务员提出的订单要求日期；车间未另填预计完成日时，生产完成基准沿用此日期。 */
  orderRequiredDate?: string | null
  plannedStartDate?: string | null
  forecastFinishDate?: string | null
  riskCategory?: string | null
  delayReason?: string | null
  /** ⚠️ 前端已不再展示（2026-10-10 从跟单列表整列移除）。注意：核实记录 TrackingTimeReview 里另有一个同名子字段，两者不是一份数据。 */
  nextFollowupDate?: string | null
  /** 按生产车间确定，即使尚无跟单记录也已有责任人。 */
  assigneeEmployeeId?: number | null
  assigneeName?: string | null
  lastFollowupAt?: string | null
  version: number
  stageDoneCount?: number
  stageTotal?: number
  stageProgress?: TrackingStageProgress[]
  deliveryResult?: string | null
  shipmentStatus?: string | null
  remark?: string | null
  /** 氚云生产任务单「备注」原文，存于 production_orders.production_requirement；与本地跟单备注分开。 */
  sourceOrderRemark?: string | null
  /** 氚云生产任务单的可选配置原文，用于辨认氧化/喷特氟龙、木箱等按需加工。 */
  sourceOptionalConfiguration?: string | null
}

export type TrackingTimeReviewKind = 'SOURCE_DATE' | 'ACCEPTANCE' | 'COMMITMENT' | 'REPORTING' | 'WAREHOUSING' | 'OVERDUE'
export type TrackingTimeReviewStatus = 'OPEN' | 'RESOLVED' | 'ON_TIME' | 'NEGOTIATED'
export interface TrackingTimeReview {
  id?: number
  productionOrderId?: number
  kind: TrackingTimeReviewKind
  status: TrackingTimeReviewStatus
  reason: string
  contactName?: string | null
  agreedDate?: string | null
  nextFollowupDate?: string | null
  sourceDueDate?: string | null
  sourceApplicationDate?: string | null
  sourceCreatedAt?: string | null
  sourceExpectedDate?: string | null
  sourceScheduleTime?: string | null
  sourceReportDate?: string | null
  sourceInboundDate?: string | null
  createdByName?: string | null
  createdAt?: string | null
}

export interface TrackingMilestone {
  code: string
  label: string
  status: TrackingMilestoneStatus
  completedDate: string | null
  note: string | null
  /** 当前任务单的人工预计日期与原因；为空时使用产品/通用参考推算。 */
  manualReferenceDate?: string | null
  manualReferenceReason?: string | null
  referenceSource?: 'MANUAL' | 'SYSTEM' | 'NONE'
  optional?: boolean
  applicable?: boolean
  /** 该工序一般耗时（天）＝基准表里上游完成→本节点完成的中位间隔；无基准（如机器车间暂无数据）为 null。 */
  typicalDays?: number | null
  /**
   * 当前生效的节点参考完成日期：人工预计优先；否则从排产锚点与产品/通用参考天数逐节点推算。
   * 该结果不落库，人工预计日期单独保存在 manualReferenceDate。
   */
  referenceDate?: string | null
  /** 人工预计应用前的系统推算值；只读对照。 */
  systemReferenceDate?: string | null
  /** 为赶上本单车间交期倒排出的最晚建议日期；不写库。 */
  targetDate?: string | null
  /** 超参考未完成的天数（今天 > 参考完成日期且状态非已完成）；已完成或未超时为 null。 */
  referenceOverdueDays?: number | null
}

export interface TrackingCandidate {
  id: number
  orderNo: string
  productName: string
  workshopType: string | null
  plannedStartDate: string | null
  dueDate: string | null
  expectedFinishDate?: string | null
  plannedQuantity: number | null
}

/** 「关联工单」窗口的智能候选：带评分与命中/冲突字段（关键词搜索结果不带评分）。 */
export interface TrackingWorkbookCandidate extends TrackingCandidate {
  score?: number
  matchedFields?: string[]
  conflictFields?: string[]
}

/** 待处理按钮计数用的轻量统计（不拉明细）。 */
export interface TrackingWorkbookSummary {
  pending: number
  review: number
  actionable: number
  archived: number
  skipped: number
  imported: number
  updated: number
  total: number
}

/** 归档规则：命中产品名的表格行在导入时直接归为已归档。 */
export interface TrackingWorkbookArchiveRule {
  id: number
  productKey: string
  productLabel: string
  reason: string
  createdByName: string
  createdAt: string
}

export interface TrackingWorkbookRow {
  id: number
  sheetName: string
  sheetRow: number
  sourceOrderNo: string | null
  productName: string | null
  requiredCompletionDate: string | null
  plannedStartDate: string | null
  sourceQuantity: string | null
  matchNote: string | null
  matchStatus: 'PENDING' | 'REVIEW' | 'IMPORTED' | 'UPDATED' | 'EXISTING' | 'REVIEWED' | 'REVIEW_IGNORED' | 'SKIPPED' | 'SUPERSEDED' | 'ARCHIVED'
  sourceHash: string
  sourceFile: string | null
  createdAt: string
  /** 该车间之后导入过更新版本却没覆盖到本行 ⇒ 不必再逐行处理这条旧行。 */
  stale?: number | null
  supersededAt?: string | null
  supersededByHash?: string | null
  reviewedAt?: string | null
  reviewedByEmployeeId?: number | null
  productionOrderId?: number | null
}

export interface TrackingWorkbookImportRow {
  sheet: string
  rowNumber: number
  sourceOrderNo: string | null
  productName: string | null
  plannedStartDate: string | null
  requiredCompletionDate: string | null
  quantity: string | null
  status: 'READY' | 'UPDATE' | 'EXISTING' | 'REVIEW' | 'ARCHIVED' | 'PENDING'
  productionOrderId: number | null
  workOrderNo: string | null
  reason: string
}

export interface TrackingWorkbookPreview {
  sha256: string
  planHash: string
  total: number
  ready: number
  update: number
  existing: number
  review: number
  archived: number
  pending: number
  alreadyUploaded: boolean
  rows: TrackingWorkbookImportRow[]
}

export interface TrackingWorkbookApplyResult {
  sha256: string
  imported: number
  updated: number
  existing: number
  review: number
  archived: number
  pending: number
  alreadyUploaded: boolean
}

/**
 * 导回（导出件改完再导回）：只回写跟单员维护的四类字段
 * —— 跟单备注 / 车间预计完成（＝延期至）/ 延期原因 / 各节点完成日期；空白＝不修改。
 */
export interface TrackingRoundTripChange {
  field: string
  before: string
  after: string
}

export type TrackingRoundTripStatus = 'WRITE' | 'UNCHANGED' | 'UNMATCHED' | 'FORBIDDEN' | 'DUPLICATE' | 'INVALID'

export interface TrackingRoundTripRow {
  sheet: string
  excelRow: number
  orderNo: string | null
  productName: string | null
  workshop: string | null
  status: TrackingRoundTripStatus
  /** 跳过原因或提示（产品/车间与系统不一致等）。 */
  note: string
  changes: TrackingRoundTripChange[]
}

export interface TrackingRoundTripPreview {
  sha256: string
  planHash: string
  total: number
  write: number
  unchanged: number
  unmatched: number
  forbidden: number
  duplicate: number
  invalid: number
  rows: TrackingRoundTripRow[]
}

export interface TrackingRoundTripApplyResult {
  sha256: string
  written: number
  unchanged: number
  skipped: number
  rows: TrackingRoundTripRow[]
}

/** 导回预检：零写入，逐行给出「能写什么、为什么跳过」。 */
export const previewTrackingRoundTrip = async (file: File) => {
  const form = new FormData(); form.append('file', file)
  return (await http.post<TrackingRoundTripPreview>('/manufacturing/followups/round-trip/preview', form)).data
}

/** 导回应用：整批一个事务；文件与库内跟单都要与预检一致（哈希 + 计划指纹）。 */
export const applyTrackingRoundTrip = async (file: File, expectedHash: string, expectedPlanHash: string) => {
  const form = new FormData(); form.append('file', file); form.append('expectedHash', expectedHash); form.append('expectedPlanHash', expectedPlanHash)
  return (await http.post<TrackingRoundTripApplyResult>('/manufacturing/followups/round-trip/apply', form, {
    headers: { 'X-Request-Key': createRequestKey() }
  })).data
}

export interface TrackingOperation {
  id: number
  sequenceNo: number
  operationNameSnapshot: string
  status: string
  reportedQuantity: number
  requiredQuantity: number
  completedAt?: string | null
}

export interface TrackingEntry {
  id: number
  version: number
  content: string
  createdByName: string
  createdAt: string
  /** 本次跟进前后的状态与节点快照（JSON 字符串），用于展示变更对比。 */
  beforeJson?: string | null
  afterJson?: string | null
}

export interface TrackingFilterOptions {
  /** 车间（生产线）下拉，与列表同一可见性口径。 */
  workshops: string[]
  /** 当前可见工单中的模具排布原值，供精确筛选。 */
  moldLayouts?: string[]
  sourceStatuses: string[]
  /** 有归属跟单的跟单员。 */
  assignees: { id: number; name: string }[]
  /** 列表里出现过的业务员姓名（去重），供「业务员」列头多选筛选使用。 */
  salespersons?: string[]
  months?: { month: string; count: number }[]
  /**
   * 各类跟单待办的条数：与列表同一段可见范围与同一个 FROM（含采购入库关联），
   * 故下拉里的数字必然等于点进去的条数；无提醒的单不出现在这个列表里。
   */
  actionCounts?: { action: TrackingActionCode; count: number }[]
  /**
   * 各道「按需工序」的工单条数（氧化/喷特氟龙 = OXIDATION、木箱 = WOODEN_CRATE）：
   * 与列表同一段可见范围与同一个 FROM，故下拉里的数字必然等于点进去的条数。
   * 口径＝源单配置要求这道工序 或 已有该节点的跟单记录。
   */
  optionalStageCounts?: { code: string; count: number }[]
}

export interface TrackingAnalyticsBucket {
  name?: string
  dueTasks: number; cancelled: number; matured: number; openNotDue: number; openDueToday: number
  completionRate: number | null; onTimeAchievementRate: number | null; completedOnTimeRate: number | null
  total: number; stopped: number; delivered: number; comparable: number; onTime: number; overdue: number
  onTimeRate: number | null; deliveredNoBaseline: number
  open: number; openOverdue: number; openDueSoon: number; openNoBaseline: number; openOverdueDays: number
  requestedComparable: number; requestedOnTime: number; requestedOnTimeRate: number | null
  extended: number; advanced: number; extensionDays: number; extensionReasonMissing: number
  overdueReasonMissing: number; overdueDays: number; averageOverdueDays: number | null
  overdue1to3: number; overdue4to7: number; overdue8plus: number
  receiptOnly: number; manualOnly: number; bothSources: number
}
export interface TrackingAnalytics {
  missingExpected: number
  totals: TrackingAnalyticsBucket
  workshops: TrackingAnalyticsBucket[]
  months: TrackingAnalyticsBucket[]
  riskCategories: { name: string; count: number }[]
  extensionReasons: { name: string; count: number }[]
  overdueReasons: { name: string; count: number }[]
  asOf: string
}
export type TrackingAnalysisDateBasis = 'baseline'
export const fetchTrackingAnalytics = async (params: { fromDate?: string; toDate?: string; workshop?: string; dateBasis?: TrackingAnalysisDateBasis }) =>
  (await http.get<TrackingAnalytics>('/manufacturing/followups/analytics', { params })).data
export interface TrackingAnalyticsOrder {
  id: number
  orderNo: string
  productName: string
  /**
   * 业务员 / 产品规格 / 模具出数：只服务于下钻列表的「一眼认出这单是什么」，
   * 不参与任何指标判据。三者都可能缺（氚云未填），缺失时为 null，展示成「—」。
   * 规格口径与生产跟单列表一致（尺寸 / 形状 / 材质，用「 / 」连接）。
   */
  salesperson: string | null
  specification: string | null
  moldLayout: string | null
  workshop: string
  submittedDate: string | null
  baseline: string | null
  deliveredDate: string | null
  status: 'ON_TIME' | 'LATE' | 'UNFINISHED' | 'CANCELLED' | 'MISSING_EXPECTED'
  overdueDays: number | null
  unfinishedOverdueDays: number | null
}
export const fetchTrackingAnalyticsOrders = async (params: {
  fromDate?: string; toDate?: string; workshop?: string; dateBasis?: TrackingAnalysisDateBasis; month?: string
  bucket: string; reason?: string
  /** 按原因**类别**下钻（组装 / 入库 / 工期 / 业务员 / 配件 / 模具 …），取值是后端词典的固定 key。 */
  reasonCategory?: string
  page: number; size: number
}) => (await http.get<{ items: TrackingAnalyticsOrder[]; total: number; page: number; size: number }>(
  '/manufacturing/followups/analytics/orders', { params }
)).data

/**
 * ====================== 跟单周复盘 ======================
 * 同一个周期区间出「需完成 / 按期 / 逾期 / 未完工 + 逾期天数分档 + 原因分布」，
 * 与「生产交期达成分析」共用同一套口径（卡片 65 = 按期 55 + 逾期 7 + 未完工 3），
 * 区别只在组织方式：这里按跟单员手写周报的表格结构输出。
 */
/** 六类原因之一；OTHER＝词典未收录的写法，UNRECORDED＝没登记原因。 */
export interface TrackingReasonCategory {
  key: string
  label: string
  count: number
  /** 占比（%），分母是该榜的总单数；无样本时为 null —— 不用 0% 冒充结论。 */
  rate: number | null
  /** 该类别下条数最多的几条原因原文（来自跟单员填写的原话），看板「备注」列直接展示。 */
  samples: string[]
}
export interface TrackingOverdueLevel {
  key: string
  label: string
  range: string
  count: number
  rate: number | null
}
export interface TrackingWeeklyReviewSummary {
  dueTasks: number
  onTime: number
  overdue: number
  open: number
  /** 按期达成率＝按期 ÷ 应完成（周报里的「按期完工率」）；无样本时 null。 */
  onTimeAchievementRate: number | null
  completionRate: number | null
  extended: number
  extensionDays: number
  overdueDays: number
  averageOverdueDays: number | null
  openOverdue: number
  openOverdueDays: number
  cancelled: number
  stopped: number
  missingExpected: number
}
export interface TrackingWeeklyReview {
  period: { from: string; to: string; days: number }
  asOf: string
  /** 车间下拉选项，与区间内出现的车间同一份口径。 */
  workshops: string[]
  summary: TrackingWeeklyReviewSummary
  /** 逾期榜的分母（＝逾期单数），与各分档、各原因之和一致。 */
  overdueTotal: number
  /** 延期榜的分母（＝协商延后单数）。 */
  extensionTotal: number
  overdueLevels: TrackingOverdueLevel[]
  overdueReasons: TrackingReasonCategory[]
  extensionReasons: TrackingReasonCategory[]
}
export const fetchTrackingWeeklyReview = async (params: { fromDate?: string; toDate?: string; workshop?: string }) =>
  (await http.get<TrackingWeeklyReview>('/manufacturing/followups/weekly-review', { params })).data

export const fetchTracking = async (params: Record<string, string | number | boolean>) =>
  (await http.get<{ items: TrackingOrder[]; total: number; page: number; size: number; visibility?: string }>('/manufacturing/followups', { params })).data

/**
 * 导出用：同一套筛选条件下的**全部**行（服务端不分页）。
 * 与列表同一可见性口径 —— 跟单员导出到的仍只是本人可见的任务。
 * 返回 JSON 而非文件：中文文案口径只在前端一套，避免前后端各写一份。
 */
export const fetchTrackingExport = async (params: Record<string, string | number | boolean>) =>
  (await http.get<{
    items: TrackingOrder[]
    /** 满足筛选条件的总条数（与列表同一口径）。 */
    total: number
    /** 本次实际返回条数；被截断时小于 total。 */
    exported: number
    truncated: boolean
    maxRows: number
    visibility?: string
  }>('/manufacturing/followups/export', { params })).data

/** 搜索栏下拉选项（车间 / 跟单员）；后端未升级时调用方需容错。 */
export const fetchTrackingFilterOptions = async () =>
  (await http.get<TrackingFilterOptions>('/manufacturing/followups/filter-options')).data

/**
 * 头部统计卡已下线（口径不准：把已完工入库的也算进「已超期」）。
 * 端点与封装保留备用；重做统计前先厘清「已超期」是否应排除已完工入库。
 */
export const fetchTrackingSummary = async () =>
  (await http.get<{
    untracked: number; due: number; overdue: number; delayed: number; timeReviewDue: number
  }>('/manufacturing/followups/summary')).data

export const fetchTrackingDetail = async (id: number) =>
  (await http.get<{
    order: TrackingOrder
    schedule: {
      latestStartDate: string | null
      predictedFinishDate: string | null
      slackDays: number | null
      risk: 'NO_BASELINE' | 'SCHEDULE_LATE' | 'SCHEDULE_DUE' | 'SCHEDULE_NORMAL' | 'UNKNOWN' | 'PRESSURE' | 'NO_BUFFER' | 'ON_TRACK'
      deadline: string | null
    }
    milestones: TrackingMilestone[]
    operations: TrackingOperation[]
    entries: TrackingEntry[]
    timeReviews: TrackingTimeReview[]
    timeReviewHistory: TrackingTimeReview[]
    /** 本单需要完成的节点数（必经节点 + 本单适用的按需工序）——进度分母的唯一来源。 */
    stageTotal?: number
  }>(`/manufacturing/followups/${id}`)).data

export const addTrackingTimeReview = async (id: number, review: {
  kind: TrackingTimeReviewKind
  status: TrackingTimeReviewStatus
  reason: string
  contactName: string | null
  agreedDate: string | null
  nextFollowupDate: string | null
}) => (await http.post<{ id: number }>(`/manufacturing/followups/${id}/time-reviews`, review,
  { headers: { 'X-Request-Key': createRequestKey() } })).data

/**
 * 修改某类时间核实的**最新一条**记录（写错原因/补漏细节）。
 * 不追加历史：列表 badge、详情「已记录原因」、导出「逾期原因」都读最新一条，改这条才改得动它们。
 */
export const updateTrackingTimeReview = async (id: number, noteId: number, review: {
  kind: TrackingTimeReviewKind
  status: TrackingTimeReviewStatus
  reason: string
  contactName: string | null
  agreedDate: string | null
  nextFollowupDate: string | null
}) => (await http.put<{ id: number }>(`/manufacturing/followups/${id}/time-reviews/${noteId}`, review,
  { headers: { 'X-Request-Key': createRequestKey() } })).data

export interface TrackingPurchaseReceipt {
  id: number
  sourceNo?: string | null
  purchaseDate?: string | null
  warehouseName?: string | null
}

export const fetchTrackingPurchaseReceipts = async (id: number) =>
  (await http.get<TrackingPurchaseReceipt[]>(`/manufacturing/followups/${id}/purchase-receipts`)).data

export const fetchTrackingPurchaseReceiptDetail = async (orderId: number, receiptId: number) =>
  (await http.get<LedgerDetailResult>(`/manufacturing/followups/${orderId}/purchase-receipts/${receiptId}`)).data

export interface TrackingSalesTraceReceipt {
  receiptItemId: number
  receiptId: number
  receiptNo: string
  receiptDate: string | null
  receiptStatus: string | null
  receiptWarehouse: string | null
  productCode: string | null
  productName: string | null
  spec: string | null
  unit: string | null
  quantity: number | null
  confirmedQuantity: number
}
export interface TrackingSalesTraceAllocation {
  id: number
  receiptItemId: number
  saleItemId: number
  receiptNo: string
  outboundNo: string
  unit: string
  quantity: number
  reason: string
  status: string
  currentSource: number
  confirmedByName: string
  confirmedAt: string
  revokeReason?: string | null
}
export interface TrackingSalesTraceCandidate {
  saleItemId: number
  outboundId: number
  outboundNo: string
  outboundDate: string | null
  outboundWarehouse: string | null
  outboundStatus: string
  businessType: string
  productCode: string | null
  productName: string | null
  spec: string | null
  unit: string | null
  quantity: number
  sameUnit: number
  sameWarehouse: number
  dateAfterReceipt: number
}
export interface TrackingSalesTrace {
  orderId: number
  requiredQuantity: number | null
  requiredUnit?: string | null
  receivedQuantity: number | null
  confirmedQuantity: number | null
  unitSummaries: { unit: string; receivedQuantity: number; confirmedQuantity: number }[]
  receipts: TrackingSalesTraceReceipt[]
  allocations: TrackingSalesTraceAllocation[]
  /** Older running backends may omit this metadata while still returning candidates. */
  outboundCoverageThrough?: string | null
  coverageNote: string
}
export const fetchTrackingSalesTrace = async (id: number) =>
  (await http.get<TrackingSalesTrace>(`/manufacturing/followups/${id}/sales-trace`)).data
export const fetchTrackingSalesCandidates = async (id: number, receiptItemId: number, keyword = '') =>
  (await http.get<TrackingSalesTraceCandidate[]>(`/manufacturing/followups/${id}/sales-trace/receipts/${receiptItemId}/candidates`, { params: { keyword } })).data
export const fetchTrackingSalesOutboundDetail = async (id: number, outboundId: number) =>
  (await http.get<LedgerDetailResult>(`/manufacturing/followups/${id}/sales-trace/outbounds/${outboundId}`)).data
export const confirmTrackingSalesAllocation = async (id: number, body: { receiptItemId: number; saleItemId: number; quantity: number; reason: string }) =>
  (await http.post<{ id: number }>(`/manufacturing/followups/${id}/sales-trace/allocations`, body,
    { headers: { 'X-Request-Key': createRequestKey() } })).data
export const revokeTrackingSalesAllocation = async (id: number, allocationId: number, reason: string) =>
  (await http.post<{ revoked: boolean }>(`/manufacturing/followups/${id}/sales-trace/allocations/${allocationId}/revoke`, { reason })).data

export const fetchTrackingCandidates = async (keyword: string, workbookRowId?: number) =>
  (await http.get<TrackingCandidate[]>('/manufacturing/followups/candidates', { params: { keyword, workbookRowId } })).data

export const enrollTrackingOrder = async (id: number) =>
  (await http.post<{ id: number }>(`/manufacturing/followups/enroll/${id}`, {}, {
    headers: { 'X-Request-Key': createRequestKey() }
  })).data

export const fetchTrackingWorkbookRows = async (history = false) =>
  (await http.get<TrackingWorkbookRow[]>('/manufacturing/followups/workbook-rows', { params: { history } })).data

export const previewTrackingWorkbook = async (file: File) => {
  const form = new FormData(); form.append('file', file)
  return (await http.post<TrackingWorkbookPreview>('/manufacturing/followups/workbook/preview', form)).data
}

export const applyTrackingWorkbook = async (file: File, expectedHash: string, expectedPlanHash: string) => {
  const form = new FormData(); form.append('file', file); form.append('expectedHash', expectedHash); form.append('expectedPlanHash', expectedPlanHash)
  return (await http.post<TrackingWorkbookApplyResult>('/manufacturing/followups/workbook/apply', form, {
    headers: { 'X-Request-Key': createRequestKey() }
  })).data
}

export const resolveTrackingWorkbookRow = async (rowId: number, productionOrderId: number | null, skip = false) =>
  (await http.post<{ id: number }>(`/manufacturing/followups/workbook-rows/${rowId}/resolve`, {
    productionOrderId, skip
  }, { headers: { 'X-Request-Key': createRequestKey() } })).data

/** 按钮计数：轻量统计，不拉待处理明细。 */
export const fetchTrackingWorkbookSummary = async () =>
  (await http.get<TrackingWorkbookSummary>('/manufacturing/followups/workbook-rows/summary')).data

/** 关联工单窗口的智能候选（按车间/产品/交期/订单号/数量评分排序）。 */
export const fetchTrackingWorkbookCandidates = async (rowId: number) =>
  (await http.get<TrackingWorkbookCandidate[]>(`/manufacturing/followups/workbook-rows/${rowId}/candidates`)).data

export const fetchTrackingWorkbookArchiveRules = async () =>
  (await http.get<TrackingWorkbookArchiveRule[]>('/manufacturing/followups/workbook-rows/archive-rules')).data

/** 归档一行（并按其产品名建立归档规则，后续同名行导入时自动归档）。 */
export const archiveTrackingWorkbookRow = async (rowId: number, reason: string) =>
  (await http.post<{ id: number }>('/manufacturing/followups/workbook-rows/archive', { rowId, reason },
    { headers: { 'X-Request-Key': createRequestKey() } })).data

/** 批量处理待处理行：跳过 / 归档 / 恢复为待处理。 */
export const batchResolveTrackingWorkbookRows = async (rowIds: number[], action: 'skip' | 'archive' | 'restore') =>
  (await http.post<{ changed: number }>('/manufacturing/followups/workbook-rows/batch', { rowIds, action },
    { headers: { 'X-Request-Key': createRequestKey() } })).data

/** 撤销归档规则；restoreRows=true 时把由它归档的行一并放回待处理。 */
export const revokeTrackingWorkbookArchiveRule = async (ruleId: number, restoreRows = true) =>
  (await http.post<{ restored: number; productKey: string }>(
    `/manufacturing/followups/workbook-rows/archive-rules/${ruleId}/revoke`, { restoreRows },
    { headers: { 'X-Request-Key': createRequestKey() } })).data

export interface TrackingWorkbookDifference {
  key: string
  label: string
  currentValue: string | null
  proposedValue: string
}
export interface TrackingWorkbookReview {
  rowId: number
  productionOrderId: number
  version: number
  differences: TrackingWorkbookDifference[]
}
export const fetchTrackingWorkbookReview = async (rowId: number) =>
  (await http.get<TrackingWorkbookReview>(`/manufacturing/followups/workbook-rows/${rowId}/review`)).data

export const applyTrackingWorkbookReview = async (rowId: number, expectedVersion: number, selectedKeys: string[]) =>
  (await http.post<{ id: number }>(`/manufacturing/followups/workbook-rows/${rowId}/apply-review`,
    { expectedVersion, selectedKeys }, { headers: { 'X-Request-Key': createRequestKey() } })).data

export const ignoreTrackingWorkbookReview = async (rowId: number) =>
  (await http.post<{ id: number }>(`/manufacturing/followups/workbook-rows/${rowId}/ignore-review`, {},
    { headers: { 'X-Request-Key': createRequestKey() } })).data

/**
 * 登记延期日期及原因；节点推进使用 addTrackingStageEntry，分别保存。
 * content 留空由后端按变更自动生成。
 */
export const addTrackingEntry = async (
  id: number,
  version: number,
  milestones: TrackingMilestone[],
  forecastFinishDate: string | null,
  delayReason: string | null = null
) =>
  (await http.post<{ version: number }>(`/manufacturing/followups/${id}/entries`, {
    version, content: '', state: { forecastFinishDate, delayReason }, milestones
  }, {
    headers: { 'X-Request-Key': createRequestKey() }
  })).data

/** 仅保存节点推进；不触碰延期日期和原因。 */
export const addTrackingStageEntry = async (id: number, version: number, milestones: TrackingMilestone[]) =>
  (await http.post<{ version: number }>(`/manufacturing/followups/${id}/entries`, {
    version, content: '', state: null, milestones
  }, { headers: { 'X-Request-Key': createRequestKey() } })).data

export const changeTrackingDisposition = async (id: number, version: number, stopped: boolean, reason: string) =>
  (await http.post<{ version: number }>(`/manufacturing/followups/${id}/disposition`,
    { version, stopped, reason }, { headers: { 'X-Request-Key': createRequestKey() } })).data

/**
 * 跟单结案确认 / 撤销：与「标记不再生产」互斥的本地处置（不修改氚云生产任务单）；
 * closed=true 写结案时间/原因/操作人；closed=false 清空三列，恢复按节点进度派生状态。
 */
export const changeTrackingClosure = async (id: number, version: number, closed: boolean, note: string) =>
  (await http.post<{ version: number }>(`/manufacturing/followups/${id}/closure`,
    { version, closed, note }, { headers: { 'X-Request-Key': createRequestKey() } })).data

export interface TrackingBenchmarkStage {
  stageCode: string
  stageLabel: string
  /** 这一段的起止：首段锚点是「排产日」，其余取上一节点。 */
  intervalLabel: string
  /** 按需工序（封口机的氧化/喷特氟龙）：配了也不占链，不影响后续节点的参考日期。 */
  optionalStage: boolean
  /** 当前生效的一般耗时（天）；未配置为 null。 */
  typicalDays: number | null
  /** 当前值推算时的样本行数；管理员手工配置后归零。 */
  sampleCount: number | null
  /** 推算方式说明，或「管理员手工配置」。 */
  baselineNote: string | null
  computedAt: string | null
}

export interface TrackingBenchmarkWorkshop {
  workshopKind: 'SEALER' | 'MACHINE'
  workshopLabel: string
  stages: TrackingBenchmarkStage[]
}

/**
 * 节点参考基准矩阵（仅系统管理员，权限 manufacturing.baseline.manage）。
 * 返回两个车间类别 × 各自全部节点，含尚未配置的节点（typicalDays 为 null）。
 */
export const fetchTrackingNodeBenchmarks = async () =>
  (await http.get<{ workshops: TrackingBenchmarkWorkshop[] }>('/manufacturing/followups/node-benchmarks')).data

/** 批量保存节点参考基准；typicalDays 传 null 表示不设基准（删除该节点基准）。 */
export const saveTrackingNodeBenchmarks = async (
  rows: { workshopKind: string; stageCode: string; typicalDays: number | null }[]
) => (await http.put<{ changed: number }>('/manufacturing/followups/node-benchmarks', rows)).data

/**
 * 产品级节点参考工期：每个产品（任务单产品名称配置行）用自己的历史工单统计候选，
 * 候选值与人工发布值并存，只有 typicalDays 参与参考日期计算。
 * 产品未计算过候选时，该产品在矩阵里只有一行 stageCode 为 null 的产品信息。
 */
export interface TrackingProductBenchmarkRow {
  taskConfigId: number
  workshopName: string | null
  configName: string | null
  stageCode: string | null
  /** 人工审核发布值；null＝沿用通用参考。 */
  typicalDays: number | null
  /** 发布来源：MANUAL 人工审核 / P50 按历史样本批量填充 / COPY 从同车间产品复制；null＝未发布（含已撤销）。 */
  publishedSource: 'MANUAL' | 'P50' | 'COPY' | null
  /** 最近一次发布时间。 */
  publishedAt: string | null
  sampleCount: number | null
  p50Days: number | null
  p75Days: number | null
  excludedCount: number | null
  plannedAnchorCount: number | null
  applicationAnchorCount: number | null
  applicationP50Days: number | null
  applicationP75Days: number | null
  zeroDayCount: number | null
  baselineNote: string | null
  computedAt: string | null
}

export const fetchTrackingProductBenchmarks = async () =>
  (await http.get<TrackingProductBenchmarkRow[]>('/manufacturing/followups/product-node-benchmarks')).data

export const saveTrackingProductBenchmark = async (row: { taskConfigId: number; stageCode: string; typicalDays: number | null }) =>
  http.put('/manufacturing/followups/product-node-benchmarks', row)

/** 只重算该产品的候选分位数，不改动已发布的参考值。 */
export const refreshTrackingProductBenchmarkCandidates = async (taskConfigId: number) =>
  (await http.post<TrackingProductBenchmarkRow[]>(`/manufacturing/followups/product-node-benchmarks/${taskConfigId}/refresh-candidates`, {})).data

/** 把候选 P50 批量写成发布值（仅补未发布且样本量达标的节点），返回受影响节点。 */
export const applyTrackingProductBenchmarkP50 = async (taskConfigId: number) =>
  (await http.post<Array<{ stageCode: string; p50Days: number; sampleCount: number }>>(
    `/manufacturing/followups/product-node-benchmarks/${taskConfigId}/apply-p50`, {})).data

/** 样本明细里的一段：相邻两个节点（或锚点→首个节点）之间的自然日间隔。 */
export interface ProductBenchmarkSampleStage {
  stageCode: string
  days: number | null
  /** 是否计入候选统计；false 时 reason 说明为什么被排除。 */
  used: boolean
  reason: string | null
  /** 间隔为 0：多数是批量补录，发布前要抽查（见 reasonsForAnchor）。 */
  zeroDay?: boolean
}
export interface ProductBenchmarkSampleOrder {
  orderId: number
  orderNo: string | null
  submittedAt: string | null
  /** PLANNED＝以排产日为锚点（本地计划开工或氚云排产时间）；APPLICATION＝退到申请日；NONE＝无锚点，该单不计入样本。 */
  anchorKind: 'PLANNED' | 'APPLICATION' | 'NONE'
  stages: ProductBenchmarkSampleStage[]
}
export interface ProductBenchmarkSamples {
  workshop: string
  product: string
  stageCodes: string[]
  summary: {
    matchedOrders: number
    plannedAnchorCount: number
    applicationAnchorCount: number
    noAnchorCount: number
    spanFrom: string | null
    spanTo: string | null
  }
  orders: ProductBenchmarkSampleOrder[]
}

/** 从同车间另一产品复制已发布间隔：只补目标产品尚未发布的节点，已发布的人工值不覆盖。 */
export const copyTrackingProductBenchmarks = async (taskConfigId: number, sourceTaskConfigId: number) =>
  (await http.post<{ copied: string[]; sourcePublished: number; skipped: number }>(
    `/manufacturing/followups/product-node-benchmarks/${taskConfigId}/copy-from/${sourceTaskConfigId}`, {})).data

/** 样本明细（只读）：候选是怎么算出来的，用于发布前抽查。需要 manufacturing.baseline.manage。 */
export const fetchProductBenchmarkSamples = async (taskConfigId: number) =>
  (await http.get<ProductBenchmarkSamples>(`/manufacturing/followups/product-benchmark-samples/${taskConfigId}`)).data

/** 生产工期里的一个节点：days 为 null＝产品发布值与通用参考都没有值，不计入合计。 */
export interface ProductDurationStage {
  stageCode: string
  stageLabel: string
  days: number | null
  /** PRODUCT＝产品发布值 / GENERAL＝通用参考兜底 / NONE＝两级都没值。 */
  source: 'PRODUCT' | 'GENERAL' | 'NONE'
}

/**
 * 产品生产工期（排产 → 入库完成）。两个数互为校验：
 * - referenceDays：各必经节点「生效参考值」之和，与跟单侧推算参考日期同源；
 * - actualP50Days：该产品跟单记录里真实走完的整体天数。
 * 口径差异要点：实测只用**排产锚点**的单，退到申请日的单不进分位（那段是等排产，不是生产）。
 */
export interface ProductDuration {
  taskConfigId: number
  productName: string
  workshopName: string
  workshopKind: 'SEALER' | 'MACHINE'
  lastStage: string
  lastStageLabel: string
  /** 各段参考值的精确和（小数），用来看构成；主数字请用 chainDays。 */
  referenceDays: number
  /**
   * 与跟单侧推算同源的天数：每段先取整到整天再相加（日期只能是整天）。
   * 系统真正在用的是这个数——跟单详情的「入库参考日期」就是 锚点 + chainDays。
   */
  chainDays: number
  referenceComplete: boolean
  stageCount: number
  /** 合计里来自产品自己发布值的节点数（其余来自通用参考）。 */
  productStageCount: number
  missingStages: string[]
  stages: ProductDurationStage[]
  actualP50Days: number | null
  actualP75Days: number | null
  actualSampleCount: number
  /** 未走完（缺末节点完成日）／只用申请日锚点被排除／倒序或超 60 天被排除。 */
  openOrderCount: number
  applicationAnchorCount: number
  excludedCount: number
}

export const fetchProductDuration = async (taskConfigId: number) =>
  (await http.get<ProductDuration>(`/manufacturing/followups/product-duration/${taskConfigId}`)).data

/** 产品的历史生产任务一行：不排除草稿/已取消，与配置页「历史任务单」计数同源。 */
export interface ProductOrderHistoryRow {
  orderId: number
  orderNo: string | null
  status: string | null
  applicationDate: string | null
  scheduledDate: string | null
  requiredDate: string | null
  expectedDate: string | null
  quantity: number | null
  productionProgress: string | null
  warehousingProgress: string | null
  createdAt: string | null
  /** 是否已建跟单记录——历史任务里只有一部分会被跟单。 */
  tracked: boolean
  /** 已完成的**必经**节点数（按需工序不计，与产品参考工期分母同源）。 */
  doneStages: number
  lastDoneDate: string | null
}
export interface ProductOrderHistory {
  product: string
  workshop: string
  requiredStageCount: number
  /** 全量画像，不随 status 筛选变化。 */
  summary: {
    total: number
    cancelled: number | null
    draft: number | null
    tracked: number | null
    firstAt: string | null
    lastAt: string | null
  }
  orders: ProductOrderHistoryRow[]
  /** 命中行数达到上限，列表被截断。 */
  truncated: boolean
}

/** 产品历史任务明细（只读）。status 为空＝全部状态。 */
export const fetchProductOrderHistory = async (taskConfigId: number, status = '', limit = 200) =>
  (await http.get<ProductOrderHistory>(`/manufacturing/followups/product-orders/${taskConfigId}`, { params: { status, limit } })).data

export interface TrackingColumnDefault {
  columnKey: string
  /** 配置表里有没有这一列的显式值；false＝从未配过，visible 取的是代码兜底值。 */
  configured: boolean
  visible: boolean
}

export interface TrackingColumnDefaults {
  columns: TrackingColumnDefault[]
  /** 未配置任何默认时兜底隐藏的列；后端与前端认同一份，避免两边各说各话。 */
  fallbackHidden: string[]
}

/**
 * 生产跟单列表的「全站默认可见列」（管理员配置、对全员生效）。
 * 读走页面读权限（manufacturing.read.*）——全站默认要对所有人生效，员工必须读得到；
 * 写需要 manufacturing.listcolumns.manage（仅管理员）。
 */
export const fetchTrackingColumnDefaults = async () =>
  (await http.get<TrackingColumnDefaults>('/manufacturing/followups/list-column-defaults')).data

/** 全量覆盖保存全站默认列；未提交的列＝回落代码兜底。 */
export const saveTrackingColumnDefaults = async (
  rows: { columnKey: string; visible: boolean }[]
) => (await http.put<{ changed: number }>('/manufacturing/followups/list-column-defaults', rows)).data
