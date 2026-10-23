/**
 * 前景/背景对比度探针（WCAG 2.1，浅色 + 深色双主题）
 *
 * 解决什么问题：`color: var(--color-surface)` 这类"把背景令牌当前景色"的写法，
 * 在浅色下恰好等于白色、看不出问题，只有量出真实计算色与对比度才能判断是否真缺陷。
 * 实例：深色下主色实心按钮曾算得 5.88:1（达标），改白反而掉到 2.49:1——
 * 结论与"想当然改成白色"相反，所以必须实测而不是靠推断。
 *
 * 两个阶段：
 *  A. SOLID_TARGETS：固定背景的对比度，含半透明前景/背景对父级的合成
 *  B. IMAGE_TARGETS：文字压在**真实图片**上的情况。先把文字设为透明再截取元素区域，
 *     在页面内用 canvas 解码取亮度分位数，判断白字在各明暗底图上是否可读。
 *     注意：这些元素多在不带参数的落地页看不到（如素材面板需先选规格），
 *     此时会输出 NOT FOUND，属正常；判定时请配合"纯白/纯黑"最坏情况包络。
 *
 * 运行（在 frontend/ 下，需先启动 dev server）：
 *   node scripts/audit/primary-btn-probe.mjs
 *
 * 判定门槛：普通文字 4.5:1，大字（≥24px 或 ≥18.66px 且加粗）与图标控件 3:1。
 */
import { chromium } from '@playwright/test'

const BASE = process.env.AUDIT_BASE || 'http://127.0.0.1:5173'

const SOLID_TARGETS = [
  ['/packaging', ['.btn-sm.primary'], 'A 主色实心（回归样本：新增规格按钮）'],
  ['/certificates', ['.portal-pay', '.portal-search', '.portal-query'], 'A/E 门户按钮默认态']
]

const IMAGE_TARGETS = [
  ['/company', ['.asset-ext-corner', '.htb-btn'], '公司品牌资产：角标 + hover 工具条'],
  ['/packaging', ['.asset-ext-corner', '.hero-image-count'], '包装资产：角标/主图计数'],
  ['/products', ['.hero-image-count'], '产品主图计数']
]

const PROBE_JS = selectors => {
  const parse = c => {
    const m = c.match(/rgba?\(([^)]+)\)/)
    if (!m) return null
    const p = m[1].split(',').map(s => parseFloat(s.trim()))
    return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 }
  }
  const over = (fg, bg) => {
    const a = fg.a
    return { r: fg.r * a + bg.r * (1 - a), g: fg.g * a + bg.g * (1 - a), b: fg.b * a + bg.b * (1 - a), a: 1 }
  }
  const lum = c => {
    const f = v => {
      v /= 255
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b)
  }
  const ratio = (a, b) => {
    const l1 = lum(a)
    const l2 = lum(b)
    return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05)
  }
  // 逐级向上合成背景，直到不透明
  const effectiveBg = el => {
    let acc = null
    let node = el
    while (node && node.parentElement) {
      const bg = parse(getComputedStyle(node).backgroundColor)
      if (bg && bg.a > 0) {
        acc = acc ? over(acc, bg) : bg
        if (acc.a >= 0.999) return acc
      }
      node = node.parentElement
    }
    const root = parse(getComputedStyle(document.body).backgroundColor) || { r: 255, g: 255, b: 255, a: 1 }
    return acc ? over(acc, root) : root
  }
  const hits = []
  for (const sel of selectors) {
    const el = document.querySelector(sel)
    if (!el) continue
    const cs = getComputedStyle(el)
    const fg = parse(cs.color)
    const bg = effectiveBg(el)
    if (!fg) continue
    const fgOn = fg.a < 1 ? over(fg, bg) : fg
    const fs = parseFloat(cs.fontSize)
    const isLarge = fs >= 24 || (fs >= 18.66 && parseInt(cs.fontWeight, 10) >= 700)
    const r = ratio(fgOn, bg)
    hits.push({
      selector: sel,
      text: (el.textContent || '').trim().slice(0, 12),
      color: cs.color,
      bg: `rgb(${Math.round(bg.r)},${Math.round(bg.g)},${Math.round(bg.b)})`,
      fontSize: fs,
      contrast: Math.round(r * 100) / 100,
      threshold: isLarge ? 3 : 4.5,
      pass: r >= (isLarge ? 3 : 4.5)
    })
  }
  return hits
}

