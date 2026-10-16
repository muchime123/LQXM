import { describe, expect, it } from 'vitest'
import type { TrackingMilestone, TrackingOrder } from '@/api/productionTracking'
import {
  canQuickComplete, countdownTone, currentCompletionDate, deadlineOf,
  deliveryOutcomeText, deliveryOutcomeTone, forecastAdjustText, inboundBeforeReporting,
  listCompletionTarget, listStages, needsTimeReason, nextListStage, productSize, productionCountdown,
  reviewOtherReasonsText, reviewReasonText, shortDate, shortOrderNo, shortWorkshop, stageNotesText,
  stageRelevant, stageValidationError, timeException, timeSignals
} from './rules'

const order = (patch: Partial<TrackingOrder> = {}): TrackingOrder => ({
  id: 1, orderNo: 'SCGD20260006047', productNameSnapshot: '封口机', plannedQuantity: 1, unit: '台',
  customerNameSnapshot: '示例客户', version: 0, ...patch
})

const stage = (patch: Partial<TrackingMilestone> = {}): TrackingMilestone => ({
  code: 'MATERIAL', label: '备料', status: 'PENDING', completedDate: null, note: null, ...patch
})

describe('tracking/rules · 通用展示', () => {
  it('任务号只留末尾连续数字的最后四位', () => {
    expect(shortOrderNo('SCGD20260006047')).toBe('6047')
    expect(shortOrderNo('SCGD20260006047-2')).toBe('2')
    expect(shortOrderNo(null)).toBe('')
  })

  it('列表日期省年份、车间省「车间」后缀、产品只留规格尺寸段', () => {
    expect(shortDate('2026-09-15')).toBe('09-15')
    expect(shortDate(null)).toBe('')
    expect(shortWorkshop('封口机车间')).toBe('封口机')
    expect(shortWorkshop(null)).toBe('')
    expect(productSize({ specificationSnapshot: '120×80 / 双层' })).toBe('120×80')
    expect(productSize({ specificationSnapshot: null })).toBe('')
  })
})

describe('tracking/rules · 节点（按需工序不参与分母）', () => {
  const stages = [
    stage({ code: 'MATERIAL', label: '备料', status: 'DONE', completedDate: '2026-09-10' }),
    stage({ code: 'OXIDATION', label: '氧化·喷特氟龙', optional: true, applicable: false }),
    stage({ code: 'ASSEMBLY', label: '组装' })
  ]

  it('不适用的按需工序不出现、也不算未完成', () => {
    expect(stageRelevant({ optional: true, applicable: false })).toBe(false)
    expect(stageRelevant({ optional: true, applicable: true })).toBe(true)
    expect(stageRelevant({})).toBe(true)
    expect(listStages({ stageProgress: stages }).map(item => item.code)).toEqual(['MATERIAL', 'ASSEMBLY'])
  })

  it('批量完成的目标＝下一个未完成节点，可指定节点', () => {
    const row = order({ stageProgress: stages })
    expect(nextListStage(row)?.code).toBe('ASSEMBLY')
    expect(listCompletionTarget(row, 'MATERIAL')?.code).toBe('MATERIAL')
    expect(listCompletionTarget(row, null)?.code).toBe('ASSEMBLY')
    expect(canQuickComplete(row)).toBe(true)
    expect(canQuickComplete(order({ stageProgress: stages, stoppedAt: '2026-09-20T10:00:00' }))).toBe(false)
  })

  it('节点校验：已完成必须有完成日期；超参考未完成必须写原因', () => {
    expect(stageValidationError([stage({ code: 'A', label: '备料', status: 'DONE', completedDate: null })]))
      .toBe('节点「备料」已完成时请填写完成日期')
    expect(stageValidationError([stage({ code: 'A', label: '备料', status: 'IN_PROGRESS', referenceOverdueDays: 3 })]))
      .toBe('节点「备料」已超参考完成日期仍未完成，请在节点备注中说明原因')
    expect(stageValidationError([stage({ code: 'A', label: '备料', status: 'IN_PROGRESS', referenceOverdueDays: 3, note: '等料' })]))
      .toBeNull()
  })
})

