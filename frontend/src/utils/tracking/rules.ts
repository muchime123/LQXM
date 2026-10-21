/**
 * 生产跟单的**纯业务规则唯一来源**（不依赖 Vue，可直接单测）。
 *
 * 【为什么下沉】这些判据原先长在 `ProductionTrackingView.vue` 里（近 3000 行的单文件），
 * 于是唯一的安全网只能是「直读页面源码文本」的契约测试：改一句措辞就可能让测试变红，
 * 而真正的规则（时间核实六类判据、节点完成校验、交付结果口径）反而无法单测。
 * 规则落到这里之后，页面只负责「取数 + 编排 + 渲染」，测试直接调函数。
 *
 * 【两条硬约束】
 * 1. 「今天」绝不在这里读系统时间后散落使用：凡需要当天日期的判据一律**显式接收** `today`
 *    （默认 `todayKey()`），页面把同一个今天传进来，避免跨零点时同一行数据两处判据不一致。
 * 2. 这里只放**判定与展示文案**，不放请求、不放 Vue 状态。凡涉及 `detail` / `items` /
 *    弹窗开关的一律留在页面；`leadDays*` 系列留在页面（它们是 `trackingDateGuard` 的页面级组合，
 *    且被 `trackingDateGuard.test.ts` 以「页面源码里必须出现」的方式钉住）。
 */
import { daysBetween, invalidApplicationDate, invalidRequiredDate } from '@/utils/trackingDateGuard'
import { todayKey } from '@/utils/tracking/today'
import type {
  TrackingMilestone, TrackingOrder,
  TrackingTimeReview, TrackingTimeReviewKind, TrackingTimeReviewStatus
} from '@/api/productionTracking'
import type { Tone } from '@/utils/export/trackingHtml'

/* ============================ 通用展示 ============================ */

const RISK_LABELS: Record<string, string> = { NONE: '无', MATERIAL: '缺料', DESIGN: '设计变更', CAPACITY: '产能', CUSTOMER: '客户确认', OTHER: '其他' }
export function riskText(value?: string | null) { return value ? RISK_LABELS[value] || value : '无' }

/** `YYYY-MM-DDTHH:MM...` → `YYYY-MM-DD HH:MM`（列表 / 详情同款）。 */
export function formatMinute(value?: string | null) { return value ? value.replace('T', ' ').slice(0, 16) : '' }
/** 与 formatMinute 同一口径（历史命名，两处调用点各自沿用旧名）。 */
export const formatTime = formatMinute

/**
 * 列表「任务号」只显示编号末四位 —— 全号形如 SCGD20260006047（15 位），整串太长。
 * 取末尾连续数字的最后 4 位（编号带后缀时也不会取错）；hover 可见全号。
 */
export function shortOrderNo(value?: string | null) {
  if (!value) return ''
  const tail = value.match(/\d+$/)?.[0]
  return tail ? tail.slice(-4) : value
}
/** 列表产品副标题只保留规格的尺寸段；完整规格仍在详情和导出中。 */
export function productSize(row: Pick<TrackingOrder, 'specificationSnapshot'>): string {
  return row.specificationSnapshot?.split('/')[0]?.trim() || ''
}
/**
 * 车间值统一以「车间」结尾（封口机车间 / 全自动气调机车间 / 半自动气调机车间 / 称重机车间），
 * 展示时省掉这两个字；筛选 option 的值仍用原名，不受影响。
 */
export function shortWorkshop(value?: string | null) {
  return value ? value.replace(/车间$/, '') : ''
}
/**
 * 列表日期省略年份，只展示 MM-DD —— 跟单数据从 2026-09 开始，同屏年份一致，年份属冗余信息（2026-10-12 LQ 需求）。
 * 仅用于列表展示；导出文件仍走完整日期（回写按任务号与日期口径匹配，不能省年份）。
 */
export function shortDate(value?: string | null) {
  const day = value ? value.slice(0, 10) : ''
  return day.length === 10 ? day.slice(5) : day
}

/* ============================ 跟单状态 / 节点 ============================ */

