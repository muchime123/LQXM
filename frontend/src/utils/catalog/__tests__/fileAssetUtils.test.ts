import { describe, expect, it } from 'vitest'
import {
  groupProductFileAssetIdEntries,
  packagingFileOwnerType,
  packagingFileOwnerTypeEntries,
  productFileOwnerType
} from '../fileAssetUtils'

describe('catalog file asset owner helpers', () => {
  it('resolves product asset owner type from explicit owner type first', () => {
    expect(productFileOwnerType({ ownerType: 'product_variant', variantId: null })).toBe('product_variant')
    // 显式 ownerType（如 company）优先于 variantId 推断，避免按页面上下文重复推断
    expect(productFileOwnerType({ ownerType: 'company', variantId: 9 })).toBe('company')
  })

  it('falls back to product variant ownership when variant id is present', () => {
    expect(productFileOwnerType({ variantId: 9 })).toBe('product_variant')
    expect(productFileOwnerType({ variantId: null })).toBe('product_model')
  })

  it('groups product asset ids by normalized owner type', () => {
    expect(
      groupProductFileAssetIdEntries([
        { id: 1, ownerType: 'product_variant', variantId: null },
        { id: 2, variantId: null },
        { id: 3, ownerType: 'company', variantId: 7 },
        { id: null, variantId: 8 }
      ])
    ).toEqual([
      ['product_variant', [1]],
      ['product_model', [2]],
      ['company', [3]]
    ])
  })

  it('resolves packaging asset owner type only after an asset has been registered', () => {
    expect(packagingFileOwnerType({ ownerType: 'packaging_variant', id: 3, variantId: null })).toBe('packaging_variant')
    expect(packagingFileOwnerType({ ownerType: 'company', id: 3, variantId: 4 })).toBe('packaging_variant')
    expect(packagingFileOwnerType({ variantId: 4, id: 3 })).toBe('packaging_variant')
    expect(packagingFileOwnerType({ variantId: null, id: 3 })).toBe('packaging_model')
    expect(packagingFileOwnerType({ variantId: 4 })).toBeNull()
  })

  it('deduplicates packaging owner types for reorder decisions', () => {
    expect(
      packagingFileOwnerTypeEntries([
        { id: 1, ownerType: 'packaging_model', variantId: 7 },
        { id: 2, variantId: null },
        { id: 3, variantId: 8 },
        { variantId: 9 }
      ])
    ).toEqual(['packaging_model', 'packaging_variant'])
  })
})
