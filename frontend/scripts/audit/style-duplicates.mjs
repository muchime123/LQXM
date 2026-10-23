/**
 * C7 跨文件重复样式实现扫描器（只读）。
 *
 * 与 C6 的 style-scope.mjs 不同：C6 看「谁在改谁」（归属），C7 看「同一份视觉规则被写了几遍」（重复实现）。
 *
 * 四类：
 *   C7-A 完全重复   同一选择器 + 同一套声明出现在多个文件
 *   C7-B 轻微漂移   同一选择器出现在多个文件，但声明不一致（逐属性列出差异）
 *   C7-C 相似不同职责 交给人工结合组件用途判断，扫描器只提供候选项
 *   C7-D 重复引入   同一份样式文件被多个组件 @import，导致重复编译
 *
 * 排除：__tests__ / *.test.* / *.spec.*、node_modules、dist、构建产物目录、以及注释内容。
 *
 * 用法（frontend/ 下）：
 *   node scripts/audit/style-duplicates.mjs
 *   node scripts/audit/style-duplicates.mjs --json
 */
import fs from 'node:fs'
import path from 'node:path'

const ROOT = 'src'
const JSON_OUT = process.argv.includes('--json')

const SKIP_DIR = new Set(['node_modules', 'dist', '_build_check', '_build_verify', '__tests__', 'coverage'])
const isTestFile = n => /\.(test|spec)\.(ts|js|vue)$/.test(n) || /__(tests|mocks)__/.test(n)

const rules = []      // { file, kind, sel, decls: Map<prop, {value, important}>, line }
const imports = []    // { file, target, scoped }

const stripComments = t => t.replace(/\/\*[\s\S]*?\*\//g, ' ')

/** 极简 CSS 解析：跟踪花括号得到每条规则的最终选择器、直接声明与所在 @-规则上下文。 */
const parse = (file, text, lineOffset, scoped) => {
  const clean = stripComments(text)
  const stack = []
  let buf = ''
  let cur = null
  for (let i = 0; i < clean.length; i++) {
    const c = clean[i]
    if (c === '{') {
      const selRaw = buf.trim().replace(/\s+/g, ' ')
      stack.push({ sel: selRaw, isAt: selRaw.startsWith('@'), start: i })
      if (selRaw.startsWith('@import')) {
        const m = selRaw.match(/@import\s+['"]([^'"]+)['"]/)
        if (m) imports.push({ file, target: m[1], scoped })
      }
      buf = ''
      continue
    }
    if (c === '}') {
      stack.pop()
      if (cur) { rules.push(cur); cur = null }
      buf = ''
      continue
    }
    if (c === ';') {
      const decl = buf.trim()
      // @import 以分号结尾，不进 {}，必须在这里捕获
      if (/^@import/.test(decl)) {
        const m = decl.match(/@import\s+['"]([^'"]+)['"]/)
        if (m) imports.push({ file, target: m[1], scoped })
        buf = ''
        continue
      }
      const frame = stack[stack.length - 1]
      const inKeyframes = stack.some(s => /^@(-\w+-)?keyframes/.test(s.sel))
      if (frame && !frame.isAt && !inKeyframes) {
        const dm = decl.match(/^([a-zA-Z-]+)\s*:\s*(.+)$/)
        if (dm) {
          // 非 @ 框架依次拼接即为完整选择器（不要再叠加 frame，否则会重复自身）
          const selAbs = stack.filter(s => !s.isAt).map(s => s.sel).join(' ').replace(/\s+/g, ' ').trim()
          const line = lineOffset + clean.slice(0, i).split(/\r?\n/).length
          if (!cur || cur.file !== file || cur.sel !== selAbs) {
            if (cur) rules.push(cur)
            cur = { file, kind: scoped ? 'scoped' : (file.startsWith('src' + path.sep + 'styles') ? 'global' : 'component'), sel: selAbs, line, decls: {} }
          }
          const imp = /!\s*important/.test(dm[2])
          cur.decls[dm[1]] = { value: dm[2].replace(/!\s*important/g, '').trim(), important: imp }
        }
      }
      buf = ''
      continue
    }
    buf += c
  }
  if (cur) rules.push(cur)
}

const walk = d => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (SKIP_DIR.has(e.name) || e.name.startsWith('_') || e.name.startsWith('.')) continue
    const p = path.join(d, e.name)
    if (e.isDirectory()) walk(p)
    else if (e.name.endsWith('.css')) parse(p, fs.readFileSync(p, 'utf8'), 0, false)
    else if (e.name.endsWith('.vue')) {
      if (isTestFile(e.name)) continue
      const t = fs.readFileSync(p, 'utf8')
      const re = /<style\b([^>]*)>([\s\S]*?)<\/style>/g
      let m
      while ((m = re.exec(t))) {
        parse(p, m[2], t.slice(0, m.index).split(/\r?\n/).length - 1, /scoped/.test(m[1]))
      }
    }
  }
}
walk(ROOT)

