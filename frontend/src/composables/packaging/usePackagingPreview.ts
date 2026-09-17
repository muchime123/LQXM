import { type ComputedRef, type Ref, reactive } from 'vue'
import type { PackagingAssetView, PackagingModelDetail, PackagingVariantView } from '@/api/packaging'
import type { FileOwnerType } from '@/types/fileAsset'
import { PACKAGING_ASSET_CATEGORY_KEYS } from '@/constants/packaging'

/**
 * 包材预览状态管理
 *
 * 抽取自 PackagingDetailDrawer.vue，负责 LqPreview 组件的状态管理，
 * 包括系列主图、规格图片、素材库资产的预览入口与画廊切换。
 */
export interface PreviewState {
  visible: boolean
  url: string
  title: string
  fileName: string
  category: string
  ownerType: '' | FileOwnerType
  assetId: number | null
  assets: Array<Partial<PackagingAssetView> & { category: string; fileName: string; title?: string }>
  index: number
}

export interface PackagingPreviewHelpers {
  mainImage: ComputedRef<string>
  variantImageAssets: (variant: PackagingVariantView) => PackagingAssetView[]
  variantCarouselIndex: Record<number, number>
  packagingAssetPreviewUrl: (asset: Partial<PackagingAssetView> | null | undefined) => string
  fileOwnerTypeForPackagingAsset: (asset: Partial<PackagingAssetView> | null | undefined) => FileOwnerType | null
}

type GalleryAsset = Partial<PackagingAssetView> & { category: string; fileName: string; title?: string }

export function usePackagingPreview(detail: Ref<PackagingModelDetail | null>, helpers: PackagingPreviewHelpers) {
  const {
    mainImage,
    variantImageAssets,
    variantCarouselIndex,
    packagingAssetPreviewUrl,
    fileOwnerTypeForPackagingAsset
  } = helpers

  const preview = reactive<PreviewState>({
    visible: false,
    url: '',
    title: '',
    fileName: '',
    category: '',
    ownerType: '',
    assetId: null,
    assets: [],
    index: 0
  })

  function openPreview(asset: GalleryAsset, title?: string, galleryAssets?: GalleryAsset[], galleryIndex = 0) {
    const assets = galleryAssets?.length ? galleryAssets : [{ ...asset, title }]
    const index = Math.min(Math.max(galleryIndex, 0), assets.length - 1)
    const current = assets[index] || asset
    preview.visible = true
    preview.assets = assets
    preview.index = index
    preview.category = current.category
    preview.fileName = current.fileName
    preview.ownerType = current.id ? fileOwnerTypeForPackagingAsset(current) || '' : ''
    preview.assetId = current.id || null
    preview.title = current.title || title || current.fileName
    preview.url = packagingAssetPreviewUrl(current)
  }

  function closePreview() {
    preview.visible = false
    preview.url = ''
    preview.title = ''
    preview.fileName = ''
    preview.category = ''
    preview.ownerType = ''
    preview.assetId = null
    preview.assets = []
    preview.index = 0
  }

  function shiftPreview(step: number) {
    if (!preview.assets.length) return
    const nextIndex = (preview.index + step + preview.assets.length) % preview.assets.length
    const next = preview.assets[nextIndex]
    preview.index = nextIndex
    preview.category = next.category
    preview.fileName = next.fileName
    preview.ownerType = next.id ? fileOwnerTypeForPackagingAsset(next) || '' : ''
    preview.assetId = next.id || null
    preview.title = next.title || next.fileName
    preview.url = packagingAssetPreviewUrl(next)
  }

  function previewSeriesImage() {
    if (!mainImage.value) return
    openPreview(
      { category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage, fileName: mainImage.value },
      detail.value?.model.name || '系列主图'
    )
  }

  function variantPreviewGallery(variant: PackagingVariantView): GalleryAsset[] {
    const title = variant.variantName || variant.specLabel || '规格图片'
    return variantImageAssets(variant).map(asset => ({
      id: asset.id,
      variantId: asset.variantId,
      category: asset.category,
      fileName: asset.fileName,
      title
    }))
  }

  function previewVariantImage(variant: PackagingVariantView, index = variantCarouselIndex[variant.id] || 0) {
    const assets = variantImageAssets(variant)
    if (!assets.length) return
    const safeIndex = Math.min(Math.max(index, 0), assets.length - 1)
    const gallery = variantPreviewGallery(variant)
    openPreview(assets[safeIndex], variant.variantName || variant.specLabel || '规格图片', gallery, safeIndex)
  }

  function previewAsset(asset: PackagingAssetView) {
    openPreview(asset, asset.fileName)
  }

  return {
    preview,
    openPreview,
    closePreview,
    shiftPreview,
    previewSeriesImage,
    previewVariantImage,
    previewAsset
  }
}
