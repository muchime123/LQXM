/**
 * C5 令牌关系扫描器：定义位置 → 最终生效值 → 被哪些令牌引用 → 影响哪些组件
 *
 * 与 C4 的 token-usage.mjs 分工不同：C4 找「引用了不存在的令牌」，
 * C5 看「已定义的令牌之间是什么关系」，用于判断改一个变量会波及多大范围。
 *
 * 覆盖四类问题：
 *   C5-A 重复定义：同一作用域内重复声明同一变量（值不同才是真冲突）
 *   C5-B 主题覆盖：base(:root) / dark([data-theme='dark']) 之间的覆盖是否对称
 *   C5-C 语义重叠：不同变量解析到同一个值（同一设计意图的多套命名）
 *   C5-D 无效令牌：已定义但零引用（需区分死代码与预留规范）
 *
 * 只读，不修改任何文件。
 *
 * 用法（frontend/ 下）：
 *   node scripts/audit/token-graph.mjs           # 人读摘要
 *   node scripts/audit/token-graph.mjs --json    # 机器可读
 *   node scripts/audit/token-graph.mjs --name --color-border-light
 */
import fs from 'node:fs'
import path from 'node:path'

const ROOT = 'src'
const JSON_OUT = process.argv.includes('--json')
const NAME_ARG = (() => {
  const i = process.argv.indexOf('--name')
  return i >= 0 ? process.argv[i + 1] : null
})()