export function trackingStatusText(value?: TrackingOrder['trackingStatus']) {
  return ({ pending: '待跟单', in_progress: '跟单中', completed: '跟单已完成', stopped: '停止生产跟单' } as Record<string, string>)[value || ''] || '待跟单'
}
/**
 * 跟单状态筛选下拉与 chip 用的展示文案：
 * 含后端真实状态（pending/in_progress/completed/stopped）和筛选入参 'closed'（人工结案）、
 * 前端伪值 'open'。closed 在状态字段上不会出现，仅作为筛选参数；
 * 带 closedAt 的行 trackingStatus 字段为 'completed'。
 *
 * ⚠️ 'open' 的语义 2026-10-21 改过：原先是「未结束跟单」（pending+in_progress），
 * 但跟单从 2026-09 才开始，九月那批 571 单里 508 单已交付，一过交期就全成了「已超期」，
 * 工作台 63 条被 5119 条已交付历史单淹掉。现在 'open' ＝ **还没入库**（未停工、未结案、无入库日）。
 * 入库口径（后端 DELIVERED_IN_DATE 唯一来源）：跟单员填写的入库里程碑优先，没填则看采购入库，
 * 两者都没有才算未交付。文案改成「待处理（未交付）」，继续叫「未结束跟单」会让人以为已交付的也在里面。
 */
export const TRACKING_STATUS_FILTER_LABEL: Record<string, string> = {
  pending: '待跟单', in_progress: '跟单中', completed: '跟单已完成',
  closed: '人工结案', stopped: '停止生产跟单', open: '待处理（未交付）'
}
export function trackingStatusTone(value?: TrackingOrder['trackingStatus']): 'success' | 'warning' | 'info' {
  return value === 'completed' ? 'success' : value === 'in_progress' ? 'warning' : 'info'
}
export function stageStatusText(status: TrackingMilestone['status']) {
  return ({ PENDING: '未开始', IN_PROGRESS: '进行中', DONE: '已完成' } as Record<TrackingMilestone['status'], string>)[status]
}
/** 超参考完成日期仍未完成：用于非阻断提醒。 */
export function stageOverdue(stage: TrackingMilestone): boolean {
  return stage.status !== 'DONE' && (stage.referenceOverdueDays ?? 0) > 0
}
/**
 * 按需工序（封口机的氧化/喷特氟龙）：源表填「无」或留空的单不写里程碑行 ⇒ 本单不走这道工序。
 * 这类节点既不进分母、也不该被显示成「未完成」，否则封口机几乎每单都永远差一个节点。
 */
export function stageRelevant(stage: Pick<TrackingMilestone, 'optional' | 'applicable'>) {
  return stage.optional !== true || stage.applicable === true
}
/**
 * 列表里只画本单真正要走的节点：没选氧化/喷特氟龙就只有 4 个点，选了的才 5 个点。
 * 不适用的按需工序连灰点都不显示——它不是「未完成」，画出来只会让人以为还差一步。
 */
export function listStages(row: Pick<TrackingOrder, 'stageProgress'>) {
  return (row.stageProgress || []).filter(stageRelevant)
}
/** 单次保存前的节点校验（批量完成与详情保存共用同一份判据）。 */
export function stageValidationError(stages: TrackingMilestone[]) {
  const missingDate = stages.find(stage => stage.status === 'DONE' && !stage.completedDate)
  if (missingDate) return `节点「${missingDate.label}」已完成时请填写完成日期`
  return null
}
/** 列表「批量完成」的目标节点：未完成序列里的第一个。 */
export function nextListStage(row: Pick<TrackingOrder, 'stageProgress'>) {
  return listStages(row).find(stage => stage.status !== 'DONE') || null
}
/** 指定节点优先；未指定时取该单的下一个未完成节点。 */
export function listCompletionTarget(row: Pick<TrackingOrder, 'stageProgress'>, stageCode: string | null) {
  return stageCode
    ? listStages(row).find(stage => stage.code === stageCode) || null
    : nextListStage(row)
}
export function canQuickComplete(row: TrackingOrder) {
  return !row.stoppedAt && Boolean(nextListStage(row))
}

