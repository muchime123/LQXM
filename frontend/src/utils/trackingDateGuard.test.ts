import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  daysBetween, effectiveLeadDays, effectiveLeadDaysBasisText, effectiveLeadDaysCellText,
  effectiveLeadDaysText, invalidApplicationDate, invalidRequiredDate, leadBreakdownText,
  leadDaysAlarm, leadDaysAwaitingSchedule, leadDaysAwaitingScheduleNote, leadDaysBreakdown,
  leadDaysDelta, leadDaysDeltaText, leadDaysDeltaTone, leadDaysHasActual, leadDaysHasPlan,
  leadDaysKind, leadDaysKindText, leadDaysStart, leadDaysStartFallback, leadDaysStartMissingPlan,
  negativeLeadDays, negativeLeadReason, otherLeadDaysText, plannedFinishDate, requestedLeadDaysText
} from './trackingDateGuard'

/**
 * 工期的三个真身（2026-10-15 定，2026-10-20 改起算）：
 * - 要求工期 = **申请时间** → 业务员要求完成日（客户的期望，从不改动）
 * - 计划工期 = **车间排产时间** → 车间给出的预计完成日（**认延期**，有延期至时标出来）
 * - 实际工期 = **车间排产时间** → 实际入库日（跟单员登记优先，未登记时取采购首次入库，后端算好）
 *
 * 原「承诺工期」并入计划工期（LQ：不想按是否延期分成两个显示），延期改由徽章 `计划·延` 承载。
 * ⚠️ 起算日**并不相同**（要求从申请日、计划/实际从车间排产日）——有意分叉：
 * ① 申请日常被填早（先申请后建单）；② 业务员提交后车间不一定马上接单（真库 6030 单提交→排产
 * 平均滞后 1.5 天、最长 83 天），把「等车间接单」算进车间工期，就不是车间的问题却记在车间头上。
 * 未排产（真库 44 单）**不回落**提交日，主数值直接「未排产」；只有「已交付但没登记排产」（1 单）
 * 才回落提交日，并标注。
 */
const base = { applicationDate: '2026-09-01', sourceCreatedAt: '2026-09-03 09:30:00', scheduleTime: '2026-09-05 08:00:00', dueDate: '2026-09-11' }
const planned = { ...base, expectedFinishDate: '2026-09-15' }
const delayed = { ...planned, forecastFinishDate: '2026-09-20' }
const finished = { ...delayed, deliveredDate: '2026-09-25' }
/** 车间还没接单排产的单：只有业务员要求日期，车间预计完成日已给（车间已报价但没排产）。 */
const unscheduled = { ...planned, scheduleTime: null }
/** 车间没登记排产，但已经交付了（真库 1 单）：起算日必须兜底，否则既成事实会被整条藏掉。 */
const deliveredWithoutSchedule = { ...finished, scheduleTime: null }

describe('trackingDateGuard · 源工单日期判据', () => {
  it('申请日早于任务单提交日才判可疑（相等或更晚都不算）', () => {
    expect(invalidApplicationDate({ applicationDate: '2026-08-30', sourceCreatedAt: '2026-08-31 00:10:00' })).toBe(true)
    expect(invalidApplicationDate({ applicationDate: '2026-08-31', sourceCreatedAt: '2026-08-31 00:10:00' })).toBe(false)
    expect(invalidApplicationDate({ applicationDate: '2026-09-02', sourceCreatedAt: '2026-08-31 00:10:00' })).toBe(false)
    expect(invalidApplicationDate({ applicationDate: null, sourceCreatedAt: '2026-08-31 00:10:00' })).toBe(false)
    expect(invalidApplicationDate({ applicationDate: '2026-08-30', sourceCreatedAt: null })).toBe(false)
  })

  it('要求完成日早于申请日或任务单提交日都判可疑', () => {
    expect(invalidRequiredDate({ applicationDate: '2026-09-10', sourceCreatedAt: null, dueDate: '2026-09-05' })).toBe(true)
    expect(invalidRequiredDate({ applicationDate: null, sourceCreatedAt: '2026-09-10 00:00:00', dueDate: '2026-09-05' })).toBe(true)
    expect(invalidRequiredDate({ ...base })).toBe(false)
    expect(invalidRequiredDate({ applicationDate: '2026-09-01', sourceCreatedAt: '2026-08-30 00:00:00', dueDate: null })).toBe(false)
  })

  it('daysBetween 按自然日算，非法值返回 null', () => {
    expect(daysBetween('2026-09-01', '2026-09-11')).toBe(10)
    expect(daysBetween('2026-09-01 08:00:00', '2026-09-01 23:00:00')).toBe(0)
    expect(daysBetween(null, '2026-09-11')).toBeNull()
    expect(daysBetween('2026-09-01', '')).toBeNull()
    expect(daysBetween('2026/09/01', '2026-09-11')).toBeNull()
  })
})

