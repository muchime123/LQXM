/**
 * CSS 自定义属性（令牌）用法扫描器
 *
 * 解决什么问题：`var(--x)` 引用了不存在的令牌时，浏览器**不报错**——
 * 没有 fallback 时整条声明直接被丢弃，有 fallback 时用 fallback 静默顶替。
 * 两种情况下源码看起来都"正常"，只有交叉比对才算得出来。
 *
 * 定义来源（三类都要算，否则会误报）：
 *   1. CSS 声明          --x: value
 *   2. 内联/JS 对象键     :style="{ '--x': v }" / { '--x': v }
 *   3. 运行时 setProperty  el.style.setProperty('--x', v)
 * 第 2/3 类属于"动态注入"，静态看是缺失、运行时有值，不能当缺陷修。
 *
 * 用法（在 frontend/ 下）：
 *   node scripts/audit/token-usage.mjs            # 人读摘要
 *   node scripts/audit/token-usage.mjs --json     # 机器可读
 *
 * 输出：
 *   - 无 fallback 且未定义 → 声明会失效，按 noFb 次数倒序
 *   - 有 fallback 且未定义 → 实际渲染的是 fallback 值（命名漂移），附取值分布
 */
import fs from 'node:fs'
import path from 'node:path'

const ROOT = process.argv.includes('--all') ? '.' : 'src'
const JSON_OUT = process.argv.includes('--json')
const EXTS = /\.(css|vue|ts|js|scss|html)$/

const files = []
const walk = d => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === 'dist' || e.name.startsWith('_')) continue
    const p = path.join(d, e.name)
    if (e.isDirectory()) walk(p)
    else if (EXTS.test(e.name)) files.push(p)
  }
}
walk(ROOT)
// index.html 里也可能定义根变量
for (const extra of ['index.html']) if (fs.existsSync(extra)) files.push(extra)

const used = new Map() // name -> { count, noFb, fallbacks: Map, sites: [{file,line,fb}] }
const defined = new Set()

for (const file of files) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
  lines.forEach((text, idx) => {
    for (const m of text.matchAll(/var\(\s*(--[a-zA-Z0-9-]+)\s*/g)) {
      const name = m[1]
      // 从 var( 之后开始，按括号配对取出完整实参，避免 rgba()/嵌套 var() 被截断
      const start = m.index + m[0].length
      let depth = 1
      let end = start
      while (end < text.length && depth > 0) {
        if (text[end] === '(') depth++
        else if (text[end] === ')') depth--
        if (depth === 0) break
        end++
      }
      const rest = text.slice(start, end).trim()
      const fb = rest.startsWith(',') ? rest.slice(1).trim() : null
      const rec = used.get(name) || { count: 0, noFb: 0, fallbacks: new Map(), sites: [] }
      rec.count++
      if (fb === null) rec.noFb++
      else rec.fallbacks.set(fb, (rec.fallbacks.get(fb) || 0) + 1)
      rec.sites.push({ file, line: idx + 1, fallback: fb })
      used.set(name, rec)
    }
    // 定义：CSS 声明、内联 style、JS 对象键
    for (const m of text.matchAll(/(--[a-zA-Z0-9-]+)\s*:/g)) defined.add(m[1])
    for (const m of text.matchAll(/(--[a-zA-Z0-9-]+)['"]\s*:/g)) defined.add(m[1])
    for (const m of text.matchAll(/setProperty\(\s*['"](--[a-zA-Z0-9-]+)/g)) defined.add(m[1])
  })
}

const missing = [...used.entries()]
  .filter(([k]) => !defined.has(k))
  .map(([name, rec]) => ({
    name,
    count: rec.count,
    noFb: rec.noFb,
    fallbacks: [...rec.fallbacks.entries()].sort((a, b) => b[1] - a[1]).map(([v, n]) => ({ value: v, n })),
    noFbSites: rec.sites.filter(s => s.fallback === null).map(s => `${s.file}:${s.line}`),
    // 有 fallback 时给出全部点位与各自取值，供逐处映射（B2/B3）使用
    sites: rec.sites.map(s => `${s.file}:${s.line}${s.fallback === null ? '' : `  fb=${s.fallback}`}`),
    files: [...new Set(rec.sites.map(s => s.file))]
  }))
  .sort((a, b) => b.noFb - a.noFb || b.count - a.count)

const hard = missing.filter(m => m.noFb > 0)
const soft = missing.filter(m => m.noFb === 0)

if (JSON_OUT) {
  console.log(JSON.stringify({ scanned: files.length, missing, hard, soft }, null, 2))
} else {
  console.log(`scanned=${files.length} files, usedTokens=${used.size}, definedTokens=${defined.size}`)
  console.log(`missing=${missing.length}  无fallback=${hard.length}  有fallback=${soft.length}\n`)
  console.log('=== 无 fallback（声明会失效）===')
  for (const m of hard) console.log(`${m.name}  noFb=${m.noFb}/${m.count}\n    ${m.noFbSites.join('\n    ')}`)
  console.log('\n=== 有 fallback（实际渲染 fallback 值）===')
  for (const m of soft) {
    const dist = m.fallbacks.map(f => `${f.value}×${f.n}`).join(', ')
    console.log(`${m.name}  x${m.count}  fallback: ${dist}`)
  }
}
