import { http } from '../http'
import type { FileOwnerType } from '@/types/fileAsset'
import {
  assertFileAssetIdentity,
  assertFileCategory,
  assertFileName,
  assertFileOwnerId,
  assertFileOwnerType,
  validateFileAssetDownloadResponse
} from './fileAssetErrors'
import {
  normalizeFileAssetReorderItems,
  normalizeFileAssetTags,
  normalizeFileCapabilities,
  normalizeFileCapabilityMap,
  normalizeFileHandle,
  normalizeFileHandles,
  normalizeFileAssetLocalPathResult,
  normalizeFileAssetOpenFolderResult,
  type FileAssetReorderItem,
  type FileAssetReorderPayload,
  type FileAssetTagsPayload,
  type LegacyFileAssetReorderItem
} from './fileAssetNormalizers'

export type FileAssetDeleteOptions =
  { permanent?: false; deletePhysical?: never } | { permanent: true; deletePhysical: boolean }

export type { FileAssetReorderItem, FileAssetReorderPayload, FileAssetTagsPayload, LegacyFileAssetReorderItem }

export function fetchFileAssets(
  ownerType: FileOwnerType,
  ownerId: number | null,
  signal?: AbortSignal
) {
  assertFileOwnerType(ownerType)
  assertFileOwnerId(ownerId, true)
  const url =
    ownerId == null ? `/file-assets/singleton/${ownerType}` : `/file-assets/${ownerType}/${ownerId}`
  return http
    .get<unknown>(url, { signal })
    .then(res => normalizeFileHandles(res.data, ownerType, ownerId ?? undefined))
}

export function fetchFileAsset(ownerType: FileOwnerType, assetId: number) {
  assertFileAssetIdentity(ownerType, assetId)
  return http
    .get<unknown>(`/file-assets/${ownerType}/assets/${assetId}`)
    .then(res => normalizeFileHandle(res.data, { ownerType, assetId }))
}

export function fetchFileAssetCapabilities(ownerType: FileOwnerType) {
  assertFileOwnerType(ownerType)
  return http.get<unknown>(`/file-assets/${ownerType}/capabilities`).then(res => normalizeFileCapabilities(res.data))
}

export function fetchAllFileAssetCapabilities() {
  return http.get<unknown>('/file-assets/capabilities').then(res => normalizeFileCapabilityMap(res.data))
}

export function updateFileAssetTags(
  ownerType: FileOwnerType,
  assetId: number,
  tags: FileAssetTagsPayload,
  replace = true
) {
  assertFileAssetIdentity(ownerType, assetId)
  return http
    .put<unknown>(`/file-assets/${ownerType}/assets/${assetId}/tags`, { tags: normalizeFileAssetTags(tags), replace })
    // 标签可能自动建立/解除规格关联，因此 ownerType/ownerId 允许随响应变化。
    // assetId 才是这次更新过程中保持不变的稳定身份。
    .then(res => normalizeFileHandle(res.data, { assetId }))
}

export function setDefaultFileAsset(ownerType: FileOwnerType, assetId: number) {
  assertFileAssetIdentity(ownerType, assetId)
  return http
    .post<unknown>(`/file-assets/${ownerType}/assets/${assetId}/default`)
    .then(res => normalizeFileHandle(res.data, { ownerType, assetId }))
}

export function reorderFileAssets(ownerType: FileOwnerType, items: FileAssetReorderPayload) {
  assertFileOwnerType(ownerType)
  return http
    .put<void>(`/file-assets/${ownerType}/reorder`, normalizeFileAssetReorderItems(items))
    .then(res => res.data)
}

export function moveFileAssetCategory(ownerType: FileOwnerType, assetId: number, category: string) {
  assertFileAssetIdentity(ownerType, assetId)
  assertFileCategory(category)
  return http.put<void>(`/file-assets/${ownerType}/assets/${assetId}/category`, { category }).then(res => res.data)
}