describe('trackingDateGuard · 要求工期（申请时间 → 要求完成日）', () => {
  it('从申请日算到要求完成日，不再退化成提交日起算', () => {
    expect(requestedLeadDaysText(base)).toBe('10 天')
    // 申请日早于提交日 5 天：要求工期仍按申请日算（10 天），不是按提交日算（5 天）
    const lateApply = { ...base, applicationDate: '2026-08-29' }
    expect(invalidApplicationDate(lateApply)).toBe(true)
    expect(requestedLeadDaysText(lateApply)).toBe('13 天')
  })

  it('要求完成日早于申请日：日期待核实，不编天数', () => {
    const bad = { ...base, dueDate: '2026-08-20' }
    expect(invalidRequiredDate(bad)).toBe(true)
    expect(requestedLeadDaysText(bad)).toBe('日期待核实')
  })

  it('要求完成日未填：未计算', () => {
    expect(requestedLeadDaysText({ ...base, dueDate: null })).toBe('未计算')
  })
})

describe('trackingDateGuard · 三个真身各算各的起算日', () => {
  it('计划工期 = 车间排产 → 车间预计完成（认延期）', () => {
    expect(plannedFinishDate(planned)).toBe('2026-09-15')
    expect(effectiveLeadDays(planned)).toBe(10)
    // 有延期至：终点换成延期至 —— 这就是「延期之后工期跟着变」
    expect(plannedFinishDate(delayed)).toBe('2026-09-20')
    expect(effectiveLeadDays(delayed)).toBe(15)
  })

  it('起算日取车间排产日，不是任务单提交日（提交后等车间接单的那几天不算车间工期）', () => {
    expect(leadDaysStart(planned)).toBe('2026-09-05 08:00:00')
    // 同一单：要求 10 天（从申请日 09-01 算）、计划 10 天（从排产日 09-05 算），
    // 若还按提交日 09-03 起算会是 12 天
    expect(requestedLeadDaysText(planned)).toBe('10 天')
    expect(effectiveLeadDays(planned)).toBe(10)
    expect(effectiveLeadDaysBasisText(planned)).toContain('车间排产日')
    // 排产滞后（提交→排产）仍是另一件事，由 rules.scheduleLagDays 单独算
    expect(daysBetween(planned.sourceCreatedAt, planned.scheduleTime)).toBe(2)
  })

  it('实际工期 = 车间排产 → 实际入库日（后端已按跟单员优先处理，前端不重复推导）', () => {
    expect(leadDaysKind(finished)).toBe('ACTUAL')
    expect(effectiveLeadDays(finished)).toBe(20)
    expect(effectiveLeadDaysBasisText(finished)).toContain('实际入库日')
    expect(effectiveLeadDaysBasisText(finished)).toContain('车间排产日')
  })

  it('优先级：已入库 > 车间计划 > 业务员要求', () => {
    expect(leadDaysKind(finished)).toBe('ACTUAL')
    expect(leadDaysKind(delayed)).toBe('PLANNED')
    expect(leadDaysKind(planned)).toBe('PLANNED')
    expect(leadDaysKind(base)).toBe('REQUIRED')
    // 已入库的单即便车间预计更晚，也只看既成事实
    expect(effectiveLeadDaysText(finished)).toBe('20 天')
  })

  it('只剩要求完成时回落成要求工期；要求日期不可信则日期待核实', () => {
    expect(effectiveLeadDaysText(base)).toBe('10 天')
    expect(effectiveLeadDaysText({ ...base, dueDate: '2026-08-20' })).toBe('日期待核实')
  })
})

/**
 * 未排产＝车间还没接单，不是数据错误（真库 44 单）。
 * 10-20 LQ 定：**未排产只显示要求工期**，计划/实际连明细都不出现（不是显示 `—`，也不是「未排产」三个字）；
 * 也不回落提交日——回落等于把「等车间接单」又算进车间工期，正是本次要修的病根。
 */
