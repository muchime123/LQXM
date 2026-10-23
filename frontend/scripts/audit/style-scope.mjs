/**
 * C6 样式职责冲突扫描器（只读）。
 *
 * 关注「谁在改谁」：全局 CSS 是否伸进了业务组件、组件 scoped 样式是否重复实现公共规范、
 * :deep() / !important / Element Plus 内部选择器是否造成归属混乱。
 *
 * 这些写法本身不是错误，因此扫描器只做统计与定位，判断是否有真实冲突仍需人工 + 实测。
 *
 * 用法（frontend/ 下）：
 *   node scripts/audit/style-scope.mjs
 *   node scripts/audit/style-scope.mjs --json
 */
import fs from 'node:fs'
import path from 'node:path'

const ROOT = 'src'
const JSON_OUT = process.argv.includes('--json')

const GLOBAL_DIR = path.join('src', 'styles')
const rules = [] // { file, kind, sel, props: [[prop, value]], important, deep }

const addUnit = (file, text, lineOffset, scoped) => {
  const clean = text.replace(/\/\*[\s\S]*?\*\//g, s => s.replace(/[^\n]/g, ' '))
  const stack = []
  let buf = ''
  for (let i = 0; i < clean.length; i++) {
    const c = clean[i]
    if (c === '{') {
      const selRaw = buf.trim().replace(/\s+/g, ' ')
      stack.push({ sel: selRaw, isAt: selRaw.startsWith('@'), line: lineOffset + clean.slice(0, i).split(/\r?\n/).length })
      buf = ''
      continue
    }
    if (c === '}') {
      // 块结束时解析其直接声明
      const top = stack.pop()
      buf = ''
      void top
      continue
    }
    if (c === ';') {
      const decl = buf.trim()
      const top = stack[stack.length - 1]
      if (top && !stack.some(s => s.isAt && /^@(keyframes|font-face|-webkit-keyframes)/.test(s.sel))) {
        const dm = decl.match(/^([a-zA-Z-]+)\s*:\s*(.+)$/)
        if (dm) {
          const prev = rules[rules.length - 1]
          const selKey = stack.filter(s => !s.isAt).map(s => s.sel).join(' ')
          if (!prev || prev.file !== file || prev.selKey !== selKey || prev.line !== top.line) {
            rules.push({
              file, selKey, line: top.line, kind: scoped ? 'scoped' : (file.startsWith(GLOBAL_DIR) ? 'global' : 'component'),
              props: [], important: 0, deep: /:deep\(|::v-deep|>>>/.test(selKey)
            })
          }
          const cur = rules[rules.length - 1]
          cur.props.push([dm[1], dm[2]])
          if (/!\s*important/.test(dm[2])) cur.important++
        }
      }
      buf = ''
      continue
    }
    buf += c
  }
}

const walk = d => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === 'dist' || e.name.startsWith('_')) continue
    const p = path.join(d, e.name)
    if (e.isDirectory()) walk(p)
    else if (e.name.endsWith('.css')) addUnit(p, fs.readFileSync(p, 'utf8'), 0, false)
    else if (e.name.endsWith('.vue')) {
      const t = fs.readFileSync(p, 'utf8')
      const re = /<style\b([^>]*)>([\s\S]*?)<\/style>/g
      let m
      while ((m = re.exec(t))) {
        addUnit(p, m[2], t.slice(0, m.index).split(/\r?\n/).length - 1, /scoped/.test(m[1]))
      }
    }
  }
}
walk(ROOT)

const norm = s => s.replace(/\s*,\s*/g, ', ').replace(/\s+/g, ' ').trim()
const isEp = s => /\bel-[a-z0-9-]+/.test(s)

// ---------- 1. Element Plus 选择器：谁在改组件库内部样式 ----------
const epRules = rules.filter(r => isEp(r.selKey))
const epByFile = new Map()
for (const r of epRules) {
  const g = epByFile.get(r.file) || { file: r.file, kind: r.kind, n: 0, important: 0, deep: 0, samples: [] }
  g.n++
  g.important += r.important
  if (r.deep) g.deep++
  if (g.samples.length < 3) g.samples.push(r.selKey)
  epByFile.set(r.file, g)
}
const epFiles = [...epByFile.values()].sort((a, b) => b.n - a.n)

// ---------- 2. !important 分布 ----------
const impRules = rules.filter(r => r.important > 0)
const impByFile = new Map()
for (const r of impRules) {
  const g = impByFile.get(r.file) || { file: r.file, kind: r.kind, n: 0, samples: [] }
  g.n += r.important
  if (g.samples.length < 3) g.samples.push(r.selKey)
  impByFile.set(r.file, g)
}
const impFiles = [...impByFile.values()].sort((a, b) => b.n - a.n)

// ---------- 3. :deep() 分布 ----------
const deepByFile = new Map()
for (const r of rules.filter(r => r.deep)) {
  const g = deepByFile.get(r.file) || { file: r.file, kind: r.kind, n: 0, samples: [] }
  g.n++
  if (g.samples.length < 3) g.samples.push(r.selKey)
  deepByFile.set(r.file, g)
}
const deepFiles = [...deepByFile.values()].sort((a, b) => b.n - a.n)