/* ============================ 日期口径（组合取数） ============================ */

/** 有效的业务员要求完成日；疑似填错（早于申请/提交日）时返回 null。 */
export function validRequiredDate(row: Pick<TrackingOrder, 'applicationDate' | 'sourceCreatedAt' | 'dueDate'>) {
  return invalidRequiredDate(row) ? null : row.dueDate || null
}
/** 跟单截止日基准：车间预计完成优先，未填时回落有效的业务员要求日。 */
export function deadlineOf(row: Pick<TrackingOrder, 'applicationDate' | 'sourceCreatedAt' | 'expectedFinishDate' | 'dueDate'>) {
  return row.expectedFinishDate || validRequiredDate(row)
}
/** 当前承诺日期优先取已协商延期；源工单预计日期仍用于追溯和延期校验。 */
export function currentCompletionDate(row: TrackingOrder) { return row.forecastFinishDate || deadlineOf(row) }
/**
 * 排产滞后天数＝任务单提交日（业务员提交生产任务单给车间审批，sourceCreatedAt）→ 排产时间。
 * 2026-10-15 LQ 定：不再从申请日算（申请日可能填早，如 SCGD20260006013 实际 10-08 提交、申请日却更早，
 * 会出现「申请后 78 天排产」的假象）；申请日可信且提交时间缺失时才回落申请日。任一日期缺失返回 null。
 */
export function scheduleLagDays(row: TrackingOrder) {
  const basis = row.sourceCreatedAt || row.applicationDate
  if (!basis || !row.scheduleTime) return null
  return daysBetween(basis, row.scheduleTime)
}
/**
 * 跟单确认后的交期变化文案＝延期至 − 原交付基准（同 deadlineOf 口径）。
 * ⚠️ 「延期至」的语义是「跟单确认后的预计完成」，**不一定是推后**：真库有 6 单延期至早于
 * 车间源预计（SCGD20260005644 源预计 09-12 → 延期至 09-10），写死「已协商延期」就说反了。
 * 故按方向分三种措辞：推后 / 提前 / 同日仅确认。
 */
export function forecastAdjustText(row: TrackingOrder) {
  const days = daysBetween(deadlineOf(row), row.forecastFinishDate)
  if (days == null || days === 0) return '已确认交期'
  return days > 0 ? `已协商延期 ${days} 天` : `已提前 ${-days} 天`
}
/** 优先使用已登记的车间交期；车间未另定日期时，沿用列表展示的有效业务员要求日期。 */
export function countdownDays(row: TrackingOrder, today: string = todayKey()) {
  const baseline = row.deliveryBaseline || currentCompletionDate(row)
  return baseline ? daysBetween(today, baseline) : null
}
export function productionCountdown(row: TrackingOrder, today: string = todayKey()) {
  if (row.stoppedAt) return '已停止跟单'
  if (row.deliveredDate) return '已交付'
  const days = countdownDays(row, today)
  if (days == null) return '待定交期'
  return days < 0 ? `超期 ${-days} 天` : days === 0 ? '今日到期' : `剩余 ${days} 天`
}
export function countdownTone(row: TrackingOrder, today: string = todayKey()) {
  if (row.stoppedAt) return 'muted'
  if (row.deliveredDate) return 'muted'
  const days = countdownDays(row, today)
  return days == null ? 'countdown-undated' : days < 0 ? 'is-danger' : days <= 3 ? 'countdown-soon' : 'countdown-normal'
}

/* ============================ 完工 / 入库 / 交付 ============================ */

/**
 * 采购入库时间：任务单本身不记入库，来自采购入库明细关联回的首次入库日期。
 * 一个工单分批发货时补一句「共 N 单 · 末次 X」，只入库一次则不啰嗦。
 */
