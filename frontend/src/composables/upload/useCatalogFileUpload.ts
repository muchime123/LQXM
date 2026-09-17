import { ref } from 'vue'
import { addCatalogAsset } from '../../api/catalog'
import type { CatalogDetail } from '../../types'
import { useRegisteredFileUpload } from '@/composables/upload/useRegisteredFileUpload'
import type { RegisteredFileUploadResult } from '@/composables/upload/useRegisteredFileUpload'
import type { NotifyFn } from '@/composables/ui/useToast'
import { ASSET_CATEGORY_KEYS } from '../../constants/fields'
import { FILE_ASSET_FAILURE_PREFIX } from '@/constants/fileAssetMessages'

interface CatalogFileUploadOptions {
  notify: NotifyFn
  getDetail: () => CatalogDetail | null | undefined
  getSelectedId: () => number | null | undefined
  refreshCurrentModel: (tab?: string) => Promise<void>
  emit?: (event: 'imported') => void
}

interface ProcessUploadOptions {
  /** 每个文件注册成功后回调，可拿到后端返回的最终 fileName 与注册的 asset */
  onRegistered?: (result: RegisteredFileUploadResult<unknown>) => void
  /** 自定义成功提示，不传则使用默认的"素材已上传" */
  successText?: string
}

export function useCatalogFileUpload({
  notify,
  getDetail,
  getSelectedId,
  refreshCurrentModel,
  emit
}: CatalogFileUploadOptions) {
  const assetUploadCategory = ref<string>(ASSET_CATEGORY_KEYS.deviceVideo)
  const { uploadAndRegister, uploading: assetUploadUploading } = useRegisteredFileUpload({ notify })

  async function processUploadFiles(files: File[], category: string, options: ProcessUploadOptions = {}) {
    const detail = getDetail()
    if (!detail || !files.length) return

    let successCount = 0
    for (const file of files) {
      try {
        const registered = await uploadAndRegister(category, file, {
          modelId: getSelectedId() ?? undefined,
          modelName: detail.model.name,
          errorPrefix: file.name + ' 上传失败',
          register: uploaded =>
            addCatalogAsset(detail.model.id, {
              category,
              fileName: uploaded.fileName,
              variantId: null,
              sourceRowNumber: 0
            })
        })
        if (registered) {
          successCount++
          options.onRegistered?.(registered)
        }
      } catch (err) {
        notify.errorWithDetail(file.name + ' ' + FILE_ASSET_FAILURE_PREFIX.register, err)
      }
    }
    await refreshCurrentModel()
    assetUploadCategory.value = category
    emit?.('imported')
    if (successCount === files.length) {
      notify.success(options.successText ?? (successCount > 1 ? successCount + ' 个素材已上传' : '素材已上传'))
    } else if (successCount > 0) {
      notify.warning(successCount + '/' + files.length + ' 个素材上传成功')
    }
  }

  async function uploadAsset(event: Event) {
    const input = event.target as HTMLInputElement
    const files = Array.from(input.files || [])
    if (!files.length) return
    await processUploadFiles(files, assetUploadCategory.value)
    input.value = ''
  }

  return {
    assetUploadCategory,
    assetUploadUploading,
    processUploadFiles,
    uploadAsset
  }
}
