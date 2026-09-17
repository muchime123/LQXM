import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createNotifyMock } from './notifyMock'
import { ref } from 'vue'
import { fetchFileAssets } from '../../../api/file'
import type { FileHandle } from '../../../types/fileAsset'
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

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => {
    resolve = done
  })
  return { promise, resolve }
}

function handle(ownerId: number, assetId: number): FileHandle {
  return {
    assetId,
    ownerType: 'product_model',
    ownerId,
    category: 'main_image',
    storageCategory: 'main_image',
    storageOwnerId: ownerId,
    fileName: `${assetId}.png`,
    originalFileName: null,
    mimeType: 'image/png',
    fileSize: 1,
    tags: null,
    defaultFile: false,
    sortOrder: 0,
    deleted: false,
    coverFileName: null,
    createdAt: null,
    updatedAt: null,
    capabilities: ['PREVIEW']
  }
}

describe('useFileAssets owner request races', () => {
  beforeEach(() => vi.clearAllMocks())

  it('aborts the previous request and rejects its late response', async () => {
    const first = deferred<FileHandle[]>()
    const second = deferred<FileHandle[]>()
    vi.mocked(fetchFileAssets).mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const ownerId = ref<number | null>(1)
    const notify = createNotifyMock()
    const assets = useFileAssets({ ownerType: 'product_model', ownerId, notify })

    const firstLoading = assets.load()
    const firstSignal = vi.mocked(fetchFileAssets).mock.calls[0][2]
    ownerId.value = 2
    const secondLoading = assets.load()

    expect(firstSignal?.aborted).toBe(true)
    expect(assets.loading.value).toBe(true)
    second.resolve([handle(2, 22)])
    await secondLoading
    first.resolve([handle(1, 11)])
    await firstLoading

    expect(assets.items.value.map(item => item.assetId)).toEqual([22])
    expect(assets.loading.value).toBe(false)
    expect(notify).not.toHaveBeenCalled()
  })
})
