import { describe, expect, it } from 'vitest'
import { normalizeFileAssetIds, runFileAssetBatch } from '../fileAssetBatch'

describe('unified file asset id boundaries', () => {
  it('preserves negative synthetic ids used by registered field files', () => {
    expect(normalizeFileAssetIds([-1_000_000_000_001, -2_000_000_000_007, 42])).toEqual([
      -1_000_000_000_001, -2_000_000_000_007, 42
    ])
  })

  it('rejects zero, fractions, infinities and unsafe integers', () => {
    expect(
      normalizeFileAssetIds([0, 1.5, Number.NaN, Number.POSITIVE_INFINITY, Number.MAX_SAFE_INTEGER + 1, 7])
    ).toEqual([7])
  })

  it('executes batch actions for synthetic ids instead of silently dropping them', async () => {
    const visited: number[] = []

    await runFileAssetBatch([-1_000_000_000_003], async assetId => {
      visited.push(assetId)
    })

    expect(visited).toEqual([-1_000_000_000_003])
  })
})