describe('tracking/rules · 日期口径', () => {
  it('截止基准：车间预计优先，未填时回落有效的业务员要求日；延期至再优先', () => {
    expect(deadlineOf(order({ expectedFinishDate: '2026-09-15', dueDate: '2026-09-11' }))).toBe('2026-09-15')
    expect(deadlineOf(order({ dueDate: '2026-09-11', applicationDate: '2026-09-01' }))).toBe('2026-09-11')
    // 要求日早于申请日 ⇒ 不可信，不作基准
    expect(deadlineOf(order({ dueDate: '2026-08-20', applicationDate: '2026-09-01' }))).toBeNull()
    expect(currentCompletionDate(order({ dueDate: '2026-09-11', forecastFinishDate: '2026-09-20' }))).toBe('2026-09-20')
  })

  it('交期变化文案认方向，不写死「延期」', () => {
    expect(forecastAdjustText(order({ expectedFinishDate: '2026-09-15', forecastFinishDate: '2026-09-20' }))).toBe('已协商延期 5 天')
    expect(forecastAdjustText(order({ expectedFinishDate: '2026-09-15', forecastFinishDate: '2026-09-13' }))).toBe('已提前 2 天')
    expect(forecastAdjustText(order({ expectedFinishDate: '2026-09-15', forecastFinishDate: '2026-09-15' }))).toBe('已确认交期')
  })

  it('倒计时的「今天」由调用方显式给出', () => {
    const row = order({ deliveryBaseline: '2026-10-18' })
    expect(productionCountdown(row, '2026-10-15')).toBe('剩余 3 天')
    expect(productionCountdown(row, '2026-10-18')).toBe('今日到期')
    expect(productionCountdown(row, '2026-10-20')).toBe('超期 2 天')
    expect(productionCountdown(row, '')).toBe('待定交期')
    expect(countdownTone(row, '2026-10-15')).toBe('countdown-soon')
    expect(countdownTone(order({}), '2026-10-15')).toBe('countdown-undated')
    expect(productionCountdown(order({ deliveredDate: '2026-10-01' }), '2026-10-15')).toBe('已交付')
    expect(productionCountdown(order({ stoppedAt: '2026-10-01T09:00:00' }), '2026-10-15')).toBe('已停止跟单')
  })
})

describe('tracking/rules · 完工 / 入库 / 交付', () => {
  it('入库早于报工（含未报工）都要提示', () => {
    expect(inboundBeforeReporting(order({ inboundDate: '2026-09-20', actualFinishDate: '2026-09-25' }))).toBe(true)
    expect(inboundBeforeReporting(order({ inboundDate: '2026-09-20' }))).toBe(true)
    expect(inboundBeforeReporting(order({ inboundDate: '2026-09-26', actualFinishDate: '2026-09-25' }))).toBe(false)
  })

  it('交付结果文案与语义色四处一致', () => {
    expect(deliveryOutcomeText(order({ deliveryOutcome: 'OVERDUE', deliveryOverdueDays: 4 }))).toBe('逾期完工 4 天')
    expect(deliveryOutcomeText(order({ deliveryOutcome: 'ON_TIME' }))).toBe('按期完工')
    expect(deliveryOutcomeText(order({ deliveryOutcome: 'NO_BASELINE' }))).toBe('已交付（无基准）')
    expect(deliveryOutcomeText(order({ deliveryOutcome: 'PENDING' }))).toBe('未完工')
    expect(deliveryOutcomeText(order({ stoppedAt: '2026-10-01T09:00:00' }))).toBe('已停止跟单')
    expect(deliveryOutcomeTone(order({ deliveryOutcome: 'OVERDUE' }))).toBe('danger')
    expect(deliveryOutcomeTone(order({ deliveryOutcome: 'PENDING' }))).toBe('')
  })
})