describe('trackingDateGuard · 未排产（车间还没接单，只给要求工期）', () => {
  it('主数值降级成要求工期，不是「未排产」也不是「未计算」', () => {
    expect(leadDaysStartMissingPlan(unscheduled)).toBe(true)
    expect(leadDaysStart(unscheduled)).toBeNull()
    expect(leadDaysKind(unscheduled)).toBe('REQUIRED')
    expect(effectiveLeadDaysText(unscheduled)).toBe('10 天')
    expect(leadDaysKindText(unscheduled)).toBe('要求')
    expect(effectiveLeadDaysCellText(unscheduled)).toBe('10 天（要求）')
    // 不是数据错误 ⇒ 不标红（红只留给负数与「日期待核实」）
    expect(leadDaysAlarm(unscheduled)).toBe(false)
  })

  it('计划/实际整条不进明细，列表次行小字也为空（没有「计划 —」这种半截行）', () => {
    expect(leadDaysHasPlan(unscheduled)).toBe(false)
    expect(leadDaysHasActual(unscheduled)).toBe(false)
    expect(leadDaysBreakdown(unscheduled).map(item => item.key)).toEqual(['REQUIRED'])
    expect(leadBreakdownText(unscheduled)).toBe('要求 10 天（当前）')
    expect(otherLeadDaysText(unscheduled)).toBe('')
  })

  it('车间给了预计完成日、甚至登记过延期，未排产也一样不给计划工期', () => {
    const delayedUnscheduled = { ...unscheduled, forecastFinishDate: '2026-09-20' }
    expect(leadDaysHasPlan(delayedUnscheduled)).toBe(false)
    expect(leadDaysBreakdown(delayedUnscheduled).map(item => item.key)).toEqual(['REQUIRED'])
    expect(effectiveLeadDaysText(delayedUnscheduled)).toBe('10 天')
  })

  it('偏差不比较（要求 vs 要求恒为 0，界面不出小字）；悬停只给一句「等车间接单」的说明', () => {
    // 终点降级成要求完成日 ⇒ 偏差恒 0，`leadDaysDeltaText` 因此给「与要求一致」，界面据空串规则不显示
    expect(leadDaysDelta(unscheduled)).toBe(0)
    expect(leadDaysDeltaText(unscheduled)).toBe('与要求一致')
    expect(leadDaysDeltaTone(unscheduled)).toBe('same')
    expect(leadDaysAwaitingSchedule(unscheduled)).toBe(true)
    expect(leadDaysAwaitingScheduleNote(unscheduled)).toContain('暂只显示业务员要求工期')
  })

  it('已排产的单不挂这句提示', () => {
    expect(leadDaysAwaitingSchedule(planned)).toBe(false)
    expect(leadDaysAwaitingScheduleNote(planned)).toBe('')
  })

  it('已交付但没登记排产：起算日兜底到任务单提交日，实际工期照给（既成事实不能藏）', () => {
    expect(leadDaysStartFallback(deliveredWithoutSchedule)).toBe(true)
    expect(leadDaysHasPlan(deliveredWithoutSchedule)).toBe(true)
    expect(leadDaysHasActual(deliveredWithoutSchedule)).toBe(true)
    expect(leadDaysStart(deliveredWithoutSchedule)).toBe('2026-09-03 09:30:00')
    expect(effectiveLeadDays(deliveredWithoutSchedule)).toBe(22)
    expect(effectiveLeadDaysText(deliveredWithoutSchedule)).toBe('22 天')
    expect(effectiveLeadDaysBasisText(deliveredWithoutSchedule)).toContain('排产日缺失')
    expect(leadDaysBreakdown(deliveredWithoutSchedule).map(item => item.key)).toEqual(['REQUIRED', 'PLANNED', 'ACTUAL'])
    // 已交付的单不该再说「等车间接单」
    expect(leadDaysAwaitingSchedule(deliveredWithoutSchedule)).toBe(false)
  })

  it('未排产且未填车间预计完成日：同样只有要求工期', () => {
    const bare = { ...base, scheduleTime: null, expectedFinishDate: null }
    expect(leadDaysKind(bare)).toBe('REQUIRED')
    expect(leadDaysBreakdown(bare).map(item => item.key)).toEqual(['REQUIRED'])
    expect(effectiveLeadDaysText(bare)).toBe('10 天')
  })
})

