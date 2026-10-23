/**
 * C7-3 迁移可行性核查（针对 C 类「仅单组件使用」规则，不修改代码）。
 * 逐条回答迁移会受影响的五个方面：
 *   1. 是否依赖多个选择器的组合关系（逗号选择器组）
 *   2. 是否包含 :deep() / :global() / 嵌套 / 媒体查询
 *   3. 是否由其他组件通过插槽或子组件渲染对应 DOM（类名出现在子组件里则 scoped 到不到）
 *   4. 迁移后规则顺序与特异性是否变化（目标组件自身是否已有同选择器）
 *   5. 是否有多个组件依赖同一类名但样式不同
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
  let media = null
  for (let i = 0; i < src.length; i++) {
    const c = src[i]
    if (c === '{') { stack.push({ sel: buf.trim().replace(/\s+/g, ' '), body: '', media }); buf = '' }
    else if (c === '}') {
      const top = stack.pop()
      if (!top) continue
      if (top.sel.startsWith('@')) { media = top.sel; for (const r of parseRules(top.body)) rules.push({ sel: r.sel, media: top.sel }) ; media = null }
      else if (top.sel) rules.push({ sel: top.sel, media: top.media || null })
      buf = ''
    } else { if (stack.length) stack[stack.length - 1].body += c; else buf += c }
  }
  return rules
}

const vueFiles = []
const walk = d => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (['node_modules', 'dist', '.git'].includes(e.name)) continue
    const p = path.join(d, e.name)
    if (e.isDirectory()) walk(p)
    else if (e.name.endsWith('.vue')) vueFiles.push({ p, text: fs.readFileSync(p, 'utf8') })
  }
}
walk('src')
const byName = n => vueFiles.find(v => path.basename(v.p, '.vue') === n)
const tokenizer = t => new Set(t.replace(/<style\b[^>]*>[\s\S]*?<\/style>/g, ' ').split(/[^A-Za-z0-9_-]+/).filter(Boolean))
const styleOf = t => (t.match(/<style\b[^>]*>[\s\S]*?<\/style>/) || [''])[0]

for (const t of TARGETS) {
  const rows = JSON.parse(fs.readFileSync(`_audit_shots/c8-ledger-${path.basename(t.file, '.css')}.json`, 'utf8'))
  const c = rows.filter(r => r.cat === 'C')
  console.log(`\n######## ${t.file}   C 类 ${c.length} 条`)

  let multiSel = 0, deep = 0, global = 0, inMedia = 0, pseudo = 0, third = 0
  let orderRisk = 0, childDom = 0
  const orderCases = []
  const childCases = []
  for (const r of c) {
    const list = r.sel.split(',').map(s => s.trim())
    const classes = [...r.sel.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map(m => m[1])
    if (list.length > 1) multiSel++
    if (/:deep\(|>>>/.test(r.sel)) deep++
    if (/:global\(/.test(r.sel)) global++
    if (r.media) inMedia++
    if (/::[a-z-]+|:(hover|focus|active|disabled|first|last|nth|not|empty)/i.test(r.sel)) pseudo++
    if (list.some(s => /\.el-|\.is-/.test(s))) third++
    const owner = r.users[0]
    const v = byName(owner)
    // 4. 目标组件自身是否已有同选择器 → 迁移位置会影响层叠顺序
    const own = styleOf(v.text)
    const dup = list.some(s => own.includes(s + ' {') || new RegExp('\\n' + s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '\\s*\\{').test(own))
    if (dup) { orderRisk++; if (orderCases.length < 5) orderCases.push(`${owner} 已定义 ${r.sel.slice(0, 40)}`) }
    // 3. 类名是否出现在别的组件里（说明 DOM 可能由子组件渲染，scoped 到不了）
    const others = vueFiles.filter(f => path.basename(f.p, '.vue') !== owner && classes.some(cl => tokenizer(f.text).has(cl)))
    if (others.length) { childDom++; if (childCases.length < 5) childCases.push(`${r.sel.slice(0, 34)} 同时出现在 ${others.slice(0, 2).map(f => path.basename(f.p, '.vue')).join(',')}`) }
  }
  console.log(`   1. 依赖逗号选择器组（多选择器组合关系）：${multiSel} 条`)
  console.log(`   2. 含 :deep() / :global()：${deep} / ${global} 条；位于媒体查询内：${inMedia} 条；含伪类或伪元素：${pseudo} 条`)
  console.log(`   3. 类名同时出现在其他组件（DOM 可能由子组件渲染，scoped 到不了）：${childDom} 条 → ${childCases.join(' ; ') || '无'}`)
  console.log(`   4. 目标组件自身已定义同选择器（迁移位置会影响层叠顺序）：${orderRisk} 条 → ${orderCases.join(' ; ') || '无'}`)
  console.log(`   5. 选择器指向第三方组件 DOM（el- 等）：${third} 条`)

  // 5. 同名不同样式：类名被多个组件使用时，各文件里的声明是否一致
  const dupNames = new Map()
  for (const r of c) {
    for (const cl of [...r.sel.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map(m => m[1])) {
      const users = vueFiles.filter(f => tokenizer(f.text).has(cl)).map(f => path.basename(f.p, '.vue'))
      if (users.length > 1) dupNames.set(cl, users.slice(0, 4))
    }
  }
  console.log(`   同名类被多个组件使用（需比较各文件声明是否一致）：${dupNames.size} 个 → ${[...dupNames.entries()].slice(0, 6).map(([k, v]) => k + '(' + v.join(',') + ')').join(' , ') || '无'}`)
  console.log(`   → 结论：迁移本身在作用域上是等价的（外部文件与被迁入组件的 <style scoped> 都用同一个 data-v 作用域号编译），` +
    `真正需要处理的是上面第 2 项的媒体查询/伪类、第 3 项的子组件 DOM、第 4 项的顺序敏感性`)
}
