import { describe, expect, it } from 'vitest'
import { certificateFileAssetId, certificateFileFieldLabel } from '../certificateFileAsset'

describe('certificate file asset helpers', () => {
  it('encodes certificate file assets as deterministic synthetic ids', () => {
    expect(certificateFileAssetId(12, 'specFile')).toBe(121)
    expect(certificateFileAssetId(12, 'certFile')).toBe(122)
    expect(certificateFileAssetId(1, 'specFile')).toBe(11)
    expect(certificateFileAssetId(7, 'certFile')).toBe(72)
  })

  it('mirrors the backend field suffix contract', () => {
    expect(certificateFileAssetId(42, 'specFile') % 10).toBe(1)
    expect(certificateFileAssetId(42, 'certFile') % 10).toBe(2)
  })

  it('provides human readable field labels', () => {
    expect(certificateFileFieldLabel('specFile')).toBe('说明书文件')
    expect(certificateFileFieldLabel('certFile')).toBe('证书图片')
  })
})
