/**
 * 自媒体账号通用工具函数（列表表格与卡片视图共享）
 */

import { tagList } from '../common/textUtils'

export const STALE_DAYS = 14

export { tagList }

interface AccountLike {
  lastUpdatedAt?: string
  latestUpdateType?: string
  latestUpdateTitle?: string
}

export function formatNum(n: number): string {
  if (n >= 10000) return (n / 10000).toFixed(1).replace(/\.0$/, '') + 'w'
  return String(n)
}

export function parseNum(val: string | number | null | undefined): number {
  if (!val) return 0
  return parseInt(String(val).replace(/,/g, ''), 10) || 0
}

export function isStale(acct: AccountLike | null | undefined): boolean {
  if (!acct?.lastUpdatedAt) return true
  return (new Date().getTime() - new Date(acct.lastUpdatedAt).getTime()) / 86400000 > STALE_DAYS
}

export function statusClass(acct: AccountLike | null | undefined): 'never' | 'active' | 'normal' | 'warning' | 'stale' {
  if (!acct?.lastUpdatedAt) return 'never'
  const diff = (new Date().getTime() - new Date(acct.lastUpdatedAt).getTime()) / 86400000
  if (diff <= 3) return 'active'
  if (diff <= 7) return 'normal'
  if (diff <= 14) return 'warning'
  return 'stale'
}

export function statusTitle(acct: AccountLike | null | undefined): string {
  if (!acct?.lastUpdatedAt) return '从未更新'
  return `${Math.floor((new Date().getTime() - new Date(acct.lastUpdatedAt).getTime()) / 86400000)}天前更新`
}

export function updateTitle(acct: AccountLike | null | undefined): string {
  if (!acct?.lastUpdatedAt) return ''
  const type = acct.latestUpdateType || '更新'
  const title = acct.latestUpdateTitle ? `：${acct.latestUpdateTitle}` : ''
  return `[${type}]${title}`
}