export function moveFileAssetOwner(
  ownerType: FileOwnerType,
  assetId: number,
  targetOwnerType: FileOwnerType,
  targetOwnerId: number
) {
  assertFileAssetIdentity(ownerType, assetId)
  assertFileOwnerType(targetOwnerType)
  assertFileOwnerId(targetOwnerId)
  return http
    .put<void>(`/file-assets/${ownerType}/assets/${assetId}/owner`, { targetOwnerType, targetOwnerId })
    .then(res => res.data)
}

export function deleteFileAsset(ownerType: FileOwnerType, assetId: number, options: FileAssetDeleteOptions = {}) {
  assertFileAssetIdentity(ownerType, assetId)
  return http
    .delete<void>(`/file-assets/${ownerType}/assets/${assetId}`, {
      params: {
        permanent: options.permanent || false,
        deletePhysical: options.deletePhysical ?? false
      }
    })
    .then(res => res.data)
}

export function permanentDeleteFileAsset(ownerType: FileOwnerType, assetId: number, deletePhysical: boolean) {
  return deleteFileAsset(ownerType, assetId, { permanent: true, deletePhysical })
}

export function restoreFileAsset(ownerType: FileOwnerType, assetId: number) {
  assertFileAssetIdentity(ownerType, assetId)
  return http.post<void>(`/file-assets/${ownerType}/assets/${assetId}/restore`).then(res => res.data)
}

export function downloadFileAsset(ownerType: FileOwnerType, assetId: number) {
  assertFileAssetIdentity(ownerType, assetId)
  return http
    .get<unknown>(`/file-assets/${ownerType}/assets/${assetId}/download`, { responseType: 'blob' })
    .then(validateFileAssetDownloadResponse)
}

export function fetchFileAssetLocalPath(ownerType: FileOwnerType, assetId: number) {
  assertFileAssetIdentity(ownerType, assetId)
  return http
    .get<unknown>(`/file-assets/${ownerType}/assets/${assetId}/local-path`)
    .then(res => normalizeFileAssetLocalPathResult(res.data, ownerType))
}

export function openFileAssetFolder(ownerType: FileOwnerType, assetId: number) {
  assertFileAssetIdentity(ownerType, assetId)
  return http
    .get<unknown>(`/file-assets/${ownerType}/assets/${assetId}/open-folder`)
    .then(res => normalizeFileAssetOpenFolderResult(res.data))
}

export function openFileAssetFolderByPath(
  ownerType: FileOwnerType,
  ownerId: number | null,
  category: string,
  fileName: string
) {
  assertFileOwnerType(ownerType)
  assertFileOwnerId(ownerId, true)
  assertFileCategory(category)
  assertFileName(fileName)
  return http
    .get<unknown>('/file-assets/open-folder-by-path', {
      params: { ownerType, ownerId, category, fileName }
    })
    .then(res => normalizeFileAssetOpenFolderResult(res.data))
}

// ====== 自定义封面 ======

/**
 * 为素材上传自定义封面图片（仅允许图片类型）。
 * 封面图片将保存到原素材文件所在目录，自动替换旧封面。
 */
export function uploadFileAssetCover(ownerType: FileOwnerType, assetId: number, file: File) {
  assertFileAssetIdentity(ownerType, assetId)
  const fd = new FormData()
  fd.append('file', file)
  return http
    .post<unknown>(`/file-assets/${ownerType}/assets/${assetId}/cover`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 600000
    })
    .then(res => normalizeFileHandle(res.data, { ownerType, assetId }))
}

/**
 * 清除素材的自定义封面（同时删除物理文件）。
 */
export function clearFileAssetCover(ownerType: FileOwnerType, assetId: number) {
  assertFileAssetIdentity(ownerType, assetId)
  return http
    .delete<unknown>(`/file-assets/${ownerType}/assets/${assetId}/cover`)
    .then(res => normalizeFileHandle(res.data, { ownerType, assetId }))
}
