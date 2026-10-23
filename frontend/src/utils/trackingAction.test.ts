import { describe, expect, it } from 'vitest'
import { STALE_FOLLOWUP_DAYS, TRACKING_ACTION_CODES, TRACKING_ACTION_LABELS, trackingActionHint, trackingActionLabel, trackingActionTone } from './trackingAction'
import type { TrackingActionCode } from '@/api/productionTracking'

/**
 * 跟单待办是「后端出代码、前端出文案」的跨层契约：代码写错或漏映射会静默显示成空白，
 * 所以两边能对上的部分全部钉死。
 */
describe('生产跟单：跟单待办提醒的文案与配色', () => {
  it('每个后端可能返回的代码都必须有中文标签，一个都不能漏', () => {
    for (const code of TRACKING_ACTION_CODES) {
      expect(TRACKING_ACTION_LABELS[code], code).toBeTruthy()
      expect(trackingActionLabel(code)).toBe(TRACKING_ACTION_LABELS[code])
    }
    expect(TRACKING_ACTION_CODES).toEqual(['FINISHED_PENDING', 'INBOUND_PENDING', 'SCHEDULED_PENDING', 'INBOUND_UNLOGGED', 'SCHEDULE_DUE', 'DELIVERY_PRESSURE', 'STALE_FOLLOWUP'])
  })

  it('没有提醒时不显示文字、也不给配色', () => {
    expect(trackingActionLabel(null)).toBe('')
    expect(trackingActionLabel(undefined)).toBe('')
    expect(trackingActionHint({ trackingAction: null })).toBe('')
  })

  it('越严重越红：已完工红、已入库橙、其余蓝', () => {
    expect(trackingActionTone('FINISHED_PENDING')).toBe('danger')
    expect(trackingActionTone('INBOUND_PENDING')).toBe('warning')
    expect(trackingActionTone('SCHEDULE_DUE')).toBe('warning')
    expect(trackingActionTone('DELIVERY_PRESSURE')).toBe('warning')
    expect(trackingActionTone('SCHEDULED_PENDING')).toBe('info')
    expect(trackingActionTone('INBOUND_UNLOGGED')).toBe('info')
    expect(trackingActionTone('STALE_FOLLOWUP')).toBe('info')
  })

  it('提示里要带上命中的那个事实日期，而不是只说一句「待跟单」', () => {
    expect(trackingActionHint({ trackingAction: 'FINISHED_PENDING', actualFinishDate: '2026-10-08' }))
      .toContain('2026-10-08')
    expect(trackingActionHint({ trackingAction: 'INBOUND_PENDING', inboundDate: '2026-10-09' }))
      .toContain('2026-10-09')
    // 排产时间是 DATETIME，只取日期部分，别把 00:00:00 带出去。
    expect(trackingActionHint({ trackingAction: 'SCHEDULED_PENDING', scheduleTime: '2026-10-09T08:06:00' }))
      .toContain('2026-10-09')
    expect(trackingActionHint({ trackingAction: 'INBOUND_UNLOGGED', inboundDate: '2026-10-09' }))
      .toContain('入库')
    expect(trackingActionHint({ trackingAction: 'STALE_FOLLOWUP', lastFollowupAt: '2026-09-20T10:00:00' }))
      .toContain('2026-09-20')
  })

  it('事实日期缺失时显式说明，不留空让人以为是排版漏了', () => {
    expect(trackingActionHint({ trackingAction: 'FINISHED_PENDING' })).toContain('日期缺失')
    expect(trackingActionHint({ trackingAction: 'STALE_FOLLOWUP' })).toContain('无记录')
  })

  it('「7 天未更新」的天数与后端阈值同源', () => {
    expect(STALE_FOLLOWUP_DAYS).toBe(7)
    expect(trackingActionLabel('STALE_FOLLOWUP')).toBe('7 天未更新')
    expect(trackingActionHint({ trackingAction: 'STALE_FOLLOWUP', lastFollowupAt: '2026-09-20' }))
      .toContain(`${STALE_FOLLOWUP_DAYS} 天`)
  })

  it('标签即枚举值本身，不能被悄悄改名（改了后端筛选就匹配不上）', () => {
    const codes: TrackingActionCode[] = ['FINISHED_PENDING', 'INBOUND_PENDING', 'SCHEDULED_PENDING', 'INBOUND_UNLOGGED', 'SCHEDULE_DUE', 'DELIVERY_PRESSURE', 'STALE_FOLLOWUP']
    expect(Object.keys(TRACKING_ACTION_LABELS).sort()).toEqual([...codes].sort())
  })
})
