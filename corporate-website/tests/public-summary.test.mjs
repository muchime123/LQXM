import assert from 'node:assert/strict'
import test from 'node:test'
import { reviewedSummary } from '../src/utils/publicSummary.js'

test('generated product slogans do not appear as reviewed public descriptions', () => {
  assert.equal(reviewedSummary('LQ-1', 'LQ-1，专业封口机，品质保障。'), '')
  assert.equal(reviewedSummary('LQ-1', '另一款设备，专业封口机，品质保障。'), '')
  assert.equal(reviewedSummary('LQ-1', 'LQ-1 可用于熟食包装。'), 'LQ-1 可用于熟食包装。')
})
