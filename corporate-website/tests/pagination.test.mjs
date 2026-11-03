import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { buildPageWindow, PAGE_GAP } from '../src/utils/pagination.js'

const numeric = (window) => window.filter((item) => item !== PAGE_GAP)

test('页码窗口在总数不超过 7 时全部渲染', () => {
  assert.deepEqual(buildPageWindow(0, 0), [])
  assert.deepEqual(buildPageWindow(0, 1), [0])
  assert.deepEqual(buildPageWindow(3, 4), [0, 1, 2, 3])
  assert.deepEqual(buildPageWindow(6, 7), [0, 1, 2, 3, 4, 5, 6])
})

test('页码窗口始终包含首页、末页和当前页', () => {
  for (const total of [8, 9, 13, 25, 300]) {
    for (let current = 0; current < total; current += 1) {
      const window = buildPageWindow(current, total)
      const pages = numeric(window)
      assert.equal(pages[0], 0, `total=${total} current=${current} 应包含首页`)
      assert.equal(pages[pages.length - 1], total - 1, `total=${total} current=${current} 应包含末页`)
      assert.ok(pages.includes(current), `total=${total} current=${current} 应包含当前页`)
    }
  }
})

test('页码窗口最多渲染 7 个按钮并最多出现两处省略', () => {
  for (const total of [8, 25, 300]) {
    for (let current = 0; current < total; current += 1) {
      const window = buildPageWindow(current, total)
      assert.ok(numeric(window).length <= 7, `total=${total} current=${current} 页码按钮过多`)
      assert.ok(window.filter((item) => item === PAGE_GAP).length <= 2)
      assert.equal(new Set(numeric(window)).size, numeric(window).length, '页码不应重复')
      assert.ok(numeric(window).every((page, index, list) => index === 0 || page > list[index - 1]), '页码应递增')
    }
  }
})

test('窗口形态符合预期：首页区、中段、末页区', () => {
  assert.deepEqual(buildPageWindow(0, 25), [0, 1, 2, 3, PAGE_GAP, 24])
  assert.deepEqual(buildPageWindow(12, 25), [0, PAGE_GAP, 11, 12, 13, PAGE_GAP, 24])
  assert.deepEqual(buildPageWindow(24, 25), [0, PAGE_GAP, 21, 22, 23, 24])
})

test('异常入参不产生越界页码', () => {
  assert.deepEqual(buildPageWindow(999, 10), [0, PAGE_GAP, 6, 7, 8, 9])
  assert.deepEqual(buildPageWindow(-5, 10), [0, 1, 2, 3, PAGE_GAP, 9])
  assert.deepEqual(buildPageWindow(Number.NaN, 10), [0, 1, 2, 3, PAGE_GAP, 9])
  assert.deepEqual(buildPageWindow(2, -1), [])
  assert.deepEqual(buildPageWindow(2, 3.5), [])
})

test('产品中心分页与筛选的关键交互契约不被回退', async () => {
  const read = (path) => readFile(fileURLToPath(new URL(path, import.meta.url)), 'utf8')
  const [pagination, products, searchbar, productDetail, productCard, snapshot] = await Promise.all([
    read('../src/components/common/Pagination.vue'),
    read('../src/views/Products.vue'),
    read('../src/components/common/SearchBar.vue'),
    read('../src/views/ProductDetail.vue'),
    read('../src/components/ProductCard.vue'),
    read('../src/api/snapshot.js'),
  ])

  /* 分页：窗口化 + 手机紧凑形态 + 加载中禁用 */
  assert.match(pagination, /buildPageWindow/)
  assert.match(pagination, /pagination__compact/)
  assert.match(pagination, /if \(props\.busy\) return/)

  /* 搜索：输入值与已提交关键词分离，且可独立清除 */
  assert.match(products, /const inputKeyword = ref/)
  assert.match(products, /function commitKeyword\(\)/)
  assert.match(products, /function clearKeyword\(\)/)
  assert.match(products, /@clear="clearKeyword"/)
  assert.match(searchbar, /searchbar__clear/)
  assert.match(searchbar, /aria-label="清除搜索关键词"/)

  /* 已选条件逐项移除 + 分类降级提示 */
  assert.match(products, /activeFilterChips/)
  assert.match(products, /function removeFilter\(key\)/)
  assert.match(products, /categoriesError/)

  /* 错误状态优先于旧结果：错误分支必须出现在 products.length 分支之前 */
  const errorIndex = products.indexOf('v-else-if="error"')
  const listIndex = products.indexOf('v-else-if="products.length"')
  assert.ok(errorIndex > 0 && listIndex > 0, '模板应同时包含错误分支与列表分支')
  assert.ok(errorIndex < listIndex, '错误分支应优先于旧结果分支')

  /* 详情返回优先恢复来源列表 */
  assert.match(productDetail, /function backToCatalog\(\)/)
  assert.match(productDetail, /window\.history\.state\?\.back/)

  /* 卡片链接与详情查询口径一致：slug 优先 */
  assert.match(productCard, /props\.product\.slug \|\| props\.product\.websiteSlug/)

  /* 快照页码收敛 */
  assert.match(snapshot, /Math\.min\(requested, totalPages - 1\)/)
})
