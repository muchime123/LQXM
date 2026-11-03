/**
 * 轻量焦点陷阱（覆盖层共用）。
 *
 * 询盘弹窗与移动导航都是覆盖式浮层：打开时背景内容仍在 DOM 中，
 * 键盘用户按 Tab 会跑到浮层背后。这里只做一件事——把 Tab / Shift+Tab
 * 限制在给定容器内，并保证焦点始终落在可见元素上。
 *
 * 用法：
 *   import { trapTabKey, focusFirst } from '@/composables/useFocusTrap'
 *   function onKeydown(e) { trapTabKey(e, dialogRef.value) }
 */

const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ')

/** 容器内可聚焦且可见的元素，按 DOM 顺序。 */
export function focusableIn(container) {
  if (!container?.querySelectorAll) return []
  return [...container.querySelectorAll(FOCUSABLE_SELECTOR)]
    .filter((el) => el.getClientRects().length > 0)
}

/** 把焦点移到容器内第一个可聚焦元素，返回是否成功。 */
export function focusFirst(container) {
  const [first] = focusableIn(container)
  if (!first) return false
  first.focus()
  return true
}

/**
 * 处理 Tab 键：在首尾之间循环；焦点已经在容器外时拉回第一个元素。
 * 非 Tab 键直接忽略，由调用方继续处理 Escape 等按键。
 */
export function trapTabKey(event, container) {
  if (event.key !== 'Tab') return
  const items = focusableIn(container)
  if (!items.length) {
    event.preventDefault()
    return
  }
  const first = items[0]
  const last = items[items.length - 1]
  const active = document.activeElement
  if (!container.contains(active)) {
    event.preventDefault()
    first.focus()
    return
  }
  if (event.shiftKey && active === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && active === last) {
    event.preventDefault()
    first.focus()
  }
}
