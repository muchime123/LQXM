/**
 * 工期的**展示口径**（配色类名 / 悬停明细 HTML）唯一来源。
 *
 * 生产跟单列表与跟单详情抽屉（生产跟单页、生产产品与工期管理页共用同一个抽屉组件）
 * 都显示同一套工期数字，配色与悬停文案必须一份实现——两边各写一份，
 * 就会出现「列表说晚 2 天、详情解释另一套」的口径漂移。
 */
import type { TrackingOrder } from '@/api/productionTracking'
import {
  invalidApplicationDate, invalidRequiredDate, leadDaysAlarm, leadDaysAwaitingScheduleNote,
  leadDaysBreakdown, leadDaysDeltaText, leadDaysDeltaTone, leadDaysStartFallback,
  negativeLeadDays, negativeLeadReason
} from '@/utils/trackingDateGuard'
import { shortDate } from '@/utils/tracking/rules'

/** 偏差小字的配色：晚=红、早=绿、一致=中性灰。 */
export function leadDeltaClass(row: TrackingOrder) {
  const tone = leadDaysDeltaTone(row)
  return tone === 'late' ? 'lead-delta-late' : tone === 'early' ? 'lead-delta-early' : ''
}

/** 工期主数值的配色：负数 / 「日期待核实」=红；否则比要求晚=红、比要求早=绿。 */
export function leadDaysCellClass(row: TrackingOrder) {
  if (leadDaysAlarm(row)) return 'is-danger'
  return leadDeltaClass(row)
}

/**
 * 工期悬停提示里的**原因**部分；空串＝没有需要解释的事。
 * 三真身起算日不同（要求从申请日、计划/实际从车间排产日），起算口径不在这里复述。
 */
export function leadDaysTooltip(row: TrackingOrder) {
  const notes: string[] = []
  if (negativeLeadDays(row)) notes.push(negativeLeadReason(row))
  if (leadDaysStartFallback(row)) notes.push('排产时间未登记，实际工期按任务单提交日起算')
  else if (leadDaysAwaitingScheduleNote(row)) notes.push(leadDaysAwaitingScheduleNote(row))
  if (invalidApplicationDate(row)) notes.push('申请日期早于任务单提交日期，要求工期仍按申请日起算，天数偏长')
  if (invalidRequiredDate(row)) notes.push('业务员要求日期早于申请/提交日期，要求工期无法计算')
  else {
    // 未排产的单只有要求工期：偏差恒为「与要求一致」时跳过，只补一句为什么没有计划/实际
    const delta = leadDaysDeltaText(row)
    if (delta && delta !== '与要求一致') notes.push(delta)
  }
  return notes.join('；')
}

/**
 * 工期悬停明细（el-tooltip `raw-content`）。
 * 逐条列出这条工单实际具备的真身（各带起算日与终点日），当前取用的打「当前」，再挂原因。
 *
 * ⚠️ 内容全部由日期与数字拼出，不含用户输入，不做转义是安全的；不要往里塞备注类自由文本。
 */
export function leadDaysTooltipHtml(row: TrackingOrder) {
  const lines = leadDaysBreakdown(row).map(item => {
    const range = item.start && item.date ? `（${shortDate(item.start)} → ${shortDate(item.date)}）` : ''
    const delay = item.delayed ? '，含延期' : ''
    const hint = item.missing && item.date && !item.start ? `（起算日缺失 → ${shortDate(item.date)}）` : ''
    return `${item.current ? '<b>当前</b> ' : ''}${item.label} ${item.text}${range}${hint}${delay}`
  })
  const note = leadDaysTooltip(row)
  if (note) lines.push(note)
  return lines.join('<br/>')
}
