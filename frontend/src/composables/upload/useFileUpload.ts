import { ref, unref, type Ref } from 'vue'
import { uploadFile, type PhysicalUploadResult } from '../../api/file/file'
import type { NotifyFn } from '../ui/useToast'

export type UploadResult = PhysicalUploadResult

export interface UploadOptions {
  modelId?: number | null | Ref<number | null | undefined>
  modelName?: string
  accept?: string
  errorPrefix?: string
}

interface FileUploadOptions {
  notify?: NotifyFn
}

function resultFileName(result: UploadResult): string {
  return result?.fileName || result?.data?.fileName || ''
}

interface OpenFilePickerOptions {
  accept?: string
  multiple?: boolean
  onSelected: (files: File[]) => void | Promise<void>
}

/**
 * 打开系统文件选择器（共享 input 创建 + focus 兜底逻辑）
 *
 * 用于消除 pickAndUpload / pickFiles 中重复的 `createElement('input')` + settled 状态机。
 * 选择文件后调用 onSelected；用户取消（oncancel 或 focus 回退）时直接 resolve。
 */
export function openFilePicker({ accept, multiple, onSelected }: OpenFilePickerOptions): Promise<void> {
  return new Promise(resolve => {
    const input = document.createElement('input')
    let settled = false
    const openedAt = Date.now()

    function finish() {
      if (settled) return
      settled = true
      window.removeEventListener('focus', handleFocus)
      resolve()
    }

    function handleFocus() {
      if (Date.now() - openedAt < 500) return
      window.setTimeout(() => {
        if (!input.files?.length) finish()
      }, 150)
    }

    input.type = 'file'
    if (accept) input.accept = accept
    if (multiple) input.multiple = true
    input.onchange = async () => {
      const files = Array.from(input.files || [])
      if (files.length) await onSelected(files)
      finish()
    }
    input.oncancel = () => finish()
    window.addEventListener('focus', handleFocus)
    input.click()
  })
}

export function useFileUpload({ notify }: FileUploadOptions = {}) {
  const uploading = ref(false)

  async function uploadSelectedFile(
    category: string,
    file: File | null | undefined,
    options: UploadOptions = {}
  ): Promise<UploadResult | null> {
    if (!file) return null
    uploading.value = true
    try {
      const modelId = unref(options.modelId) ?? null
      const result = await uploadFile(category, file, modelId, options.modelName)
      const fileName = resultFileName(result)
      if (!fileName) throw new Error('服务器未返回文件名')
      return { ...result, fileName }
    } catch (err) {
      notify?.errorWithDetail(options.errorPrefix || '上传失败', err)
      console.error('[useFileUpload] 上传失败', err)
      return null
    } finally {
      uploading.value = false
    }
  }

  async function uploadFromInputEvent(
    event: Event,
    category: string,
    options: UploadOptions = {}
  ): Promise<UploadResult | null> {
    const input = event?.target as HTMLInputElement
    const file = input?.files?.[0]
    try {
      return await uploadSelectedFile(category, file, options)
    } finally {
      if (input) input.value = ''
    }
  }

  async function pickAndUpload(category: string, options: UploadOptions = {}): Promise<UploadResult | null> {
    let result: UploadResult | null = null
    await openFilePicker({
      accept: options.accept,
      onSelected: async files => {
        result = await uploadSelectedFile(category, files[0], options)
      }
    })
    return result
  }

  return {
    uploading,
    uploadSelectedFile,
    uploadFromInputEvent,
    pickAndUpload
  }
}
