import { computed, onBeforeUnmount, ref } from 'vue'
import { useFileUpload, openFilePicker } from '@/composables/upload/useFileUpload'
import type { UploadOptions } from '@/composables/upload/useFileUpload'
import type { NotifyFn } from '@/composables/ui/useToast'

export type MaybeRefOrGetter<T> = T | { value: T } | (() => T)

export interface UploadWorkflowResult<TAsset = unknown> {
  file: File
  result?: unknown
  fileName?: string
  asset?: TAsset | null
}

export type UploadOverrideOptions = Omit<UploadOptions, 'accept'> & {
  category?: MaybeRefOrGetter<string>
  accept?: MaybeRefOrGetter<string>
  multiple?: MaybeRefOrGetter<boolean>
  source?: string
}

export interface UploadWorkflowUploadedPayload<TAsset = unknown> {
  file: File
  result: unknown
  fileName: string
  asset?: TAsset | null
  index: number
  total: number
}

export interface UploadWorkflowRegisterPayload {
  file: File
  result: unknown
  fileName: string
  category: string
  index: number
  total: number
}

export interface FileUploadWorkflowOptions<TAsset = unknown> {
  notify?: NotifyFn
  category?: MaybeRefOrGetter<string>
  accept?: MaybeRefOrGetter<string>
  multiple?: boolean
  disabled?: MaybeRefOrGetter<boolean>
  uploadOptions?: MaybeRefOrGetter<UploadOptions>
  autoUpload?: MaybeRefOrGetter<boolean>
  onFilesReady?: (payload: {
    files: File[]
    category: string
    options: UploadOverrideOptions
    source: string
  }) => void | Promise<void>
  onUploaded?: (payload: UploadWorkflowUploadedPayload<TAsset>) => void | Promise<void>
  registerUploaded?: (payload: UploadWorkflowRegisterPayload) => TAsset | null | Promise<TAsset | null>
  onBatchComplete?: (results: UploadWorkflowResult<TAsset>[]) => void | Promise<void>
  onRejected?: (files: File[]) => void
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

function normalizeFiles(input?: FileList | File[] | null): File[] {
  return Array.from(input || []).filter(Boolean)
}

export function matchesAccept(file: File, accept?: string) {
  if (!accept) return true
  const patterns = String(accept)
    .split(',')
    .map(item => item.trim().toLowerCase())
    .filter(Boolean)
  if (!patterns.length) return true

  const fileName = String(file.name || '').toLowerCase()
  const fileType = String(file.type || '').toLowerCase()

  return patterns.some(pattern => {
    if (pattern === '*/*') return true
    if (pattern.endsWith('/*')) return fileType.startsWith(pattern.slice(0, -1))
    if (pattern.startsWith('.')) return fileName.endsWith(pattern)
    return fileType === pattern
  })
}

export function filesFromClipboardEvent(event: ClipboardEvent, accept?: string) {
  const items = Array.from(event?.clipboardData?.items || [])
  return items
    .filter(item => item.kind === 'file')
    .map(item => item.getAsFile())
    .filter((file): file is File => Boolean(file && matchesAccept(file, accept)))
}

export function isEditableElement(element: Element | null = document.activeElement) {
  const tag = element?.tagName?.toLowerCase()
  return (
    tag === 'input' ||
    tag === 'textarea' ||
    tag === 'select' ||
    Boolean((element as HTMLElement | null)?.isContentEditable)
  )
}

export function useFileUploadWorkflow<TAsset = unknown>(options: FileUploadWorkflowOptions<TAsset> = {}) {
  const {
    notify,
    category,
    accept,
    multiple = true,
    disabled,
    uploadOptions,
    autoUpload = true,
    onFilesReady,
    onUploaded,
    registerUploaded,
    onBatchComplete,
    onRejected
  } = options

  const { uploading, uploadSelectedFile } = useFileUpload({ notify })
  const dragOver = ref(false)
  const dragCounter = ref(0)
  const uploadProgress = ref(0)
  const rejectedFiles = ref<File[]>([])
  const isPasteActive = ref(false)

  const isDisabled = computed(() => Boolean(readValue(disabled, false) || uploading.value))

  function filterFiles(files?: FileList | File[] | null) {
    const currentAccept = readValue(accept, '')
    const source = normalizeFiles(files)
    const accepted: File[] = []
    const rejected: File[] = []

    for (const file of source) {
      if (matchesAccept(file, currentAccept)) accepted.push(file)
      else rejected.push(file)
    }

    rejectedFiles.value = rejected
    if (rejected.length) {
      onRejected?.(rejected)
      notify?.warning(`${rejected.length} 个文件类型不符合要求`)
    }

    return multiple ? accepted : accepted.slice(0, 1)
  }

  async function uploadFiles(files?: FileList | File[] | null, overrideOptions: UploadOverrideOptions = {}) {
    if (isDisabled.value) return []
    const accepted = filterFiles(files)
    if (!accepted.length) return []

    const currentCategory = readValue(overrideOptions.category, readValue(category, ''))
    if (!currentCategory) {
      notify?.warning('缺少上传分类')
      return []
    }

    if (!readValue(autoUpload, true)) {
      await onFilesReady?.({
        files: accepted,
        category: currentCategory,
        options: overrideOptions,
        source: overrideOptions.source || 'manual'
      })
      return accepted.map(file => ({ file }))
    }

    const results: UploadWorkflowResult<TAsset>[] = []
    uploadProgress.value = 0

    for (const [index, file] of accepted.entries()) {
      const {
        category: _category,
        accept: overrideAccept,
        multiple: _multiple,
        source: _source,
        ...uploadOverrideOptions
      } = overrideOptions
      const selectedUploadOptions: UploadOptions = {
        ...readValue(uploadOptions, {}),
        ...uploadOverrideOptions
      }
      if (overrideAccept) selectedUploadOptions.accept = readValue(overrideAccept, '')

      const result = await uploadSelectedFile(currentCategory, file, selectedUploadOptions)
      const uploadResult = result as { fileName?: string } | undefined
      if (uploadResult?.fileName) {
        const fileName = uploadResult.fileName
        const asset = await registerUploaded?.({
          file,
          result,
          fileName,
          category: currentCategory,
          index,
          total: accepted.length
        })
        results.push({ file, result, fileName, asset })
        await onUploaded?.({ file, result, fileName, asset, index, total: accepted.length })
      }
      uploadProgress.value = Math.round(((index + 1) / accepted.length) * 100)
    }

    await onBatchComplete?.(results)
    uploadProgress.value = 0
    return results
  }

  async function handleInputChange(event: Event, overrideOptions: UploadOverrideOptions = {}) {
    const input = event?.target as HTMLInputElement | null
    try {
      return await uploadFiles(input?.files, { ...overrideOptions, source: overrideOptions.source || 'input' })
    } finally {
      if (input) input.value = ''
    }
  }

  async function pickFiles(
    overrideOptions: UploadOverrideOptions = {}
  ): Promise<UploadWorkflowResult<TAsset>[] | Array<{ file: File }>> {
    if (isDisabled.value) return []
    let result: UploadWorkflowResult<TAsset>[] | Array<{ file: File }> = []
    const currentAccept = readValue(overrideOptions.accept, readValue(accept, ''))
    const isMultiple = Boolean(readValue(overrideOptions.multiple, multiple))
    await openFilePicker({
      accept: currentAccept,
      multiple: isMultiple,
      onSelected: async files => {
        result = await uploadFiles(files, { ...overrideOptions, source: overrideOptions.source || 'pick' })
      }
    })
    return result
  }

  function onDragEnter(event: DragEvent) {
    if (isDisabled.value) return
    event?.preventDefault?.()
    dragCounter.value += 1
    dragOver.value = true
  }

  function onDragOver(event: DragEvent) {
    if (isDisabled.value) return
    event?.preventDefault?.()
    if (event?.dataTransfer) event.dataTransfer.dropEffect = 'copy'
    dragOver.value = true
  }

  function onDragLeave(event: DragEvent) {
    if (isDisabled.value) return
    event?.preventDefault?.()
    dragCounter.value -= 1
    if (dragCounter.value <= 0) {
      dragCounter.value = 0
      dragOver.value = false
    }
  }

  async function onDrop(event: DragEvent, overrideOptions: UploadOverrideOptions = {}) {
    if (isDisabled.value) return []
    event?.preventDefault?.()
    dragCounter.value = 0
    dragOver.value = false
    return uploadFiles(event?.dataTransfer?.files, { ...overrideOptions, source: overrideOptions.source || 'drag' })
  }

  async function handlePaste(event: ClipboardEvent, overrideOptions: UploadOverrideOptions = {}) {
    if (isDisabled.value || isEditableElement()) return []
    const files = filesFromClipboardEvent(event, readValue(accept, ''))
    if (!files.length) return []
    event?.preventDefault?.()
    return uploadFiles(files, { ...overrideOptions, source: overrideOptions.source || 'paste' })
  }

  function activatePaste() {
    if (isPasteActive.value) return
    document.addEventListener('paste', handlePaste)
    isPasteActive.value = true
  }

  function deactivatePaste() {
    if (!isPasteActive.value) return
    document.removeEventListener('paste', handlePaste)
    isPasteActive.value = false
  }

  const dropZoneProps = {
    onDragenter: onDragEnter,
    onDragover: onDragOver,
    onDragleave: onDragLeave,
    onDrop
  }

  onBeforeUnmount(deactivatePaste)

  return {
    uploading,
    dragOver,
    uploadProgress,
    rejectedFiles,
    isDisabled,
    dropZoneProps,
    pickFiles,
    uploadFiles,
    handleInputChange,
    handlePaste,
    activatePaste,
    deactivatePaste,
    onDragEnter,
    onDragOver,
    onDragLeave,
    onDrop
  }
}
