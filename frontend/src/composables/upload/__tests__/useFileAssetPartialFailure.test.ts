import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createNotifyMock } from './notifyMock'
import { useFileAssetActions } from '../useFileAssetActions'
import { useFileAssetCapabilities } from '../useFileAssetCapabilities'
import { fetchAllFileAssetCapabilities } from '../../../api/file'

const deleteMock = vi.fn()

vi.mock('../../../api/file', () => ({
  fetchAllFileAssetCapabilities: vi.fn(),
  fetchFileAssetCapabilities: vi.fn(),
  fileAssetApi: vi.fn(() => ({
    delete: deleteMock
  }))
}))

describe('useFileAssetActions partial batch failures', () => {
  const notify = createNotifyMock()

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(fetchAllFileAssetCapabilities).mockResolvedValue({
      document: ['DELETE']
    })
    useFileAssetCapabilities({ notify }).clearCapabilities()
  })

  it('refreshes changed data after an earlier item succeeds and a later item fails', async () => {
    const failure = new Error('second item failed')
    deleteMock.mockResolvedValueOnce(undefined).mockRejectedValueOnce(failure)
    const onChanged = vi.fn().mockResolvedValue(undefined)
    const actions = useFileAssetActions({ notify, notifySuccess: false, onChanged })

    await expect(actions.batchRemove('document', [11, 12])).rejects.toMatchObject({
      assetId: 12,
      completedCount: 1,
      cause: failure
    })

    expect(deleteMock).toHaveBeenNthCalledWith(1, 11)
    expect(deleteMock).toHaveBeenNthCalledWith(2, 12)
    expect(onChanged).toHaveBeenCalledTimes(1)
    expect(notify.errorWithDetail).toHaveBeenCalledWith('批量删除文件失败：', expect.objectContaining({ assetId: 12 }))
  })
})
