import { describe, expect, it } from 'vitest'
import { deliveryText, countdown, followUpChanges, orderDeliveryText, productionIsComplete } from './productionFollowup'
import type { FollowUpState, FollowUpOrder } from '@/api/manufacturing/followup'

const blank: FollowUpState = { plannedStartDate: null, expectedFinishDate: null, delayReason: '', completedDate: null, warehousedDate: null, shippedDate: null, remark: '', stages: {} }
describe('生产跟单交期与修订', () => {
  it('氚云已完成但缺日期时不能显示为逾期未完工', () => {
    const order: FollowUpOrder = { id: 1, workOrderNo: 'H3-1', productName: '测试', version: 0, state: blank, stageNames: [], productionCompleted: true, requiredCompletionDate: '2025-04-07' }
    expect(productionIsComplete(order)).toBe(true)
    expect(orderDeliveryText(order, '2026-09-22')).toBe('氚云已完成，缺完成日期')
  })
  it('修订预计日期不会抹掉原交期超期', () => {
    expect(deliveryText('2026-09-20', null, '2026-09-22')).toBe('原交期已超 2 天')
    expect(countdown('2026-09-25', '2026-09-22')).toBe('剩余 3 天')
  })
  it('按实际生产完成评价，缺交期不能伪装为按期', () => {
    expect(deliveryText('2026-09-20', '2026-09-19')).toBe('按期完工')
    expect(deliveryText('2026-09-20', '2026-09-22')).toBe('延期完工 2 天')
    expect(deliveryText(undefined, '2026-09-19')).toBe('未设置原交期')
  })
  it('历史明确显示字段清空、阶段更正且不制造无意义改动', () => {
    const before = { ...blank, expectedFinishDate: '2026-09-25', stages: { 备料: '2026-09-18' } }
    expect(followUpChanges(before, blank)).toEqual(['预计完成：2026-09-25 → 已清空', '备料：2026-09-18 → 已清空'])
    expect(followUpChanges(blank, { ...blank, stages: { 备料: null } })).toEqual([])
  })
})
