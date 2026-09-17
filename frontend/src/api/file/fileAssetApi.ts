import type { FileOwnerType } from '@/types/fileAsset'
import {
  deleteFileAsset,
  downloadFileAsset,
  moveFileAssetCategory,
  moveFileAssetOwner,
  fetchFileAssetLocalPath,
  openFileAssetFolder,
  permanentDeleteFileAsset,
  reorderFileAssets,
  restoreFileAsset,
  setDefaultFileAsset,
  updateFileAssetTags,
  uploadFileAssetCover,
  clearFileAssetCover,
  type FileAssetReorderPayload,
  type FileAssetTagsPayload
} from './fileAssets'
import { fileAssetDownloadUrlById, fileAssetPreviewUrlById, fileAssetThumbnailUrlById } from './fileAssetUrls'

function buildFileAssetApi(ownerType: FileOwnerType) {
  return {
    delete(assetId: number) {
      return deleteFileAsset(ownerType, assetId)
    },
    restore(assetId: number) {
      return restoreFileAsset(ownerType, assetId)
    },
    permanentDelete(assetId: number, deletePhysical: boolean) {
      return permanentDeleteFileAsset(ownerType, assetId, deletePhysical)
    },
    updateTags(assetId: number, tags: FileAssetTagsPayload, replace = true) {
      return updateFileAssetTags(ownerType, assetId, tags, replace)
    },
    setDefault(assetId: number) {
      return setDefaultFileAsset(ownerType, assetId)
    },
    reorder(items: FileAssetReorderPayload) {
      return reorderFileAssets(ownerType, items)
    },
    moveCategory(assetId: number, category: string) {
      return moveFileAssetCategory(ownerType, assetId, category)
    },
    moveOwner(assetId: number, targetOwnerType: FileOwnerType, targetOwnerId: number) {
      return moveFileAssetOwner(ownerType, assetId, targetOwnerType, targetOwnerId)
    },
    download(assetId: number) {
      return downloadFileAsset(ownerType, assetId)
    },
    localPath(assetId: number) {
      return fetchFileAssetLocalPath(ownerType, assetId)
    },
    openFolder(assetId: number) {
      return openFileAssetFolder(ownerType, assetId)
    },
    previewUrl(assetId: number) {
      return fileAssetPreviewUrlById(ownerType, assetId)
    },
    thumbnailUrl(assetId: number) {
      return fileAssetThumbnailUrlById(ownerType, assetId)
    },
    downloadUrl(assetId: number) {
      return fileAssetDownloadUrlById(ownerType, assetId)
    },
    uploadCover(assetId: number, file: File) {
      return uploadFileAssetCover(ownerType, assetId, file)
    },
    clearCover(assetId: number) {
      return clearFileAssetCover(ownerType, assetId)
    }
  }
}

export type BoundFileAssetApi = ReturnType<typeof buildFileAssetApi>

const fileAssetApiCache = new Map<FileOwnerType, BoundFileAssetApi>()

export function fileAssetApi(ownerType: FileOwnerType) {
  const cached = fileAssetApiCache.get(ownerType)
  if (cached) return cached
  const api = buildFileAssetApi(ownerType)
  fileAssetApiCache.set(ownerType, api)
  return api
}
