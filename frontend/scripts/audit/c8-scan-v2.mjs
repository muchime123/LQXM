/**
 * C8 扫描器 v2：修正 v1 的「通用词 token 盲点」。只读，不修改任何业务代码。
 *
 * v1 的判定：规则里任一类名 token 在全仓任一位置出现 → 视为被引用。
 *   于是 `.manager-item.active` 因 `active` 到处出现而被判为「有引用」（误判为活规则）；
 *   反之也可能把含通用词的真死规则放过。
 *
 * v2 的判定（遵循四条原则）：
 *   1. 按「完整选择器分支」判定，而不是按 token 集合；每个分支独立判断有无消费者。
 *   2. 分支内的类名区分为「宿主类」与「状态/修饰类」：
 *      active/danger/disabled/selected/… 这类通用状态词不作为存在消费者的证据。
 *   3. 伪类（:hover/:focus/:disabled/:active/…）与伪元素不参与类名匹配。
 *   4. 多个宿主类共存的分支要求在同一处 class 绑定中共现；无法验证共现时按「有消费者」保守处理。
 *      分支若只剩状态类（没有任何宿主类），一律按「有消费者」保守处理，绝不进入删除候选。
 *
 * 输出：A2（整条可删）/ P2（仅部分分支可删）/ 需运行时核实 / 与 v1 分类的差异。
 */
import fs from 'node:fs'
import path from 'node:path'

// C8B_FILES / C8B_OUT 用于「用 v2 复核历史版本」等一次性核对，不影响默认扫描
const TARGETS = process.env.C8B_FILES
  ? process.env.C8B_FILES.split(',').map(f => ({ file: f, importers: [] }))
  : [
    { file: 'src/styles/media-account-view.css', importers: ['MediaAccountView', 'ImportUpdatePreviewModal', 'MediaAccountDetailModal', 'MediaAccountEditorModal', 'MediaAccountStats', 'MediaAnalyticsPanel', 'MediaPlatformConfigModal', 'UpdateRecordFormDialog'] },
    { file: 'src/styles/packaging-detail-drawer.css', importers: ['PackagingDetailDrawer', 'PackagingSpecDetailPanel', 'PackagingVariantCards'] }
  ]

/** 状态/修饰词：不作为「存在消费者」的证据。仅收录几乎不会单独作为宿主类使用的词。 */
const STATE_TOKENS = new Set(['active', 'inactive', 'disabled', 'enabled', 'checked', 'selected', 'unselected',
  'current', 'danger', 'warning', 'error', 'success', 'info', 'primary', 'secondary', 'muted', 'plain', 'ghost',
  'round', 'circle', 'small', 'large', 'mini', 'sm', 'md', 'lg', 'xs', 'xl', 'bold', 'light', 'dark',
  'open', 'closed', 'expanded', 'collapsed', 'loading', 'loaded', 'empty', 'full', 'first', 'last', 'odd', 'even',
  'show', 'hide', 'hidden', 'visible', 'on', 'off', 'new', 'old', 'default', 'fixed', 'sticky', 'wrap', 'nowrap',
  'ellipsis', 'center', 'left', 'right', 'top', 'bottom', 'start', 'end', 'required', 'optional', 'readonly',
  'invalid', 'valid', 'done', 'pending', 'running', 'failed', 'complete', 'completed', 'highlight', 'highlighted',
  'dim', 'dimmed', 'subtle', 'strong', 'weak', 'dense', 'compact', 'flat', 'outline', 'link', 'block', 'inline',
  'flex', 'grid', 'auto', 'fit', 'half', 'wide', 'narrow', 'hover', 'focus', 'pressed', 'dragging', 'sortable',
  'asc', 'desc', 'ascending', 'descending', 'even', 'odd', 'striped', 'bordered', 'shadowed', 'rounded', 'sharp'])

const isStateToken = t => STATE_TOKENS.has(t) || /^(is|has|not|no)-/.test(t) || /^(el|v|vue)-/.test(t)

