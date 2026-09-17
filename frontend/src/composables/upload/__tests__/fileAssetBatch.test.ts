import { describe, expect, it } from 'vitest'
import { normalizeFileAssetIds, runFileAssetBatch } from '../fileAssetBatch'

describe('file asset batch helpers', () => {
  it('normalizes empty asset ids', () => {
    expect(normalizeFileAssetIds([0, 1, 2, Number.NaN, 3, -4])).toEqual([1, 2, 3, -4])
  })

  it('deduplicates asset ids while preserving first-seen order', () => {
    expect(normalizeFileAssetIds([2, 1, 2, 3, 1])).toEqual([2, 1, 3])
  })

  it('runs batch actions in order', async () => {
    const visited: number[] = []
    const results = await runFileAssetBatch([2, 0, 1], async assetId => {
      visited.push(assetId)
      return `asset-${assetId}`
    })

    expect(visited).toEqual([2, 1])
    expect(results).toEqual(['asset-2', 'asset-1'])
  })
})
