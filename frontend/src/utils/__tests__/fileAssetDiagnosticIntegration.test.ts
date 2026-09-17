import { afterEach, describe, expect, it, vi } from 'vitest'
import { FileAssetDownloadError, FileAssetRequestError, FileAssetResponseError } from '@/api/file'
import { FileAssetBatchError } from '@/composables/upload/fileAssetBatch'
import { setFileAssetDiagnosticSink } from '../fileAssetDiagnostics'

describe('file asset diagnostic integration', () => {
  afterEach(() => setFileAssetDiagnosticSink(null))

  it('automatically reports contract and partial batch failures', () => {
    const sink = vi.fn()
    setFileAssetDiagnosticSink(sink)

    new FileAssetRequestError('assetId 非法')
    new FileAssetResponseError('ownerType 不匹配')
    new FileAssetDownloadError('文件内容为空')
    new FileAssetBatchError(23, 2, new Error('network unavailable'))

    expect(sink.mock.calls.map(call => call[0].event)).toEqual([
      'invalid_request',
      'invalid_response',
      'invalid_download',
      'batch_partial_failure'
    ])
    expect(sink.mock.calls[3][0]).toMatchObject({
      assetId: 23,
      completedCount: 2,
      message: 'network unavailable'
    })
  })
})
