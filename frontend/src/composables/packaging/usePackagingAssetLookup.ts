import type { Ref } from 'vue'
import type { PackagingAssetView, PackagingModelDetail } from '@/api/packaging'
import { packagingFileOwnerType } from '@/utils/catalog/fileAssetUtils'
import { fileAssetPreviewUrlByOptions, fileAssetThumbnailUrlByOptions } from '@/utils/ui/fileAssetUrls'

/**
 * 包材素材查找与 URL 构造
 *
 * 抽取自 PackagingDetailDrawer.vue 与 PackagingSpecDetailPanel.vue 的重复实现。
 * 负责在 detail.assets 中定位素材、解析 ownerType、构造预览 URL。
 */
export interface PackagingAssetLookupOptions {
  detail: Ref<PackagingModelDetail | null>
  modelId: number
}

export function usePackagingAssetLookup(options: PackagingAssetLookupOptions) {
  const { detail, modelId } = options

  function fileOwnerTypeForPackagingAsset(asset: Partial<PackagingAssetView> | null | undefined) {
    return packagingFileOwnerType(asset)
  }

  function findPackagingAsset(asset: Partial<PackagingAssetView> | null | undefined) {
    if (!asset) return null
    if (asset.id) return detail.value?.assets.find(item => item.id === asset.id) || asset
    return (
      detail.value?.assets.find(
        item =>
          item.category === asset.category &&
          item.fileName === asset.fileName &&
          (asset.variantId == null || item.variantId === asset.variantId)
      ) || null
    )
  }

  function packagingAssetPreviewUrl(asset: Partial<PackagingAssetView> | null | undefined) {
    const target = findPackagingAsset(asset)
    const ownerType = fileOwnerTypeForPackagingAsset(target)
    return fileAssetPreviewUrlByOptions({
      ownerType,
      assetId: target?.id || null,
      category: target?.category || asset?.category || null,
      fileName: target?.fileName || asset?.fileName || null,
      modelId
    })
  }

  function packagingAssetThumbnailUrl(asset: Partial<PackagingAssetView> | null | undefined) {
    const target = findPackagingAsset(asset)
    const ownerType = fileOwnerTypeForPackagingAsset(target)
    return fileAssetThumbnailUrlByOptions({
      ownerType,
      assetId: target?.id || null,
      category: target?.category || asset?.category || null,
      fileName: target?.fileName || asset?.fileName || null,
      modelId
    })
  }

  return {
    fileOwnerTypeForPackagingAsset,
    findPackagingAsset,
    packagingAssetPreviewUrl,
    packagingAssetThumbnailUrl
  }
}
