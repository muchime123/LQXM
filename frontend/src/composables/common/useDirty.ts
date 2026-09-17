/**
 * 脏状态标记 Composable
 * 提供表单修改状态的全局标记，用于路由守卫提示
 */

import { reactive } from 'vue'

const dirtyFlags = reactive<Record<string, boolean>>({})

export function setDirty(key: string): void {
  dirtyFlags[key] = true
}

export function clearDirty(key: string): void {
  if (key) {
    delete dirtyFlags[key]
  }
}

export function isDirty(): boolean {
  return Object.keys(dirtyFlags).length > 0
}

export function getDirtyState(key: string): boolean {
  return Boolean(dirtyFlags[key])
}

/** 返回全部已标记的 key（主要用于测试断言） */
export function getDirtyKeys(): string[] {
  return Object.keys(dirtyFlags)
}

/** 清空所有脏标记（主要用于测试隔离） */
export function clearAllDirty(): void {
  Object.keys(dirtyFlags).forEach(k => delete dirtyFlags[k])
}
