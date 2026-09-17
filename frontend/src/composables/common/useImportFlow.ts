import type { ImportResultLevel, ImportResultSummary, ImportState } from '../../types/importFlow'

/* ================ 纯函数版（toast 场景，无需 computed） ================ */

/**
 * R1 · 纯函数：根据 ImportResultSummary 判定结果等级。
 * 用于 toast / 行内文案等无需 computed 响应式包装的场景。
 */
export function deriveImportLevel(s: ImportResultSummary | null | undefined): ImportResultLevel {
  if (!s) return 'info'
  if (s.errors && s.errors.length > 0) return 'error'
  const created = s.created ?? 0
  const updated = s.updated ?? 0
  const skipped = s.skipped ?? 0
  if (created === 0 && updated === 0 && skipped === 0) return 'info'
  if (skipped > 0 && created + updated === 0) return 'info'
  if (skipped > 0) return 'warning'
  return 'success'
}

/**
 * R1 · 纯函数：生成标题。
 */
export function deriveImportTitle(s: ImportResultSummary | null | undefined): string {
  if (!s) return ''
  switch (deriveImportLevel(s)) {
    case 'error':
      return '导入失败'
    case 'info':
      return '没有新数据可导入'
    case 'warning':
      return '部分导入成功'
    case 'success':
    default:
      return '导入完成'
  }
}

/**
 * R1 · 纯函数：生成副标题 / 详情文案。
 */
export function deriveImportSubtitle(s: ImportResultSummary | null | undefined): string {
  if (!s) return ''
  const parts: string[] = []
  if (s.created && s.created > 0) parts.push(`新增 ${s.created} 条`)
  if (s.updated && s.updated > 0) parts.push(`更新 ${s.updated} 条`)
  if (s.skipped && s.skipped > 0) parts.push(`跳过 ${s.skipped} 条`)
  if (s.errors && s.errors.length > 0) parts.push(`错误 ${s.errors.length} 条`)
  if (s.custom) {
    for (const [key, val] of Object.entries(s.custom)) {
      if (val > 0) parts.push(`${key} ${val} 条`)
    }
  }
  return parts.length === 0 ? '' : parts.join('，') + '。'
}

/**
 * R1 · 纯函数：生成 toast 通知参数 { message, level }。
 * level 会映射成 Element Plus / Lq Toast 的通知类型（success / warning / error / info）。
 *
 * 用法：
 *   const toast = buildImportToast(summary)
 *   notify(toast.message, toast.level)
 */
export function buildImportToast(s: ImportResultSummary | null | undefined): {
  title: string
  message: string
  level: ImportResultLevel
} {
  const title = deriveImportTitle(s)
  const message = deriveImportSubtitle(s)
  const level = deriveImportLevel(s)
  return { title, message, level }
}

/* ================ 导入流程状态顺序常量 ================ */

/**
 * 导入流程 state 顺序：idle(0) < upload(1) < preview(2) < executing(3) < result(4)。
 */
export const IMPORT_STATE_ORDER: Record<ImportState, number> = {
  idle: 0,
  upload: 1,
  preview: 2,
  executing: 3,
  result: 4
}
