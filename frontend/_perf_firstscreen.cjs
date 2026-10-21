/**
 * 生产跟单首屏计时（Playwright）。
 *
 * 目的：量化「三跳串行 → 并行」的真实收益。
 * 关键指标不是「load 事件」，而是**最后一个业务接口完成的时间**——那才是用户看到完整页面的时刻。
 *
 * 用法：node _perf_firstscreen.cjs <baseUrl>
 *   baseUrl 例：http://localhost:5173
 *
 * 免登录：channel=msedge + localStorage.lq.currentEmployeeId=23（见项目备忘）。
 */
const { chromium } = require('playwright')

const BASE = process.argv[2] || 'http://localhost:5173'
const ROUTE = '/#/production-tracking'
const TRACKED = ['/api/manufacturing/followups', '/api/manufacturing/followups/filter-options',
  '/api/manufacturing/followups/workbook-summary']

async function once(browser) {
  const ctx = await browser.newContext()
  await ctx.addInitScript(() => {
    localStorage.setItem('lq.currentEmployeeId', '23')
  })
  const page = await ctx.newPage()
  const marks = []
  const t0 = Date.now()
  page.on('request', (req) => {
    const u = req.url()
    if (TRACKED.some((p) => u.includes(p))) marks.push({ url: u.replace(BASE, ''), start: Date.now() - t0, end: null })
  })
  page.on('requestfinished', (req) => {
    const u = req.url()
    const hit = marks.filter((m) => m.end === null).find((m) => u.includes(m.url))
    if (hit) hit.end = Date.now() - t0
  })
  page.on('requestfailed', (req) => {
    const u = req.url()
    const hit = marks.filter((m) => m.end === null).find((m) => u.includes(m.url))
    if (hit) hit.end = Date.now() - t0
  })

  await page.goto(BASE + ROUTE, { waitUntil: 'load' })
  // 等表格出第一行 / 或空态
  let firstRowAt = null
  try {
    await page.waitForFunction(() => {
      const rows = document.querySelectorAll('.el-table__body tbody tr')
      return rows.length > 0
    }, { timeout: 20000 })
    firstRowAt = Date.now() - t0
  } catch (e) {
    firstRowAt = -1
  }
  // 再等一会儿，让 filter-options 之类收尾
  await page.waitForTimeout(1500)
  const lastApi = marks.reduce((m, x) => Math.max(m, x.end || 0), 0)
  const result = { marks, firstRowAt, lastApi, total: Date.now() - t0 }
  await ctx.close()
  return result
}

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  const runs = []
  for (let i = 0; i < 3; i++) runs.push(await once(browser))
  await browser.close()

  console.log('=== 生产跟单首屏（3 次）===')
  for (const r of runs) {
    console.log(`  首行可见 ${r.firstRowAt}ms | 末个业务接口 ${r.lastApi}ms | 总耗时 ${r.total}ms`)
    for (const m of r.marks) {
      const name = m.url.split('/').pop().split('?')[0]
      console.log(`     ${name.padEnd(22)} start=${m.start}ms end=${m.end}ms`)
    }
  }
  const best = (k) => Math.min(...runs.map((r) => r[k]).filter((v) => v > 0))
  console.log(`\n最优：首行 ${best('firstRowAt')}ms | 末接口 ${best('lastApi')}ms`)
})().catch((e) => { console.error('FAILED', e.message); process.exit(1) })
