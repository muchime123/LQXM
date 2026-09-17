import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createNotifyMock } from './notifyMock'
import { ref } from 'vue'
import { fetchFileAssets } from '../../../api/file'
import { useFileAssets } from '../useFileAssets'

vi.mock('../../../api/file', () => ({
  fetchFileAsset: vi.fn(),
  fetchFileAssets: vi.fn(),
  fetchAllFileAssetCapabilities: vi.fn(),
  fetchFileAssetCapabilities: vi.fn(),
  fileAssetApi: vi.fn(),
  fileAssetDownloadUrlById: vi.fn(),
  fileAssetPreviewUrlById: vi.fn()
}))

describe('useFileAssets owner boundary', () => {
  beforeEach(() => vi.clearAllMocks())

  // ownerId 为空不再是"缺失归属"，而是走 /file-assets/singleton/{ownerType} 的单例归属请求
  it('fetches via the singleton owner endpoint when no owner id is present', async () => {
    const ownerId = ref<number | null>(null)
    vi.mocked(fetchFileAssets).mockResolvedValue([])
    const assets = useFileAssets({ ownerType: 'product_model', ownerId, notify: createNotifyMock() })

    await expect(assets.load()).resolves.toEqual([])
    expect(assets.loaded.value).toBe(true)
    expect(assets.loading.value).toBe(false)
    expect(fetchFileAssets).toHaveBeenCalledTimes(1)
    expect(fetchFileAssets).toHaveBeenCalledWith('product_model', null, expect.any(AbortSignal))
  })

  it('reloads against the concrete owner once it becomes available', async () => {
    const ownerId = ref<number | null>(null)
    vi.mocked(fetchFileAssets).mockResolvedValue([])
    const assets = useFileAssets({ ownerType: 'product_model', ownerId, notify: createNotifyMock() })

    await assets.load()
    ownerId.value = 12
    await assets.load()

    expect(fetchFileAssets).toHaveBeenCalledTimes(2)
    expect(fetchFileAssets).toHaveBeenLastCalledWith('product_model', 12, expect.any(AbortSignal))
  })
})