describe('tracking/rules · 时间核实六类判据', () => {
  it('接单：排产早于任务单提交日 → 判可疑', () => {
    expect(timeException(order({ sourceCreatedAt: '2026-09-10 09:00:00', scheduleTime: '2026-09-01' }), 'ACCEPTANCE'))
      .toBe('排产时间早于任务单提交日期，需核对源数据')
  })

  it('接单：要求日已过仍未排产 → 判可疑（取决于传入的今天）', () => {
    const row = order({ dueDate: '2026-09-11', applicationDate: '2026-09-01' })
    expect(timeException(row, 'ACCEPTANCE', '2026-09-30')).toBe('业务员要求日期已过，仍未排产接单，需核实原因')
    expect(timeException(row, 'ACCEPTANCE', '2026-09-05')).toBeNull()
  })

  it('报工：入库早于报工 → 复用入库提示文案', () => {
    expect(timeException(order({ inboundDate: '2026-09-20', actualFinishDate: '2026-09-25' }), 'REPORTING'))
      .toBe('入库早于氚云报工，请核实补录时间或工单关联')
  })

  it('逾期：只有真的逾期才要求记原因', () => {
    expect(timeException(order({ deliveryOutcome: 'ON_TIME' }), 'OVERDUE')).toBeNull()
    expect(timeException(order({ deliveryOutcome: 'OVERDUE', deliveryOverdueDays: 4 }), 'OVERDUE'))
      .toBe('实际交付比车间预计晚 4 天，需记录逾期原因')
  })

  it('已有核实记录但源日期被改过 ⇒ 仍要求再记一次', () => {
    const reviewed = { id: 9, kind: 'ACCEPTANCE' as const, status: 'RESOLVED' as const, reason: '已核实', sourceDueDate: '2026-09-11' }
    const row = order({ dueDate: '2026-09-11', applicationDate: '2026-09-01', timeReviews: [reviewed] })
    expect(timeException(row, 'ACCEPTANCE', '2026-09-30')).not.toBeNull()
    expect(needsTimeReason(row, 'ACCEPTANCE', '2026-09-30')).toBe(false)
    // 源日期改成 09-12 之后，同一条记录不再匹配 ⇒ 要求重记
    const drifted = { ...row, dueDate: '2026-09-12' }
    expect(needsTimeReason(drifted, 'ACCEPTANCE', '2026-09-30')).toBe(true)
  })

  it('时间核实列的信号清单来自同一套判据', () => {
    const row = order({ applicationDate: '2026-08-20', sourceCreatedAt: '2026-09-01 09:00:00' })
    expect(timeSignals(row, '2026-10-15')).toEqual(['申请日期待核'])
  })

  it('核实事项到期：下次跟进日 ≤ 今天才提示', () => {
    const row = order({
      timeReviews: [{ id: 1, kind: 'ACCEPTANCE', status: 'OPEN', reason: '等车间', nextFollowupDate: '2026-10-15' }]
    })
    expect(timeSignals(row, '2026-10-15')).toContain('核实事项应跟进')
    expect(timeSignals(row, '2026-10-14')).not.toContain('核实事项应跟进')
  })
})

describe('tracking/rules · 导出：原因与备注（10-15 LQ「导出后要能分析」）', () => {
  it('逾期原因只取 OVERDUE 那一条，没填就留空（不写「无」）', () => {
    const row = order({ timeReviews: [{ kind: 'OVERDUE', status: 'OPEN', reason: '配件原因：差打码机支架' }] })
    expect(reviewReasonText(row, 'OVERDUE')).toBe('配件原因：差打码机支架')
    expect(reviewReasonText(order({ timeReviews: [] }), 'OVERDUE')).toBe('')
    expect(reviewReasonText(order(), 'OVERDUE')).toBe('')
    // 空白串（录了但没内容）同样算没填
    expect(reviewReasonText(order({ timeReviews: [{ kind: 'OVERDUE', status: 'OPEN', reason: '   ' }] }), 'OVERDUE')).toBe('')
  })

  it('其他核实原因带类目前缀、多类用 / 连接，且不含被排除的 OVERDUE', () => {
    const row = order({
      timeReviews: [
        { kind: 'OVERDUE', status: 'OPEN', reason: '入库延迟，忘记入库' },
        { kind: 'ACCEPTANCE', status: 'OPEN', reason: '接单时已超期' },
        { kind: 'REPORTING', status: 'RESOLVED', reason: '源工单未填报完工' },
        { kind: 'COMMITMENT', status: 'NEGOTIATED', reason: '   ' }
      ]
    })
    expect(reviewOtherReasonsText(row, ['OVERDUE'])).toBe('接单等待：接单时已超期 / 报工核实：源工单未填报完工')
    // 不排除时 OVERDUE 也在（保留通用性，便于将来单开一列）
    expect(reviewOtherReasonsText(row)).toContain('交付逾期：入库延迟，忘记入库')
    expect(reviewOtherReasonsText(order(), ['OVERDUE'])).toBe('')
  })

  it('节点备注只列填了的节点，格式「节点名：备注」', () => {
    const row = order({
      stageProgress: [
        { code: 'MATERIAL', label: '备料', status: 'DONE', completedDate: '2026-09-10', note: '和黑白机一起发货' },
        { code: 'MOLD', label: '模具生产', status: 'DONE', completedDate: '2026-09-12', note: null },
        { code: 'ASSEMBLY', label: '组装', status: 'IN_PROGRESS', note: '下模具已好上模具还在加工' }
      ]
    })
    expect(stageNotesText(row)).toBe('备料：和黑白机一起发货 / 组装：下模具已好上模具还在加工')
    expect(stageNotesText(order())).toBe('')
    expect(stageNotesText(order({ stageProgress: [{ code: 'X', label: 'X', status: 'DONE', note: '  ' }] }))).toBe('')
  })
})