const normSel = s => s.replace(/\s*,\s*/g, ', ').replace(/\s+/g, ' ').trim()
const declKey = d => Object.keys(d).sort().map(k => `${k}:${d[k].value}${d[k].important ? '!' : ''}`).join(';')

// ---------- 归入选择器维度（同一选择器在多少文件出现） ----------
const bySel = new Map()
for (const r of rules) {
  const s = normSel(r.sel)
  if (!s || s.startsWith('@')) continue
  const g = bySel.get(s) || []
  g.push(r)
  bySel.set(s, g)
}

const crossFile = [...bySel.entries()].filter(([, l]) => new Set(l.map(x => x.file)).size > 1)

/** C7-A 完全重复：声明集合完全相同 */
const exact = crossFile
  .map(([sel, l]) => {
    const keys = new Set(l.map(x => declKey(x.decls)))
    if (keys.size !== 1) return null
    return { sel, files: [...new Set(l.map(x => x.file))], count: l.length, decls: Object.keys(l[0].decls).length }
  })
  .filter(Boolean)
  .sort((a, b) => b.count - a.count || b.decls - a.decls)

/**
 * C7-B 轻微漂移：声明不一致，逐属性列出差异。
 *
 * 排除两类误报：
 *  1) 只定义自定义属性（令牌）的选择器——:root / [data-theme=…] 分文件分层定义令牌是正常的；
 *  2) 差异全部来自令牌名称（--*）的情况。
 */
const drift = crossFile
  .filter(([sel]) => sel !== ':root' && !/^\[data-theme[=\]]/.test(sel))
  .map(([sel, l]) => {
    const keys = new Set(l.map(x => declKey(x.decls)))
    if (keys.size < 2) return null
    const props = new Set()
    l.forEach(x => Object.keys(x.decls).forEach(p => props.add(p)))
    const diffs = []
    for (const p of props) {
      const vals = [...new Set(l.map(x => x.decls[p] ? x.decls[p].value : '(未定义)'))]
      if (vals.length > 1 && !p.startsWith('--')) diffs.push({ prop: p, values: vals })
    }
    if (!diffs.length) return null
    return { sel, files: [...new Set(l.map(x => x.file))], variants: keys.size, diffs }
  })
  .filter(Boolean)
  .sort((a, b) => b.diffs.length - a.diffs.length)

/** C7-D 重复引入：同一份样式文件被多个组件 import */
const importMap = new Map()
for (const im of imports) {
  const g = importMap.get(im.target) || { target: im.target, files: new Set(), scoped: new Set() }
  g.files.add(im.file)
  g.scoped.add(im.scoped)
  importMap.set(im.target, g)
}
const repeatedImports = [...importMap.values()]
  .filter(g => g.files.size > 1)
  .map(g => ({ target: g.target, count: g.files.size, files: [...g.files], scoped: [...g.scoped] }))
  .sort((a, b) => b.count - a.count)

