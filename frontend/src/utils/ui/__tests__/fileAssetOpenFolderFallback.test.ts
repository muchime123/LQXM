import { beforeEach, describe, expect, it, vi } from 'vitest'
import { openFileAssetFolder, openFileAssetFolderByPath } from '../../../api/file'
import { openFileAssetFolderByOptions } from '../fileAssetUrls'

vi.mock('../../../api/file', () => ({
  fileAssetDownloadUrlById: vi.fn(),
  fileAssetPreviewUrlById: vi.fn(),
  openFileAssetFolder: vi.fn(),
  openFileAssetFolderByPath: vi.fn()
}))

describe('open file asset folder fallback', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it.each(['FILE_NOT_FOUND', 'FILE_BEHAVIOR_UNSUPPORTED'] as const)(
    'falls back to the registered physical path for recoverable %s results',
    async errorCode => {
      vi.mocked(openFileAssetFolder).mockResolvedValue({ success: false, errorCode })
      vi.mocked(openFileAssetFolderByPath).mockResolvedValue({ success: true, localPath: 'D:/legacy/file.png' })

      const result = await openFileAssetFolderByOptions({
        ownerType: 'product_model',
        assetId: 42,
        category: 'main_image',
        fileName: 'file.png',
        modelId: 7
      })

      expect(result).toEqual({ success: true, localPath: 'D:/legacy/file.png' })
      expect(openFileAssetFolderByPath).toHaveBeenCalledWith('product_model', 7, 'main_image', 'file.png')
    }
  )

  it.each(['OPEN_FOLDER_DISABLED', 'INVALID_FILE', 'INVALID_CATEGORY', 'OPEN_FOLDER_FAILED'] as const)(
    'does not bypass unified policy for non-recoverable %s results',
    async errorCode => {
      const failure = { success: false, errorCode }
      vi.mocked(openFileAssetFolder).mockResolvedValue(failure)

      const result = await openFileAssetFolderByOptions({
        ownerType: 'company',
        assetId: 9,
        category: 'company_asset',
        fileName: 'logo.png'
      })

      expect(result).toEqual(failure)
      expect(openFileAssetFolderByPath).not.toHaveBeenCalled()
    }
  )

  it('returns the unified failure when recoverable but no legacy identity exists', async () => {
    const failure = { success: false, errorCode: 'FILE_NOT_FOUND' as const }
    vi.mocked(openFileAssetFolder).mockResolvedValue(failure)

    const result = await openFileAssetFolderByOptions({ ownerType: 'document', assetId: 8 })

    expect(result).toEqual(failure)
  })
})
