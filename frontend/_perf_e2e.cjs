// 端到端首屏测量 v2：用绝对时间戳记录网络瀑布。只读。
const { chromium } = require('playwright')

const BASE = process.env.BASE || 'http://127.0.0.1:5173'
const PATH = process.env.PATH_ || '/#/production/tracking'

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const page = await browser.newPage()

  const start = new Map()
  const done = []
  page.on('request', (req) => start.set(req, Date.now()))
  page.on('requestfinished', async (req) => {
    const t = start.get(req)
    if (!t) return
    const res = await req.response().catch(() => null)
    done.push({ url: req.url(), type: req.resourceType(), status: res ? res.status() : 0, t0: t, t1: Date.now() })
  })

  await page.addInitScript(() => localStorage.setItem('lq.currentEmployeeId', '23'))

  const T = Date.now()
  await page.goto(BASE + PATH, { waitUntil: 'load', timeout: 120000 })
  const loadMs = Date.now() - T

  let firstRow = null
  try {
    await page.waitForSelector('.el-table__row', { timeout: 90000 })
    firstRow = Date.now() - T
  } catch { firstRow = -1 }
  // 再等一会儿，抓住后续串行的请求
  await page.waitForTimeout(4000)

  console.log(`\n=== ${PATH} ===`)
  console.log(`load: ${loadMs}ms   列表首行: ${firstRow}ms   请求总数: ${done.length}\n`)

  const api = done.filter(r => r.url.includes('/api/')).sort((a, b) => a.t0 - b.t0)
  console.log('--- /api 请求瀑布（相对导航起点） ---')
  for (const r of api) {
    const u = r.url.replace(BASE, '').replace('http://127.0.0.1:8081', 'BE')
    console.log(`  起 ${String(r.t0 - T).padStart(5)}ms  耗 ${String(r.t1 - r.t0).padStart(5)}ms  ${r.status}  ${u.slice(0, 100)}`)
  }
  const lastApi = api.length ? Math.max(...api.map(r => r.t1)) - T : 0
  console.log(`  → 最后一个 API 完成于 ${lastApi}ms`)

  const mod = done.filter(r => r.url.includes('/src/'))
  console.log(`\n--- 前端模块请求：${mod.length} 个（vite dev，仅看数量与体积趋势） ---`)
  const biggest = mod.map(r => ({ u: r.url.replace(BASE, ''), ms: r.t1 - r.t0 })).sort((a, b) => b.ms - a.ms).slice(0, 8)
  console.log('  最慢 8 个模块：')
  for (const b of biggest) console.log(`    ${String(b.ms).padStart(5)}ms  ${b.u.slice(0, 90)}`)

  await page.screenshot({ path: '_perf_tracking.png' })
  await browser.close()
})().catch(e => { console.error('ERR', e.message); process.exit(1) })
