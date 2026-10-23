/**
 * C8 只读台账：把两个高频引入样式文件的规则分成四类，并逐条记录来源、消费者、样式来源与风险。
 *   A 全仓无引用  B 归属错位（引入者不用、别处出现）  C 单组件使用  D 多组件共用
 * 同时：
 *   - A 类做误判排除：伪元素、:deep()、第三方 DOM（el-* 等）、ts/js 里的类名、动态拼接风险
 *   - B 类回答四个问题：消费者是谁、消费者是否自带同选择器、是否被引入者渲染（插槽/子组件）、是否有 :deep
 * 只读，不修改任何业务代码。
 */
import fs from 'node:fs'
import path from 'node:path'

const TARGETS = [
  { file: 'src/styles/media-account-view.css', importers: ['MediaAccountView', 'ImportUpdatePreviewModal', 'MediaAccountDetailModal', 'MediaAccountEditorModal', 'MediaAccountStats', 'MediaAnalyticsPanel', 'MediaPlatformConfigModal', 'UpdateRecordFormDialog'] },
  { file: 'src/styles/packaging-detail-drawer.css', importers: ['PackagingDetailDrawer', 'PackagingSpecDetailPanel', 'PackagingVariantCards'] }
]

function parseRules(css) {
  const src = css.replace(/\/\*[\s\S]*?\*\//g, ' ')
  const rules = []
  let buf = ''
  const stack = []
  let line = 1
  const lineAt = []
  for (let i = 0; i < src.length; i++) {
    if (src[i] === '\n') line++
    const c = src[i]
    if (c === '{') { stack.push({ sel: buf.trim().replace(/\s+/g, ' '), line, body: '' }); buf = '' }
    else if (c === '}') {
      const top = stack.pop()
      if (!top) continue
      if (top.sel.startsWith('@')) { for (const r of parseRules(top.body)) rules.push({ sel: r.sel, line: top.line + 1 }) }
      else if (top.sel) rules.push({ sel: top.sel, line: top.line })
      buf = ''
    } else { if (stack.length) stack[stack.length - 1].body += c; else buf += c }
    lineAt.push(line)
  }
  return rules
}

const tokenizer = t => new Set(t.replace(/<style\b[^>]*>[\s\S]*?<\/style>/g, ' ').split(/[^A-Za-z0-9_-]+/).filter(Boolean))

// 全仓源码索引
const allFiles = []
const walk = d => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (['node_modules', 'dist', '.git', '_build_check', '_build_verify'].includes(e.name)) continue
    const p = path.join(d, e.name)
    if (e.isDirectory()) walk(p)
    else if (/\.(vue|ts|js)$/.test(e.name)) allFiles.push({ p, kind: e.name.split('.').pop(), text: fs.readFileSync(p, 'utf8') })
  }
}
walk('src')
const vueFiles = allFiles.filter(f => f.kind === 'vue')
const codeFiles = allFiles.filter(f => f.kind !== 'vue')
const vueByName = n => vueFiles.find(v => path.basename(v.p, '.vue') === n)
const tplOf = f => f.text.replace(/<style\b[^>]*>[\s\S]*?<\/style>/g, ' ')

