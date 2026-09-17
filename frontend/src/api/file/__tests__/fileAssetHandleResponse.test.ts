import { describe, expect, it } from 'vitest'
import { FileAssetResponseError } from '../fileAssetErrors'
import { normalizeFileHandle, normalizeFileHandles } from '../fileAssetNormalizers'

function handle(overrides: Record<string, unknown> = {}) {
  return {
    assetId: 7,
    ownerType: 'document',
    ownerId: 3,
    category: 'document',
    storageCategory: 'document',
    fileName: 'manual.pdf',
    capabilities: ['DOWNLOAD', 'UNKNOWN', 'DOWNLOAD'],
    ...overrides
  }
}

describe('file asset handle response normalization', () => {
  it('normalizes capabilities while preserving a valid handle', () => {
    expect(normalizeFileHandle(handle(), { ownerType: 'document', assetId: 7 })).toMatchObject({
      assetId: 7,
      ownerType: 'document',
      capabilities: ['DOWNLOAD']
    })
  })

  it('accepts negative synthetic asset ids', () => {
    expect(normalizeFileHandle(handle({ assetId: -9 })).assetId).toBe(-9)
  })

  it('accepts an ownership transition when the stable asset id is the expected identity', () => {
    expect(
      normalizeFileHandle(handle({ ownerType: 'product_variant', ownerId: 31 }), { assetId: 7 })
    ).toMatchObject({ assetId: 7, ownerType: 'product_variant', ownerId: 31 })
  })

  it('rejects malformed and mismatched identities', () => {
    expect(() => normalizeFileHandle(handle({ assetId: 0 }))).toThrow(FileAssetResponseError)
    expect(() => normalizeFileHandle(handle({ ownerType: 'company' }), { ownerType: 'document' })).toThrow(
      /ownerType 不匹配/
    )
    expect(() => normalizeFileHandle(handle({ assetId: 8 }), { assetId: 7 })).toThrow(/assetId 不匹配/)
  })

  it('rejects a mixed-owner list instead of silently exposing it', () => {
    expect(() => normalizeFileHandles([handle(), handle({ ownerId: 4 })], 'document', 3)).toThrow(/ownerId 不匹配/)
    expect(() => normalizeFileHandles({}, 'document', 3)).toThrow(/文件列表不是数组/)
  })
})
