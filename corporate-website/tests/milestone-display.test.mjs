import test from 'node:test'
import assert from 'node:assert/strict'
import { displayMilestones } from '../src/utils/milestoneDisplay.js'

test('native company content never shows legacy placeholder milestones', () => {
  assert.deepEqual(displayMilestones([], 'native'), [])
  assert.equal(displayMilestones([], 'legacy').length, 4)
})

test('published milestones display in year order without mutating API response', () => {
  const rows = [{ year: 2024, title: 'B' }, { year: 2020, title: 'A' }]
  assert.deepEqual(displayMilestones(rows, 'native').map(row => row.title), ['A', 'B'])
  assert.equal(rows[0].title, 'B')
})
