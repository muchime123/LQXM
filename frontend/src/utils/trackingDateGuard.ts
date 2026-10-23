/**
 * 生产跟单「源工单日期是否可信 / 工期怎么算」的**口径唯一来源**。
 *
 * 列表列、详情卡片、网页版导出、xlsx 导出四处都必须调用这里的函数——
 * 这四处任一另写一份判断，就会出现「列表显示 6 天、导出显示日期待核实」这类
 * 只在导出件上才被发现的静默分叉。
 *
 * ## 四个日期
 * - 生产工单申请时间 `applicationDate` —— 业务员提申请那天（氚云 production_orders.application_date，日期）。
 * - 任务单提交时间 `sourceCreatedAt` —— 业务员把生产任务单提交给车间审批的时间（氚云建单时间，DATETIME）。
 *   界面/导出一律叫「任务单提交」，不再叫「源创建」（2026-10-15 LQ 定）。
 * - 车间排产时间 `scheduleTime` —— 车间**开始接单排产**的时间（氚云 production_orders.schedule_time）。
 *   提交之后车间不一定马上接单，这段等待不属于生产周期。
 * - 业务员要求完成日 `dueDate` —— 业务员提出的完工日期。
 *
 * ## 三个真身（10-15 定，10-20 改起算：**计划与实际都从排产日起算**）
 *
 * | 真身 | 起算 | 终点 | 性质 |
 * |---|---|---|---|
 * | 要求工期 | **申请时间** | 业务员要求完成日 `dueDate` | 客户的期望，从不改动 |
 * | 计划工期 | **车间排产时间** | 车间预计完成日，认延期（`forecastFinishDate` ?? `expectedFinishDate`） | 车间的承诺，有延期时要标出来 |
 * | 实际工期 | **车间排产时间** | 实际入库日 `deliveredDate` = 跟单员登记优先，未登记时取采购首次入库 | 既成事实 |
 *
 * 为什么起算日是两套：申请日期常被填早（先申请、后建单），**要求工期**本来就是业务员在申请日
 * 提出的期望，只能从申请日起算；而计划/实际是**车间要兑现的周期**，业务员提交后车间可能压着
 * 没接单（真库 6030 单里提交到排产平均滞后 1.5 天、最长 83 天），把这段等待算进车间工期，会让
 * 「车间慢/快」和「业务员报得早/晚」搅在一起（2026-10-20 LQ 定：从车间开始接单排产算）。
 * 因此要求工期与另外两个真身**不可直接比大小**，界面里必须各自说清起算（见下面各 basis 文案）。
 *
 * ⚠️ **未排产的单只给要求工期**（`scheduleTime` 为空，真库 44 单，2026-10-20 LQ 定）：
 * 车间还没接单排产，就没有「车间多久做完」这件事——计划工期与实际工期**连明细都不出现**，
 * 也不回落提交日（回落等于把「车间还没接单」的等待又算进工期，正是上面要修的病根）。
 * 主数值降级成要求工期，悬停给一句 `leadDaysAwaitingScheduleNote` 说明「等车间接单」。
 * 已交付但从没登记排产的历史单同样没有可靠生产起点，不回落任务单提交日。
 *
 * 历史：10-15 版计划/实际从**任务单提交时间**起算，10-20 起改从排产时间起算；`sourceCreatedAt`
 * 仍留在本模块里，只用于「申请日是否填早 / 要求日期是否可疑」的判据与排产滞后。
 *
 * 原「承诺工期」被并入计划工期（10-15 LQ 定：不想按是否延期分成两个显示），
 * 延期的信息改由**徽章 `计划·延` 与明细里的「含延期」**承载。
 *
 * `deliveredDate` 由后端算好（`DELIVERED_IN_DATE`：跟单员入库节点优先，采购首次入库兜底），
 * 前端**禁止**自己再写一遍 `LEAST`——MySQL 的 LEAST 遇 NULL 整条返回 NULL，会把
 * 「只有一个来源」误判成「未交付」。
 */

