import { afterEach, describe, expect, it, vi } from 'vitest'
import { reportFileAssetDiagnostic, setFileAssetDiagnosticSink } from '../fileAssetDiagnostics'

describe('file asset diagnostics', () => {
  afterEach(() => setFileAssetDiagnosticSink(null))

  it('emits structured identity and operation context', () => {
    const sink = vi.fn()
    setFileAssetDiagnosticSink(sink)

    reportFileAssetDiagnostic('invalid_response', {
      severity: 'error',
      operation: 'fetchFileAsset',
      ownerType: 'document',
      assetId: 7,
      message: 'assetId 不匹配'
    })

    expect(sink).toHaveBeenCalledWith(
      expect.objectContaining({
        event: 'invalid_response',
        severity: 'error',
        operation: 'fetchFileAsset',
        ownerType: 'document',
        assetId: 7,
        message: 'assetId 不匹配',
        timestamp: expect.any(String)
      })
    )
  })

  it('does not expose arbitrary objects as diagnostic text', () => {
    const sink = vi.fn()
    setFileAssetDiagnosticSink(sink)
    reportFileAssetDiagnostic('invalid_download', { severity: 'error', cause: { localPath: 'D:/secret' } })

    expect(sink.mock.calls[0][0].message).toBe('未知错误')
  })
})
