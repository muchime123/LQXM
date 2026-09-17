/**
 * 通用弹窗栈管理
 * 用于多层弹窗场景，提供统一注册、优先级排序、ESC 关闭最上层弹窗等能力。
 *
 * 使用示例：
 * ```ts
 * const stack = useDialogStack()
 *
 * // 注册弹窗
 * stack.register('editor', editorVisible, closeEditor, 100)
 * stack.register('detail', detailVisible, closeDetail, 10)
 *
 * // 查询
 * if (stack.anyOpenExcept('detail')) { ... }
 *
 * // Esc 关闭最上层
 * onKeydown('Escape', () => stack.closeTopmost())
 * ```
 */

import { ref, computed, isRef, type Ref } from 'vue'

interface DialogStackEntry {
  getVisible: () => boolean
  close: () => void
  priority: number
}

function toVisibleGetter(visibleRef: Ref<boolean> | (() => boolean) | boolean): () => boolean {
  if (isRef(visibleRef)) {
    return () => visibleRef.value
  }
  if (typeof visibleRef === 'function') {
    return visibleRef
  }
  return () => visibleRef
}

export interface DialogStack {
  /** 注册弹窗（name 唯一，priority 越大越先关闭） */
  register: (
    name: string,
    visible: Ref<boolean> | (() => boolean) | boolean,
    close: () => void,
    priority?: number
  ) => void
  /** 注销弹窗 */
  unregister: (name: string) => void
  /** 是否有任意弹窗打开 */
  anyOpen: Ref<boolean>
  /** 是否有指定弹窗之外的其他弹窗打开 */
  anyOpenExcept: (...excludeNames: string[]) => boolean
  /** 当前打开的弹窗名称列表（按优先级从高到低） */
  openList: Ref<string[]>
  /** 关闭最上层弹窗（优先级最高且已打开的） */
  closeTopmost: () => boolean
  /** 关闭所有已打开的弹窗 */
  closeAll: () => void
  /** 指定弹窗是否打开 */
  isOpen: (name: string) => boolean
  /** @deprecated 别名，请使用 register */
  registerModal: DialogStack['register']
  /** @deprecated 别名，请使用 unregister */
  unregisterModal: DialogStack['unregister']
  /** @deprecated 别名，请使用 anyOpen */
  anyModalOpen: DialogStack['anyOpen']
  /** @deprecated 别名，请使用 anyOpenExcept */
  anyModalOpenExcept: DialogStack['anyOpenExcept']
  /** @deprecated 别名，请使用 openList */
  openModals: DialogStack['openList']
}

export function useDialogStack(): DialogStack {
  const registry = ref<Map<string, DialogStackEntry>>(new Map())
  const order = ref<string[]>([])

  function register(
    name: string,
    visible: Ref<boolean> | (() => boolean) | boolean,
    close: () => void,
    priority = 10
  ): void {
    const getVisible = toVisibleGetter(visible)
    registry.value.set(name, { getVisible, close, priority })
    // 按优先级降序排序
    const sorted = Array.from(registry.value.entries()).sort((a, b) => b[1].priority - a[1].priority)
    order.value = sorted.map(([n]) => n)
  }

  function unregister(name: string): void {
    registry.value.delete(name)
    order.value = order.value.filter(n => n !== name)
  }

  const anyOpen = computed(() => {
    for (const [, entry] of registry.value) {
      if (entry.getVisible()) return true
    }
    return false
  })

  function anyOpenExcept(...excludeNames: string[]): boolean {
    const exclude = new Set(excludeNames)
    for (const [name, entry] of registry.value) {
      if (exclude.has(name)) continue
      if (entry.getVisible()) return true
    }
    return false
  }

  const openList = computed(() => {
    return order.value.filter(name => {
      const entry = registry.value.get(name)
      return entry && entry.getVisible()
    })
  })

  function closeTopmost(): boolean {
    for (const name of order.value) {
      const entry = registry.value.get(name)
      if (entry && entry.getVisible()) {
        entry.close()
        return true
      }
    }
    return false
  }

  function closeAll(): void {
    for (const [, entry] of registry.value) {
      if (entry.getVisible()) entry.close()
    }
  }

  function isOpen(name: string): boolean {
    const entry = registry.value.get(name)
    return entry ? entry.getVisible() : false
  }

  return {
    register,
    unregister,
    anyOpen,
    anyOpenExcept,
    openList,
    closeTopmost,
    closeAll,
    isOpen,
    // 别名，兼容旧 API
    registerModal: register,
    unregisterModal: unregister,
    anyModalOpen: anyOpen,
    anyModalOpenExcept: anyOpenExcept,
    openModals: openList
  }
}