describe('trackingDateGuard · 延期只做标注，不再单开一个真身', () => {
  it('有延期至时徽章是「计划·延」，没有就是「计划」', () => {
    expect(leadDaysKindText(planned)).toBe('计划')
    expect(leadDaysKindText(delayed)).toBe('计划·延')
    expect(leadDaysKindText(base)).toBe('要求')
    expect(leadDaysKindText(finished)).toBe('实际')
  })

  it('延期至早于车间源预计也仍以它为准（是提前不是推后），措辞不写死', () => {
    const pulled = { ...planned, forecastFinishDate: '2026-09-13' }
    expect(plannedFinishDate(pulled)).toBe('2026-09-13')
    expect(effectiveLeadDays(pulled)).toBe(8)
    expect(leadDaysKindText(pulled)).toBe('计划·延')
  })
})

describe('trackingDateGuard · 与要求完成日的偏差（比终点，不比起算）', () => {
  it('晚=红 / 早=绿 / 一致=中性；要求日期不可信时不比较', () => {
    expect(leadDaysDelta(finished)).toBe(14)
    expect(leadDaysDeltaText(finished)).toBe('比要求晚 14 天')
    expect(leadDaysDeltaTone(finished)).toBe('late')

    const early = { ...planned, expectedFinishDate: '2026-09-09' }
    expect(leadDaysDeltaText(early)).toBe('比要求早 2 天')
    expect(leadDaysDeltaTone(early)).toBe('early')

    expect(leadDaysDeltaText({ ...base, expectedFinishDate: '2026-09-11' })).toBe('与要求一致')

    const badDue = { ...base, dueDate: '2026-08-01', deliveredDate: '2026-09-25' }
    expect(leadDaysDelta(badDue)).toBeNull()
    expect(leadDaysDeltaText(badDue)).toBe('')
    expect(effectiveLeadDaysText(badDue)).toBe('20 天')
  })
})

/**
 * 负数工期＝终点早于起算日。真库 5964 单里 70 单命中（要求 42 / 计划 24 / 实际 4），
 * 全是历史补录或日期填错（如 SCGD20260005953 要求完成日写成 2025-09-30，工期 −363 天）。
 * 这是**数据现象不是算法错**，但必须标红并给出原因，不能静默显示一个负数。
 */
describe('trackingDateGuard · 负数工期（数据可疑，标红但不藏）', () => {
  it('三个真身各自的负数原因', () => {
    const badActual = { ...base, deliveredDate: '2026-09-02' }
    expect(effectiveLeadDays(badActual)).toBe(-3)
    expect(negativeLeadDays(badActual)).toBe(true)
    expect(leadDaysAlarm(badActual)).toBe(true)
    expect(negativeLeadReason(badActual)).toContain('实际入库日早于车间排产日')

    const badPlan = { ...base, expectedFinishDate: '2026-09-02' }
    expect(negativeLeadDays(badPlan)).toBe(true)
    expect(negativeLeadReason(badPlan)).toContain('车间预计完成日早于车间排产日')

    const badRequired = { ...base, dueDate: '2026-08-20' }
    expect(effectiveLeadDaysText(badRequired)).toBe('日期待核实')
  })

  it('兜底起算时，负数原因说的是「任务单提交日」而不是排产日', () => {
    const badActual = { ...deliveredWithoutSchedule, deliveredDate: '2026-09-02' }
    expect(negativeLeadReason(badActual)).toContain('实际入库日早于任务单提交日')
  })
})