export function inboundHint(row: Pick<TrackingOrder, 'inboundLastDate' | 'inboundCount'>) {
  const count = row.inboundCount || 0
  if (count <= 1) return ''
  return row.inboundLastDate ? `共 ${count} 单 · 末次 ${row.inboundLastDate}` : `共 ${count} 单`
}
/** 报工完工 → 首次入库 的间隔。入库早于报工需核对现场补录或工单关联。 */
export function inboundGapText(row: Pick<TrackingOrder, 'reportToInboundDays'>) {
  const days = row.reportToInboundDays
  if (days == null) return ''
  if (days > 0) return `报工完工后 ${days} 天入库`
  if (days === 0) return '与报工完工同日入库'
  return `早于报工完工 ${-days} 天`
}
export function inboundBeforeReporting(row: Pick<TrackingOrder, 'inboundDate' | 'actualFinishDate'>) {
  return Boolean(row.inboundDate && (!row.actualFinishDate || row.inboundDate < row.actualFinishDate))
}
export function inboundReportingHint(row: Pick<TrackingOrder, 'actualFinishDate'>) {
  return row.actualFinishDate ? '入库早于氚云报工，请核实补录时间或工单关联' : '已有采购入库，氚云仍未报工完工；请核实报工状态或入库关联'
}
export function acceptedAfterDue(row: TrackingOrder) {
  const due = validRequiredDate(row)
  return Boolean(row.scheduleTime && due && row.scheduleTime.slice(0, 10) > due)
}
/**
 * 车间预计完成 vs 业务员要求的比较。
 * ⚠️ 必须认延期：本卡片主数值已经是 currentCompletionDate（延期后），
 * 若这里还拿源预计去比，就会出现「显示 10-17、却说比要求晚 2 天（按 10-15 算）」的错位。
 */
export function expectedCompareText(row: TrackingOrder) {
  if (invalidRequiredDate(row)) return '业务员要求日期早于申请或任务单提交日期，待核对后再比较'
  if (!row.expectedFinishDate && !row.forecastFinishDate) return row.dueDate ? '未填车间预计，暂按业务员要求显示' : '车间与业务员日期均未填写'
  const days = daysBetween(row.dueDate, currentCompletionDate(row))
  if (days == null) return '业务员要求日期未填写'
  return days > 0 ? `比业务员要求晚 ${days} 天` : days < 0 ? `比业务员要求提前 ${-days} 天` : '与业务员要求一致'
}
export function reportCompareText(row: TrackingOrder) {
  const days = daysBetween(row.expectedFinishDate, row.actualFinishDate)
  return days == null ? '' : days < 0 ? `比车间预计早 ${-days} 天（报工仅供对照）` : days === 0 ? '与车间预计同日（报工仅供对照）' : `比车间预计晚 ${days} 天（报工仅供对照）`
}
/** 首次入库/跟单确认的交期对照；可能只是部分入库，不代表整单完工（10-19 LQ 定名：统一叫「交付」，不叫「入库」）。 */
export function deliveryOutcomeText(row: TrackingOrder) {
  if (row.stoppedAt) return '已停止跟单'
  if (row.deliveryOutcome === 'OVERDUE') return `逾期交付 ${row.deliveryOverdueDays ?? 0} 天`
  if (row.deliveryOutcome === 'ON_TIME') return '按期交付'
  if (row.deliveryOutcome === 'NO_BASELINE') return '已交付（无基准）'
  return '未交付'
}
/** 生产交付结果的语义色：口径与列表 el-tag 的 type 完全一致（网页导出用它上色）。 */
export function deliveryOutcomeTone(row: TrackingOrder): Tone | '' {
  if (row.stoppedAt) return 'info'
  if (row.deliveryOutcome === 'OVERDUE') return 'danger'
  if (row.deliveryOutcome === 'ON_TIME') return 'success'
  if (row.deliveryOutcome === 'NO_BASELINE') return 'info'
  return ''
}

/* ============================ 时间核实 ============================ */

export function reviewOf(row: TrackingOrder, kind: TrackingTimeReviewKind): TrackingTimeReview | undefined {
  return row.timeReviews?.find(review => review.kind === kind)
}
/**
 * 六类时间异常的唯一判据。返回 null ＝ 该类没有问题。
 * `today` 由调用方给出（页面传当天 / 心跳 ref），使判据保持纯函数、可单测。
 */
