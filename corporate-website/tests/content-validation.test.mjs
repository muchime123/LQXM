import assert from 'node:assert/strict'
import test from 'node:test'
import { validateContent } from '../scripts/validate-content.mjs'

test('B1 结构性不变量：产品 slug、详情、媒体、下线路由与 sitemap', async () => {
  const report = await validateContent()
  const check = (code) => report.checks.find((row) => row.code === code)
  assert.equal(report.totals.products, 45)
  for (const code of [
    'content.files', 'product.slug', 'product.detail', 'product.media',
    'media.manifest', 'content.archiveUrls', 'featured.slugs',
    'retired.routes', 'retired.snapshot',
  ]) {
    assert.equal(check(code)?.status, 'pass', `${code} 校验应通过，实际: ${JSON.stringify(check(code)?.issues?.slice(0, 3))}`)
  }
  assert.equal(report.totals.products, report.totals.details)
  assert.equal(report.gate.passed, false, '业务审核签署文件建立前，发布闸门必须保持阻断')
})

test('B1 清理后占位文案、缺失媒体和旧站点地图不再进入发布内容', async () => {
  const report = await validateContent()
  const missing = report.checks.find((row) => row.code === 'media.references')
  assert.equal(missing.status, 'pass')
  const copy = report.checks.find((row) => row.code === 'product.copy')
  assert.equal(copy.status, 'pass')
  const review = report.checks.find((row) => row.code === 'review.manifest')
  assert.equal(review.status, 'fail', '应报告缺少业务审核签署文件')
  const sitemap = report.checks.find((row) => row.code === 'retired.sitemap')
  assert.equal(sitemap.status, 'pass')
})
