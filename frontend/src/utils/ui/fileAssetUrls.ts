import {
  isFileOwnerType,
  type FileAssetOpenFolderResult,
  type FileHandle,
  type FileOwnerType
} from '../../types/fileAsset'
import {
  fileAssetDownloadUrlById,
  fileAssetPreviewUrlById,
  fileAssetThumbnailUrlById,
  openFileAssetFolder,
  openFileAssetFolderByPath
} from '../../api/file'
import { filePreviewType, isPreviewableFile } from './fileUtils'
import { FILE_ASSET_MESSAGES } from '@/constants/fileAssetMessages'

interface FileAssetLookupTarget {
  id?: number | null
  assetId?: number | null
  fileName?: string | null
  originalFileName?: string | null
}

interface OpenFileAssetFolderOptions {
  ownerType?: FileOwnerType | string | null
  assetId?: number | null
  category?: string | null
  fileName?: string | null
  modelId?: number | null
  onUnifiedError?: (err: unknown) => void
}

interface FileAssetUrlFallbackOptions {
  ownerType?: FileOwnerType | null
  assetId?: number | null
  category?: string | null
  fileName?: string | null
  modelId?: number | null
  /** 缩略图目标最大边长（px）。仅 thumbnail 路径生效，传给后端由其 clamp。 */
  size?: number | null
}

export { fileAssetDownloadUrlById, fileAssetPreviewUrlById, fileAssetThumbnailUrlById }

export function fileAssetPreviewUrl(handle: FileHandle | null | undefined): string {
  if (!handle?.assetId) return ''
  return fileAssetPreviewUrlById(handle.ownerType, handle.assetId)
}

export function fileAssetPreviewUrlByOptions(options: FileAssetUrlFallbackOptions): string {
  return fileAssetPreviewUrlById(options.ownerType, options.assetId) || ''
}

/**
 * 文件缩略图 URL。失败/不支持时返回空串，前端应保留 fallback 图标。
 */
export function fileAssetThumbnailUrl(handle: FileHandle | null | undefined): string {
  if (!handle?.assetId) return ''
  return fileAssetThumbnailUrlById(handle.ownerType, handle.assetId)
}

export function fileAssetThumbnailUrlByOptions(options: FileAssetUrlFallbackOptions): string {
  return fileAssetThumbnailUrlById(options.ownerType, options.assetId, options.size) || ''
}

export function fileAssetDownloadUrl(handle: FileHandle | null | undefined): string {
  if (!handle?.assetId) return ''
  return fileAssetDownloadUrlById(handle.ownerType, handle.assetId)
}

export function fileAssetTitle(handle: FileHandle | null | undefined): string {
  return handle?.originalFileName || handle?.fileName || ''
}

export function fileAssetPreviewType(handle: FileHandle | null | undefined) {
  return filePreviewType(handle?.originalFileName || handle?.fileName || handle?.mimeType)
}

export function isPreviewableFileAsset(handle: FileHandle | null | undefined): boolean {
  if (!handle) return false
  if (!handle.capabilities.includes('PREVIEW')) return false
  return isPreviewableFile(handle.originalFileName || handle.fileName || handle.mimeType)
}

export function findFileAssetHandle(
  handles: FileHandle[],
  target: FileAssetLookupTarget | string | null | undefined
): FileHandle | null {
  if (!target) return null
  const lookup = typeof target === 'string' ? { fileName: target } : target
  const targetId = lookup.assetId ?? lookup.id
  const targetNames = [lookup.fileName, lookup.originalFileName].filter(Boolean)

  return (
    handles.find(handle => {
      if (targetId && handle.assetId === targetId) return true
      return targetNames.some(name => handle.fileName === name || handle.originalFileName === name)
    }) || null
  )
}

const RECOVERABLE_OPEN_FOLDER_ERRORS = new Set(['FILE_NOT_FOUND', 'FILE_BEHAVIOR_UNSUPPORTED'])

export async function openFileAssetFolderByOptions(options: OpenFileAssetFolderOptions) {
  const ownerType = options.ownerType || null
  let unifiedFailure: FileAssetOpenFolderResult | null = null
  if (isFileOwnerType(ownerType) && options.assetId) {
    try {
      const result = await openFileAssetFolder(ownerType, options.assetId)
      if (result.success || !result.errorCode || !RECOVERABLE_OPEN_FOLDER_ERRORS.has(result.errorCode)) {
        return result
      }
      unifiedFailure = result
    } catch (err) {
      options.onUnifiedError?.(err)
    }
  }
  if (isFileOwnerType(ownerType) && options.category && options.fileName) {
    try {
      return await openFileAssetFolderByPath(ownerType, options.modelId || null, options.category, options.fileName)
    } catch (err) {
      options.onUnifiedError?.(err)
      return unifiedFailure
    }
  }
  return unifiedFailure
}
export function fileAssetOpenFolderMessage(result: FileAssetOpenFolderResult | null | undefined) {
  if (result?.success) {
    return { message: FILE_ASSET_MESSAGES.folderOpened, type: 'success' as const }
  }
  return {
    message: result?.error || FILE_ASSET_MESSAGES.folderOpenUnavailable,
    type: 'warning' as const
  }
}
