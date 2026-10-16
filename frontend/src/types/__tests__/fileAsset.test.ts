import { describe, expect, it } from 'vitest'
import {
  FILE_ASSET_ERROR_CODES,
  FILE_CAPABILITIES,
  FILE_OWNER_TYPES,
  isFileAssetErrorCode,
  isFileCapability,
  isFileOwnerType
} from '../fileAsset'

describe('file asset runtime types', () => {
  it('keeps the unified owner type list available at runtime', () => {
    expect(FILE_OWNER_TYPES).toEqual([
      'product_model',
      'product_variant',
      'packaging_model',
      'packaging_variant',
      'company',
      'certificate',
      'employee',
      'document'
    ])
    expect(isFileOwnerType('company')).toBe(true)
    expect(isFileOwnerType('unknown')).toBe(false)
    expect(isFileOwnerType(null)).toBe(false)
  })

  it('keeps the unified capability list available at runtime', () => {
    expect(FILE_CAPABILITIES).toContain('OPEN_FOLDER')
    expect(FILE_CAPABILITIES).toContain('UPDATE_TAGS')
    expect(isFileCapability('DOWNLOAD')).toBe(true)
    expect(isFileCapability('EXPORT')).toBe(false)
  })

  it('keeps structured file error codes available at runtime', () => {
    expect(FILE_ASSET_ERROR_CODES).toContain('OPEN_FOLDER_DISABLED')
    expect(FILE_ASSET_ERROR_CODES).toContain('FILE_BEHAVIOR_UNSUPPORTED')
    expect(isFileAssetErrorCode('FILE_NOT_FOUND')).toBe(true)
    expect(isFileAssetErrorCode('UNKNOWN')).toBe(false)
  })
})
