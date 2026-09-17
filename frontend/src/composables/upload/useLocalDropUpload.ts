import { ref } from 'vue'
import { matchesAccept } from '@/composables/upload/useFileUploadWorkflow'

interface LocalDropUploadOptions {
  onFiles?: (files: File[]) => void | Promise<void>
  accept?: string
}

/**
 * 局部拖拽上传 composable — 用于单个上传容器
 *
 * 与 useFileUploadWorkflow 的拖拽逻辑同源，accept 过滤复用 matchesAccept
 * （支持通配符 xxx/星号、扩展名 .ext、MIME 完整匹配）。
 *
 * 用法：
 *   const { isDragOver, dragHandlers } = useLocalDropUpload({
 *     onFiles: (files) => { ... },
 *     accept: 'image/*',  // 可选
 *   })
 *
 *   // 模板：
 *   <div :class="{ 'drag-over': isDragOver }" v-bind="dragHandlers">
 *     <span v-if="isDragOver">释放以上传</span>
 *     ...
 *   </div>
 */
export function useLocalDropUpload({ onFiles, accept }: LocalDropUploadOptions = {}) {
  const isDragOver = ref(false)
  const dragCounter = ref(0)

  function onDragEnter(e: DragEvent) {
    e.preventDefault()
    e.stopPropagation()
    dragCounter.value++
    isDragOver.value = true
  }

  function onDragOver(e: DragEvent) {
    e.preventDefault()
    e.stopPropagation()
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
  }

  function onDragLeave(e: DragEvent) {
    e.preventDefault()
    e.stopPropagation()
    dragCounter.value--
    if (dragCounter.value <= 0) {
      isDragOver.value = false
      dragCounter.value = 0
    }
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    e.stopPropagation()
    isDragOver.value = false
    dragCounter.value = 0

    const files = Array.from(e.dataTransfer?.files || [])
    if (!files.length) return

    const filtered = accept ? files.filter(file => matchesAccept(file, accept)) : files
    if (!filtered.length) return

    const result = onFiles?.(filtered)
    if (result && typeof (result as Promise<void>).finally === 'function') {
      ;(result as Promise<void>).finally(() => {
        isDragOver.value = false
        dragCounter.value = 0
      })
    }
  }

  function reset() {
    isDragOver.value = false
    dragCounter.value = 0
  }

  const dragHandlers = {
    onDragenter: onDragEnter,
    onDragover: onDragOver,
    onDragleave: onDragLeave,
    onDrop
  }

  return { isDragOver, dragHandlers, reset }
}
