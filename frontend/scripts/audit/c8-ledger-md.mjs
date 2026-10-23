/**
 * 生成 C8 明细台账文档：docs/css-c8-ledger.md
 * 数据来自：scripts/audit/c8-ledger.mjs（静态分类与消费者）、
 *          _audit_shots/c8-runtime-*.json（运行时命中与副本是否生效）
 * 写法是机器生成，便于后续按批次推进时直接取用。
 */
import fs from 'node:fs'
import path from 'node:path'

const JOBS = [
  { key: 'media-account-view', file: 'src/styles/media-account-view.css', importers: 8 },
  { key: 'packaging-detail-drawer', file: 'src/styles/packaging-detail-drawer.css', importers: 3 }
]

/** 解析成 选择器 -> 声明体 */
function declMap(css) {
  const src = css.replace(/\/\*[\s\S]*?\*\//g, ' ')
  const map = new Map()
  let buf = ''
  const stack = []
  for (let i = 0; i < src.length; i++) {
    const c = src[i]
    if (c === '{') { stack.push({ sel: buf.trim().replace(/\s+/g, ' '), body: '' }); buf = '' }
    else if (c === '}') {
      const top = stack.pop()
      if (!top) continue
      if (top.sel.startsWith('@')) { for (const [k, v] of declMap(top.body)) if (!map.has(k)) map.set(k, v) }
      else if (top.sel) {
        const norm = top.body.split(';').map(s => s.trim()).filter(Boolean).sort().join(';')
        for (const s of top.sel.split(',').map(x => x.trim())) if (!map.has(s)) map.set(s, norm)
      }
      buf = ''
    } else { if (stack.length) stack[stack.length - 1].body += c; else buf += c }
  }
  return map
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

const out = []
out.push('# C8 明细台账（无引用与归属错位样式）')
out.push('')
out.push('> 本文件由 `scripts/audit/c8-ledger-md.mjs` 机器生成，数据来源：`scripts/audit/c8-ledger.mjs`（静态分类）与 `_audit_shots/c8-runtime-*.json`（运行时验证）。')
out.push('> 口径说明：分类依据是「类名是否出现在组件模板或脚本文本中」，属于**静态关联**，不等于实际渲染结果；因此每条都补了运行时命中情况。')
out.push('')

for (const job of JOBS) {
  const rows = JSON.parse(fs.readFileSync(`_audit_shots/c8-ledger-${job.key}.json`, 'utf8'))
  const targetDecl = declMap(fs.readFileSync(job.file, 'utf8'))
  const rt = JSON.parse(fs.readFileSync(`_audit_shots/c8-runtime-${job.key}.json`, 'utf8'))
  const rtB = new Map(rt.bInfo.map(x => [x.sel, x]))
  const dialogs = fs.existsSync('_audit_shots/c8-runtime-media-dialogs.json') && job.key === 'media-account-view'
    ? new Map(JSON.parse(fs.readFileSync('_audit_shots/c8-runtime-media-dialogs.json', 'utf8')).map(x => [x.sel, x]))
    : new Map()

  const a = rows.filter(r => r.cat === 'A')
  const b = rows.filter(r => r.cat === 'B')
  const c = rows.filter(r => r.cat === 'C')
  const d = rows.filter(r => r.cat === 'D')

  out.push(`## ${job.file}`)
  out.push('')
  out.push(`规则总数 ${rows.length}：A 全仓无引用 ${a.length} ／ B 归属错位 ${b.length} ／ C 单组件使用 ${c.length} ／ D 多组件共用 ${d.length}`)
  out.push('')
  out.push('### A 类：全仓无引用（删除候选）')
  out.push('')
  out.push('| 行号 | 选择器 | 实际消费者 | 样式来源 | 风险 | 建议 | 验证方式 |')
  out.push('|---|---|---|---|---|---|---|')
  for (const r of a) {
    out.push(`| ${r.line} | \`${r.sel.slice(0, 70)}\` | 无（全仓 .vue/.ts/.js 均未出现） | 无（无元素可命中） | 低 | C8-A 小批量删除 | 静态全仓检索 + 运行时 0 元素 |`)
  }
  out.push('')
  out.push('### B 类：归属错位（不等于死代码，需逐个核实）')
  out.push('')
  out.push('| 行号 | 选择器 | 实际消费者（是否自带同选择器） | 运行时元素 | 本文件副本是否生效 | 风险 | 建议 | 验证方式 |')
  out.push('|---|---|---|---|---|---|---|---|')
  for (const r of b) {
    const cons = r.consumers.map(x => `${x.name}${x.hasOwn ? '（自带）' : '（无）'}`).join('、')
    const dlg = dialogs.get(r.sel)
    const info = rtB.get(r.sel)
    const exists = (info?.exists || 0) + (dlg?.n || 0)
    let effective = info?.effective === true
    if (dlg && dlg.reachable) effective = true
    // 声明一致性：消费者自带同选择器时，比较两边声明是否一致
    let same = ''
    if (r.sel.includes(',')) {
      // 逗号选择器组：两侧可能拆成不同规则，自动比对不可靠，标注需人工比对
      same = '；逗号选择器组，需人工比对'
    } else if (r.consumers.some(x => x.hasOwn)) {
      const mine = targetDecl.get(r.sel)
      const owner = r.consumers.find(x => x.hasOwn)
      const v = byName(owner.name)
      if (v) {
        const their = declMap((v.text.match(/<style\b[^>]*>[\s\S]*?<\/style>/) || [''])[0].replace(/<\/?style[^>]*>/g, ''))
        const theirs = their.get(r.sel)
        same = theirs ? (theirs === mine ? '；声明与消费者自带版本一致' : '；声明与消费者自带版本**不一致**') : '（消费者侧未定位到该选择器）'
      }
    }
    const risk = exists === 0 ? '中' : (effective ? '中' : '低')
    const advice = exists === 0 ? '进一步核实（可能在其他交互态出现）' : (effective ? `先确认等价提供者${same}` : `可判无效${same}`)
    out.push(`| ${r.line} | \`${r.sel.slice(0, 58)}\` | ${cons} | ${exists || 0} | ${exists === 0 ? '未出现' : (effective ? '**是**' : '否')} | ${risk} | ${advice} | 静态引用 + 运行时匹配 |`)
  }
  out.push('')
  out.push('### C 类：仅单组件使用（迁移可行性待试点验证，暂不迁移）')
  out.push('')
  out.push(`共 ${c.length} 条。逐条记录略，风险点汇总见正文「C 类迁移可行性」。按使用者分布：`)
  const byUser = new Map()
  for (const r of c) byUser.set(r.users[0], (byUser.get(r.users[0]) || 0) + 1)
  for (const [u, n] of [...byUser.entries()].sort((x, y) => y[1] - x[1])) out.push(`- ${u}：${n} 条`)
  out.push('')
  out.push('### D 类：多组件共用（评估保留 scoped 还是模块共享）')
  out.push('')
  out.push(`共 ${d.length} 条。使用组件数分布：`)
  const byN = new Map()
  for (const r of d) byN.set(r.users.length, (byN.get(r.users.length) || 0) + 1)
  for (const [n, cnt] of [...byN.entries()].sort((x, y) => y[0] - x[0])) out.push(`- 被 ${n} 个组件共用：${cnt} 条`)
  out.push('')
}

fs.writeFileSync('docs/css-c8-ledger.md', out.join('\n'))
console.log('已生成 docs/css-c8-ledger.md，共 ' + out.length + ' 行')
