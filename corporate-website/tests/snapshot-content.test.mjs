import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile, access } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { snapshotGet, snapshotMediaUrl } from '../src/api/snapshot.js'

const contentRoot = new URL('../public/content/', import.meta.url)

test('static product pages resolve all published slugs and website media locally', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async (url) => {
    const file = new URL(String(url).replace(/^\/content\//, ''), contentRoot)
    try {
      const data = JSON.parse(await readFile(file, 'utf8'))
      return { ok: true, json: async () => data }
    } catch {
      return { ok: false, status: 404 }
    }
  }
  try {
    const products = await snapshotGet('/api/website/products?page=0&size=100')
    assert.ok(products.total > 0)
    assert.equal(products.items.length, products.total)
    for (const product of products.items) {
      const detail = await snapshotGet(`/api/website/products/${encodeURIComponent(product.slug)}`)
      assert.equal(detail.product.slug, product.slug)
      for (const id of [product.cardMediaId, product.heroMediaId, ...(detail.gallery || []).map((item) => item.mediaId)]) {
        if (!id) continue
        const mediaUrl = snapshotMediaUrl(id)
        assert.ok(mediaUrl.startsWith('/content/media/'))
        await access(fileURLToPath(new URL(mediaUrl.slice(1), new URL('../public/', import.meta.url))))
      }
    }
    assert.deepEqual(await snapshotGet('/api/website/packaging'), [])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('public snapshots never expose unresolved archive or website media URLs', async () => {
  const originalFetch = globalThis.fetch
  globalThis.fetch = async (url) => {
    const file = new URL(String(url).replace(/^\/content\//, ''), contentRoot)
    return { ok: true, json: async () => JSON.parse(await readFile(file, 'utf8')) }
  }
  try {
    const content = await Promise.all([
      snapshotGet('/api/website/home'),
      snapshotGet('/api/website/company-detail'),
      snapshotGet('/api/website/banners/public'),
      snapshotGet('/api/website/solutions/public'),
      snapshotGet('/api/website/page-sections/HOME'),
      snapshotGet('/api/website/page-sections/PRODUCTS'),
      snapshotGet('/api/website/page-sections/SOLUTIONS'),
      snapshotGet('/api/website/page-sections/ABOUT'),
    ])
    const serialized = JSON.stringify(content)
    assert.doesNotMatch(serialized, /\/api\/file-assets\/|\/api\/website\/media\/\d+\/content/)
    for (const match of serialized.matchAll(/\/content\/media\/([^"\\]+\.(?:png|jpe?g|webp))/g)) {
      await access(fileURLToPath(new URL(`content/media/${match[1]}`, new URL('../public/', import.meta.url))))
    }
  } finally {
    globalThis.fetch = originalFetch
  }
})
