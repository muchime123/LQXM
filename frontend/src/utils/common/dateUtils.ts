/**
 * 日期处理工具
 * 从 MediaAccountView.vue 重复的日期处理逻辑抽取
 */

const DAY_MS = 1000 * 60 * 60 * 24

/**
 * 获取 YYYY-MM-DD 格式日期字符串
 * @param date - 日期对象或日期字符串，默认为今天
 */
export function formatDate(date: Date | string = new Date()): string {
  const d = date instanceof Date ? date : new Date(date)
  return d.toISOString().slice(0, 10)
}

/**
 * 获取 YYYY-MM-DD HH:mm 格式日期时间字符串
 */
export function formatDateTime(date: Date | string): string {
  const d = date instanceof Date ? date : new Date(date)
  if (isNaN(d.getTime())) return String(date)
  const pad = (n: number) => n.toString().padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/**
 * 计算日期距今天数并返回友好描述
 * @param dateStr - YYYY-MM-DD 格式的日期字符串
 */
export function daysAgo(dateStr: string | null | undefined): string {
  if (!dateStr) return ''
  const diff = Math.floor((new Date().getTime() - new Date(dateStr).getTime()) / DAY_MS)
  if (diff === 0) return '今天'
  if (diff === 1) return '昨天'
  if (diff < 30) return `${diff}天前`
  if (diff < 365) return `${Math.floor(diff / 30)}个月前`
  return `${Math.floor(diff / 365)}年前`
}

/**
 * 获取指定天数范围内的日期数组
 * @param days - 天数
 * @returns YYYY-MM-DD 格式的日期数组，从 days-1 天前到今天
 */
export function getDateRange(days: number): string[] {
  const today = new Date()
  const dates: string[] = []
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today)
    d.setDate(d.getDate() - i)
    dates.push(formatDate(d))
  }
  return dates
}

/**
 * 解析 YYYY-MM-DD 格式的日期字符串
 * @param dateStr
 * @returns Date|null
 */
export function parseDate(dateStr: string | null | undefined): Date | null {
  if (!dateStr) return null
  const d = new Date(dateStr)
  return isNaN(d.getTime()) ? null : d
}

/**
 * 计算两个日期之间的天数差
 * @param a
 * @param b
 */
export function diffDays(a: Date | string | null | undefined, b: Date | string | null | undefined): number {
  const da = a instanceof Date ? a : parseDate(a)
  const db = b instanceof Date ? b : parseDate(b)
  if (!da || !db) return 0
  return Math.floor((db.getTime() - da.getTime()) / DAY_MS)
}

/**
 * 格式化日期时间为 YYYY-MM-DD HH:mm 格式
 * @param d - ISO 日期字符串
 * @returns string
 */
export function fmtDateTime(d: string | null | undefined): string {
  if (!d) return '-'
  return String(d).slice(0, 16).replace('T', ' ')
}

/**
 * 格式化日期为 YYYY-MM-DD 格式（仅日期部分）
 * @param d - ISO 日期字符串或 YYYY-MM-DD
 * @returns string
 */
export function fmtDate(d: string | null | undefined): string {
  if (!d) return '-'
  return String(d).slice(0, 10)
}

/**
 * 格式化为本地化完整日期时间（zh-CN）
 * @param iso - ISO 日期字符串
 * @returns string
 */
export function fmtLocale(iso: string | null | undefined): string {
  if (!iso) return ''
  return new Date(iso).toLocaleString('zh-CN')
}

/**
 * 格式化为本地化日期时间（年月日时分，2 位数）
 * @param iso - ISO 日期字符串
 * @returns string
 */
export function fmtLocaleDateTime(iso: string | null | undefined): string {
  if (!iso) return '-'
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  return d.toLocaleString('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  })
}

/**
 * 格式化为相对时间（今天/昨天 + 时分秒，否则完整日期）
 * @param iso - ISO 日期字符串
 * @returns string
 */
export function fmtRelativeTime(iso: string | null | undefined): string {
  if (!iso) return ''
  const d = new Date(iso)
  const now = new Date()
  const today = now.toLocaleDateString('zh-CN')
  const dateStr = d.toLocaleDateString('zh-CN')
  const timeStr = d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  if (dateStr === today) return '今天 ' + timeStr
  const yesterday = new Date(now)
  yesterday.setDate(yesterday.getDate() - 1)
  if (dateStr === yesterday.toLocaleDateString('zh-CN')) return '昨天 ' + timeStr
  return dateStr + ' ' + timeStr
}

/**
 * 获取今天的 ISO 日期字符串（YYYY-MM-DD）
 * @returns string
 */
export function todayIso(): string {
  return new Date().toISOString().slice(0, 10)
}

/**
 * 格式化为本地化日期（YYYY/MM/DD 格式）
 * @param d - ISO 日期字符串或 Date 对象
 * @returns string
 */
export function fmtLocaleDate(d: string | Date | null | undefined): string {
  if (!d) return ''
  const date = d instanceof Date ? d : new Date(d)
  if (isNaN(date.getTime())) return ''
  return date.toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' })
}
