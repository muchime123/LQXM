import { reactive, type Reactive } from 'vue'
import type {
  BatchResultLevel,
  BatchResultSummary,
  BatchProgressState
} from '../../types/batchFlow'

/* ================ 纯函数版（toast 场景，无需 computed） ================ */

/**
 * R2 · 纯函数：根据 BatchResultSummary 判定结果等级。
 *
 * 判定规则（与 Catalog/Packaging 批量操作保持一致）：
 *   1. errors 非空或 failed>0 → error（批量操作失败）
 *   2. affected=0 && skipped=0 → info（无受影响数据）
 *   3. skipped>0 或 failed=0 但 affected<total → warning（部分成功）
 *   4. 否则 → success（完全成功）
 */
export function deriveBatchLevel(s: BatchResultSummary | null | undefined): BatchResultLevel {
  if (!s) return 'info'
  if (s.errors && s.errors.length > 0) return 'error'
  const affected = s.affected ?? 0
  const skipped = s.skipped ?? 0
  const failed = s.failed ?? 0
  if (failed > 0) return 'error'
  if (affected === 0 && skipped === 0) return 'info'
  const total = s.total ?? 0
  if (total > 0 && affected < total) return 'warning'
  if (skipped > 0) return 'warning'
  return 'success'
}

/**
 * R2 · 纯函数：生成标题。
 */
export function deriveBatchTitle(s: BatchResultSummary | null | undefined, actionLabel = '操作'): string {
  if (!s) return ''
  switch (deriveBatchLevel(s)) {
    case 'error':
      return `${actionLabel}失败`
    case 'info':
      return `没有数据被${actionLabel}`
    case 'warning':
      return `部分${actionLabel}成功`
    case 'success':
    default:
      return `${actionLabel}完成`
  }
}

/**
 * R2 · 纯函数：生成副标题 / 详情文案。
 */
export function deriveBatchSubtitle(s: BatchResultSummary | null | undefined): string {
  if (!s) return ''
  const parts: string[] = []
  if (s.affected != null && s.affected > 0) parts.push(`成功 ${s.affected} 条`)
  if (s.skipped && s.skipped > 0) parts.push(`跳过 ${s.skipped} 条`)
  if (s.failed && s.failed > 0) parts.push(`失败 ${s.failed} 条`)
  if (s.errors && s.errors.length > 0) parts.push(`错误 ${s.errors.length} 条`)
  if (s.custom) {
    for (const [key, val] of Object.entries(s.custom)) {
      if (val > 0) parts.push(`${key} ${val} 条`)
    }
  }
  return parts.length === 0 ? '' : parts.join('，') + '。'
}

/**
 * R2 · 纯函数：生成 toast 通知参数 { message, level }。
 *
 * 用法：
 *   const toast = buildBatchToast(summary, '批量删除')
 *   notify(toast.message || '操作完成', toast.level)
 */
export function buildBatchToast(
  s: BatchResultSummary | null | undefined,
  actionLabel = '操作'
): {
  title: string
  message: string
  level: BatchResultLevel
} {
  const title = deriveBatchTitle(s, actionLabel)
  const message = deriveBatchSubtitle(s)
  const level = deriveBatchLevel(s)
  return { title, message, level }
}

/* ================ 响应式 composable 版（进度面板场景） ================ */

/**
 * R2 · 批量操作进度状态管理。
 *
 * 替代各业务 composable 中手写的 reactive progress + show/update/done/error 四件套。
 *
 * 用法：
 *   const { progress, showProgress, updateProgress, setDone, setError, hide } = useBatchProgress()
 *   showProgress('批量删除规格', items.length)
 *   for (let i = 0; i < items.length; i++) {
 *     await api.delete(items[i].id)
 *     updateProgress(i + 1)
 *   }
 *   setDone()
 */
export function useBatchProgress(): {
  progress: Reactive<BatchProgressState>
  showProgress: (title: string, total: number) => void
  updateProgress: (current: number) => void
  setDone: (autoHideMs?: number) => void
  setError: (autoHideMs?: number) => void
  hide: () => void
} {
  const progress = reactive<BatchProgressState>({
    visible: false,
    title: '',
    current: 0,
    total: 0,
    status: 'running'
  })

  function showProgress(title: string, total: number) {
    progress.visible = true
    progress.title = title
    progress.current = 0
    progress.total = total
    progress.status = 'running'
  }

  function updateProgress(current: number) {
    progress.current = current
  }

  function setDone(autoHideMs = 800) {
    progress.status = 'done'
    if (autoHideMs > 0) {
      setTimeout(() => {
        progress.visible = false
      }, autoHideMs)
    }
  }

  function setError(autoHideMs = 1500) {
    progress.status = 'error'
    if (autoHideMs > 0) {
      setTimeout(() => {
        progress.visible = false
      }, autoHideMs)
    }
  }

  function hide() {
    progress.visible = false
  }

  return {
    progress,
    showProgress,
    updateProgress,
    setDone,
    setError,
    hide
  }
}
