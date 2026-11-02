import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import test from 'node:test'
import { certifications, patents } from '../src/data/qualifications.js'

const publicRoot = join(dirname(fileURLToPath(import.meta.url)), '..', 'public')

test('every displayed credential and patent has a website-owned image', () => {
  assert.ok(certifications.length > 0)
  assert.ok(patents.length > 0)
  for (const row of [...certifications, ...patents]) {
    assert.ok(row.name)
    assert.match(row.image, /^\/images\/[a-z0-9-]+\.(?:jpg|png|webp)$/)
    assert.ok(existsSync(join(publicRoot, row.image.slice(1))), row.image)
  }
})
