import { ref, reactive, computed } from 'vue'

interface ItemWithId {
  id: number | string
}

/**
 * 批量选择状态管理。
 *
 * 泛型 T 用于约束 id 类型：产品/素材等数值 id 场景传 `number`，
 * 通用场景保持默认 `number | string`。`selectedIds` 运行时仍是
 * reactive Set（响应式有效），类型上断言为 `Set<T>` 以便与期望
 * `Set<number>` 的组件 props 直接兼容。
 */
export function useBatchSelection<T extends number | string = number | string>() {
  const batchMode = ref(false)

  const selectedIds = reactive(new Set<T>()) as unknown as Set<T>

  function toggleBatchMode(): void {
    batchMode.value = !batchMode.value
    if (!batchMode.value) {
      clearSelection()
    }
  }

  function toggleSelection(id: T): void {
    if (selectedIds.has(id)) {
      selectedIds.delete(id)
    } else {
      selectedIds.add(id)
    }
  }

  function selectAll(items: ItemWithId[]): void {
    items.forEach(item => {
      if (item.id) {
        selectedIds.add(item.id as T)
      }
    })
  }

  function clearSelection(): void {
    selectedIds.clear()
  }

  const isAllSelected = computed(() => (items: ItemWithId[]) => {
    return items.length > 0 && items.every(item => selectedIds.has(item.id as T))
  })

  const isPartiallySelected = computed(() => (items: ItemWithId[]) => {
    const hasSelected = items.some(item => selectedIds.has(item.id as T))
    const hasUnselected = items.some(item => !selectedIds.has(item.id as T))
    return hasSelected && hasUnselected
  })

  function toggleSelectAll(items: ItemWithId[]): void {
    if (isAllSelected.value(items)) {
      clearSelection()
    } else {
      selectAll(items)
    }
  }

  function getSelectedIds(): T[] {
    return Array.from(selectedIds)
  }

  const selectedCount = computed(() => selectedIds.size)

  function isSelected(id: T): boolean {
    return selectedIds.has(id)
  }

  return {
    batchMode,
    selectedIds,
    selectedCount,
    isAllSelected,
    isPartiallySelected,
    toggleBatchMode,
    toggleSelection,
    selectAll,
    clearSelection,
    toggleSelectAll,
    getSelectedIds,
    isSelected
  }
}
