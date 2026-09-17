import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createNotifyMock } from './notifyMock'
import { fetchAllFileAssetCapabilities, fetchFileAssetCapabilities } from '../../../api/file'
import { useFileAssetCapabilities } from '../useFileAssetCapabilities'

vi.mock('../../../api/file', () => ({
  fetchAllFileAssetCapabilities: vi.fn(),
  fetchFileAssetCapabilities: vi.fn()
}))

describe('useFileAssetCapabilities', () => {
  const notify = createNotifyMock()

  beforeEach(() => {
    vi.clearAllMocks()
    useFileAssetCapabilities({ notify }).clearCapabilities()
  })

  it('loads all owner capabilities once and reuses the shared cache', async () => {
    vi.mocked(fetchAllFileAssetCapabilities).mockResolvedValue({
      document: ['DOWNLOAD', 'OPEN_FOLDER'],
      company: ['DELETE', 'OPEN_FOLDER']
    })

    const capabilities = useFileAssetCapabilities({ notify })

    await expect(capabilities.ensureCapability('document', 'OPEN_FOLDER')).resolves.toBe(true)
    await expect(capabilities.ensureCapability('company', 'DELETE')).resolves.toBe(true)

    expect(fetchAllFileAssetCapabilities).toHaveBeenCalledTimes(1)
    expect(fetchFileAssetCapabilities).not.toHaveBeenCalled()
    expect(notify).not.toHaveBeenCalled()
  })

  it('falls back to per-owner capabilities when the all-capabilities endpoint fails', async () => {
    vi.mocked(fetchAllFileAssetCapabilities).mockRejectedValue(new Error('all failed'))
    vi.mocked(fetchFileAssetCapabilities).mockResolvedValue(['DOWNLOAD'])

    const capabilities = useFileAssetCapabilities({ notify })

    await expect(capabilities.ensureCapability('document', 'DOWNLOAD')).resolves.toBe(true)

    expect(fetchAllFileAssetCapabilities).toHaveBeenCalledTimes(1)
    expect(fetchFileAssetCapabilities).toHaveBeenCalledWith('document')
    expect(notify).not.toHaveBeenCalled()
  })

  it('does not retry all-capabilities after it failed in the current cache cycle', async () => {
    vi.mocked(fetchAllFileAssetCapabilities).mockRejectedValue(new Error('all failed'))
    vi.mocked(fetchFileAssetCapabilities).mockResolvedValueOnce(['DOWNLOAD']).mockResolvedValueOnce(['DELETE'])

    const capabilities = useFileAssetCapabilities({ notify })

    await expect(capabilities.ensureCapability('document', 'DOWNLOAD')).resolves.toBe(true)
    await expect(capabilities.ensureCapability('company', 'DELETE')).resolves.toBe(true)

    expect(fetchAllFileAssetCapabilities).toHaveBeenCalledTimes(1)
    expect(fetchFileAssetCapabilities).toHaveBeenNthCalledWith(1, 'document')
    expect(fetchFileAssetCapabilities).toHaveBeenNthCalledWith(2, 'company')
  })

  it('uses all-capabilities when explicitly loading one owner after cache reset', async () => {
    vi.mocked(fetchAllFileAssetCapabilities).mockResolvedValue({
      product_model: ['PREVIEW', 'DOWNLOAD']
    })

    const capabilities = useFileAssetCapabilities({ notify })

    await capabilities.ensureLoaded('product_model')

    expect(capabilities.capabilities('product_model')).toEqual(['PREVIEW', 'DOWNLOAD'])
    expect(fetchAllFileAssetCapabilities).toHaveBeenCalledTimes(1)
    expect(fetchFileAssetCapabilities).not.toHaveBeenCalled()
  })
})