// ---------- 4. 同选择器跨文件重复定义（重复实现 / 优先级隐患）----------
const selIndex = new Map() // norm(sel) -> [{file,line,kind,props}]
for (const r of rules) {
  const key = norm(r.selKey)
  const g = selIndex.get(key) || []
  g.push(r)
  selIndex.set(key, g)
}
const dupSelectors = [...selIndex.entries()]
  .filter(([, list]) => new Set(list.map(x => x.file)).size > 1)
  .map(([sel, list]) => {
    const files = [...new Set(list.map(x => x.file))]
    // 属性重叠才可能是真实重复实现
    const propSets = list.map(x => new Set(x.props.map(p => p[0])))
    let overlap = 0
    if (propSets.length > 1) {
      for (let i = 1; i < propSets.length; i++) for (const p of propSets[i]) if (propSets[0].has(p)) overlap++
    }
    return { sel, files, count: list.length, overlap, sites: list.map(x => `${x.file}:${x.line}[${x.kind}]`) }
  })
  .filter(d => d.overlap > 0)
  .sort((a, b) => b.overlap - a.overlap)

// ---------- 5. 所有权：全局样式表的类选择器，是否又在组件 scoped 内联重复实现 ----------
const globalClasses = new Set()
for (const r of rules) {
  if (r.kind !== 'global') continue
  for (const m of r.selKey.matchAll(/\.([a-zA-Z0-9_-]+)/g)) globalClasses.add(m[1])
}
const scopedOverride = new Map()
for (const r of rules) {
  if (r.kind !== 'scoped') continue
  for (const m of r.selKey.matchAll(/\.([a-zA-Z0-9_-]+)/g)) {
    if (!globalClasses.has(m[1])) continue
    const g = scopedOverride.get(m[1]) || { cls: m[1], comps: new Set() }
    g.comps.add(r.file)
    scopedOverride.set(m[1], g)
  }
}
const ownership = [...scopedOverride.values()].sort((a, b) => b.comps.size - a.comps.size)

if (JSON_OUT) {
  console.log(JSON.stringify({ rules: rules.length, epFiles, impFiles, deepFiles, dupSelectors, ownership }, null, 2))
} else {
  console.log(`规则总数 ${rules.length}（global ${rules.filter(r => r.kind === 'global').length} / component ${rules.filter(r => r.kind === 'component').length} / scoped ${rules.filter(r => r.kind === 'scoped').length}）\n`)

  console.log('=== C6-1 改写 Element Plus 内部选择器 ===')
  console.log(`涉及 ${epFiles.length} 个文件，其中：全局表 ${epFiles.filter(f => f.kind === 'global').length} / 组件非 scoped ${epFiles.filter(f => f.kind === 'component').length} / 组件 scoped ${epFiles.filter(f => f.kind === 'scoped').length}`)
  for (const f of epFiles.slice(0, 15)) {
    console.log(`  ${String(f.n).padStart(4)} 条 [${f.kind.padEnd(9)}] ${f.file}${f.important ? `  含 !important×${f.important}` : ''}${f.deep ? `  含 :deep×${f.deep}` : ''}`)
  }

  console.log('\n=== C6-2 !important 分布 ===')
  console.log(`合计 ${impFiles.reduce((s, f) => s + f.n, 0)} 处，涉及 ${impFiles.length} 个文件`)
  for (const f of impFiles.slice(0, 15)) console.log(`  ${String(f.n).padStart(4)} 处 [${f.kind.padEnd(9)}] ${f.file}`)

  console.log('\n=== C6-3 :deep() 分布 ===')
  console.log(`合计 ${deepFiles.reduce((s, f) => s + f.n, 0)} 条，涉及 ${deepFiles.length} 个文件`)
  for (const f of deepFiles.slice(0, 12)) console.log(`  ${String(f.n).padStart(4)} 条 [${f.kind.padEnd(9)}] ${f.file}`)

  console.log('\n=== C6-4 同选择器跨文件重复且有属性重叠 ===')
  console.log(`${dupSelectors.length} 组（按重叠属性数倒序）`)
  for (const d of dupSelectors.slice(0, 15)) {
    console.log(`  ${d.sel.slice(0, 70)}  重叠属性=${d.overlap}`)
    console.log(`      ${d.sites.slice(0, 4).join('  ')}`)
  }

  console.log('\n=== C6-5 组件 scoped 重复覆盖全局样式的类 ===')
  console.log(`${ownership.length} 个类在全局样式表与 ≥1 个组件 scoped 中同时出现（Top 15）`)
  for (const o of ownership.slice(0, 15)) console.log(`  .${o.cls}  ← ${[...o.comps].slice(0, 3).join(', ')}${o.comps.size > 3 ? ` (+${o.comps.size - 3})` : ''}`)
}
