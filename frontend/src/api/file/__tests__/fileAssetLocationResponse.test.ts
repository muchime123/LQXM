import { describe, expect, it } from 'vitest'
import { normalizeFileAssetLocalPathResult, normalizeFileAssetOpenFolderResult } from '../fileAssetNormalizers'

describe('file asset location response normalization', () => {
  it('accepts explicit successful location results', () => {
    expect(normalizeFileAssetOpenFolderResult({ success: true, localPath: 'D:/files/a.pdf' })).toEqual({
      success: true,
      localPath: 'D:/files/a.pdf'
    })
    expect(
      normalizeFileAssetLocalPathResult(
        { found: true, localPath: 'D:/files/a.pdf', ownerType: 'document', ownerId: 3 },
        'document'
      )
    ).toMatchObject({ found: true, ownerType: 'document' })
  })

  it('preserves extension error codes on an explicit failure', () => {
    expect(normalizeFileAssetOpenFolderResult({ success: false, errorCode: 'FUTURE_ERROR' })).toEqual({
      success: false,
      errorCode: 'FUTURE_ERROR'
    })
  })

  it('rejects truthy status values and pathless successes', () => {
    expect(() => normalizeFileAssetOpenFolderResult({ success: 'true', localPath: 'D:/files' })).toThrow(/success 非法/)
    expect(() => normalizeFileAssetOpenFolderResult({ success: true })).toThrow(/缺少 localPath/)
    expect(() => normalizeFileAssetLocalPathResult({ found: true }, 'document')).toThrow(/缺少 localPath/)
  })

  it('rejects a mismatched owner identity', () => {
    expect(() => normalizeFileAssetLocalPathResult({ found: false, ownerType: 'company' }, 'document')).toThrow(
      /ownerType 不匹配/
    )
  })
})