// ---------- CSS 解析：规则 + 分支 ----------
function parseRules(css) {
  const src = css.replace(/\/\*[\s\S]*?\*\//g, ' ')
  const rules = []
  let buf = ''
  const stack = []
  let line = 1
  for (let i = 0; i < src.length; i++) {
    const c = src[i]
    if (c === '\n') line++
    if (c === '{') { stack.push({ sel: buf.trim().replace(/\s+/g, ' '), line, body: '' }); buf = '' }
    else if (c === '}') {
      const top = stack.pop()
      if (!top) continue
      if (top.sel.startsWith('@')) for (const r of parseRules(top.body)) rules.push({ sel: r.sel, line: top.line + 1 })
      else if (top.sel) rules.push({ sel: top.sel, line: top.line })
      buf = ''
    } else { if (stack.length) stack[stack.length - 1].body += c; else buf += c }
  }
  return rules
}
const splitBranches = sel => {
  const out = []
  let buf = '', depth = 0
  for (const ch of sel) {
    if (ch === '(') depth++
    if (ch === ')') depth--
    if (ch === ',' && depth === 0) { out.push(buf.trim()); buf = ''; continue }
    buf += ch
  }
  if (buf.trim()) out.push(buf.trim())
  return out
}
/** 分支内的类名（去掉伪类/伪元素/属性选择器里的类名，只保留结构类名） */
const branchClasses = b => [...b.replace(/::[a-z-]+(\([^)]*\))?/g, ' ').matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map(m => m[1])

// ---------- 源码索引：类名的「真实使用位置」 ----------
const stripStyle = t => t.replace(/<style\b[^>]*>[\s\S]*?<\/style>/g, ' ')
const splitTokens = s => s.split(/[^A-Za-z0-9_-]+/).filter(Boolean)

const usageTokens = new Set()   // 出现过的类名 token
const usageSets = []            // 同一处 class 绑定里共现的类名集合
const addSet = set => { if (set.size) usageSets.push(set); set.forEach(t => usageTokens.add(t)) }

const collectFromText = text => {
  // class="a b" / class='a b'
  for (const m of text.matchAll(/\sclass\s*=\s*["']([^"']+)["']/g)) addSet(new Set(splitTokens(m[1])))
  // :class="..." / v-bind:class="..."（必须支持 :class 简写，否则动态绑定的类名会被漏掉）
  // 属性值按引号类型分别匹配：值里常含单引号（对象写法的键），用 [^"'] 会截断
  for (const m of text.matchAll(/(^|\s)(?:v-bind:|:)class\s*=\s*"([^"]*)"/gm)) collectClassExpr(m[2])
  for (const m of text.matchAll(/(^|\s)(?:v-bind:|:)class\s*=\s*'([^']*)'/gm)) collectClassExpr(m[2])
  function collectClassExpr(expr) {
    const set = new Set()
    for (const s of expr.matchAll(/['"`]([^'"`]+)['"`]/g)) splitTokens(s[1]).forEach(t => set.add(t))
    for (const k of expr.matchAll(/([A-Za-z_][\w-]*)\s*:/g)) set.add(k[1])          // 对象写法的键
    for (const k of expr.matchAll(/\?\s*['"`]([^'"`]+)['"`]/g)) splitTokens(k[1]).forEach(t => set.add(t))
    addSet(set)
  }
  // classList.add/remove/toggle('x')
  for (const m of text.matchAll(/classList\.(?:add|remove|toggle|contains)\(\s*['"`]([^'"`]+)['"`]/g)) addSet(new Set(splitTokens(m[1])))
  // render 函数 / 对象里的 class: 'a b'
  for (const m of text.matchAll(/\bclass\s*:\s*['"`]([^'"`]+)['"`]/g)) addSet(new Set(splitTokens(m[1])))
  for (const m of text.matchAll(/\bclassName\s*=\s*['"`]([^'"`]+)['"`]/g)) addSet(new Set(splitTokens(m[1])))
}

const allFiles = []
;(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (['node_modules', 'dist', '.git', '_build_check', '_build_verify', '_build_c8a_before', '_build_c8a_after', '_build_c8a_before2'].includes(e.name)) continue
    const p = path.join(d, e.name)
    if (e.isDirectory()) walk(p)
    else if (/\.(vue|ts|js)$/.test(e.name)) allFiles.push({ p, kind: e.name.split('.').pop(), text: fs.readFileSync(p, 'utf8') })
  }
})('src')
for (const f of allFiles) collectFromText(stripStyle(f.text))

/**
 * 分支是否被使用：
 *   - 剥离状态/修饰词后，只要还有任一宿主类被引用 → 判「有消费者」（保守，绝不误删）；
 *   - 剥离后没有任何宿主类（全是状态词）→ 判「有消费者」；
 *   - 所有宿主类均无引用 → 判「无消费者」。
 * 状态词未收录完整只会让判定更保守（少删），不会导致误删活规则。
 */
function branchUsage(branch) {
  const classes = branchClasses(branch)
  const hosts = classes.filter(c => !isStateToken(c))
  if (!classes.length) return { used: true, reason: '无类名（元素/伪类选择器）', hosts }
  if (!hosts.length) return { used: true, reason: '仅状态类，保守视为有消费者', hosts }
  const present = hosts.filter(h => usageTokens.has(h))
  if (present.length) {
    const co = present.length > 1 || hosts.length > 1
      ? (usageSets.find(s => hosts.every(h => s.has(h))) ? '（已验证共现）' : '（未验证共现，仍按有消费者处理）')
      : ''
    return { used: true, reason: `宿主类有引用：${present.join(', ')}${co}`, hosts }
  }
  return { used: false, reason: `全部宿主类无引用：${hosts.join(', ')}`, hosts }
}

const out = { scannedAt: new Date().toISOString(), files: {} }
for (const target of TARGETS) {
  const raw = fs.readFileSync(target.file, 'utf8')
  const rules = parseRules(raw)
  const oldRows = JSON.parse(fs.readFileSync(`_audit_shots/c8-ledger-${path.basename(target.file, '.css')}.json`, 'utf8'))
  const oldCat = new Map(oldRows.map(r => [r.sel, r.cat]))

  const rows = rules.map(r => {
    const branches = splitBranches(r.sel)
    const usage = branches.map(b => ({ branch: b, ...branchUsage(b) }))
    const deadBranches = usage.filter(u => !u.used)
    const verdict = deadBranches.length === 0 ? 'LIVE'
      : deadBranches.length === branches.length ? 'DEAD_RULE' : 'DEAD_BRANCH'
    return { sel: r.sel, line: r.line, branches, usage, deadBranches, verdict }
  })

  const dead = rows.filter(r => r.verdict === 'DEAD_RULE')
  const partial = rows.filter(r => r.verdict === 'DEAD_BRANCH')
  // 需运行时核实：伪元素 / :deep / 第三方 DOM / Vue 过渡类 / 类名出现在 ts-js / 动态拼接
  const codeText = allFiles.filter(f => f.kind !== 'vue').map(f => f.text).join('\n')
  const allText = allFiles.map(f => stripStyle(f.text)).join('\n')
  const why = r => {
    // 状态词不作为任何证据（与 branchUsage 同一策略），只核对宿主类
    const cs = branchClasses(r.branches.join(',')).filter(c => !isStateToken(c))
    const reasons = []
    if (/::[a-z-]+/.test(r.sel)) reasons.push('含伪元素')
    if (/:deep\(|>>>/.test(r.sel)) reasons.push('含 :deep/>>>')
    if (cs.some(c => /^(el|is|v|vue)-/.test(c))) reasons.push('第三方/框架前缀类')
    // Vue 过渡类：<transition name="x"> 会在运行时生成 x-enter-active / x-leave-to 等
    if (cs.some(c => /-(enter|leave|move)(-|$)/.test(c))) reasons.push('疑似 Vue 过渡类（运行时生成）')
    const inCode = cs.filter(c => new RegExp(`['"\`][^'"\`]*\\b${c}\\b`).test(codeText))
    if (inCode.length) reasons.push(`类名出现在 ts/js 字符串：${inCode.join(',')}`)
    const dyn = cs.filter(c => {
      for (let i = 4; i < c.length; i++) {
        const p = c.slice(0, i)
        if (new RegExp("['`\"]" + p + '\\$\\{').test(allText)) return true
        if (new RegExp("['`\"]" + p + "['`\"]\\s*\\+").test(allText)) return true
      }
      return false
    })
    if (dyn.length) reasons.push(`存在同前缀动态拼接：${dyn.join(',')}`)
    return reasons
  }
  const withReason = dead.map(r => ({ ...r, reasons: why(r) })).map(r => ({ ...r, needsRuntime: r.reasons.length > 0 }))
  const needsRuntime = withReason.filter(r => r.needsRuntime)
  const cleanDead = withReason.filter(r => !r.needsRuntime)

  console.log(`\n################ ${target.file}`)
  console.log(`规则总数 ${rows.length}｜v2 判定：整条无消费者 ${dead.length} / 仅部分分支无消费者 ${partial.length} / 有消费者 ${rows.length - dead.length - partial.length}`)
  console.log(`  其中需先运行时核实 ${needsRuntime.length} 条 → 可直接作为删除候选 ${cleanDead.length} 条`)
  for (const r of needsRuntime) console.log(`      需核实 @${r.line} ${r.sel.slice(0, 56)}  ← ${r.reasons.join('；')}`)
  for (const r of cleanDead) console.log(`      可删除 @${r.line} ${r.sel.slice(0, 56)}`)

  console.log('\n===== v1 → v2 分类差异（校验是否漏判/误判）=====')
  const nowDead = new Set(dead.map(r => r.sel))
  const v1A = oldRows.filter(r => r.cat === 'A').map(r => r.sel)
  const v1AnotDead = v1A.filter(s => !nowDead.has(s))
  console.log(`  v1 判 A（无引用）${v1A.length} 条，v2 仍判整条无消费者 ${v1A.filter(s => nowDead.has(s)).length} 条`)
  console.log(`  v1 判 A 但 v2 认为有消费者（需复核，不应删除）：${v1AnotDead.length} 条${v1AnotDead.length ? ' → ' + v1AnotDead.slice(0, 8).map(s => s.slice(0, 46)).join(' | ') : ''}`)

  console.log('\n===== 新增候选：v1 未判 A、v2 判整条无消费者 =====')
  const added = dead.filter(r => oldCat.get(r.sel) !== 'A')
  console.log(`  新增 ${added.length} 条：`)
  for (const r of added) {
    console.log(`    @${String(r.line).padStart(4)} ${r.sel.slice(0, 70)}`)
    console.log(`          ${r.usage.map(u => (u.used ? '[有]' : '[无]') + u.branch + ' → ' + u.reason).join('\n          ')}`)
  }

  console.log('\n===== 仅部分分支无消费者（只能删分支，不能删整条）=====')
  for (const r of partial) {
    console.log(`    @${String(r.line).padStart(4)} ${r.sel.slice(0, 70)}`)
    console.log(`          无效分支：${r.deadBranches.map(d => d.branch).join(' | ')}`)
  }

  out.files[target.file] = {
    total: rows.length, deadRule: dead.length, deadBranch: partial.length,
    needsRuntime: needsRuntime.map(r => ({ sel: r.sel, line: r.line, reasons: r.reasons })),
    cleanDead: cleanDead.map(r => ({ sel: r.sel, line: r.line, branches: r.branches })),
    partial: partial.map(r => ({ sel: r.sel, line: r.line, deadBranches: r.deadBranches.map(d => d.branch) })),
    v1AnotDead, added: added.map(r => ({ sel: r.sel, line: r.line, branches: r.branches }))
  }
}
const OUT = process.env.C8B_OUT || '_audit_shots/c8b-scan-v2.json'
fs.writeFileSync(OUT, JSON.stringify(out, null, 2))
console.log(`\n→ 明细已写入 ${OUT}`)