/** 判定只读的日期字段；`TrackingOrder` 等更宽的类型可直接传（结构子类型）。 */
export interface TrackingDateFields {
  applicationDate?: string | null
  sourceCreatedAt?: string | null
  dueDate?: string | null
}

/** 取 `YYYY-MM-DD` 的 UTC 零点毫秒；空值或非法格式返回 null（避免本地时区把日期算偏一天）。 */
export function dateDay(value?: string | null) {
  const match = value?.slice(0, 10).match(/^(\d{4})-(\d{2})-(\d{2})$/)
  return match ? Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : null
}

/** 自然日差（end − start）；任一为空或非法返回 null。 */
export function daysBetween(start?: string | null, end?: string | null) {
  const first = dateDay(start); const last = dateDay(end)
  return first == null || last == null ? null : Math.round((last - first) / 86400000)
}

/** 申请日期早于任务单提交日期 ＝ 疑似填写错误，申请日不可信（申请时间列据此标红）。 */
export function invalidApplicationDate(row: TrackingDateFields) {
  return Boolean(row.applicationDate && row.sourceCreatedAt && row.applicationDate < row.sourceCreatedAt.slice(0, 10))
}

/** 要求完成日早于申请日或任务单提交日 ＝ 疑似填写错误，要求完成日不可信。 */
export function invalidRequiredDate(row: TrackingDateFields) {
  return Boolean(row.dueDate && ((row.applicationDate && row.dueDate < row.applicationDate)
    || (row.sourceCreatedAt && row.dueDate < row.sourceCreatedAt.slice(0, 10))))
}

export interface TrackingLeadFields extends TrackingDateFields {
  /** 车间开始接单排产的时间（氚云 `schedule_time`）；未排产为空 —— 计划/实际工期都从这里起算。 */
  scheduleTime?: string | null
  expectedFinishDate?: string | null
  forecastFinishDate?: string | null
  /** 实际入库日期（后端 `delivered_date`）优先跟单员登记的入库，缺失时取采购首次入库。 */
  deliveredDate?: string | null
}

/** 工期的三个真身。 */
export type LeadDaysKind = 'REQUIRED' | 'PLANNED' | 'ACTUAL'

/** 计划工期的终点＝车间给出的预计完成日，**认延期**（延期至优先于车间源预计）。 */
export function plannedFinishDate(row: TrackingLeadFields) {
  return row.forecastFinishDate || row.expectedFinishDate || null
}

/** 该单是否登记过延期至（只有它才能让「计划工期」变成「含延期」）。 */
export function hasForecastFinish(row: TrackingLeadFields) {
  return Boolean(row.forecastFinishDate)
}

/** 该单是否还没被车间接单排产（未排产时计划/实际工期都不给，只给要求工期）。 */
export function leadDaysStartMissingPlan(row: TrackingLeadFields) {
  return !row.scheduleTime
}

/**
 * 计划/实际工期的起算日＝**车间排产时间**（10-20 LQ 定）。
 * 未排产一律不计算生产周期；任务单提交时间不是开工时间。
 */
export function leadDaysStart(row: TrackingLeadFields) {
  return row.scheduleTime || null
}

/** 兼容旧展示调用；生产起点不再使用任务单提交日兜底。 */
export function leadDaysStartFallback(_row: TrackingLeadFields) {
  return false
}

/**
 * 这条单该不该给计划工期。
 * **未排产就一律不给**（10-20 LQ 定：只显示要求工期）——不管氚云里有没有车间预计完成日、
 * 有没有登记延期，车间没接单排产就没有「车间多久做完」这件事，硬给一个数是编的。
 * 已交付但排产日缺失也不推算生产周期。
 */
export function leadDaysHasPlan(row: TrackingLeadFields) {
  return Boolean(row.scheduleTime)
}

