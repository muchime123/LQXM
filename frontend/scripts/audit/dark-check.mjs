/**
 * 深色主题令牌解析校验 + 页面截图留档
 *
 * 用途：确认 data-theme="dark" 下关键令牌真的解析为深色值（而不是静默回落浅色）。
 * 之所以需要它：深色主题是"部分子集"，缺令牌时浏览器不会报错，只会回落成浅色值，
 * 只有逐条比对计算值才能发现。
 *
 * 运行（在 frontend/ 下，需先启动 dev server）：
 *   node scripts/audit/dark-check.mjs                 # 深色
 *   THEME=light node scripts/audit/dark-check.mjs     # 浅色（tokenChecks 会大量失败，属预期）
 *
 * 输出：JSON 到 stdout；截图到 AUDIT_OUT（默认 _audit_shots/，已在 .gitignore 中）。
 * 判定：tokenFailures 为空数组即通过。
 */
import { chromium } from '@playwright/test'
import path from 'node:path'

const BASE = process.env.AUDIT_BASE || 'http://127.0.0.1:5173'
const OUT = path.resolve(process.cwd(), process.env.AUDIT_OUT || '_audit_shots')

const expectDark = {
  '--color-text-on-overlay': '#ffffff',
  '--color-primary-hover': '#2dd4bf',
  '--color-primary-bg-light': 'rgba(20, 184, 166, 0.06)',
  '--color-success': '#22c55e',
  '--color-warning': '#f59e0b',
  '--color-danger': '#ef4444',
  '--color-info': '#3b82f6',
  '--color-purple': '#8b5cf6',
  '--color-purple-bg': 'rgba(139, 92, 246, 0.15)',
  '--color-purple-border': 'rgba(139, 92, 246, 0.3)',
  '--ep-primary-filled': '#14b8a6',
  '--shadow-xs': '0 1px 2px rgba(0, 0, 0, 0.2)',
  '--shadow-drawer': '0 24px 72px rgba(0, 0, 0, 0.5)'
}

const pages = [
  ['customers', '/customers'],
  ['products', '/products'],
  ['production-tracking', '/production-tracking']
]

// 注意：注入元素只能命中**全局样式表**里的类。组件 <style scoped> 的选择器带
// [data-v-xxx] 属性，注入的裸元素不会命中，读到的将是继承色（如 body 文字色），
// 看到"颜色不对"先怀疑未命中，而不是规则失效。需要覆盖 scoped 组件时，
// 请在真实页面里定位元素，用 primary-btn-probe.mjs 的口径测。
const CLASS_PROBE = ['hero-image-count', 'thumb-drop-label', 'asset-website-hidden-badge', 'asset-dup-badge']

const browser = await chromium.launch(
  process.env.PW_CHANNEL === 'none'
    ? { headless: true }
    : { channel: 'msedge', headless: true }
).catch(() => chromium.launch({ headless: true }))

const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'zh-CN' })
await ctx.addInitScript(t => {
  localStorage.setItem('lq.currentEmployeeId', '23')
  localStorage.setItem('theme', t)
}, process.env.THEME || 'dark')
const page = await ctx.newPage()

const report = { tokenChecks: null, pages: [], classProbe: {} }

for (const [name, route] of pages) {
  const entry = { name }
  try {
    await page.goto(`${BASE}/#${route}`, { waitUntil: 'domcontentloaded', timeout: 45000 })
    await page.waitForTimeout(2500)
    entry.themeAttr = await page.evaluate(() => document.documentElement.getAttribute('data-theme'))
    if (!report.tokenChecks) {
      const resolved = await page.evaluate(names => {
        const cs = getComputedStyle(document.documentElement)
        return Object.fromEntries(names.map(n => [n, cs.getPropertyValue(n).trim()]))
      }, Object.keys(expectDark))
      report.tokenChecks = Object.fromEntries(
        Object.entries(expectDark).map(([k, want]) => [k, { want, got: resolved[k], ok: resolved[k] === want }])
      )
    }
    if (!report.classProbe[entry.themeAttr || 'unknown']) {
      report.classProbe[entry.themeAttr || 'unknown'] = await page.evaluate(names => {
        const host = document.createElement('div')
        host.style.cssText = 'position:fixed;top:-9999px;left:0'
        document.body.appendChild(host)
        const out = {}
        for (const n of names) {
          const el = document.createElement('span')
          el.className = n
          el.textContent = 'x'
          host.appendChild(el)
          const cs = getComputedStyle(el)
          out[n] = { color: cs.color, backgroundColor: cs.backgroundColor }
        }
        host.remove()
        return out
      }, CLASS_PROBE)
    }
    entry.probe = await page.evaluate(() => {
      const pick = sel => {
        const el = document.querySelector(sel)
        if (!el) return null
        const s = getComputedStyle(el)
        return { selector: sel, color: s.color, backgroundColor: s.backgroundColor }
      }
      return [pick('.btn-primary'), pick('.btn-sm.primary'), pick('.tb-btn.primary'), pick('.el-button--primary')].filter(Boolean)
    })
    await page.screenshot({ path: path.join(OUT, `dark-${name}.png`) })
    entry.ok = true
  } catch (e) {
    entry.ok = false
    entry.error = String(e).slice(0, 200)
  }
  report.pages.push(entry)
}

await browser.close()
const failed = Object.entries(report.tokenChecks || {}).filter(([, v]) => !v.ok)
console.log(JSON.stringify({ ...report, tokenFailures: failed.map(([k]) => k) }, null, 2))
if (failed.length) process.exitCode = 1
