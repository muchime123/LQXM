import { computed, nextTick, ref } from 'vue'

type MaybeRefOrGetter<T> = T | { value: T } | (() => T)
type DragId = number
type DragItem = { id?: DragId }

interface DragSortOptions<T extends DragItem> {
  items?: MaybeRefOrGetter<T[]>
  getId?: (item: T) => DragId
  canDrag?: MaybeRefOrGetter<boolean>
  sameGroup?: (source: T, target: T) => boolean
  onReorder?: (payload: {
    items: Array<{ id: DragId; sortOrder: number }>
    orderedIds: DragId[]
    reordered: T[]
    sourceItem: T | undefined
    targetItem: T
  }) => void | Promise<void>
  sortOrderStart?: MaybeRefOrGetter<number>
  sortOrderStep?: MaybeRefOrGetter<number>
}

function readValue<T>(value: MaybeRefOrGetter<T> | undefined, fallback: T): T {
  const raw =
    typeof value === 'function'
      ? (value as () => T)()
      : value && typeof value === 'object' && 'value' in value
        ? value.value
        : value
  return raw == null ? fallback : raw
}

function moveItem<T>(items: T[], sourceId: DragId, targetId: DragId, getId: (item: T) => DragId) {
  const next = [...items]
  const fromIndex = next.findIndex(item => getId(item) === sourceId)
  const toIndex = next.findIndex(item => getId(item) === targetId)
  if (fromIndex < 0 || toIndex < 0 || fromIndex === toIndex) return null
  const [moved] = next.splice(fromIndex, 1)
  next.splice(toIndex, 0, moved)
  return next
}

export function buildSortPayload<T extends DragItem>(
  items: T[],
  options: Pick<DragSortOptions<T>, 'getId'> & {
    start?: number
    step?: number
  } = {}
) {
  const getId = options.getId || (item => item.id as DragId)
  const start = options.start ?? 0
  const step = options.step ?? 1
  return items.map((item, index) => ({
    id: getId(item),
    sortOrder: start + index * step
  }))
}

export function useDragSort<T extends DragItem>(options: DragSortOptions<T> = {}) {
  const {
    items,
    getId = item => item.id as DragId,
    canDrag = true,
    sameGroup,
    onReorder,
    sortOrderStart = 0,
    sortOrderStep = 1
  } = options

  const draggedId = ref<DragId | null>(null)
  const dragOverId = ref<DragId | null>(null)
  const suppressClick = ref(false)

  const draggable = computed(() => Boolean(readValue(canDrag, true)))

  function currentItems() {
    return [...readValue(items, [])]
  }

  function canDropOn(targetItem: T) {
    if (!draggable.value || draggedId.value == null) return false
    const sourceItem = currentItems().find(item => getId(item) === draggedId.value)
    if (!sourceItem || getId(targetItem) === draggedId.value) return false
    return sameGroup ? sameGroup(sourceItem, targetItem) : true
  }

  function handleDragStart(item: T, event?: DragEvent) {
    if (!draggable.value) {
      event?.preventDefault?.()
      return
    }
    const id = getId(item)
    draggedId.value = id
    dragOverId.value = null
    if (event?.dataTransfer) {
      event.dataTransfer.effectAllowed = 'move'
      event.dataTransfer.setData('text/plain', String(id))
    }
  }

  function handleDragOver(item: T, event?: DragEvent) {
    if (!canDropOn(item)) return
    event?.preventDefault?.()
    dragOverId.value = getId(item)
  }

  function handleDragLeave(item: T) {
    if (dragOverId.value === getId(item)) dragOverId.value = null
  }

  async function handleDrop(item: T, event?: DragEvent) {
    event?.preventDefault?.()
    if (!canDropOn(item)) {
      handleDragEnd()
      return null
    }

    const sourceId = draggedId.value
    if (sourceId == null) {
      handleDragEnd()
      return null
    }
    const sourceItem = currentItems().find(entry => getId(entry) === draggedId.value)
    const targetItem = item
    const reordered = moveItem(currentItems(), sourceId, getId(item), getId)
    if (!reordered) {
      handleDragEnd()
      return null
    }

    const payload = buildSortPayload(reordered, {
      getId,
      start: readValue(sortOrderStart, 0),
      step: readValue(sortOrderStep, 1)
    })
    const orderedIds = reordered.map(getId)

    suppressClick.value = true
    await onReorder?.({ items: payload, orderedIds, reordered, sourceItem, targetItem })
    handleDragEnd()
    nextTick(() => {
      suppressClick.value = false
    })

    return { items: payload, orderedIds, reordered }
  }

  function handleDragEnd() {
    draggedId.value = null
    dragOverId.value = null
    nextTick(() => {
      suppressClick.value = false
    })
  }

  function isDragging(itemOrId: T | DragId) {
    const id = typeof itemOrId === 'object' ? getId(itemOrId) : itemOrId
    return draggedId.value === id
  }

  function isDragOver(itemOrId: T | DragId) {
    const id = typeof itemOrId === 'object' ? getId(itemOrId) : itemOrId
    return dragOverId.value === id
  }

  function dragHandlers(item: T) {
    return {
      draggable: draggable.value,
      onDragstart: (event: DragEvent) => handleDragStart(item, event),
      onDragover: (event: DragEvent) => handleDragOver(item, event),
      onDragleave: () => handleDragLeave(item),
      onDrop: (event: DragEvent) => handleDrop(item, event),
      onDragend: handleDragEnd
    }
  }

  return {
    draggedId,
    dragOverId,
    suppressClick,
    draggable,
    dragHandlers,
    handleDragStart,
    handleDragOver,
    handleDragLeave,
    handleDrop,
    handleDragEnd,
    isDragging,
    isDragOver,
    buildSortPayload
  }
}
