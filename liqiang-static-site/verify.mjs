import fs from 'node:fs'
import path from 'node:path'

const dist = path.join(process.cwd(), 'dist')
function walk(d) { let o = []; for (const f of fs.readdirSync(d)) { const p = path.join(d, f); const st = fs.statSync(p); if (st.isDirectory()) o = o.concat(walk(p)); else o.push(p) } return o }
const all = walk(dist)
const size = all.reduce((s, f) => s + fs.statSync(f).size, 0)
console.log('total files:', all.length, '| size MB:', (size / 1048576).toFixed(1))
console.log('images in dist:', all.filter((f) => f.includes(path.join('assets', 'images'))).length)

const idx = fs.readFileSync(path.join(dist, 'index.html'), 'utf8')
const checks = {
  'root site-page--home': idx.includes('class="site-page site-page--home"'),
  'hero-carousel': idx.includes('hero hero-carousel'),
  'hero-slide is-active': /hero-slide is-active/.test(idx),
  'hero-slides': idx.includes('class="hero-slides"'),
  'hero-nav prev/next': idx.includes('hero-nav--prev') && idx.includes('hero-nav--next'),
  'hero-dots': idx.includes('class="hero-dots"'),
  'hero-copy inner': idx.includes('hero-copy__inner'),
  'home-strength': idx.includes('class="home-strength"'),
  'live-catalog': idx.includes('class="live-catalog"'),
  'manufacturing-showcase': idx.includes('manufacturing-showcase'),
  'site-cta': idx.includes('class="site-cta'),
  'window.__HERO': idx.includes('window.__HERO'),
  'logo media_147': idx.includes('media_147'),
  'hero bg media_2.jpg': idx.includes('media_2.jpg'),
  'hero bg media_3.jpg': idx.includes('media_3.jpg'),
  'header-cta': idx.includes('class="header-cta"'),
  'menu-button': idx.includes('class="menu-button"'),
  'back-to-top': idx.includes('class="back-to-top"'),
}
console.log('--- index.html ---')
let ok = 0
for (const k in checks) { console.log(checks[k] ? 'PASS' : 'FAIL', k); if (checks[k]) ok++ }
console.log('index checks:', ok + '/' + Object.keys(checks).length)

const pd = fs.readFileSync(path.join(dist, 'products', 'product-67.html'), 'utf8')
console.log('--- product-67.html ---')
console.log('breadcrumb:', pd.includes('class="breadcrumb"'))
console.log('detail-container:', pd.includes('class="detail-container"'))
console.log('detail-info:', pd.includes('detail-info'))
console.log('detail-cta:', pd.includes('detail-cta'))
console.log('detail-sections:', pd.includes('detail-sections'))
console.log('canonical:', pd.includes('https://liqiangpacking.com/products/product-67.html'))
console.log('jsonld Product:', pd.includes('"@type":"Product"'))
console.log('title:', (pd.match(/<title>(.*?)<\/title>/s) || [])[1])

const ab = fs.readFileSync(path.join(dist, 'about.html'), 'utf8')
console.log('--- about ---')
console.log('timeline items:', (ab.match(/about-timeline__item/g) || []).length)
console.log('milestones 2013/2018/2021:', ab.includes('>2013<') && ab.includes('>2018<') && ab.includes('>2021<'))
console.log('phone text:', ab.includes('400 900 8878'))
console.log('patents empty:', ab.includes('暂无公开的专利信息'))

const co = fs.readFileSync(path.join(dist, 'contact.html'), 'utf8')
console.log('--- contact ---')
console.log('inquiry-form:', co.includes('id="inquiry-form"'))
console.log('mailto data:', co.includes('data-mailto="768963166@qq.com"'))

// solutions
const so = fs.readFileSync(path.join(dist, 'solutions.html'), 'utf8')
console.log('--- solutions ---')
console.log('direction cards:', (so.match(/solution-direction-card/g) || []).length)
console.log('process steps:', (so.match(/process-step/g) || []).length)
console.log('empty state:', so.includes('方案内容正在整理中'))

// sitemap & redirects
console.log('--- seo ---')
console.log('sitemap urls:', (fs.readFileSync(path.join(dist, 'sitemap.xml'), 'utf8').match(/<loc>/g) || []).length)
console.log('robots:', fs.existsSync(path.join(dist, 'robots.txt')))
console.log('_redirects:', fs.existsSync(path.join(dist, '_redirects')))
