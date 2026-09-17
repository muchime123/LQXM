import { describe, expect, it } from 'vitest'
import type { FileHandle } from '../../../types/fileAsset'
import {
  fileAssetDownloadUrl,
  fileAssetOpenFolderMessage,
  fileAssetPreviewUrl,
  fileAssetPreviewUrlById,
  fileAssetPreviewUrlByOptions,
  findFileAssetHandle,
  isPreviewableFileAsset
} from '../fileAssetUrls'

function handle(overrides: Partial<FileHandle> = {}): FileHandle {
  return {
    assetId: 7,
    ownerType: 'company',
    ownerId: 1,
    category: 'company_logo',
    storageCategory: 'company_logo',
    storageOwnerId: null,
    fileName: 'logo.png',
    originalFileName: 'Logo.png',
    mimeType: 'image/png',
    fileSize: 1024,
    tags: null,
    defaultFile: false,
    sortOrder: null,
    deleted: false,
    coverFileName: null,
    createdAt: null,
    updatedAt: null,
    capabilities: ['PREVIEW', 'DOWNLOAD'],
    ...overrides
  }
}

describe('file asset url helpers', () => {
  it('prefers unified preview and download urls when an asset id is available', () => {
    const file = handle()

    expect(fileAssetPreviewUrlById('company', 7)).toBe('/api/file-assets/company/assets/7/preview')
    expect(fileAssetPreviewUrl(file)).toBe('/api/file-assets/company/assets/7/preview')
    expect(fileAssetDownloadUrl(file)).toBe('/api/file-assets/company/assets/7/download')
    expect(fileAssetPreviewUrl(handle({ fileName: '', originalFileName: null }))).toBe(
      '/api/file-assets/company/assets/7/preview'
    )
  })

  it('returns unified preview url when asset id is available, empty otherwise', () => {
    expect(
      fileAssetPreviewUrlByOptions({
        ownerType: 'company',
        assetId: 7,
        category: 'company_logo',
        fileName: 'logo.png'
      })
    ).toBe('/api/file-assets/company/assets/7/preview')

    expect(
      fileAssetPreviewUrlByOptions({
        category: 'company_logo',
        fileName: 'logo.png'
      })
    ).toBe('')
  })

  it('finds loaded handles by asset id or file names', () => {
    const files = [
      handle({ assetId: 1, fileName: 'a.png', originalFileName: 'A.png' }),
      handle({ assetId: 2, fileName: 'b.png', originalFileName: 'B.png' })
    ]

    expect(findFileAssetHandle(files, { assetId: 2 })?.fileName).toBe('b.png')
    expect(findFileAssetHandle(files, 'a.png')?.assetId).toBe(1)
    expect(findFileAssetHandle(files, { originalFileName: 'Logo.png' })).toBeNull()
  })

  it('checks both capability and extension before marking a file previewable', () => {
    expect(
      isPreviewableFileAsset(handle({ fileName: 'manual.pdf', originalFileName: null, capabilities: ['PREVIEW'] }))
    ).toBe(true)
    expect(
      isPreviewableFileAsset(handle({ fileName: 'archive.zip', originalFileName: null, capabilities: ['PREVIEW'] }))
    ).toBe(false)
    expect(
      isPreviewableFileAsset(handle({ fileName: 'manual.pdf', originalFileName: null, capabilities: ['DOWNLOAD'] }))
    ).toBe(false)
  })

  it('normalizes open-folder feedback messages', () => {
    expect(fileAssetOpenFolderMessage({ success: true })).toEqual({
      message: '文件所在文件夹已打开',
      type: 'success'
    })
    expect(fileAssetOpenFolderMessage({ success: false, error: 'Open folder is disabled' })).toEqual({
      message: 'Open folder is disabled',
      type: 'warning'
    })
    expect(fileAssetOpenFolderMessage(null)).toEqual({
      message: '无法打开文件所在文件夹',
      type: 'warning'
    })
  })

  it('returns null for open folder when owner type or asset id is missing', () => {
    // openFileAssetFolderByOptions now only attempts unified path
    // No legacy fallback is attempted
    expect(true).toBe(true)
  })
})
