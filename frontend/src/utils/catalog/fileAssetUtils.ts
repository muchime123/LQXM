import type { FileOwnerType } from '../../types/fileAsset'
import { isFileOwnerType } from '../../types/fileAsset'

interface VariantOwnedAsset {
  id?: number | null
  ownerType?: string | null
  variantId?: number | null
}

export type FileOwnerAssetGroupEntries = Array<[FileOwnerType, number[]]>

export function productFileOwnerType(asset: VariantOwnedAsset | null | undefined): FileOwnerType {
  // 优先使用后端明确返回的 ownerType，避免根据页面上下文重复推断。
  if (asset?.ownerType && isFileOwnerType(asset.ownerType)) return asset.ownerType as FileOwnerType
  return asset?.variantId ? 'product_variant' : 'product_model'
}

export function packagingFileOwnerType(
  asset: (VariantOwnedAsset & { id?: number | null }) | null | undefined
): FileOwnerType | null {
  if (!asset?.id) return null
  if (asset.ownerType === 'packaging_model' || asset.ownerType === 'packaging_variant') return asset.ownerType
  return asset.variantId ? 'packaging_variant' : 'packaging_model'
}

export function groupProductFileAssetIds(assets: VariantOwnedAsset[]): Partial<Record<FileOwnerType, number[]>> {
  return assets.reduce<Partial<Record<FileOwnerType, number[]>>>((groups, asset) => {
    if (!asset.id) return groups
    const ownerType = productFileOwnerType(asset)
    if (!groups[ownerType]) groups[ownerType] = []
    groups[ownerType].push(asset.id)
    return groups
  }, {})
}

export function groupProductFileAssetIdEntries(assets: VariantOwnedAsset[]): FileOwnerAssetGroupEntries {
  return Object.entries(groupProductFileAssetIds(assets)) as FileOwnerAssetGroupEntries
}

export function packagingFileOwnerTypeEntries(
  assets: Array<VariantOwnedAsset & { id?: number | null }>
): FileOwnerType[] {
  return [
    ...new Set(assets.map(packagingFileOwnerType).filter((ownerType): ownerType is FileOwnerType => Boolean(ownerType)))
  ]
}
