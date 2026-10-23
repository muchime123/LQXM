/**
 * C7-3 只读审计 · 第三部分：职责归属与作用域边界。
 *   ① 按功能分组，看一个文件是否同时承担多个不同职责
 *   ② 每条规则被哪些引入组件使用，据此区分：模块级公共 / 组件级 / 无人使用（转 C8）
 *   ③ 未被任何引入者使用、但在别处出现的规则（可能是归属错位）
 */
import fs from 'node:fs'
import path from 'node:path'

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
      if (top.sel.startsWith('@')) { for (const r of parseRules(top.body)) rules.push({ sel: r.sel }) }
      else if (top.sel) rules.push({ sel: top.sel })
      buf = ''
    } else { if (stack.length) stack[stack.length - 1].body += c; else buf += c }
  }
  return rules
}

const tokenize = t => new Set(t.replace(/<style\b[^>]*>[\s\S]*?<\/style>/g, ' ').split(/[^A-Za-z0-9_-]+/).filter(Boolean))
const firstClass = sel => (sel.match(/\.(-?[_a-zA-Z][\w-]*)/) || [])[1] || '(无类名)'
const groupOf = sel => {
  const c = firstClass(sel)
  const parts = c.split('-')
  return parts.length >= 2 ? parts[0] + '-' + parts[1] : parts[0]
}

// 引入者名单（与主脚本一致）
const importers = {
  'media-account-view.css': ['MediaAccountView', 'ImportUpdatePreviewModal', 'MediaAccountDetailModal', 'MediaAccountEditorModal', 'MediaAccountStats', 'MediaAnalyticsPanel', 'MediaPlatformConfigModal', 'UpdateRecordFormDialog'],
  'packaging-detail-drawer.css': ['PackagingDetailDrawer', 'PackagingSpecDetailPanel', 'PackagingVariantCards']
}
const base = {
  'media-account-view.css': 'src/components/media/',
  'packaging-detail-drawer.css': 'src/components/packaging/'
}

// 全仓 .vue 词表，用于判断无人命中的规则是否在别处被使用
const allVue = []
const walk = d => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (['node_modules', 'dist', '_build_check', '_build_verify', '_build_c7c3'].includes(e.name)) continue
    const p = path.join(d, e.name)
    if (e.isDirectory()) walk(p)
    else if (e.name.endsWith('.vue')) allVue.push({ p, words: tokenize(fs.readFileSync(p, 'utf8')) })
  }
}
walk('src')

/** 按组件名在 src 下定位文件（MediaAccountView 等页面组件不在 components 目录） */
const resolve = name => {
  const hit = allVue.find(v => path.basename(v.p, '.vue') === name)
  if (!hit) throw new Error('未找到组件文件：' + name)
  return hit
}

for (const target of TARGETS) {
  const rules = parseRules(fs.readFileSync(`src/styles/${target}`, 'utf8'))
  const comps = importers[target].map(n => ({ name: n, words: resolve(n).words }))

  console.log(`\n######## ${target}`)

  // ① 功能分组
  const groups = new Map()
  for (const r of rules) {
    const g = groupOf(r.sel)
    const e = groups.get(g) || { count: 0, users: new Set() }
    e.count++
    for (const c of comps) {
      const classes = [...r.sel.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map(m => m[1])
      if (classes.some(cl => c.words.has(cl))) e.users.add(c.name)
    }
    groups.set(g, e)
  }
  console.log('\n  ① 功能分组（按类名前缀归并）')
  const gs = [...groups.entries()].sort((a, b) => b[1].count - a[1].count)
  for (const [g, e] of gs.slice(0, 14)) {
    console.log(`     ${g.padEnd(24)} 规则 ${String(e.count).padStart(3)} 条  使用者 ${e.users.size} 个：${[...e.users].join(', ') || '（无人使用）'}`)
  }
  const multi = gs.filter(([, e]) => e.users.size >= 2)
  const solo = gs.filter(([, e]) => e.users.size === 1)
  const dead = gs.filter(([, e]) => e.users.size === 0)
  console.log(`     分组小计：多组件共用 ${multi.length} 组、单组件专用 ${solo.length} 组、无人使用 ${dead.length} 组`)

  // ② 规则归属
  const usedBy = new Map()
  for (const r of rules) {
    const classes = [...r.sel.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map(m => m[1])
    for (const c of comps) if (classes.some(cl => c.words.has(cl))) { (usedBy.get(r.sel) || usedBy.set(r.sel, []).get(r.sel)).push(c.name) }
  }
  const none = rules.filter(r => !usedBy.has(r.sel))
  console.log('\n  ② 规则归属')
  console.log(`     被 2 个及以上引入者共用：${[...usedBy.values()].filter(v => v.length >= 2).length} 条`)
  console.log(`     仅被 1 个引入者使用：${[...usedBy.values()].filter(v => v.length === 1).length} 条`)
  console.log(`     8/3 个引入者都不使用：${none.length} 条 → 转 C8 核实`)

  // ③ 无人使用但在别处出现的规则（归属错位候选）
  const elsewhere = []
  for (const r of none) {
    const classes = [...r.sel.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map(m => m[1])
    const hit = allVue.filter(v => classes.some(cl => v.words.has(cl))).map(v => path.basename(v.p))
    if (hit.length) elsewhere.push({ sel: r.sel.slice(0, 58), files: [...new Set(hit)].slice(0, 3) })
  }
  console.log(`     其中在其他组件里出现（归属可能错位）：${elsewhere.length} 条`)
  for (const e of elsewhere.slice(0, 6)) console.log(`        ${e.sel}  → ${e.files.join(', ')}`)
  console.log(`     全仓都找不到的（真·无引用候选）：${none.length - elsewhere.length} 条`)
}
