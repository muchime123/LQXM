import { beforeEach, describe, expect, it, vi } from 'vitest'
import { deleteFileAsset, permanentDeleteFileAsset } from '../fileAssets'
import { http } from '../../http'

vi.mock('../../http', () => ({
  http: {
    delete: vi.fn()
  }
}))

describe('file asset delete safety', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(http.delete).mockResolvedValue({ data: undefined })
  })

  it('preserves the physical file when explicitly requested', async () => {
    await permanentDeleteFileAsset('document', 12, false)

    expect(http.delete).toHaveBeenCalledWith('/file-assets/document/assets/12', {
      params: { permanent: true, deletePhysical: false }
    })
  })

  it('deletes the physical file only when explicitly requested', async () => {
    await permanentDeleteFileAsset('document', 12, true)

    expect(http.delete).toHaveBeenCalledWith('/file-assets/document/assets/12', {
      params: { permanent: true, deletePhysical: true }
    })
  })

  it('ignores deletePhysical for ordinary soft deletes at the request boundary', async () => {
    await deleteFileAsset('document', 12)

    expect(http.delete).toHaveBeenCalledWith('/file-assets/document/assets/12', {
      params: { permanent: false, deletePhysical: false }
    })
  })
})