const LUM_JS = async ({ b64 }) => {
  const img = new Image()
  img.src = 'data:image/png;base64,' + b64
  await img.decode()
  const c = document.createElement('canvas')
  c.width = img.width
  c.height = img.height
  const ctx = c.getContext('2d')
  ctx.drawImage(img, 0, 0)
  const d = ctx.getImageData(0, 0, c.width, c.height).data
  const f = v => {
    v /= 255
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)
  }
  const lums = []
  for (let i = 0; i < d.length; i += 4) {
    if (d[i + 3] < 200) continue
    lums.push(0.2126 * f(d[i]) + 0.7152 * f(d[i + 1]) + 0.0722 * f(d[i + 2]))
  }
  lums.sort((a, b) => a - b)
  const q = p => lums[Math.min(lums.length - 1, Math.floor(p * (lums.length - 1)))]
  return { n: lums.length, min: lums[0], p05: q(0.05), p50: q(0.5), p95: q(0.95), max: lums[lums.length - 1] }
}

const WHITE_LUM = 1
const DARK_SURFACE_LUM = 0.0217 // #1e293b
const contrastWith = (bgLum, fgLum = WHITE_LUM) =>
  Math.round(((Math.max(fgLum, bgLum) + 0.05) / (Math.min(fgLum, bgLum) + 0.05)) * 100) / 100

const launch = async () => {
  try {
    return await chromium.launch({ channel: 'msedge', headless: true })
  } catch {
    return chromium.launch({ headless: true })
  }
}

const browser = await launch()
const report = { solid: [], image: [] }

for (const theme of ['light', 'dark']) {
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
  await ctx.addInitScript(t => {
    localStorage.setItem('lq.currentEmployeeId', '23')
    localStorage.setItem('theme', t)
  }, theme)
  const page = await ctx.newPage()

  for (const [route, selectors, label] of SOLID_TARGETS) {
    const entry = { theme, route, label, samples: [] }
    try {
      await page.goto(`${BASE}/#${route}`, { waitUntil: 'domcontentloaded', timeout: 45000 })
      await page.waitForTimeout(2200)
      entry.themeAttr = await page.evaluate(() => document.documentElement.getAttribute('data-theme'))
      entry.samples = await page.evaluate(PROBE_JS, selectors)
    } catch (e) {
      entry.error = String(e).slice(0, 140)
    }
    report.solid.push(entry)
  }

  for (const [route, selectors, label] of IMAGE_TARGETS) {
    const entry = { theme, route, label, samples: [] }
    try {
      await page.goto(`${BASE}/#${route}`, { waitUntil: 'domcontentloaded', timeout: 45000 })
      await page.waitForTimeout(2200)
      for (const sel of selectors) {
        const loc = page.locator(sel).first()
        if ((await loc.count()) === 0) {
          entry.samples.push({ selector: sel, found: false })
          continue
        }
        if (sel === '.htb-btn') {
          const card = page.locator('.asset-preview-card, .asset-card, .card').first()
          if ((await card.count()) > 0) await card.hover().catch(() => {})
          await page.waitForTimeout(400)
        }
        // 隐藏文字与图标，只留底色，避免文字像素污染亮度统计
        await loc.evaluate(el => {
          el.dataset.probeColor = el.style.color
          el.style.color = 'transparent'
          el.querySelectorAll('svg, span, em').forEach(n => (n.style.visibility = 'hidden'))
        })
        await page.waitForTimeout(120)
        let shot = null
        try {
          shot = await loc.screenshot({ timeout: 8000 })
        } catch (e) {
          entry.samples.push({ selector: sel, found: true, error: String(e).slice(0, 90) })
          await loc.evaluate(el => {
            el.style.color = el.dataset.probeColor || ''
            el.querySelectorAll('svg, span, em').forEach(n => (n.style.visibility = ''))
          })
          continue
        }
        const l = await page.evaluate(LUM_JS, { b64: shot.toString('base64') })
        await loc.evaluate(el => {
          el.style.color = el.dataset.probeColor || ''
          el.querySelectorAll('svg, span, em').forEach(n => (n.style.visibility = ''))
        })
        entry.samples.push({
          selector: sel,
          found: true,
          px: l.n,
          bgLum: { p05: +l.p05.toFixed(3), p50: +l.p50.toFixed(3), p95: +l.p95.toFixed(3), max: +l.max.toFixed(3) },
          whiteContrast: {
            p05: contrastWith(l.p05),
            p50: contrastWith(l.p50),
            p95: contrastWith(l.p95),
            max: contrastWith(l.max)
          },
          darkTextContrast: {
            p05: contrastWith(l.p05, DARK_SURFACE_LUM),
            p50: contrastWith(l.p50, DARK_SURFACE_LUM),
            p95: contrastWith(l.p95, DARK_SURFACE_LUM),
            max: contrastWith(l.max, DARK_SURFACE_LUM)
          }
        })
      }
    } catch (e) {
      entry.error = String(e).slice(0, 140)
    }
    report.image.push(entry)
  }
  await ctx.close()
}

await browser.close()
console.log(JSON.stringify(report, null, 2))
