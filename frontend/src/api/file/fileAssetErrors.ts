/**
 * 文件资产 API 的错误类与断言/校验工具。
 *
 * 合并自：fileAssetRequest.ts、fileAssetHandleResponse.ts（仅 FileAssetResponseError）、
 * fileAssetDownloadResponse.ts。
 *
 * 设计原则：
 * - 本文件不依赖 api/file/ 内任何其他模块，可被 fileAssetNormalizers / fileAssetUrls /
 *   fileAssets 任意组合引用，不会形成循环依赖。
 */
import { reportFileAssetDiagnostic } from '@/utils/fileAssetDiagnostics'
import type { AxiosResponse } from 'axios'
import type { FileOwnerType } from '@/types/fileAsset'
import { isFileOwnerType } from '@/types/fileAsset'

/* ================ 请求侧：FileAssetRequestError + 断言 ================ */

export class FileAssetRequestError extends TypeError {
  constructor(message: string) {
    super(`统一文件请求无效：${message}`)
    this.name = 'FileAssetRequestError'
    reportFileAssetDiagnostic('invalid_request', { severity: 'warning', message })
  }
}

export function isValidFileAssetId(value: unknown): value is number {
  return typeof value === 'number' && Number.isSafeInteger(value) && value !== 0
}

export function assertFileOwnerType(value: unknown): asserts value is FileOwnerType {
  if (typeof value !== 'string' || !isFileOwnerType(value)) throw new FileAssetRequestError('ownerType 非法')
}

export function assertFileAssetId(value: unknown): asserts value is number {
  if (!isValidFileAssetId(value)) throw new FileAssetRequestError('assetId 非法')
}

export function assertFileOwnerId(value: unknown, nullable = false): asserts value is number | null {
  if (nullable && (value === null || value === undefined)) return
  if (value === null) return
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0) {
    throw new FileAssetRequestError('ownerId 非法')
  }
}

export function assertFileCategory(value: unknown): asserts value is string {
  if (typeof value !== 'string' || !value.trim()) throw new FileAssetRequestError('category 不能为空')
}

export function assertFileName(value: unknown): asserts value is string {
  if (typeof value !== 'string' || !value.trim() || value === '.' || value === '..' || /[\\/\0]/.test(value)) {
    throw new FileAssetRequestError('fileName 非法')
  }
}

export function assertFileAssetIdentity(ownerType: unknown, assetId: unknown): asserts ownerType is FileOwnerType {
  assertFileOwnerType(ownerType)
  assertFileAssetId(assetId)
}

/* ================ 响应侧：FileAssetResponseError ================ */

export class FileAssetResponseError extends Error {
  constructor(message: string) {
    super(`统一文件响应无效：${message}`)
    this.name = 'FileAssetResponseError'
    reportFileAssetDiagnostic('invalid_response', { severity: 'error', message })
  }
}

/* ================ 下载响应：FileAssetDownloadError + 校验 ================ */

export class FileAssetDownloadError extends Error {
  constructor(message: string) {
    super(`文件下载响应无效：${message}`)
    this.name = 'FileAssetDownloadError'
    reportFileAssetDiagnostic('invalid_download', { severity: 'error', message })
  }
}

function responseContentType(response: AxiosResponse<unknown>) {
  const headers = response.headers as Record<string, unknown>
  const value = headers?.['content-type'] ?? headers?.['Content-Type']
  return typeof value === 'string' ? value.toLowerCase() : ''
}

async function jsonErrorMessage(blob: Blob) {
  const text = await blob.text()
  try {
    const parsed = JSON.parse(text) as Record<string, unknown>
    const message = parsed.message ?? parsed.error ?? parsed.detail
    return typeof message === 'string' && message.trim() ? message : '服务端返回了 JSON 而不是文件'
  } catch {
    return text.trim() || '服务端返回了 JSON 而不是文件'
  }
}

export async function validateFileAssetDownloadResponse(
  response: AxiosResponse<unknown>
): Promise<AxiosResponse<Blob>> {
  if (!(response.data instanceof Blob)) throw new FileAssetDownloadError('响应内容不是 Blob')
  if (response.data.size === 0) throw new FileAssetDownloadError('文件内容为空')

  const contentType = responseContentType(response) || response.data.type.toLowerCase()
  if (contentType.includes('application/json') || contentType.includes('application/problem+json')) {
    throw new FileAssetDownloadError(await jsonErrorMessage(response.data))
  }
  return response as AxiosResponse<Blob>
}