/**
 * 实际工期能不能给：既要有入库日，也要有起算日。
 * 未排产且未交付 ⇒ 不给（`leadDaysStart` 为空）；已交付未排产 ⇒ 走兜底照给。
 */
export function leadDaysHasActual(row: TrackingLeadFields) {
  return Boolean(row.deliveredDate) && leadDaysStart(row) != null
}

/** 当前工期的真身：既成事实（已入库）> 车间计划 > 业务员要求。 */
export function leadDaysKind(row: TrackingLeadFields): LeadDaysKind {
  if (leadDaysHasActual(row)) return 'ACTUAL'
  if (leadDaysHasPlan(row) && plannedFinishDate(row)) return 'PLANNED'
  return 'REQUIRED'
}

/** 要求工期：申请时间 → 业务员要求完成日。要求日期不可信时算不出来（返回 null）。 */
export function requiredLeadDays(row: TrackingLeadFields) {
  return invalidRequiredDate(row) ? null : daysBetween(row.applicationDate, row.dueDate)
}

/** 计划工期：车间排产时间 → 车间预计完成日（含延期至）。未排产返回 null。 */
export function plannedLeadDays(row: TrackingLeadFields) {
  return daysBetween(leadDaysStart(row), plannedFinishDate(row))
}

/** 实际工期：车间排产时间 → 实际入库日。未排产时始终返回 null。 */
export function actualLeadDays(row: TrackingLeadFields) {
  return daysBetween(leadDaysStart(row), row.deliveredDate)
}

/** 当前真身的天数（主数值）；起算日或终点缺失返回 null。 */
export function effectiveLeadDays(row: TrackingLeadFields) {
  switch (leadDaysKind(row)) {
    case 'ACTUAL': return actualLeadDays(row)
    case 'PLANNED': return plannedLeadDays(row)
    default: return requiredLeadDays(row)
  }
}

/** 当前真身的终点日期（用于与业务员要求完成日比较）；未排产时只比业务员要求日。 */
export function effectiveFinishDate(row: TrackingLeadFields) {
  return (leadDaysHasActual(row) ? row.deliveredDate : null)
    || (leadDaysHasPlan(row) ? plannedFinishDate(row) : null)
    || row.dueDate || null
}

/**
 * 主数值文案：按真身给天数；算不出来时说清是哪种算不出（要求日期待核实 / 未计算），不编天数。
 * 未排产的单**降级成要求工期**（只显示要求工期，见 `leadDaysHasPlan`），所以这里不再有
 * 「未排产」这种半截文案——要么给要求工期，要么给不出就说「未计算」。
 */
export function effectiveLeadDaysText(row: TrackingLeadFields): string {
  if (leadDaysKind(row) === 'REQUIRED') return requestedLeadDaysText(row)
  const days = effectiveLeadDays(row)
  return days == null ? '未计算' : `${days} 天`
}

/**
 * 计划/实际为什么没显示——**未排产**（车间还没接单）。界面据此给一句说明，不能让人以为漏算。
 * 已交付但排产日缺失的单也在此列。
 */
export function leadDaysAwaitingSchedule(row: TrackingLeadFields) {
  return leadDaysStartMissingPlan(row) && !leadDaysStartFallback(row)
}

/** 「未排产」那句说明的正文；只在 `leadDaysAwaitingSchedule` 为真时有内容。 */
export function leadDaysAwaitingScheduleNote(row: TrackingLeadFields) {
  return leadDaysAwaitingSchedule(row) ? '车间尚未接单排产，暂只显示业务员要求工期；排产后自动给出计划/实际工期' : ''
}

/** 业务员要求工期的展示文案（列表 / 详情 / 导出同一份）。 */
export function requestedLeadDaysText(row: TrackingDateFields): string {
  if (invalidRequiredDate(row)) return '日期待核实'
  const days = daysBetween(row.applicationDate, row.dueDate)
  return days == null ? '未计算' : `${days} 天`
}

