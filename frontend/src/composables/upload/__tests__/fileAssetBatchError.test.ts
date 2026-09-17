import { describe, expect, it } from 'vitest'
import { FileAssetBatchError, runFileAssetBatch } from '../fileAssetBatch'

describe('file asset batch errors', () => {
  it('reports the failed asset id, completed count and original cause', async () => {
    const cause = new Error('network unavailable')

    try {
      await runFileAssetBatch([21, 22, 23], async assetId => {
        if (assetId === 23) throw cause
        return assetId
      })
      throw new Error('expected batch to fail')
    } catch (error) {
      expect(error).toBeInstanceOf(FileAssetBatchError)
      expect(error).toMatchObject({ assetId: 23, completedCount: 2, cause })
      expect((error as Error).message).toBe('文件 23 操作失败（已完成 2 项）：network unavailable')
    }
  })

  it('normalizes non-error rejection reasons into a readable message', async () => {
    await expect(runFileAssetBatch([7], async () => Promise.reject('request rejected'))).rejects.toMatchObject({
      assetId: 7,
      completedCount: 0,
      cause: 'request rejected',
      message: '文件 7 操作失败（已完成 0 项）：request rejected'
    })
  })
})
