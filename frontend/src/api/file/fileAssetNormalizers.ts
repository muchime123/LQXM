/**
 * 文件资产 API 的响应/请求载荷归一化工具与类型。
 *
 * 合并自：fileAssetPayload.ts、fileAssetCapabilityResponse.ts、
 * fileAssetHandleResponse.ts（仅 normalizeFileHandle/Handles）、fileAssetLocationResponse.ts。
 *
 * 依赖关系：单向依赖 fileAssetErrors.ts（FileAssetResponseError），无循环。
 */
import type {
  FileAssetLocalPathResult,
  FileAssetOpenFolderResult,
  FileCapability,
  FileCapabilityMap,
  FileHandle,
  FileOwnerType
} from '@/types/fileAsset'
import { isFileCapability, isFileOwnerType } from '@/types/fileAsset'
import { FileAssetResponseError } from './fileAssetErrors'

/* ================ 请求载荷：类型 + 标签/排序归一化 ================ */

export interface FileAssetReorderItem {
  id: number
  sortOrder: number
}

export interface LegacyFileAssetReorderItem {
  id: number
  order: number
}

export type FileAssetTagsPayload = string | string[]
export type FileAssetReorderPayload = Array<FileAssetReorderItem | LegacyFileAssetReorderItem>

export function normalizeFileAssetTags(tags: FileAssetTagsPayload) {
  return Array.isArray(tags) ? tags.join(',') : tags
}

export function normalizeFileAssetReorderItems(items: FileAssetReorderPayload): FileAssetReorderItem[] {
  return items.map(item => ({
    id: item.id,
    sortOrder: 'sortOrder' in item ? item.sortOrder : item.order
  }))
}

/* ================ 能力（capabilities）归一化 ================ */

export function normalizeFileCapabilities(value: unknown): FileCapability[] {
  if (!Array.isArray(value)) return []
  return [
    ...new Set(value.filter((item): item is FileCapability => typeof item === 'string' && isFileCapability(item)))
  ]
}

export function normalizeFileCapabilityMap(value: unknown): FileCapabilityMap {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}

  const normalized: FileCapabilityMap = {}
  Object.entries(value).forEach(([ownerType, capabilities]) => {
    if (isFileOwnerType(ownerType)) {
      normalized[ownerType] = normalizeFileCapabilities(capabilities)
    }
  })
  return normalized
}

/* ================ 文件句柄（FileHandle）归一化 ================ */

interface ExpectedFileHandleIdentity {
  ownerType?: FileOwnerType
  ownerId?: number
  assetId?: number
}

function isAssetId(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value !== 0
}

export function normalizeFileHandle(value: unknown, expected: ExpectedFileHandleIdentity = {}): FileHandle {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new FileAssetResponseError('文件句柄不是对象')
  }

  const handle = value as Record<string, unknown>
  if (!isAssetId(handle.assetId)) throw new FileAssetResponseError('assetId 非法')
  if (typeof handle.ownerType !== 'string' || !isFileOwnerType(handle.ownerType)) {
    throw new FileAssetResponseError('ownerType 非法')
  }
  if (handle.ownerId !== null && !isAssetId(handle.ownerId)) {
    throw new FileAssetResponseError('ownerId 非法')
  }
  for (const field of ['category', 'storageCategory', 'fileName'] as const) {
    if (typeof handle[field] !== 'string') throw new FileAssetResponseError(`${field} 非法`)
  }
  if (!Array.isArray(handle.capabilities)) throw new FileAssetResponseError('capabilities 非法')
  if (expected.ownerType && handle.ownerType !== expected.ownerType) {
    throw new FileAssetResponseError(`ownerType 不匹配，期望 ${expected.ownerType}`)
  }
  if (expected.ownerId !== undefined && handle.ownerId !== expected.ownerId) {
    throw new FileAssetResponseError(`ownerId 不匹配，期望 ${expected.ownerId}`)
  }
  if (expected.assetId !== undefined && handle.assetId !== expected.assetId) {
    throw new FileAssetResponseError(`assetId 不匹配，期望 ${expected.assetId}`)
  }

  return {
    ...(handle as unknown as FileHandle),
    capabilities: normalizeFileCapabilities(handle.capabilities)
  }
}

export function normalizeFileHandles(
  value: unknown,
  ownerType: FileOwnerType,
  ownerId?: number
): FileHandle[] {
  if (!Array.isArray(value)) throw new FileAssetResponseError('文件列表不是数组')
  const expected: { ownerType: FileOwnerType; ownerId?: number } = { ownerType }
  if (ownerId !== undefined) expected.ownerId = ownerId
  return value.map(handle => normalizeFileHandle(handle, expected))
}

/* ================ 本地路径/打开目录响应归一化 ================ */

function responseObject(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new FileAssetResponseError('本地文件操作结果不是对象')
  }
  return value as Record<string, unknown>
}

function requireOptionalString(result: Record<string, unknown>, field: string) {
  const value = result[field]
  if (value !== undefined && typeof value !== 'string') {
    throw new FileAssetResponseError(`${field} 非法`)
  }
}

export function normalizeFileAssetOpenFolderResult(value: unknown): FileAssetOpenFolderResult {
  const result = responseObject(value)
  if (typeof result.success !== 'boolean') throw new FileAssetResponseError('success 非法')
  for (const field of ['localPath', 'error', 'errorCode']) requireOptionalString(result, field)
  if (result.success && (typeof result.localPath !== 'string' || !result.localPath.trim())) {
    throw new FileAssetResponseError('打开目录成功但缺少 localPath')
  }
  return result as unknown as FileAssetOpenFolderResult
}

export function normalizeFileAssetLocalPathResult(
  value: unknown,
  expectedOwnerType: FileOwnerType
): FileAssetLocalPathResult {
  const result = responseObject(value)
  if (typeof result.found !== 'boolean') throw new FileAssetResponseError('found 非法')
  for (const field of ['fileRoot', 'checkedPath', 'localPath', 'fileName', 'category', 'error', 'errorCode']) {
    requireOptionalString(result, field)
  }
  if (result.ownerType !== undefined) {
    if (typeof result.ownerType !== 'string' || !isFileOwnerType(result.ownerType)) {
      throw new FileAssetResponseError('ownerType 非法')
    }
    if (result.ownerType !== expectedOwnerType) {
      throw new FileAssetResponseError(`ownerType 不匹配，期望 ${expectedOwnerType}`)
    }
  }
  if (
    result.ownerId !== undefined &&
    result.ownerId !== null &&
    (typeof result.ownerId !== 'number' || !Number.isSafeInteger(result.ownerId) || result.ownerId === 0)
  ) {
    throw new FileAssetResponseError('ownerId 非法')
  }
  if (result.found && (typeof result.localPath !== 'string' || !result.localPath.trim())) {
    throw new FileAssetResponseError('找到文件但缺少 localPath')
  }
  return result as unknown as FileAssetLocalPathResult
}
