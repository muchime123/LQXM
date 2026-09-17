import { type Ref, computed, ref } from 'vue'
import {
  addPackagingAsset,
  type PackagingAssetView,
  type PackagingModelDetail,
  type PackagingVariantView
} from '@/api/packaging'
import { PACKAGING_ASSET_CATEGORY_KEYS } from '@/constants/packaging'
import { FILE_OWNER_TYPE_KEYS } from '@/types/fileAsset'
import { notify as defaultNotify, type NotifyFn } from '@/composables/ui/useToast'
import { useFileUploadWorkflow } from '@/composables/upload/useFileUploadWorkflow'
import type { useFileAssetActions } from '@/composables/upload/useFileAssetActions'

export interface PackagingUploadOptions {
  detail: Ref<PackagingModelDetail | null>
  modelId: number
  activeView: Ref<'specs' | 'series' | 'molds' | 'assets'>
  selectedVariant: Ref<PackagingVariantView | null>
  notify?: NotifyFn
  fileAssetActions: Pick<ReturnType<typeof useFileAssetActions>, 'setDefault'>
  onAssetChanged: () => void
}

export function usePackagingUpload(options: PackagingUploadOptions) {
  const { detail, modelId, activeView, selectedVariant, fileAssetActions, onAssetChanged } = options
  const notify = options.notify ?? defaultNotify

  const uploadTarget = ref<{ category: string; variantId?: number | null; makeDefault?: boolean } | null>(null)

  const { uploading, pickFiles, uploadFiles, handlePaste } = useFileUploadWorkflow({
    notify,
    category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage,
    accept: 'image/*',
    uploadOptions: () => ({ modelId }),
    registerUploaded: async ({ fileName }) => {
      const target = uploadTarget.value
      const variantId = target?.variantId ?? null
      const asset = await addPackagingAsset(
        modelId,
        PACKAGING_ASSET_CATEGORY_KEYS.mainImage,
        fileName,
        '',
        variantId
      )
      if (asset && detail.value) {
        const existingIndex = detail.value.assets.findIndex(a => a.id === asset.id)
        if (existingIndex >= 0) {
          detail.value.assets[existingIndex] = asset
        } else {
          detail.value.assets.push(asset)
        }
        if (target?.makeDefault && asset.id) {
          await fileAssetActions.setDefault(
            variantId ? FILE_OWNER_TYPE_KEYS.packagingVariant : FILE_OWNER_TYPE_KEYS.packagingModel,
            asset.id
          )
          asset.defaultAsset = true
          detail.value.assets.forEach(a => {
            if (a.category === asset.category && a.variantId === asset.variantId) {
              a.defaultAsset = a.id === asset.id
            }
          })
          if (!variantId) {
            detail.value.model.defaultImage = asset.fileName
          }
        }
      }
      return asset
    },
    onBatchComplete: async () => {
      const target = uploadTarget.value
      const variantId = target?.variantId
      let message = '系列主图已上传'
      if (variantId && detail.value) {
        const variant = detail.value.variants.find(v => v.id === variantId)
        message = `图片已上传到「${variant?.variantName || variant?.specLabel || '规格'}」`
      }
      uploadTarget.value = null
      onAssetChanged()
      notify?.(message, 'success')
    }
  })

  const pasteTargetLabel = computed(() => {
    if (activeView.value === 'specs' && selectedVariant.value) {
      return selectedVariant.value.variantName || selectedVariant.value.specLabel || '当前规格'
    }
    return '系列主图'
  })

  function contextPasteHandler(event: ClipboardEvent) {
    if (uploading.value || activeView.value === 'assets' || activeView.value === 'molds') return
    if (activeView.value === 'specs' && selectedVariant.value) {
      uploadTarget.value = {
        category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage,
        variantId: selectedVariant.value.id,
        makeDefault: true
      }
      handlePaste(event, { category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage })
    } else if (activeView.value === 'series') {
      uploadTarget.value = { category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage, variantId: null, makeDefault: true }
      handlePaste(event, { category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage })
    }
  }

  function variantImageAssets(variant: PackagingVariantView): PackagingAssetView[] {
    return (
      detail.value?.assets.filter(
        asset => asset.category === PACKAGING_ASSET_CATEGORY_KEYS.mainImage && asset.variantId === variant.id
      ) || []
    )
  }

  function variantImageAsset(variant: PackagingVariantView) {
    const assets = variantImageAssets(variant)
    return assets.find(asset => asset.defaultAsset) || assets[0] || null
  }

  function variantImage(variant: PackagingVariantView) {
    return variantImageAsset(variant)?.fileName || ''
  }

  async function handleVariantImageDrop(event: DragEvent, variant: PackagingVariantView) {
    event.preventDefault()
    const files = Array.from(event.dataTransfer?.files || []).filter(f => f.type.startsWith('image/'))
    if (!files.length) return
    selectedVariant.value = variant
    uploadTarget.value = { category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage, variantId: variant.id, makeDefault: true }
    await uploadFiles(files, { category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage })
  }

  async function uploadVariantImage(variant: PackagingVariantView) {
    selectedVariant.value = variant
    uploadTarget.value = {
      category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage,
      variantId: variant.id,
      makeDefault: !variantImage(variant)
    }
    const results = (await pickFiles({
      category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage,
      accept: 'image/*',
      multiple: true
    })) as { file: File }[]
    if (!results.length) uploadTarget.value = null
  }

  return {
    uploadTarget,
    uploading,
    pickFiles,
    uploadFiles,
    handlePaste,
    pasteTargetLabel,
    contextPasteHandler,
    variantImageAssets,
    variantImageAsset,
    variantImage,
    handleVariantImageDrop,
    uploadVariantImage
  }
}
