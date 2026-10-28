import assert from 'node:assert/strict'
import test from 'node:test'
import { fetchProducts, productImageUrl, productImageSrcSet } from '../src/api/website.js'

test('product category filter uses the public API category parameter', async () => {
  const previous = globalThis.fetch
  let requestedUrl
  globalThis.fetch = async (url) => {
    requestedUrl = url
    return { ok: true, json: async () => ({ items: [] }) }
  }
  try {
    await fetchProducts({ equipmentCategory: '封口机', page: 1, size: 12 })
    const url = new URL(requestedUrl, 'http://localhost')
    assert.equal(url.pathname, '/api/website/products')
    assert.equal(url.searchParams.get('category'), '封口机')
    assert.equal(url.searchParams.has('equipmentCategory'), false)
    assert.equal(url.searchParams.get('page'), '1')
  } finally {
    globalThis.fetch = previous
  }
})

test('website media IDs use the independent public media path', () => {
  const product = { heroMediaId: 15, cardMediaId: 16, defaultImageAssetId: 22 }
  assert.equal(productImageUrl(product), '/api/website/media/16/content')
  assert.equal(productImageSrcSet(product), '')
})

test('legacy product images keep their existing URL until public source cutover', () => {
  const product = { defaultImageAssetId: 22 }
  assert.equal(productImageUrl(product), '/api/file-assets/product_model/assets/22/thumbnail?size=640')
  assert.match(productImageSrcSet(product), /22\/thumbnail\?size=320/)
})
