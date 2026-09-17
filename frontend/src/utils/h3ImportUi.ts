/**
 * 氚云导入中心共享的展示纯函数（从 H3ImportCenterView 提取，供页面与各弹窗/抽屉子组件复用）。
 * 与业务语义相关的状态文案/标签仍统一收敛在 @/constants/h3Import。
 */

/** P0-C：金额/数量列格式化——纯数字字符串加 ¥ 前缀（生产任务的需求数量也按数字显示） */
export function amountCell(_row: any, _column: any, cellValue: any) {
  if (cellValue == null || cellValue === '') return '—'
  const num = Number(cellValue)
  if (Number.isFinite(num)) {
    return num >= 100 || !Number.isInteger(num)
      ? '¥' + num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
      : String(num)
  }
  return String(cellValue)
}

/** 台账/库存的普通数字列（不带 ¥ 前缀）。 */
export function numberCell(_row: any, _column: any, cellValue: any) {
  if (cellValue == null || cellValue === '') return '—'
  const num = Number(cellValue)
  return Number.isFinite(num) ? num.toLocaleString('zh-CN') : String(cellValue)
}

/** 金额列（¥ 两位小数）。 */
export function priceCell(_row: any, _column: any, cellValue: any) {
  if (cellValue == null || cellValue === '') return '—'
  const num = Number(cellValue)
  return Number.isFinite(num)
    ? '¥' + num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    : String(cellValue)
}

export function fmtQty(v: any) {
  if (v == null || v === '') return '—'
  const num = Number(v)
  return Number.isFinite(num) ? num.toLocaleString('zh-CN') : String(v)
}

/** 日期时间格式化（用于完成时间等列，'T' 分隔 → 空格） */
export function dateCell(_r: any, _c: any, v: any) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '-'
}

/** 日期时间格式化（用于候选统计刷新时间 tooltip） */
export function formatDateTime(v?: string | null) {
  return v ? String(v).replace('T', ' ').slice(0, 19) : '—'
}

/** 候选举百分比（用于进度条段宽度） */
export function pct(part: number | undefined, total: number | undefined) {
  const t = total ?? 0
  if (!t) return 0
  return Math.round(((part ?? 0) / t) * 100)
}

/** P0-D：编排步骤名回退（s.step / s.name / Step N） */
export function stepTitle(s: any, i: number | string): string {
  const n = Number(i)
  return String(s?.step || s?.name || '') || `Step ${Number.isFinite(n) ? n + 1 : i}`
}

/** P0-D：编排步骤状态 → el-tag type + 文案 */
export function stepStatusLabel(s: any): { text: string; type: 'success' | 'danger' | 'info' | 'warning' } {
  if (s == null) return { text: '—', type: 'info' }
  const status = String(s.status || s.outcome || '').toUpperCase()
  if (status === 'SUCCESS' || status === 'OK' || status === 'DONE') return { text: '成功', type: 'success' }
  if (status === 'FAILED' || status === 'ERROR') return { text: '失败', type: 'danger' }
  if (status === 'SKIPPED') return { text: '跳过', type: 'info' }
  if (status === 'PARTIAL') return { text: '部分', type: 'warning' }
  return { text: status || '—', type: 'info' }
}