// ---------- 面向 C7 优先核查对象的定向扫描 ----------
const isBiz = f => !f.startsWith('src' + path.sep + 'styles') && !/Lq[A-Z]/.test(path.basename(f))

const target = (re, label) => {
  const hits = rules.filter(r => re.test(r.sel))
  const byFile = new Map()
  for (const r of hits) {
    const g = byFile.get(r.file) || { file: r.file, kind: r.kind, n: 0, sels: [] }
    g.n++
    if (g.sels.length < 4) g.sels.push(r.sel.slice(0, 78))
    byFile.set(r.file, g)
  }
  return { label, total: hits.length, files: [...byFile.values()].sort((a, b) => b.n - a.n) }
}

const groups = [
  target(/\.drawer-actions|\.drawer-tabs|\.drawer-header|\.drawer-body|\.drawer-footer/, '抽屉外壳（头/体/尾/操作区）'),
  target(/\.el-table|\.el-pagination/, '表格与分页（业务侧改写 EP 结构）'),
  target(/\.el-dialog|\.el-drawer__/, '弹窗与抽屉（业务侧改写 EP 外壳）'),
  target(/(dialog|drawer|modal)-(head|header|foot|footer|actions|body)|panel-header|detail-actions|head-actions/, '业务自拟的标题栏/底部操作区')
]

if (JSON_OUT) {
  console.log(JSON.stringify({ rules: rules.length, exact, drift, repeatedImports, groups }, null, 2))
} else {
  console.log(`规则总数 ${rules.length}（已剔除测试文件与注释）\n`)

  console.log('=== C7-A 完全重复（同选择器 + 同声明，跨文件）===')
  console.log(`${exact.length} 组；重复次数最多的 15 组：`)
  for (const e of exact.slice(0, 15)) console.log(`  ×${String(e.count).padStart(2)}  ${e.sel.slice(0, 66)}   ${e.files.length} 文件 / ${e.decls} 条声明`)

  console.log('\n=== C7-B 轻微漂移（同选择器，声明不一致）===')
  console.log(`${drift.length} 组；差异属性最多的 12 组：`)
  for (const d of drift.slice(0, 12)) {
    console.log(`  ${d.sel.slice(0, 62)}   ${d.variants} 种写法 / ${d.files.length} 文件`)
    for (const df of d.diffs.slice(0, 5)) console.log(`      ${df.prop}: ${df.values.map(v => v.length > 30 ? v.slice(0, 30) + '…' : v).join('  |  ')}`)
  }

  console.log('\n=== C7-D 重复引入（同一份样式文件被多个组件引入）===')
  if (!repeatedImports.length) console.log('  无')
  for (const r of repeatedImports) {
    console.log(`  ${r.target}  被 ${r.count} 个组件引入${r.scoped.length === 1 ? (r.scoped[0] ? '（均在 scoped 中）' : '（均在全局块中）') : '（混用 scoped/全局）'}`)
    for (const f of r.files) console.log(`      ${f}`)
  }

  console.log('\n=== C7 优先核查对象（业务侧 vs 公共层分布）===')
  for (const g of groups) {
    console.log(`\n  ## ${g.label}  共 ${g.total} 条，涉及 ${g.files.length} 个文件`)
    const biz = g.files.filter(f => isBiz(f.file))
    if (biz.length) {
      console.log('     业务侧（非 styles/、非 Lq* 公共组件）：')
      for (const f of biz.slice(0, 10)) console.log(`       ${String(f.n).padStart(3)} 条 [${f.kind}] ${f.file}`)
    }
    const infra = g.files.filter(f => !isBiz(f.file))
    if (infra.length) {
      console.log('     公共层：')
      for (const f of infra.slice(0, 8)) console.log(`       ${String(f.n).padStart(3)} 条 [${f.kind}] ${f.file}`)
    }
  }
}
