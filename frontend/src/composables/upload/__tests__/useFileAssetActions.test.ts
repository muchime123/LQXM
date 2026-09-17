import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createNotifyMock } from './notifyMock'
import { useFileAssetActions } from '../useFileAssetActions'
import { useFileAssetCapabilities } from '../useFileAssetCapabilities'
import { fetchAllFileAssetCapabilities, fileAssetApi } from '../../../api/file'

const permanentDeleteMock = vi.fn()

vi.mock('../../../api/file', () => ({
  fetchAllFileAssetCapabilities: vi.fn(),
  fetchFileAssetCapabilities: vi.fn(),
  fileAssetApi: vi.fn(() => ({
    permanentDelete: permanentDeleteMock
  }))
}))

describe('useFileAssetActions', () => {
  const notify = createNotifyMock()

  beforeEach(() => {
    vi.clearAllMocks()
    permanentDeleteMock.mockResolvedValue(undefined)
    vi.mocked(fetchAllFileAssetCapabilities).mockResolvedValue({
      document: ['PERMANENT_DELETE']
    })
    useFileAssetCapabilities({ notify }).clearCapabilities()
  })

  it('forwards explicit physical deletion', async () => {
    const actions = useFileAssetActions({ notify, notifySuccess: false })

    await actions.remove('document', 12, { permanent: true, deletePhysical: true })

    expect(fileAssetApi).toHaveBeenCalledWith('document')
    expect(permanentDeleteMock).toHaveBeenCalledWith(12, true)
  })

  it('preserves explicit deletePhysical false', async () => {
    const actions = useFileAssetActions({ notify, notifySuccess: false })

    await actions.remove('document', 12, { permanent: true, deletePhysical: false })

    expect(permanentDeleteMock).toHaveBeenCalledWith(12, false)
  })
})