/** 口径说明（详情卡片副标题）：说清这个天数是从哪个起算日到哪个终点。 */
export function effectiveLeadDaysBasisText(row: TrackingLeadFields): string {
  const start = '车间排产日'
  switch (leadDaysKind(row)) {
    case 'ACTUAL': return `从${start}至实际入库日（跟单员登记优先，未登记时用采购入库），按自然日计算`
    case 'PLANNED': return hasForecastFinish(row)
      ? `从${start}至跟单确认的延期日（含延期），按自然日计算`
      : `从${start}至车间预计完成日，按自然日计算`
    default: return '从生产工单申请日至业务员要求完成日，按自然日计算'
  }
}

/** 主数值为负 ＝ 终点早于起算日（历史补录 / 日期填错），是数据现象不是算法错，但必须让人看见。 */
export function negativeLeadDays(row: TrackingLeadFields) {
  const days = effectiveLeadDays(row)
  return days != null && days < 0
}

/** 主数值要不要标红：负数（日期可疑）或「日期待核实」。 */
export function leadDaysAlarm(row: TrackingLeadFields) {
  return negativeLeadDays(row) || effectiveLeadDaysText(row) === '日期待核实'
}

/** 负数天数的一句原因（三个真身各一句）；真库 5964 单里 70 单命中，属历史补录/日期填错。 */
export function negativeLeadReason(row: TrackingLeadFields) {
  const start = '车间排产日'
  switch (leadDaysKind(row)) {
    case 'ACTUAL': return `实际入库日早于${start}，疑似补录或日期填写错误`
    case 'PLANNED': return `车间预计完成日早于${start}，疑似补录或日期填写错误`
    default: return '要求完成日早于申请日期，疑似日期填写错误'
  }
}

/** 与业务员要求完成日的偏差：正 = 比要求晚，负 = 比要求早；要求日期缺失或不可信返回 null。 */
export function leadDaysDelta(row: TrackingLeadFields) {
  if (invalidRequiredDate(row) || !row.dueDate) return null
  return daysBetween(row.dueDate, effectiveFinishDate(row))
}

/** 偏差小字文案；无需提示时返回空串（列表不占位）。 */
export function leadDaysDeltaText(row: TrackingLeadFields): string {
  const delta = leadDaysDelta(row)
  if (delta == null) return ''
  return delta > 0 ? `比要求晚 ${delta} 天` : delta < 0 ? `比要求早 ${-delta} 天` : '与要求一致'
}

/** 偏差色调：`late` 红（超）/ `early` 绿（提前）/ `same` 中性。 */
export function leadDaysDeltaTone(row: TrackingLeadFields): '' | 'late' | 'early' | 'same' {
  const delta = leadDaysDelta(row)
  if (delta == null) return ''
  return delta > 0 ? 'late' : delta < 0 ? 'early' : 'same'
}

/** 导出格文案（xlsx）：主数值 + 真身（线下看不到徽章颜色，必须落成字）。 */
export function effectiveLeadDaysCellText(row: TrackingLeadFields): string {
  const text = effectiveLeadDaysText(row)
  return `${text}（${leadDaysKindText(row)}）`
}

/**
 * ## 三真身明细（10-15 LQ 提：只显示一个数「分不清到底是哪个工期」）
 *
 * 列里只放得下一个主数值，但一条工单同时存在三个真身（还各有各的起算日）；
 * 让跟单员自己猜是哪个，等于把「延期之后工期跟着变」又变回不可见。
 * 故列内一次性给出：主数值 + 当前真身徽章 + 其余真身的紧凑小字；悬停给出带起止日期的完整明细。
 */
