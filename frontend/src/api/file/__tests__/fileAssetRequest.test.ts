import { describe, expect, it } from 'vitest'
import {
  assertFileAssetIdentity,
  assertFileCategory,
  assertFileName,
  assertFileOwnerId,
  FileAssetRequestError,
  isValidFileAssetId
} from '../fileAssetErrors'

describe('file asset request validation', () => {
  it('accepts persisted and synthetic asset ids', () => {
    expect(isValidFileAssetId(7)).toBe(true)
    expect(isValidFileAssetId(-7)).toBe(true)
  })

  it.each([0, 1.5, Number.MAX_SAFE_INTEGER + 1, Number.NaN])('rejects invalid asset id %s', assetId => {
    expect(isValidFileAssetId(assetId)).toBe(false)
  })

  it('rejects unknown owners and invalid database owner ids', () => {
    expect(() => assertFileAssetIdentity('unknown', 1)).toThrow(FileAssetRequestError)
    expect(() => assertFileOwnerId(-1)).toThrow(/ownerId 非法/)
    expect(() => assertFileOwnerId(null, true)).not.toThrow()
  })

  it('rejects empty categories and path-like file names', () => {
    expect(() => assertFileCategory('  ')).toThrow(/category/)
    expect(() => assertFileName('../secret.txt')).toThrow(/fileName/)
    expect(() => assertFileName('folder\\secret.txt')).toThrow(/fileName/)
    expect(() => assertFileName('report.pdf')).not.toThrow()
  })
})
