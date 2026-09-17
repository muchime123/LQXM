import { describe, expect, it } from 'vitest'
import { normalizeFileCapabilities, normalizeFileCapabilityMap } from '../fileAssetNormalizers'

describe('file asset capability response normalization', () => {
  it('filters malformed and duplicate capabilities', () => {
    expect(normalizeFileCapabilities(['DOWNLOAD', 'UNKNOWN', null, 'DOWNLOAD', 1])).toEqual(['DOWNLOAD'])
    expect(normalizeFileCapabilities({ DOWNLOAD: true })).toEqual([])
  })

  it('keeps only known owners and normalizes each capability list', () => {
    expect(
      normalizeFileCapabilityMap({
        company: ['DOWNLOAD', 'UNKNOWN', 'DOWNLOAD'],
        document: null,
        unknown_owner: ['DELETE']
      })
    ).toEqual({
      company: ['DOWNLOAD'],
      document: []
    })
  })

  it('treats a malformed map response as empty', () => {
    expect(normalizeFileCapabilityMap(null)).toEqual({})
    expect(normalizeFileCapabilityMap([])).toEqual({})
  })
})