export function timeException(row: TrackingOrder, kind: TrackingTimeReviewKind, today: string = todayKey()): string | null {
  if (kind === 'SOURCE_DATE') {
    if (invalidApplicationDate(row)) return '申请日期早于任务单提交日期，需核对源工单'
    return invalidRequiredDate(row) ? '业务员要求日期早于申请或任务单提交日期，需核对源工单' : null
  }
  if (kind === 'ACCEPTANCE') {
    if ((scheduleLagDays(row) ?? 0) < 0) return '排产时间早于任务单提交日期，需核对源数据'
    if (acceptedAfterDue(row)) return `接单时已超过业务员要求 ${daysBetween(row.dueDate, row.scheduleTime)} 天，需核实原因`
    if (!row.scheduleTime && validRequiredDate(row) && row.dueDate! < today) return '业务员要求日期已过，仍未排产接单，需核实原因'
  }
  if (kind === 'COMMITMENT') {
    if (invalidRequiredDate(row)) return null
    const review = reviewOf(row, kind)
    if (review && ((review.sourceDueDate || null) !== (row.dueDate || null)
      || (review.sourceExpectedDate || null) !== (row.expectedFinishDate || null)
      || (review.agreedDate && review.agreedDate !== deadlineOf(row)))) return '已记录的协商交期与当前工单日期不一致，需重新核对'
    const days = daysBetween(row.dueDate, row.expectedFinishDate)
    if (days != null && days > 0) return '车间预计晚于业务员要求，需注明协商原因'
  }
  if (kind === 'REPORTING') {
    if (inboundBeforeReporting(row)) return inboundReportingHint(row)
    const baseline = row.expectedFinishDate
    const days = daysBetween(baseline, row.actualFinishDate)
    if (days != null && days > 0) return `报工日期比车间预计晚 ${days} 天，需核实实际进度或补录原因`
    if (!row.actualFinishDate && baseline && baseline < today) return '已过车间预计日期，源工单仍未填报完工，需核实实际进度'
  }
  if (kind === 'OVERDUE') {
    if (row.deliveryOutcome !== 'OVERDUE') return null
    return `实际交付比车间预计晚 ${row.deliveryOverdueDays ?? 0} 天，需记录逾期原因`
  }
  return null
}
/**
 * 是否还要再记一次原因：已有记录但与当前工单日期不再一致时也要重记
 * （否则「录过一次」会永久掩盖后来被改过的源日期）。
 */
