import { computed, type Ref, type ComputedRef } from 'vue'
import { useFileAssets } from '../upload/useFileAssets'
import { useFileAssetActions } from '../upload/useFileAssetActions'
import type { NotifyFn } from '../ui/useToast'
import { groupProductFileAssetIdEntries, productFileOwnerType } from '../../utils/catalog/fileAssetUtils'
import { findFileAssetHandle, fileAssetPreviewUrlByOptions } from '../../utils/ui/fileAssetUrls'
import { FILE_OWNER_TYPE_KEYS } from '../../types/fileAsset'

export function useProductFileAssets({
  selectedId,
  groupedAssets,
  selectedAssetIds,
  notify,
  refreshCurrentModel
}: {
  selectedId: Ref<number | null>
  groupedAssets: ComputedRef<any[]> | Ref<any[]>
  selectedAssetIds: Set<number>
  notify: NotifyFn
  refreshCurrentModel: (tab?: string) => Promise<void>
}) {
  const productModelFileAssets = useFileAssets({
    ownerType: FILE_OWNER_TYPE_KEYS.productModel,
    ownerId: selectedId,
    notify,
    onChanged: () => refreshCurrentModel('assets')
  })

  const fileAssetActions = useFileAssetActions({
    notify,
    notifySuccess: false
  })

  const productModelFileHandles = computed(() => productModelFileAssets.items.value)

  function findProductModelFileHandle(asset: any) {
    const handle = findFileAssetHandle(productModelFileHandles.value, asset)
    return handle?.ownerType === 'product_model' ? handle : null
  }

  function fileOwnerTypeForProductAsset(asset: any) {
    return productFileOwnerType(asset)
  }

  function selectedProductAssetGroups(ids: number[] = Array.from(selectedAssetIds)) {
    return groupProductFileAssetIdEntries(groupedAssets.value.filter(asset => ids.includes(asset.id)))
  }

  function filePreviewUrlForProductAsset(asset: any) {
    if (!asset?.id) return ''
    const handle = findProductModelFileHandle(asset)
    return (
      productModelFileAssets.previewUrl(handle) ||
      fileAssetPreviewUrlByOptions({
        ownerType: fileOwnerTypeForProductAsset(asset),
        assetId: asset.id,
        category: asset.category,
        fileName: asset.fileName,
        modelId: asset.modelId || selectedId.value || null
      })
    )
  }

  async function loadProductModelFileAssets() {
    if (!selectedId.value) {
      productModelFileAssets.items.value = []
      return
    }
    try {
      await Promise.all([productModelFileAssets.loadCapabilities(), productModelFileAssets.load()])
    } catch {
      productModelFileAssets.items.value = []
    }
  }

  return {
    productModelFileAssets,
    fileAssetActions,
    productModelFileHandles,
    findProductModelFileHandle,
    fileOwnerTypeForProductAsset,
    selectedProductAssetGroups,
    filePreviewUrlForProductAsset,
    loadProductModelFileAssets
  }
}
