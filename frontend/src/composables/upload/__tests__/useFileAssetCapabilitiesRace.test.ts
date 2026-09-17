import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fetchAllFileAssetCapabilities, fetchFileAssetCapabilities } from '../../../api/file'
import { useFileAssetCapabilities } from '../useFileAssetCapabilities'
import type { FileCapability, FileCapabilityMap } from '../../../types/fileAsset'

vi.mock('../../../api/file', () => ({
  fetchAllFileAssetCapabilities: vi.fn(),
  fetchFileAssetCapabilities: vi.fn()
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => {
    resolve = done
  })
  return { promise, resolve }
}

describe('useFileAssetCapabilities cache races', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useFileAssetCapabilities().clearCapabilities()
  })

  it('does not restore an owner cache from a request started before clear', async () => {
    const oldRequest = deferred<FileCapability[]>()
    vi.mocked(fetchFileAssetCapabilities).mockReturnValue(oldRequest.promise)
    const capabilities = useFileAssetCapabilities()

    const loading = capabilities.loadCapabilities('document')
    capabilities.clearCapabilities('document')
    oldRequest.resolve(['DOWNLOAD'])
    await loading

    expect(capabilities.isLoaded('document')).toBe(false)
  })

  it('does not let an old finally block detach a newer owner request', async () => {
    const oldRequest = deferred<FileCapability[]>()
    const newRequest = deferred<FileCapability[]>()
    vi.mocked(fetchFileAssetCapabilities)
      .mockReturnValueOnce(oldRequest.promise)
      .mockReturnValueOnce(newRequest.promise)
    const capabilities = useFileAssetCapabilities()

    const oldLoading = capabilities.loadCapabilities('document')
    capabilities.clearCapabilities('document')
    const newLoading = capabilities.loadCapabilities('document')
    oldRequest.resolve(['DOWNLOAD'])
    await oldLoading

    const joinedLoading = capabilities.loadCapabilities('document')
    expect(fetchFileAssetCapabilities).toHaveBeenCalledTimes(2)
    newRequest.resolve(['DELETE'])
    await expect(Promise.all([newLoading, joinedLoading])).resolves.toEqual([['DELETE'], ['DELETE']])
  })

  it('does not restore the all-owner cache from a stale request', async () => {
    const oldRequest = deferred<FileCapabilityMap>()
    vi.mocked(fetchAllFileAssetCapabilities).mockReturnValue(oldRequest.promise)
    const capabilities = useFileAssetCapabilities()

    const loading = capabilities.loadAllCapabilities()
    capabilities.clearCapabilities()
    oldRequest.resolve({ document: ['DOWNLOAD'] })
    await loading

    expect(capabilities.loadedOwnerTypes.value.size).toBe(0)
  })
})
