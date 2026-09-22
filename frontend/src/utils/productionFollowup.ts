import type { FollowUpOrder, FollowUpState } from '@/api/manufacturing/followup'

export function productionIsComplete(order: FollowUpOrder) {
  return !!(order.productionCompleted || order.actualCompletionDate || order.state.completedDate)
}
export function orderDeliveryText(order: FollowUpOrder, today = localToday()) {
  if (['已取消', 'CANCELLED'].includes(order.status || '')) return '已取消'
  const date = order.state.completedDate || order.actualCompletionDate || null
  if (productionIsComplete(order) && !date) return '氚云已完成，缺完成日期'
  return deliveryText(order.requiredCompletionDate, date, today)
}

const day = (date: string) => Date.parse(`${date.slice(0, 10)}T00:00:00Z`) / 86400000
export function localToday() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
export function deliveryText(required: string | undefined, actual: string | null, today = localToday()) {
  if (!required) return '未设置原交期'
  const diff = day(actual || today) - day(required)
  if (actual) return diff <= 0 ? '按期完工' : `延期完工 ${diff} 天`
  return diff > 0 ? `原交期已超 ${diff} 天` : diff === 0 ? '原交期今日到期' : `原交期剩余 ${-diff} 天`
}
export function countdown(date: string | null | undefined, today = localToday()) {
  if (!date) return '未设置预计日期'
  const diff = day(date) - day(today)
  return diff > 0 ? `剩余 ${diff} 天` : diff === 0 ? '今日到期' : `已超期 ${-diff} 天`
}
const labels: Record<string, string> = {
  plannedStartDate: '计划开工', expectedFinishDate: '预计完成', delayReason: '延期原因',
  completedDate: '实际生产完成', warehousedDate: '入库', shippedDate: '发货', remark: '备注',
}
export function followUpChanges(before: FollowUpState, after: FollowUpState) {
  const changes: string[] = []
  for (const key of Object.keys(labels) as (keyof Omit<FollowUpState, 'stages'>)[]) {
    if ((before[key] || '') !== (after[key] || '')) changes.push(`${labels[key]}：${before[key] || '未填写'} → ${after[key] || '已清空'}`)
  }
  for (const key of new Set([...Object.keys(before.stages || {}), ...Object.keys(after.stages || {})])) {
    if ((before.stages?.[key] || '') !== (after.stages?.[key] || '')) changes.push(`${key}：${before.stages?.[key] || '未填写'} → ${after.stages?.[key] || '已清空'}`)
  }
  return changes
}