for (const target of TARGETS) {
  const raw = fs.readFileSync(target.file, 'utf8')
  const rules = parseRules(raw)
  const importers = target.importers.map(n => {
    const v = vueByName(n)
    return { name: n, words: tokenizer(v.text), tpl: tplOf(v), style: (v.text.match(/<style\b[^>]*>[\s\S]*?<\/style>/) || [''])[0] }
  })

  const rows = []
  for (const r of rules) {
    const classes = [...r.sel.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map(m => m[1])
    if (!classes.length) continue
    const users = importers.filter(c => classes.some(cl => c.words.has(cl))).map(c => c.name)
    // 全仓（含 ts/js）出现的位置
    const elsewhere = allFiles.filter(f => classes.some(cl => tokenizer(f.text).has(cl))).map(f => path.basename(f.p))
    const cat = users.length === 0 ? (elsewhere.length ? 'B' : 'A') : (users.length >= 2 ? 'D' : 'C')
    rows.push({ ...r, classes, users, elsewhere: [...new Set(elsewhere)], cat })
  }

  const byCat = k => rows.filter(r => r.cat === k)
  console.log(`\n################ ${target.file}`)
  console.log(`规则总数 ${rows.length} → A 全仓无引用 ${byCat('A').length} / B 归属错位 ${byCat('B').length} / C 单组件 ${byCat('C').length} / D 多组件共用 ${byCat('D').length}`)

  // ===== A 类误判排除 =====
  console.log('\n===== A 类：误判排除（伪元素 / :deep / 第三方 DOM / 代码内类名）=====')
  const a = byCat('A')
  const pseudo = a.filter(r => /::[a-z-]+/.test(r.sel))
  const deep = a.filter(r => /:deep\(|>>>/.test(r.sel))
  const third = a.filter(r => r.classes.some(c => /^el-|^is-|^v-|^vue-/.test(c)))
  const inCode = a.filter(r => r.classes.some(cl => codeFiles.some(f => tokenizer(f.text).has(cl))))
  // 动态拼接：模板或脚本里出现该类名的前缀后紧跟 ${ 或字符串拼接，可能被运行时拼成完整类名
  const allText = allFiles.map(f => f.text).join('\n')
  const dynamic = a.filter(r => r.classes.some(cl => {
    for (let i = 4; i < cl.length; i++) {
      const p = cl.slice(0, i)
      if (new RegExp("['`\"]" + p + '\\$\\{').test(allText)) return true
      if (new RegExp("['`\"]" + p + "['`\"]\\s*\\+").test(allText)) return true
    }
    return false
  }))
  console.log(`   含伪元素（如 ::before，可能作用于现有元素）：${pseudo.length} 条 → ${pseudo.slice(0, 5).map(r => r.sel).join(' , ')}`)
  console.log(`   含 :deep( 或 >>>（可能穿透到子组件）：${deep.length} 条 → ${deep.slice(0, 5).map(r => r.sel).join(' , ')}`)
  console.log(`   选择器指向第三方组件 DOM（el- 等，模板里不会出现类名）：${third.length} 条 → ${third.slice(0, 5).map(r => r.sel).join(' , ')}`)
  console.log(`   类名出现在 ts/js 代码里（可能是运行时拼接的 class）：${inCode.length} 条 → ${inCode.slice(0, 5).map(r => r.sel + ' @' + r.line).join(' , ')}`)
  console.log(`   存在同前缀的动态拼接（可能被运行时拼出）：${dynamic.length} 条 → ${dynamic.slice(0, 5).map(r => r.sel).join(' , ')}`)
  const risky = new Set([...pseudo, ...deep, ...third, ...inCode, ...dynamic])
  const clean = a.filter(r => !risky.has(r))
  console.log(`   → 可直接进入删除候选的 A 类：${clean.length} 条；需先运行时核实的：${a.length - clean.length} 条`)

  // ===== B 类四问 =====
  console.log('\n===== B 类：归属错位逐项核查 =====')
  const b = byCat('B')
  const bMap = new Map()
  for (const r of b) {
    const consumers = vueFiles.filter(f => r.classes.some(cl => tokenizer(f.text).has(cl)))
    const detail = consumers.map(cf => {
      const name = path.basename(cf.p, '.vue')
      const styleText = cf.text.slice(cf.text.indexOf('<style') < 0 ? 0 : cf.text.indexOf('<style'))
      const hasOwn = r.classes.some(cl => new RegExp('\\.' + cl.replace(/[-]/g, '\\-') + '(?![-\\w])[^{]*\\{').test(styleText))
      const importsTarget = cf.text.includes(path.basename(target.file))
      const renderedBy = importers.filter(c => new RegExp(`<${name}\\b`).test(c.tpl)).map(c => c.name)
      return { name, hasOwn, importsTarget, renderedBy }
    })
    bMap.set(r.sel, detail)
    console.log(`   ${r.sel.slice(0, 62)}  @${r.line}`)
    console.log(`       消费者：${detail.map(d => `${d.name}${d.hasOwn ? '(自带同选择器)' : '(无该选择器)'}${d.importsTarget ? '(引入了本文件)' : ''}`).join(', ')}`)
  }
  // B 类与引入者的渲染关系：消费者是否被某个引入者当作子组件渲染
  console.log('\n   B 类与引入者的渲染关系：')
  for (const r of b.slice(0, 30)) {
    const consumers = vueFiles.filter(f => r.classes.some(cl => tokenizer(f.text).has(cl))).map(f => path.basename(f.p, '.vue'))
    const renderedBy = importers.filter(c => consumers.some(cn => new RegExp(`<${cn}\\b`).test(c.tpl))).map(c => c.name)
    const deepUse = importers.filter(c => r.classes.some(cl => new RegExp(`:deep\\([^)]*\\.${cl}\\b`).test(c.style)))
    // 边界用 (?![-\w])，避免把 import-footer-left 误判成使用了 import-footer
    const slotUse = importers.filter(c => r.classes.some(cl => new RegExp(`class="[^"]*\\b${cl}(?![-\\w])`).test(c.tpl)))
    console.log(`     ${r.sel.slice(0, 46).padEnd(46)} 被引入者当子组件渲染：${renderedBy.join(',') || '否'}  引入者用 :deep 引用：${deepUse.length ? '是' : '否'}  引入者模板直接使用：${slotUse.length ? '是' : '否'}`)
  }
  fs.writeFileSync(`_audit_shots/c8-ledger-${path.basename(target.file, '.css')}.json`, JSON.stringify(rows.map(r => ({
    sel: r.sel, line: r.line, cat: r.cat, users: r.users, elsewhere: r.elsewhere,
    consumers: bMap.get(r.sel) || []
  })), null, 2))
}
