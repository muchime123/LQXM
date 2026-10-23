/**
 * C7-3 口径补充：在真实构建产物上测量「原始 / gzip / brotli」三种体积，
 * 并用「把重复副本删到只剩一份」的方式，量出重复到底增加了多少压缩后字节。
 * 同时区分：产物总量、首屏 CSS、具体路由下载的 CSS。
 * 只读，不修改业务代码（构建产物落在已被忽略的 dist/_c7c3）。
 */
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'

const DIR = 'dist/_c7c3/assets'
const OUT = 'dist/_c7c3/index.html'
const TARGETS = [
  { file: 'src/styles/media-account-view.css', importers: 8 },
  { file: 'src/styles/packaging-detail-drawer.css', importers: 3 }
]

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
const esc = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const kb = n => (n / 1024).toFixed(1) + ' KB'
const gz = t => zlib.gzipSync(Buffer.from(t), { level: 9 }).length
const br = t => zlib.brotliCompressSync(Buffer.from(t), { params: { [zlib.constants.BROTLI_PARAM_QUALITY]: 11 } }).length

const files = fs.readdirSync(DIR).filter(f => f.endsWith('.css'))
const chunks = files.map(f => {
  const p = path.join(DIR, f)
  const text = fs.readFileSync(p, 'utf8')
  return { name: f, text, raw: Buffer.byteLength(text), gz: gz(text), br: br(text) }
})
const sum = k => chunks.reduce((s, c) => s + c[k], 0)
console.log('===== 构建产物 CSS 总体 =====')
console.log(`   文件数 ${chunks.length}   原始 ${kb(sum('raw'))}   gzip ${kb(sum('gz'))}   brotli ${kb(sum('br'))}`)

// 首屏：index.html 直接引用的样式
const html = fs.readFileSync(OUT, 'utf8')
const first = [...html.matchAll(/href="[^"]*assets\/([^"]+\.css)"/g)].map(m => m[1])
const firstChunks = chunks.filter(c => first.includes(c.name))
console.log('\n===== 首屏 CSS（index.html 直接引用）=====')
console.log(`   文件数 ${firstChunks.length}（${firstChunks.map(c => c.name.split('-')[0]).join(', ')}）`)
console.log(`   原始 ${kb(firstChunks.reduce((s, c) => s + c.raw, 0))}   gzip ${kb(firstChunks.reduce((s, c) => s + c.gz, 0))}   brotli ${kb(firstChunks.reduce((s, c) => s + c.br, 0))}`)

const routeChunks = ['MediaAccountView', 'PackagingView']
console.log('\n===== 两个路由块（懒加载，访问对应路由时才下载）=====')
for (const n of routeChunks) {
  const c = chunks.find(x => x.name.startsWith(n + '-'))
  if (!c) continue
  console.log(`   ${n.padEnd(20)} 原始 ${kb(c.raw).padStart(9)}   gzip ${kb(c.gz).padStart(9)}   brotli ${kb(c.br).padStart(9)}`)
}

console.log('\n===== 重复副本对压缩后体积的实际影响 =====')
for (const t of TARGETS) {
  const rules = parseRules(fs.readFileSync(t.file, 'utf8'))
  const sels = rules.map(r => r.sel.split(',').map(s => s.trim()))
  // 找到承载该文件的块
  const all = chunks.map(c => c.text).join('\n')
  const ids = [...new Set([...all.matchAll(/data-v-([0-9a-f]+)/g)].map(m => m[1]))]
  const cov = new Map()
  for (const id of ids) {
    let hit = 0
    for (const list of sels) if (list.some(s => all.includes(`${s}[data-v-${id}]`))) hit++
    cov.set(id, hit)
  }
  const carriers = [...cov.entries()].filter(([, n]) => n > rules.length * 0.5).map(([id]) => id)
  const chunk = chunks.find(c => carriers.some(id => c.text.includes(`data-v-${id}`)))

  // 复制一份文本，把「除最后一份外的所有副本」删掉
  let reduced = chunk.text
  const drop = carriers.slice(0, -1)
  for (const list of sels) {
    for (const sel of list) {
      for (const id of drop) {
        const probe = `${sel}[data-v-${id}]`
        const at = reduced.indexOf(probe)
        if (at < 0) continue
        const open = reduced.indexOf('{', at + probe.length)
        const close = reduced.indexOf('}', open)
        if (close < 0) continue
        reduced = reduced.slice(0, at) + reduced.slice(close + 1)
      }
    }
  }
  console.log(`\n   ${t.file}（${carriers.length} 份副本，落在 ${chunk.name.split('-')[0]} 块）`)
  console.log(`      原始  ${kb(chunk.raw)} → 去重后 ${kb(Buffer.byteLength(reduced))}   重复占 ${kb(chunk.raw - Buffer.byteLength(reduced))}`)
  console.log(`      gzip  ${kb(chunk.gz)} → 去重后 ${kb(gz(reduced))}   重复占 ${kb(chunk.gz - gz(reduced))}`)
  console.log(`      brotli ${kb(chunk.br)} → 去重后 ${kb(br(reduced))}   重复占 ${kb(chunk.br - br(reduced))}`)
  console.log(`      说明：gzip/brotli 的 32KB 窗口能吃掉一部分近距离重复，所以压缩后重复占比明显低于原始占比`)
}
