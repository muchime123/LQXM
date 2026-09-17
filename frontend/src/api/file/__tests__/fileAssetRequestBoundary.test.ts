import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deleteFileAsset, openFileAssetFolderByPath } from '../fileAssets'
import { fileAssetDownloadUrlById, fileAssetPreviewUrlById } from '../fileAssetUrls'
import { http } from '../../http'

vi.mock('../../http', () => ({
  http: {
    delete: vi.fn(),
    get: vi.fn()
  }
}))

describe('file asset request boundary', () => {
  beforeEach(() => vi.clearAllMocks())

  it('does not build URLs for invalid runtime identities', () => {
    expect(fileAssetPreviewUrlById('document', 1.5)).toBe('')
    expect(fileAssetDownloadUrlById('document', 0)).toBe('')
    expect(fileAssetPreviewUrlById('unknown' as 'document', 1)).toBe('')
    expect(fileAssetPreviewUrlById('company', -3)).toBe('/api/file-assets/company/assets/-3/preview')
  })

  it('rejects an invalid asset id before issuing a delete request', () => {
    expect(() => deleteFileAsset('document', 0)).toThrow(/assetId 非法/)
    expect(http.delete).not.toHaveBeenCalled()
  })

  it('rejects path-like names before issuing an open-folder request', () => {
    expect(() => openFileAssetFolderByPath('document', 3, 'document', '../secret.txt')).toThrow(/fileName 非法/)
    expect(http.get).not.toHaveBeenCalled()
  })
})
