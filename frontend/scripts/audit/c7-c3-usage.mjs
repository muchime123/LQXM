/**
 * C7-3 只读审计 · 第一部分：
 *   ① 两个样式文件的真实引入关系（文件、组件、行号、是否 scoped、非 scoped 需警示）
 *   ② 每个引入组件实际用到的规则数 / 未命中规则数（基于模板 + script 文本，覆盖所有条件分支与动态 class）
 *   ③ 每条规则被哪些组件使用（用于区分模块级公共规则、组件级规则与无人使用的规则）
 *
 * 只读，不修改任何业务代码。
 */
import fs from 'node:fs'
import path from 'node:path'

const TARGETS = ['media-account-view.css', 'packaging-detail-drawer.css']

/** 把 CSS 解析成规则数组，支持 @media 嵌套 */
function parseRules(css) {
  const src = css.replace(/\/\*[\s\S]*?\*\//g, ' ')
  const rules = []
  let buf = ''
  const stack = []
  for (let i = 0; i < src.length; i++) {
    const c = src[i]
    if (c === '{') {
      const sel = buf.trim().replace(/\s+/g, ' ')
      stack.push({ sel, body: '' })
      buf = ''
    } else if (c === '}') {
      const top = stack.pop()
      if (!top) continue
      if (top.sel.startsWith('@')) {
        // @media 内的规则单独登记，并保留媒体条件
        for (const r of parseRules(top.body)) {
          rules.push({ sel: r.sel, decls: r.decls, media: top.sel })
        }
      } else if (top.sel) {
        const decls = top.body.split(';').map(s => s.trim()).filter(Boolean).length
        rules.push({ sel: top.sel, decls, media: stack.length ? stack[stack.length - 1].sel : null })
      }
      buf = ''
    } else {
      if (stack.length) stack[stack.length - 1].body += c
      else buf += c
    }
  }
  return rules
}

/** 取选择器中的类名与标签名，用于判定是否被组件使用 */
function tokensOf(sel) {
  const classes = [...sel.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map(m => m[1])
  const tags = [...sel.matchAll(/(^|[\s>+~])([a-zA-Z][\w-]*)/g)].map(m => m[2])
  return { classes, tags }
}

/** 组件文件中除去 style 块后的内容（模板 + script，含 :class 表达式与字符串字面量） */
function sourceWithoutStyle(file) {
  const t = fs.readFileSync(file, 'utf8')
  return t.replace(/<style\b[^>]*>[\s\S]*?<\/style>/g, ' ')
}

/** 把文本切成词元集合，用于判断某个类名是否出现在模板或脚本里（避开正则转义问题） */
function tokenSet(text) {
  return new Set(text.split(/[^A-Za-z0-9_-]+/).filter(Boolean))
}

console.log('================ C7-3 ① 真实引入关系 ================')
const importers = {}
const walk = d => {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    if (['node_modules', 'dist', '_build_check', '_build_verify', '_build_c7c3'].includes(e.name)) continue
    const p = path.join(d, e.name)
    if (e.isDirectory()) walk(p)
    else if (/\.(vue|css|ts)$/.test(e.name)) {
      const t = fs.readFileSync(p, 'utf8')
      const lines = t.split(/\r?\n/)
      lines.forEach((l, i) => {
        for (const target of TARGETS) {
          if (!l.includes(target)) continue
          if (!/@import|import\s/.test(l)) continue
          // 判断所在 style 块是否 scoped
          const before = lines.slice(0, i).join('\n')
          const openIdx = before.lastIndexOf('<style')
          const tagLine = openIdx < 0 ? '' : before.slice(openIdx).split('\n')[0]
          const inVue = p.endsWith('.vue')
          const scoped = inVue ? /scoped/.test(tagLine) : false
          ;(importers[target] = importers[target] || []).push({ file: p, line: i + 1, scoped, inVue, raw: l.trim().slice(0, 70) })
        }
      })
    }
  }
}
walk('src')

for (const target of TARGETS) {
  const list = importers[target] || []
  console.log(`\n#### ${target}   引入点：${list.length}`)
  for (const it of list) {
    const flag = !it.inVue ? '（css 文件内部引入）' : it.scoped ? 'scoped' : '★ 非 scoped（全局风险）'
    console.log(`   ${it.file}:${it.line}  ${flag}`)
  }
}

console.log('\n\n================ C7-3 ② 每个组件实际使用的规则数 ================')
for (const target of TARGETS) {
  const cssPath = `src/styles/${target}`
  const rules = parseRules(fs.readFileSync(cssPath, 'utf8'))
  const list = (importers[target] || []).filter(i => i.inVue)
  console.log(`\n#### ${target}   规则总数：${rules.length}`)
  console.log('   ┌──────────────────────────────────┬──────┬────────┬────────┬────────┐')
  console.log('   │ 引入组件                          │ 总规则│ 命中   │ 未命中 │ 使用率 │')
  console.log('   ├──────────────────────────────────┼──────┼────────┼────────┼────────┤')
  const usedBy = new Map() // sel -> Set(组件)
  for (const it of list) {
    const text = sourceWithoutStyle(it.file)
    const words = tokenSet(text)
    let hit = 0
    for (const r of rules) {
      const { classes, tags } = tokensOf(r.sel)
      const ok = classes.length
        ? classes.some(c => words.has(c))
        : tags.some(t => text.includes(`<${t}`))
      if (ok) {
        hit++
        const s = usedBy.get(r.sel) || new Set()
        s.add(path.basename(it.file))
        usedBy.set(r.sel, s)
      }
    }
    const name = path.basename(it.file)
    console.log(`   │ ${name.padEnd(32)} │ ${String(rules.length).padStart(5)} │ ${String(hit).padStart(5)} │ ${String(rules.length - hit).padStart(5)} │ ${String(Math.round((hit / rules.length) * 100) + '%').padStart(5)} │`)
  }
  console.log('   └──────────────────────────────────┴──────┴────────┴────────┴────────┘')

  const shared = [...usedBy.entries()].filter(([, s]) => s.size >= 2)
  const single = [...usedBy.entries()].filter(([, s]) => s.size === 1)
  const none = rules.filter(r => !usedBy.has(r.sel))
  console.log(`   被 2 个及以上组件共用：${shared.length} 条（模块级公共候选）`)
  console.log(`   仅被 1 个组件使用：${single.length} 条（组件级，保留 scoped 依赖）`)
  console.log(`   所有引入组件都未命中：${none.length} 条（转 C8 核实，需人工确认动态场景）`)
  const missSample = none.slice(0, 12).map(r => r.sel.split(',')[0].trim())
  if (missSample.length) console.log(`   未命中示例：${missSample.join(' , ')}`)
  fs.writeFileSync(`_audit_shots/c7-c3-${target}-none.json`, JSON.stringify(none.map(r => ({ sel: r.sel, decls: r.decls, media: r.media })), null, 2))
}
