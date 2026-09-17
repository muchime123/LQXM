import type { FileOwnerType } from '@/types/fileAsset'
import { isFileOwnerType } from '@/types/fileAsset'
import { isValidFileAssetId } from './fileAssetErrors'

export function fileAssetPreviewUrlById(
  ownerType: FileOwnerType | null | undefined,
  assetId: number | null | undefined
): string {
  if (!isFileOwnerType(ownerType) || !isValidFileAssetId(assetId)) return ''
  return `/api/file-assets/${ownerType}/assets/${assetId}/preview`
}

export function fileAssetDownloadUrlById(
  ownerType: FileOwnerType | null | undefined,
  assetId: number | null | undefined
): string {
  if (!isFileOwnerType(ownerType) || !isValidFileAssetId(assetId)) return ''
  return `/api/file-assets/${ownerType}/assets/${assetId}/download`
}

/**
 * 文件缩略图 URL（用于卡片/网格小图）。
 *
 * 后端自动处理图片缩放和 PDF 首页渲染，失败时返回空字符串让前端走 fallback。
 *
 * @param size 可选，目标最大边长（px）。后端会 clamp 到 [240, 1280]。
 *             - 卡片/网格小图：留空，使用默认 240。
 *             - 详情大图轮播、Hero 等较大容器：传 480/640/800 等，避免放大模糊。
 *             - 不同 size 由后端按 hash 独立缓存，互不影响。
 */
export function fileAssetThumbnailUrlById(
  ownerType: FileOwnerType | null | undefined,
  assetId: number | null | undefined,
  size?: number | null
): string {
  if (!isFileOwnerType(ownerType) || !isValidFileAssetId(assetId)) return ''
  const base = `/api/file-assets/${ownerType}/assets/${assetId}/thumbnail`
  if (size == null || !Number.isFinite(size) || size <= 0) return base
  return `${base}?size=${Math.round(size)}`
}