export interface LeadBreakdownItem {
  key: LeadDaysKind
  /** 全称（悬停明细、详情卡片用）。 */
  label: string
  /** 短标签（列表徽章 / 紧凑小字用）。 */
  short: string
  /** 起算日 —— 要求从申请日、计划/实际从车间排产日，明细里必须写出来。 */
  start: string | null
  /** 终点日。 */
  date: string | null
  days: number | null
  /** 是否为主数值当前取用的那个真身。 */
  current: boolean
  /** 计划工期是否含延期至。 */
  delayed: boolean
  /** 算不出天数的原因（未计算）；算得出时为空串，`text` 随即给天数。未排产的计划/实际不在明细里。 */
  missing: string
  /** 天数文案；算不出时为 `—`。 */
  text: string
}

const LEAD_KIND_TEXT: Record<LeadDaysKind, { label: string; short: string }> = {
  REQUIRED: { label: '业务员要求工期', short: '要求' },
  PLANNED: { label: '车间计划工期', short: '计划' },
  ACTUAL: { label: '实际工期', short: '实际' }
}

/** 主数值当前取用的真身的徽章文案（登记过延期的计划工期带 `·延`，这是「延期有标注」的落点）。 */
export function leadDaysKindText(row: TrackingLeadFields) {
  const kind = leadDaysKind(row)
  return kind === 'PLANNED' && hasForecastFinish(row) ? '计划·延' : LEAD_KIND_TEXT[kind].short
}

/** 三真身明细，按业务时间顺序（要求 → 计划 → 实际），只列该单真的有的真身。 */
export function leadDaysBreakdown(row: TrackingLeadFields): LeadBreakdownItem[] {
  const current = leadDaysKind(row)
  const start = leadDaysStart(row)
  const hasPlan = leadDaysHasPlan(row)
  const hasActual = leadDaysHasActual(row)
  const sources: Array<{ key: LeadDaysKind; start?: string | null; date?: string | null; days: number | null; delayed: boolean }> = [
    { key: 'REQUIRED', start: row.applicationDate, date: row.dueDate, days: requiredLeadDays(row), delayed: false },
    // 未排产（且未交付）⇒ 计划/实际**整条不进明细**（10-20 LQ 定：只显示要求工期），
    // 而不是留一行 `—` 让人以为是漏算
    { key: 'PLANNED', start, date: hasPlan ? plannedFinishDate(row) : null, days: plannedLeadDays(row), delayed: hasForecastFinish(row) },
    { key: 'ACTUAL', start, date: hasActual ? row.deliveredDate || null : null, days: actualLeadDays(row), delayed: false }
  ]
  return sources
    .filter(item => Boolean(item.date))
    .map(item => ({
      key: item.key,
      ...LEAD_KIND_TEXT[item.key],
      start: item.start ?? null,
      date: item.date ?? null,
      days: item.days,
      current: item.key === current,
      delayed: item.delayed,
      /** 只剩「算不出天数」一种（终点有、起算日缺，或要求日期可疑）；未排产已在上面被排除掉。 */
      missing: item.days == null ? '未计算' : '',
      text: item.days == null ? '—' : `${item.days} 天`
    }))
}

/** 详情卡片的一行文案：`要求 10 天 · 计划 12 天（含延期） · 实际 14 天（当前）`。 */
export function leadBreakdownText(row: TrackingLeadFields) {
  return leadDaysBreakdown(row)
    .map(item => `${item.short} ${item.text}${item.delayed ? '（含延期）' : ''}${item.current ? '（当前）' : ''}`)
    .join(' · ')
}

/**
 * 列表主数值下那行紧凑小字：**只列除当前真身以外的其它真身**
 * （当前真身已经在主数值旁的徽章里，重复一遍反而看不出主角是哪个）。
 */
export function otherLeadDaysText(row: TrackingLeadFields) {
  return leadDaysBreakdown(row)
    .filter(item => !item.current)
    .map(item => `${item.short}${item.delayed ? '·延' : ''} ${item.text}`)
    .join(' · ')
}
