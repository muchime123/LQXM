import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import {
  normalizePage, clampPage, buildProductQuery, sameQuery, createLatestRequestGuard, MAX_PAGE,
} from '../src/utils/productQuery.js'

test('异常 URL 页码一律规范化为非负整数', () => {
  for (const value of [undefined, null, '', 'abc', '-3', '99999999']) {
    assert.equal(normalizePage(value), 0, `异常页码 ${String(value)} 应归 0`)
  }
  assert.equal(normalizePage('0'), 0)
  assert.equal(normalizePage('2'), 2)
  assert.equal(normalizePage(2), 2)
  assert.equal(normalizePage(3.9), 3)
  assert.equal(normalizePage(['4', '9']), 4)
  assert.equal(normalizePage(MAX_PAGE), MAX_PAGE)
  assert.equal(normalizePage(MAX_PAGE + 1), 0)
})

test('数据返回后把越界页码收敛到实际最后一页', () => {
  assert.equal(clampPage(5, 3), 2)
  assert.equal(clampPage(2, 3), 2)
  assert.equal(clampPage(0, 3), 0)
  assert.equal(clampPage(5, 1), 0)
  assert.equal(clampPage(5, 0), 0)
  assert.equal(clampPage('-2', 0), 0)
})

test('query 构造只写非空条件与大于 0 的页码', () => {
  assert.deepEqual(buildProductQuery({}), {})
  assert.deepEqual(buildProductQuery({ keyword: '封口机', page: 0 }), { keyword: '封口机' })
  assert.deepEqual(buildProductQuery({
    keyword: '封口机', equipmentCategory: '贴体设备', application: '熟食',
    container: '', process: undefined, capacity: null, page: 3,
  }), {
    keyword: '封口机', equipmentCategory: '贴体设备', application: '熟食', page: 3,
  })
  assert.deepEqual(buildProductQuery({ page: '-3' }), {})
  assert.deepEqual(buildProductQuery({ page: ['7', '9'] }), { page: 7 })
})

test('等价 query 判等时忽略键顺序、空值与数组退化', () => {
  assert.equal(sameQuery({ keyword: 'a', page: 1 }, { page: 1, keyword: 'a' }), true)
  assert.equal(sameQuery({ keyword: 'a', page: 1 }, { keyword: 'a', page: '1' }), true)
  assert.equal(sameQuery({ keyword: 'a', page: 1 }, { keyword: 'a', page: 2 }), false)
  assert.equal(sameQuery({}, { keyword: '', page: undefined }), true)
  assert.equal(sameQuery({ keyword: ['a'] }, { keyword: 'a' }), true)
})

test('只有最新请求的响应可以落地', () => {
  const guard = createLatestRequestGuard()
  const first = guard.start()
  const second = guard.start()
  const third = guard.start()
  assert.equal(guard.isLatest(first), false)
  assert.equal(guard.isLatest(second), false)
  assert.equal(guard.isLatest(third), true)
  const fourth = guard.start()
  assert.equal(guard.isLatest(third), false)
  assert.equal(guard.isLatest(fourth), true)
})

test('产品中心与移动端导航的关键交互契约不被回退', async () => {
  const read = (path) => readFile(fileURLToPath(new URL(path, import.meta.url)), 'utf8')
  const [products, header, homeCss] = await Promise.all([
    read('../src/views/Products.vue'),
    read('../src/components/AppHeader.vue'),
    read('../src/assets/styles/home.css'),
  ])
  /* 搜索输入不写历史，分类 / 筛选 / 分页用默认 push */
  assert.match(products, /syncQuery\('replace'\)/)
  assert.match(products, /function syncQuery\(mode = 'push'\)/)
  /* 竞态、越界回退、重试沿用 URL 条件、加载中禁止翻页 */
  assert.match(products, /requestGuard\.isLatest/)
  assert.match(products, /clampPage\(requestedPage, pages\)/)
  assert.match(products, /@click="retry"/)
  assert.match(products, /if \(loading\.value \|\| p < 0/)
  /* 移动端菜单：外部点击关闭、拉宽到桌面自动收起、滚动锁定 */
  assert.match(header, /document\.addEventListener\('pointerdown'/)
  assert.match(header, /window\.addEventListener\('resize'/)
  assert.match(header, /mobile-nav-active/)
  /* 菜单 top 与最终生效的 header 高度一致，避免遮挡 */
  assert.match(homeCss, /top: var\(--header-height-mobile\);/)
  assert.doesNotMatch(homeCss, /top: var\(--header-height-mobile-sm\)/)
})
