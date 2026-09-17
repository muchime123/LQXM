/**
 * 数值格式化工具
 * 从 MediaAccountView.vue 抽取的数值格式化逻辑
 */

/**
 * 将数值格式化为带单位的紧凑形式
 * >= 10000: 以 "万" 为单位
 * >= 100000000: 以 "亿" 为单位
 * @param num
 */
export function formatCompactNumber(num: number | string | null | undefined): string {
  if (num === null || num === undefined || num === '') return ''
  const n = Number(num)
  if (isNaN(n)) return ''
  if (n >= 100000000) return (n / 100000000).toFixed(1).replace(/\.0$/, '') + '亿'
  if (n >= 10000) return (n / 10000).toFixed(1).replace(/\.0$/, '') + '万'
  return String(n)
}

/**
 * 安全解析数值字符串
 * @param val
 */
export function parseNumber(val: string | number | null | undefined): number {
  if (val === null || val === undefined || val === '') return 0
  return parseInt(String(val).replace(/,/g, ''), 10) || 0
}

/**
 * 数值显示（若为空则显示 -）
 */
export function formatValue<T>(value: T | null | undefined): T | '-' {
  if (value === null || value === undefined || String(value).trim() === '') return '-'
  return value
}

/**
 * 格式化文件大小（B / KB / MB / GB）
 */
export function formatFileSize(bytes?: number | null): string {
  if (!bytes && bytes !== 0) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

/**
 * 格式化人民币金额（带千分位与 ¥ 前缀）
 * @param value - 数值或数值字符串
 * @param empty - 空值时的占位符
 */
export function formatCurrency(value: number | string | null | undefined, empty = '-'): string {
  if (value === null || value === undefined || value === '') return empty
  const n = Number(value)
  if (isNaN(n)) return empty
  return '¥' + n.toLocaleString('zh-CN')
}

/**
 * 格式化人民币金额（带千分位、¥ 前缀与固定小数位）
 * 适用于价格、报价等需要精确到分的场景。
 * @param value - 数值或数值字符串
 * @param empty - 空值时的占位符
 * @param fractionDigits - 小数位数，默认 2
 */
export function formatCurrencyExact(
  value: number | string | null | undefined,
  empty = '-',
  fractionDigits = 2
): string {
  if (value === null || value === undefined || value === '') return empty
  const n = Number(value)
  if (isNaN(n)) return empty
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: 'CNY',
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits
  }).format(n)
}