describe('trackingDateGuard · 三真身明细', () => {
  it('明细只列该单真的有的真身，各带起算日与终点日', () => {
    const items = leadDaysBreakdown(finished)
    expect(items.map(item => item.key)).toEqual(['REQUIRED', 'PLANNED', 'ACTUAL'])
    // 要求从申请日算；计划与实际共用车间排产日
    expect(items.map(item => item.start)).toEqual(['2026-09-01', '2026-09-05 08:00:00', '2026-09-05 08:00:00'])
    expect(items.map(item => item.date)).toEqual(['2026-09-11', '2026-09-20', '2026-09-25'])
    expect(items.map(item => item.text)).toEqual(['10 天', '15 天', '20 天'])
    expect(items.filter(item => item.current).map(item => item.key)).toEqual(['ACTUAL'])
    expect(items[1].delayed).toBe(true)
  })

  it('没有的真身不进明细（未入库：只有要求与计划）', () => {
    const items = leadDaysBreakdown(planned)
    expect(items.map(item => item.key)).toEqual(['REQUIRED', 'PLANNED'])
    expect(leadBreakdownText(planned)).toBe('要求 10 天 · 计划 10 天（当前）')
    expect(leadBreakdownText(delayed)).toBe('要求 10 天 · 计划 15 天（含延期）（当前）')
  })

  it('列表次行只列当前真身以外的（当前已在徽章里，不重复）', () => {
    expect(otherLeadDaysText(finished)).toBe('要求 10 天 · 计划·延 15 天')
    expect(otherLeadDaysText(planned)).toBe('要求 10 天')
    expect(otherLeadDaysText(base)).toBe('')
  })

  it('要求日期不可信时该真身的明细给「—」，与「日期待核实」一致', () => {
    const bad = { ...base, dueDate: '2026-08-20', deliveredDate: '2026-09-25' }
    const required = leadDaysBreakdown(bad).find(item => item.key === 'REQUIRED')
    expect(required?.text).toBe('—')
    expect(required?.days).toBeNull()
  })
})

describe('trackingDateGuard · 导出与页面接线', () => {
  it('导出格把真身落成字（线下看不到徽章颜色）', () => {
    expect(effectiveLeadDaysCellText(finished)).toBe('20 天（实际）')
    expect(effectiveLeadDaysCellText(delayed)).toBe('15 天（计划·延）')
    expect(effectiveLeadDaysCellText(base)).toBe('10 天（要求）')
  })

  const here = dirname(fileURLToPath(import.meta.url))
  const viewSource = readFileSync(
    resolve(here, '../apps/archive/views/production/ProductionTrackingView.vue'), 'utf8')
  // 导出列定义 2026-10 起搬出页面（列定义与落盘收在 useTrackingExport），
  // 「两处导出都取本模块口径」这条契约跟着去新宿主查；页面侧只留列表列与详情卡片。
  const exportSource = readFileSync(
    resolve(here, '../composables/tracking/useTrackingExport.ts'), 'utf8')
  const displaySource = readFileSync(resolve(here, 'tracking/leadDaysDisplay.ts'), 'utf8')
  // 详情卡 2026-10-22 抽成 TrackingDetailDrawer（生产跟单页与「生产产品与工期管理」页共用），
  // 详情侧的接线契约跟着去该组件查，不再钉页面。
  const drawerSource = readFileSync(
    resolve(here, '../components/production/TrackingDetailDrawer.vue'), 'utf8')

  it('页面不得再自带一份日期判据 / 工期实现', () => {
    expect(viewSource).toContain("from '@/utils/trackingDateGuard'")
    expect(viewSource).not.toMatch(/function\s+(invalidApplicationDate|invalidRequiredDate|daysBetween|dateDay|requestedLeadDaysText|leadDaysKind|negativeLeadDays|leadDaysAlarm|negativeLeadReason|leadDaysKindText)\s*\(/)
  })

  it('列表列、详情卡片、两处导出都取本模块的口径', () => {
    // 列表列：页面里直接取本模块（悬停明细走 leadDaysDisplay）
    expect(viewSource.match(/effectiveLeadDaysText\(/g)?.length ?? 0).toBeGreaterThanOrEqual(1)
    expect(viewSource).toContain('leadDaysKindText(row)')
    expect(viewSource).toContain('otherLeadDaysText(row)')
    expect(viewSource).toContain('leadDaysTooltipHtml(row)')

    // 详情卡：同一套口径，宿主是 TrackingDetailDrawer
    expect(drawerSource).toContain('effectiveLeadDaysText(detail.order)')
    expect(drawerSource).toContain('leadDaysTooltipHtml(detail.order)')
    expect(drawerSource).toContain('leadDaysAlarm(detail.order)')
    expect(drawerSource).toContain('negativeLeadReason(detail.order)')
    expect(drawerSource).toContain('leadDaysDeltaText(detail.order)')

    // 配色与悬停明细由 leadDaysDisplay 提供，它自己只从本模块取数
    expect(displaySource).toContain("from '@/utils/trackingDateGuard'")
    expect(displaySource).toContain('leadDaysBreakdown(row)')

    // 两处导出：Excel 独立列 + 网页版并入「要求完成」格的副行
    expect(exportSource.match(/effectiveLeadDaysCellText\(/g)?.length ?? 0).toBeGreaterThanOrEqual(2)
  })
})