const THEME_DARK = /\[data-theme\s*=\s*['"]?dark['"]?\s*\]/
const COLORISH = /^(#|rgb|hsl|color-mix|light-dark)/i

/** 收集待解析的样式单元：独立 .css 文件 + .vue 的 <style> 块 */
const units = [] // { source, text, lineOffset, scoped }

// 引用来源：要比定义来源更宽。令牌可能以 var(--x) 字符串出现在 .vue 的 <script>
// （如 LqDialog 的 sizeWidthMap 写入 'var(--dialog-width-sm)'）或 .ts 中，
// 只扫 <style> 会把这类真实引用误判成零引用。
const useSources = []
const walk = d => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (e.name === 'node_modules' || e.name === 'dist' || e.name.startsWith('_')) continue
    const p = path.join(d, e.name)
    if (e.isDirectory()) walk(p)
    else if (e.name.endsWith('.css')) units.push({ source: p, text: fs.readFileSync(p, 'utf8'), lineOffset: 0, scoped: false })
    else if (e.name.endsWith('.ts')) useSources.push(p)
    else if (e.name.endsWith('.vue')) {
      useSources.push(p) // .vue 全文参与引用统计
      const t = fs.readFileSync(p, 'utf8')
      const re = /<style\b([^>]*)>([\s\S]*?)<\/style>/g
      let m
      while ((m = re.exec(t))) {
        const scoped = /scoped/.test(m[1])
        const offset = t.slice(0, m.index).split(/\r?\n/).length - 1
        units.push({ source: p, text: m[2], lineOffset: offset, scoped })
      }
    }
  }
}
walk(ROOT)

/**
 * 解析 CSS，产出：
 *   defs: [{ name, value, file, line, selStack, scoped }]
 *   uses: [{ name, file, line }]   // var(--x) 引用
 */
const defs = []
const uses = []

for (const u of units) {
  const text = u.text.replace(/\/\*[\s\S]*?\*\//g, s => s.replace(/[^\n]/g, ' ')) // 注释置空但保留行号
  const stack = [] // { sel, isAtRule }
  let buf = ''
  for (let i = 0; i < text.length; i++) {
    const c = text[i]
    if (c === '{') {
      const sel = buf.trim().replace(/\s+/g, ' ')
      stack.push({ sel, isAtRule: sel.startsWith('@') })
      buf = ''
      continue
    }
    if (c === '}') {
      stack.pop()
      buf = ''
      continue
    }
    if (c === ';') {
      const decl = buf.trim()
      const m = decl.match(/^(--[a-zA-Z0-9-]+)\s*:\s*(.+)$/)
      if (m) {
        const sels = stack.filter(s => !s.isAtRule).map(s => s.sel)
        defs.push({
          name: m[1],
          value: m[2].trim(),
          file: u.source,
          line: u.lineOffset + text.slice(0, i).split(/\r?\n/).length,
          sel: sels.length ? sels[sels.length - 1] : '(top)',
          selFull: sels.join(' >> ') || '(top)',
          atRules: stack.filter(s => s.isAtRule).map(s => s.sel),
          scoped: u.scoped
        })
      }
      buf = ''
      continue
    }
    buf += c
  }
  // var() 引用（样式块内）
  const lines = text.split(/\r?\n/)
  lines.forEach((l, idx) => {
    for (const m of l.matchAll(/var\(\s*(--[a-zA-Z0-9-]+)/g)) {
      uses.push({ name: m[1], file: u.source, line: u.lineOffset + idx + 1 })
    }
  })
}

// var() 引用（.vue 全文与 .ts，覆盖 <script> 里的字符串形式用法）
for (const p of useSources) {
  const lines = fs.readFileSync(p, 'utf8').split(/\r?\n/)
  lines.forEach((l, idx) => {
    for (const m of l.matchAll(/var\(\s*(--[a-zA-Z0-9-]+)/g)) {
      uses.push({ name: m[1], file: p, line: idx + 1 })
    }
  })
}

/** 作用域归一：base(:root) / dark([data-theme=dark]) / scoped(其它选择器) */
const scopeOf = d => {
  if (THEME_DARK.test(d.selFull)) return 'dark'
  if (/^(:root|html|:host)\b/.test(d.sel)) return 'base'
  return 'scoped'
}
for (const d of defs) d.scope = scopeOf(d)

const normSel = s => s.replace(/\s+/g, ' ').trim()

// ---------- C5-A 重复定义：同作用域（同选择器 + 同 @-规则上下文）重复声明 ----------
// 媒体查询内的覆盖属于响应式设计，不是重复定义错误，必须分开统计
for (const d of defs) d.responsive = d.atRules.some(a => a.startsWith('@media'))

const dupGroups = new Map()
for (const d of defs) {
  const key = `${d.name} @ ${normSel(d.selFull)} @ ${d.atRules.join('|')}`
  const g = dupGroups.get(key) || { name: d.name, sel: normSel(d.selFull), scope: d.scope, responsive: d.responsive, items: [] }
  g.items.push(d)
  dupGroups.set(key, g)
}
const duplicates = [...dupGroups.values()]
  .filter(g => g.items.length > 1)
  .map(g => ({ ...g, values: [...new Set(g.items.map(i => i.value))], conflict: new Set(g.items.map(i => i.value)).size > 1 }))
  .sort((a, b) => Number(b.conflict) - Number(a.conflict) || b.items.length - a.items.length)

// 响应式覆盖：同名令牌同时存在「媒体查询内」与「媒体查询外」的定义
const responsiveNames = new Map()
for (const d of defs) {
  const g = responsiveNames.get(d.name) || { inside: [], outside: [] }
  const bucket = d.responsive ? g.inside : g.outside
  bucket.push(d)
  responsiveNames.set(d.name, g)
}
const responsiveOverrides = [...responsiveNames.entries()]
  .filter(([, g]) => g.inside.length && g.outside.length)
  .map(([name, g]) => ({ name, base: g.outside[0], variants: g.inside }))

// ---------- C5-B 主题覆盖 ----------
const baseDefs = defs.filter(d => d.scope === 'base')
const darkDefs = defs.filter(d => d.scope === 'dark')
const baseByName = new Map()
for (const d of baseDefs) if (!baseByName.has(d.name)) baseByName.set(d.name, d)
const darkByName = new Map()
for (const d of darkDefs) if (!darkByName.has(d.name)) darkByName.set(d.name, d)

const darkMissing = [...baseByName.keys()]
  .filter(n => !darkByName.has(n) && COLORISH.test(baseByName.get(n).value))
  .map(n => ({ name: n, base: baseByName.get(n) }))
const darkOrphan = [...darkByName.keys()].filter(n => !baseByName.has(n)).map(n => darkByName.get(n))
const normalOverride = [...darkByName.keys()].filter(n => baseByName.has(n))

// ---------- C5-C 语义重叠：不同变量解析到同一个值 ----------
// 必须分域比较：14px 的间距与 14px 的字号同值但毫无关系，混在一起全是噪音
const domainOf = n => {
  if (/^--(space|page-|card-padding|control-|sidebar-width|content-|dialog-width|toolbar-)/.test(n)) return 'space'
  if (/radius/.test(n)) return 'radius'
  if (/^--(fs|text-h|text-body|text-caption|text-display|text-overline|text-placeholder|tracking|lh)/.test(n)) return 'font'
  if (/(color|shadow|opacity|border-width)/.test(n)) return 'color'
  return 'other'
}
const byValue = new Map()
for (const d of baseDefs) {
  const key = `${domainOf(d.name)}||${d.value}`
  const g = byValue.get(key) || { domain: domainOf(d.name), value: d.value, defs: [] }
  g.defs.push(d)
  byValue.set(key, g)
}
const overlaps = [...byValue.values()]
  .filter(g => new Set(g.defs.map(d => d.name)).size > 1)
  .map(g => ({ domain: g.domain, value: g.value, names: [...new Set(g.defs.map(d => d.name))], defs: g.defs }))
  .sort((a, b) => b.names.length - a.names.length)

// ---------- C5-D 无效令牌：定义了但没有被 var() 引用 ----------
const usedNames = new Set(uses.map(u => u.name))
// 被其它令牌定义引用也算“在用”（间接使用）
const depRefs = new Set()
for (const d of defs) for (const m of d.value.matchAll(/var\(\s*(--[a-zA-Z0-9-]+)/g)) depRefs.add(m[1])
const unusedAll = [...baseByName.keys()].filter(n => !usedNames.has(n) && !depRefs.has(n)).sort()
// --el-* 是 Element Plus 的覆盖钩子，由 EP 自身 CSS 消费（在 node_modules，本扫描看不到），
// 零引用不代表死代码；只有项目自有命名才可能是真正的死代码。
const unused = unusedAll.filter(n => !n.startsWith('--el-'))
const unusedEp = unusedAll.filter(n => n.startsWith('--el-'))

// ---------- 依赖关系：谁的定义引用了我；我最终影响哪些组件 ----------
const dependents = new Map() // name -> [引用它的令牌名]
for (const d of defs) {
  for (const m of d.value.matchAll(/var\(\s*(--[a-zA-Z0-9-]+)/g)) {
    const t = m[1]
    const arr = dependents.get(t) || []
    if (!arr.includes(d.name)) arr.push(d.name)
    dependents.set(t, arr)
  }
}
const closure = name => {
  const seen = new Set()
  const stack = [name]
  while (stack.length) {
    const cur = stack.pop()
    for (const nxt of dependents.get(cur) || []) {
      if (!seen.has(nxt)) { seen.add(nxt); stack.push(nxt) }
    }
  }
  return [...seen]
}
const consumers = new Map() // name -> Set(文件)
for (const u of uses) {
  const s = consumers.get(u.name) || new Set()
  s.add(u.file)
  consumers.set(u.name, s)
}

const report = name => ({
  name,
  base: baseByName.get(name) || null,
  dark: darkByName.get(name) || null,
  defCount: defs.filter(d => d.name === name).length,
  defSites: defs.filter(d => d.name === name).map(d => `${d.file}:${d.line}  [${d.scope}] ${d.selFull}`),
  // 直接引用我的令牌 + 传递闭包
  directDependents: dependents.get(name) || [],
  transitiveDependents: closure(name),
  consumerFiles: consumers.get(name) ? [...consumers.get(name)] : []
})

if (NAME_ARG) {
  console.log(JSON.stringify(report(NAME_ARG), null, 2))
} else if (JSON_OUT) {
  console.log(JSON.stringify({
    scanned: { units: units.length, defs: defs.length, uses: uses.length },
    duplicates, responsiveOverrides, darkMissing, darkOrphan, overlaps, unused, dependents: [...dependents.entries()]
  }, null, 2))
} else {
  console.log(`样式单元 ${units.length}，令牌定义 ${defs.length} 条，var() 引用 ${uses.length} 处`)
  console.log(`base(:root) ${baseByName.size} 个令牌，dark([data-theme=dark]) ${darkByName.size} 个，其中正常主题覆盖 ${normalOverride.length} 个\n`)

  console.log('=== C5-A 重复定义（同作用域重复声明）===')
  const conflicts = duplicates.filter(d => d.conflict)
  const redundant = duplicates.filter(d => !d.conflict)
  console.log(`值冲突 ${conflicts.length} 组；值相同（冗余但无害）${redundant.length} 组；响应式覆盖 ${responsiveOverrides.length} 个（正常，不计冲突）`)
  for (const d of conflicts) {
    console.log(`  ! ${d.name}  [${d.scope}] ${d.sel}`)
    for (const i of d.items) console.log(`      ${i.file}:${i.line}  ${i.value}`)
  }
  for (const d of redundant.slice(0, 10)) {
    console.log(`    ${d.name}  [${d.scope}] ${d.sel}  ×${d.items.length}  值相同`)
  }
  if (responsiveOverrides.length) {
    console.log('\n  -- 响应式覆盖（@media 内重定义，属正常设计）--')
    for (const r of responsiveOverrides.slice(0, 12)) {
      console.log(`    ${r.name}  基准=${r.base.value} (${r.base.file}:${r.base.line})  断点值 ${r.variants.map(v => v.value).join(' / ')}`)
    }
  }

  console.log('\n=== C5-B 主题覆盖 ===')
  console.log(`深色缺失（base 有色值、dark 未覆盖，深色下沿用浅色值）：${darkMissing.length}`)
  for (const m of darkMissing.slice(0, 30)) console.log(`  ${m.name}  base=${m.base.value}  (${m.base.file}:${m.base.line})`)
  console.log(`\n深色孤儿（只在 dark 定义、base 没有）：${darkOrphan.length}`)
  for (const d of darkOrphan.slice(0, 20)) console.log(`  ${d.name}  ${d.value}  (${d.file}:${d.line})`)

  console.log('\n=== C5-C 语义重叠（同域同值；括号内为消费文件数，用于挑保留哪个）===')
  const ownOverlaps = overlaps.filter(o => o.names.some(n => !n.startsWith('--el-')))
  for (const o of ownOverlaps.slice(0, 30)) {
    const counts = o.names.map(n => `${n}(${(consumers.get(n) || new Set()).size})`)
    console.log(`  [${o.domain}] ${String(o.value).padEnd(24)} ${counts.join(', ')}`)
  }

  console.log('\n=== C5-D 零引用令牌 ===')
  console.log(`项目自有命名 ${unused.length} 个（需区分死代码与预留规范）：`)
  console.log('  ' + unused.join('  '))
  console.log(`\n--el-* 覆盖钩子 ${unusedEp.length} 个：由 Element Plus 自身 CSS 消费（在 node_modules，本扫描看不到引用），不计死代码`)
}
