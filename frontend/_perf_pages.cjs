// 多页面首屏对照测量：判断慢是全局还是跟单特有。只读。
const { chromium } = require('playwright')

const BASE = 'http://127.0.0.1:5173'
const PAGES = (process.env.PAGES || '/#/production-tracking,/#/customers,/#/employees,/#/intellectual-properties').split(',')
const ROUNDS = Number(process.env.ROUNDS || 2)

;(async () => {
  const browser = await chromium.launch({ channel: 'msedge' })
  const out = []

  for (const PATH of PAGES) {
    const rows = []
    for (let i = 0; i < ROUNDS; i++) {
      const page = await browser.newPage()
      const start = new Map()
      const done = []
      page.on('request', (req) => start.set(req, Date.now()))
      page.on('requestfinished', async (req) => {
        const t = start.get(req)
        if (!t) return
        const res = await req.response().catch(() => null)
        done.push({ url: req.url(), t0: t, t1: Date.now(), status: res ? res.status() : 0 })
      })
      await page.addInitScript(() => localStorage.setItem('lq.currentEmployeeId', '23'))

      const T = Date.now()
      await page.goto(BASE + PATH, { waitUntil: 'load', timeout: 120000 })
      const loadMs = Date.now() - T
      let firstRow = -1
      try {
        await page.waitForSelector('.el-table__row, .el-empty, .cd-kv', { timeout: 60000 })
        firstRow = Date.now() - T
      } catch { firstRow = -1 }
      await page.waitForTimeout(3000)

      const api = done.filter(r => r.url.includes('/api/')).sort((a, b) => a.t0 - b.t0)
      const lastApi = api.length ? Math.max(...api.map(r => r.t1)) - T : 0
      rows.push({ loadMs, firstRow, apiCount: api.length, lastApi, api })
      await page.close()
    }
    const best = rows.reduce((a, b) => (a.lastApi <= b.lastApi ? a : b))
    out.push({ PATH, best, all: rows })
  }

  console.log(`\n${'页面'.padEnd(34)} ${'load'.padStart(7)} ${'首屏可见'.padStart(9)} ${'API数'.padStart(6)} ${'末API完成'.padStart(10)}`)
  for (const o of out) {
    console.log(`${o.PATH.padEnd(34)} ${String(o.best.loadMs).padStart(6)}ms ${String(o.best.firstRow).padStart(8)}ms ${String(o.best.apiCount).padStart(6)} ${String(o.best.lastApi).padStart(9)}ms`)
  }

  console.log('\n--- 各页 API 明细（最快那一轮） ---')
  for (const o of out) {
    console.log(`\n[${o.PATH}]`)
    for (const r of o.best.api) {
      const u = r.url.replace(BASE, '').replace('http://127.0.0.1:8081', 'BE')
      console.log(`  ${String(r.t0 - 0).padStart(6)}  耗${String(r.t1 - r.t0).padStart(5)}ms  ${r.status}  ${u.slice(0, 95)}`)
    }
  }

  await browser.close()
})().catch(e => { console.error('ERR', e.message); process.exit(1) })
