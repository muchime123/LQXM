/**
 * C7-3 只读审计 · 第二部分：生产构建影响（按引入者 scope id 隔离统计，避免把其他组件的同名选择器算进来）。
 *
 * 判定思路：引入了该文件的组件会得到文件的全部规则，其 data-v 作用域号会附在绝大多数规则上。
 * 因此先找出「承载该文件的 scope id」，再只统计这些 id 带来的重复，排除其他组件自带的同名选择器。
 */
import fs from 'node:fs'
import path from 'node:path'

const BUILT_DIR = 'dist/_c7c3/assets'
const TARGETS = ['media-account-view.css', 'packaging-detail-drawer.css']

function parseRules(css) {
  const src = css.replace(/\/\*[\s\S]*?\*\//g, ' ')
  const rules = []
  let buf = ''
  const stack = []
  for (let i = 0; i < src.length; i++) {
    const c = src[i]
    if (c === '{') { stack.push({ sel: buf.trim().replace(/\s+/g, ' '), body: '' }); buf = '' }
    else if (c === '}') {
      const top = stack.pop()
      if (!top) continue
      if (top.sel.startsWith('@')) { for (const r of parseRules(top.body)) rules.push({ sel: r.sel, decls: r.decls }) }
      else if (top.sel) rules.push({ sel: top.sel, decls: top.body.split(';').filter(s => s.trim()).length })
      buf = ''
    } else { if (stack.length) stack[stack.length - 1].body += c; else buf += c }
  }
  return rules
}

const esc = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const files = fs.readdirSync(BUILT_DIR).filter(f => f.endsWith('.css'))
const chunks = files.map(f => ({ name: f, css: fs.readFileSync(path.join(BUILT_DIR, f), 'utf8'), size: fs.statSync(path.join(BUILT_DIR, f)).size }))
const all = chunks.map(c => c.css).join('')
const totalBytes = chunks.reduce((s, c) => s + c.size, 0)
console.log(`构建产物 CSS：${files.length} 个文件，合计 ${(totalBytes / 1024).toFixed(1)} KB`)

// 收集所有 data-v 作用域号
const scopeIds = new Set([...all.matchAll(/data-v-([0-9a-f]+)/g)].map(m => m[1]))

for (const target of TARGETS) {
  const rules = parseRules(fs.readFileSync(`src/styles/${target}`, 'utf8'))
  const selLists = rules.map(r => r.sel.split(',').map(s => s.trim()))

  // 每个 scope id 承载了该文件多少条规则
  const idCoverage = new Map()
  for (const id of scopeIds) {
    let hit = 0
    for (const list of selLists) {
      if (list.some(s => all.includes(`${s}[data-v-${id}]`))) hit++
    }
    idCoverage.set(id, hit)
  }
  const threshold = rules.length * 0.5
  const carriers = [...idCoverage.entries()].filter(([, n]) => n >= threshold).sort((a, b) => b[1] - a[1])

  // 这些规则落在哪些产物文件里
  const carrierChunks = new Set()
  let spanTotal = 0
  let spanOnce = 0
  for (let i = 0; i < rules.length; i++) {
    const id = carriers[0]?.[0]
    if (!id) continue
    const probe = `${selLists[i][0]}[data-v-${id}]`
    const at = all.indexOf(probe)
    if (at < 0) continue
    const braceOpen = all.indexOf('{', at + probe.length)
    const braceClose = all.indexOf('}', braceOpen)
    const len = braceClose > 0 ? braceClose - at + 1 : probe.length
    spanTotal += len * carriers.length
    spanOnce += len
    for (const c of chunks) if (c.css.includes(probe)) carrierChunks.add(c.name)
  }

  console.log(`\n#### ${target}`)
  console.log(`   源文件规则数：${rules.length}`)
  console.log(`   认定为「引入者」的 scope id 数量：${carriers.length}（各自承载规则数：${carriers.map(([, n]) => n).join(', ')}）`)
  console.log(`   这些规则落在产物文件：${[...carrierChunks].join(', ') || '(未定位)'}`)
  console.log(`   在最终 CSS 中占用：${(spanTotal / 1024).toFixed(1)} KB`)
  console.log(`   其中重复部分：${((spanTotal - spanOnce) / 1024).toFixed(1)} KB（占全部 CSS ${(((spanTotal - spanOnce) / totalBytes) * 100).toFixed(2)}%）`)
  console.log(`   即：同一份规则在最终产物里被输出了 ${carriers.length} 份，${carriers.length - 1} 份是重复的`)
}
