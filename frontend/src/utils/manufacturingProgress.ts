import type { ManufacturingOperation } from '@/api/manufacturing'

export function processProgress(operations: ManufacturingOperation[]) {
  const required = operations.filter(op => op.required === true || op.required === 1)
  const completed = required.filter(op => op.status === 'COMPLETED').length
  return { required: required.length, completed, percent: required.length ? Math.round(completed / required.length * 100) : null }
}
export function stepPercent(completed?: number, required?: number): number | null {
  if (!required || required <= 0 || completed == null) return null
  return Math.min(100, Math.round(completed / required * 100))
}
export const operationStatusLabel = (status: string) => ({ PENDING: '待前序完成', READY: '待开工', IN_PROGRESS: '进行中', BLOCKED: '受阻', COMPLETED: '已完成', SKIPPED: '已跳过' }[status] || status)