export function needsTimeReason(row: TrackingOrder, kind: TrackingTimeReviewKind, today: string = todayKey()) {
  if (!timeException(row, kind, today)) return false
  const review = reviewOf(row, kind)
  if (!review) return true
  if (kind === 'SOURCE_DATE') return (review.sourceApplicationDate || null) !== (row.applicationDate || null)
    || (review.sourceDueDate || null) !== (row.dueDate || null)
    || (review.sourceCreatedAt || null) !== (row.sourceCreatedAt || null)
  // OVERDUE 的快照列语义不同：sourceExpectedDate 存「交付基准」、sourceInboundDate 存「实际交付日」，必须早于下面的通用比较返回。
  if (kind === 'OVERDUE') return (review.sourceExpectedDate || null) !== (row.deliveryBaseline || null)
    || (review.sourceInboundDate || null) !== (row.deliveredDate || null)
  if ((review.sourceDueDate || null) !== (row.dueDate || null) || (review.sourceExpectedDate || null) !== (row.expectedFinishDate || null)) return true
  if (kind === 'ACCEPTANCE' && (review.sourceScheduleTime || null) !== (row.scheduleTime || null)) return true
  if (kind === 'REPORTING' && ((review.sourceReportDate || null) !== (row.actualFinishDate || null)
    || (review.sourceInboundDate || null) !== (row.inboundDate || null))) return true
  if (kind === 'COMMITMENT') return review.status !== 'NEGOTIATED' || review.agreedDate !== row.expectedFinishDate
  return false
}
export function reviewKindLabel(kind: TrackingTimeReviewKind) {
  return ({ SOURCE_DATE: '源工单日期', ACCEPTANCE: '接单等待', COMMITMENT: '车间交期', REPORTING: '报工核实', WAREHOUSING: '采购入库', OVERDUE: '交付逾期' })[kind]
}
export function reviewStatusLabel(status: TrackingTimeReviewStatus) {
  return ({ OPEN: '继续跟进', RESOLVED: '已核实完成', ON_TIME: '确认可按期', NEGOTIATED: '已协商交期' })[status]
}
/** 列表「时间核实」列的徽标清单（口径全部来自 needsTimeReason，不另写一套）。 */
export function timeSignals(row: TrackingOrder, today: string = todayKey()) {
  const signals: string[] = []
  if (invalidApplicationDate(row)) signals.push(needsTimeReason(row, 'SOURCE_DATE', today) ? '申请日期待核' : '申请日期有误')
  if (invalidRequiredDate(row)) signals.push(needsTimeReason(row, 'SOURCE_DATE', today) ? '业务员要求日期待核' : '业务员要求日期有误')
  const acceptance = reviewOf(row, 'ACCEPTANCE')
  const sourceDate = reviewOf(row, 'SOURCE_DATE')
  const commitment = reviewOf(row, 'COMMITMENT')
  const reporting = reviewOf(row, 'REPORTING')
  const warehousing = reviewOf(row, 'WAREHOUSING')
  if (needsTimeReason(row, 'ACCEPTANCE', today)) signals.push('接单时间待核')
  if (needsTimeReason(row, 'COMMITMENT', today)) signals.push('车间交期待核')
  if (needsTimeReason(row, 'REPORTING', today)) signals.push(inboundBeforeReporting(row) ? '入库与报工待核' : '报工时间待核')
  if (needsTimeReason(row, 'OVERDUE', today)) signals.push('逾期原因待记')
  if ([sourceDate, acceptance, reporting, warehousing].some(review => review?.status === 'OPEN' && review.nextFollowupDate && review.nextFollowupDate <= today)
    || (commitment?.agreedDate && commitment.agreedDate !== deadlineOf(row) && commitment.nextFollowupDate && commitment.nextFollowupDate <= today)) signals.push('核实事项应跟进')
  return signals
}

/* ==================== 导出：原因与备注（10-15 LQ「导出后要能分析」） ==================== */

/**
 * 某一类时间核实的原因原文。无记录、或记录里没填原因时返回空串（导出留空，不写「无」——
 * 「留空」和「写了但内容为空」在线下是两种意思，不要混）。
 *
 * ⚠️ 这里**只**吐 reason 原文，不加类目前缀：逾期原因那一列单独成列，前缀是多余的。
 */
export function reviewReasonText(row: TrackingOrder, kind: TrackingTimeReviewKind): string {
  return reviewOf(row, kind)?.reason?.trim() || ''
}
/**
 * 除 `exclude` 之外**所有**核实记录的原因，格式「类目：原因」，多条用 ` / ` 连接。
 *
 * 为什么不大类分成六列：除了「交付逾期」，其余五类真库里几乎没数据（`WAREHOUSING`/`SOURCE_DATE` 等常年 0 条），
 * 分成六列会让导出件空出五列噪声。合并成一列后，缺失的那一格就是空的，不占视线。
 */
export function reviewOtherReasonsText(row: TrackingOrder, exclude: TrackingTimeReviewKind[] = []): string {
  return (row.timeReviews || [])
    .filter(review => !exclude.includes(review.kind) && review.reason?.trim())
    .map(review => `${reviewKindLabel(review.kind)}：${review.reason.trim()}`)
    .join(' / ')
}
/**
 * 各节点的备注，格式「节点名：备注」，多条用 ` / ` 连接；没填备注的节点不出现。
 * 节点顺序由后端按车间工序序列给出（`ProductionTrackingPolicy` 是唯一源），这里不重排。
 */
export function stageNotesText(row: TrackingOrder): string {
  return (row.stageProgress || [])
    .filter(stage => stage.note?.trim())
    .map(stage => `${stage.label}：${stage.note!.trim()}`)
    .join(' / ')
}


