/* ============================================================
   利强包装 · 静态官网生成器 (Node ESM)
   读取 _data 数据快照 + assets，输出自包含纯静态多页站到 dist/
   用法：  node build.mjs
   ============================================================ */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = __dirname
const OUT = path.join(ROOT, 'dist')
const DATA = path.join(ROOT, '_data')
const IMG_DIR = path.join(ROOT, 'assets', 'images')
const SITE = 'https://liqiangpacking.com'

/* ---------- 数据读取 ---------- */
const readJson = (n) => JSON.parse(fs.readFileSync(path.join(DATA, n), 'utf8'))
const home = readJson('home.json')
const products = readJson('products.json')
const detailList = readJson('product-details.json')
const solutions = readJson('solutions.json')
const banners = readJson('banners.json')

const company = home.company || {}
const companyName = company.companyName || '浙江利强包装科技有限公司'
const phoneRaw = company.phone || '4009008878'
const phoneText = String(phoneRaw).replace(/^(\d{3})(\d{3})(\d{4})$/, '$1 $2 $3')
const email = company.email || '768963166@qq.com'
const address = company.factoryAddress || company.officeAddress || ''
const logoId = String(company.logoMediaId || '147')
const brandName = company.brandName || '利强'
const brandEn = company.brandNameEn || 'LIQIANG'
const core = company.corePositioning || '一站式包装合作伙伴'
const mainBusiness = company.mainBusiness || ''
const establishedYear = (company.establishmentDate || '2013').slice(0, 4)
const targetIndustries = company.targetIndustries || '餐饮连锁、预制菜、生鲜肉类、烘焙、航空餐、食品工厂、医用/日化包装等'
const advantages = company.advantages || ''
let strengths = []
try { strengths = JSON.parse(company.manufacturingStrengths || '[]') } catch (e) { strengths = [] }
const year = new Date().getFullYear()

/* ---------- 图片索引：media id -> 文件名（同名优先 .jpg 优化版） ---------- */
function buildMediaMap() {
  const map = {}
  for (const f of fs.readdirSync(IMG_DIR)) {
    const m = f.match(/^media_(\d+)\.(png|jpg)$/)
    if (!m) continue
    const id = m[1]
    const ext = m[2]
    if (!map[id] || ext === 'jpg') map[id] = f
  }
  return map
}
const mediaMap = buildMediaMap()
const LOGO = `/assets/images/${mediaMap[logoId] || 'media_147.png'}`
const mediaUrl = (id) => {
  const f = mediaMap[String(id)]
  return f ? `/assets/images/${f}` : LOGO
}

/* 产品分类（从 products 派生，保持后端顺序） */
const categories = [...new Set(products.map((p) => p.category).filter(Boolean))]
/* 产品按 slug 索引 */
const productBySlug = {}
for (const p of products) productBySlug[p.slug] = p
/* 详情按 slug 索引 */
const detailBySlug = {}
for (const d of detailList) {
  if (d && d.product && d.product.slug) detailBySlug[d.product.slug] = d
}

/* 应用/容器/工艺/产能 选型维度（用于产品中心筛选） */
const dims = {
  applicationTags: [...new Set(products.flatMap((p) => p.applicationTags || []).filter(Boolean))],
  containerTags: [...new Set(products.flatMap((p) => p.containerTags || []).filter(Boolean))],
  processTags: [...new Set(products.flatMap((p) => p.processTags || []).filter(Boolean))],
  capacityLevel: [...new Set(products.map((p) => p.capacityLevel).filter(Boolean))],
}

/* ---------- 工具 ---------- */
const esc = (s) => String(s == null ? '' : s)
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
const cardLink = (p) => `/products/${(p.slug || p.id)}.html`
const cardSubtitle = (p) => p.category || '包装设备'
const cardImage = (p) => mediaUrl(p.cardMediaId || p.heroMediaId)

function copyStatic() {
  // 复制 assets -> dist/assets（css / js / images）
  const srcAssets = path.join(ROOT, 'assets')
  const dstAssets = path.join(OUT, 'assets')
  fs.rmSync(dstAssets, { recursive: true, force: true })
  fs.cpSync(srcAssets, dstAssets, { recursive: true })
  fs.writeFileSync(path.join(OUT, 'assets', 'css', 'style.css'),
    fs.readFileSync(path.join(ROOT, 'assets', 'css', 'style.css'), 'utf8') +
    '\n' + fs.readFileSync(path.join(ROOT, 'assets', 'css', 'extra.css'), 'utf8'))
}

/* ---------- 共享组件模板 ---------- */
const NAV = [
  { key: 'home', label: '首页', href: '/index.html' },
  { key: 'products', label: '产品中心', href: '/products.html' },
  { key: 'solutions', label: '解决方案', href: '/solutions.html' },
  { key: 'supplies', label: '包材耗材', href: '/packaging.html' },
  { key: 'about', label: '关于利强', href: '/about.html' },
]

function header(navKey) {
  const onHome = navKey === 'home'
  const items = NAV.map((l) =>
    `<div class="nav-group"><a class="nav-item${l.key === navKey ? ' active' : ''}" href="${l.href}">${l.label}</a></div>`
  ).join('')
  return `<header class="site-header${onHome ? ' on-home' : ''} solid">
    <a class="brand" href="/index.html" aria-label="${brandName}包装首页">
      <span class="brand-icon"><img src="${LOGO}" alt="" decoding="async" /></span>
      <span class="brand-text"><strong class="brand-title">${brandName}</strong><em class="brand-subtitle">${brandEn}</em></span>
    </a>
    <nav class="desktop-nav" aria-label="主导航">${items}
      <a class="mobile-nav-cta" href="/contact.html#inquiry">获取包装方案 <span>→</span></a>
    </nav>
    <a class="header-cta" href="/contact.html#inquiry">获取包装方案 <span>→</span></a>
    <button class="menu-button" aria-expanded="false" aria-label="打开导航">☰</button>
  </header>`
}

function footer() {
  return `<footer>
    <div class="footer-grid">
      <div class="footer-brand">
        <a class="brand footer-brand__logo" href="/index.html" aria-label="${brandName}包装首页">
          <span class="brand-icon"><img src="${LOGO}" alt="" loading="lazy" decoding="async" /></span>
          <span class="brand-text"><strong class="brand-title">${brandName}</strong><em class="brand-subtitle">${brandEn}</em></span>
        </a>
        <div class="footer-brand-meta">
          <p class="footer-slogan">一站式包装合作伙伴</p>
          <a class="footer-phone" href="#" data-copy="${esc(phoneText)}">
            <span class="footer-phone__label">服务热线</span><strong>${esc(phoneText)}</strong>
          </a>
        </div>
      </div>
      <div class="footer-nav-groups">
        <nav class="footer-nav-group" aria-label="业务导航">
          <strong>产品与方案</strong>
          <a href="/products.html">产品中心</a>
          <a href="/solutions.html">解决方案</a>
          <a href="/packaging.html">包材耗材</a>
        </nav>
        <nav class="footer-nav-group" aria-label="企业导航">
          <strong>企业与合作</strong>
          <a href="/about.html">关于利强</a>
          <a href="/about.html#credentials">企业资质</a>
          <a href="/about.html#patents">专利与知识产权</a>
          <a class="footer-plain-link" href="/contact.html#inquiry">样品试封</a>
          <a href="/about.html#contact">联系我们</a>
        </nav>
      </div>
      <div class="footer-contact">
        <strong>联系我们</strong>
        <span class="footer-addr">${esc(address)}</span>
        <a href="#" data-copy="${esc(email)}">${esc(email)}</a>
        <a class="footer-consult-link" href="/contact.html#inquiry">提交包装需求 →</a>
      </div>
    </div>
    <div class="footer-bottom">
      <span>© ${year} ${esc(companyName)} 版权所有</span>
      <div class="footer-socials"></div>
      <span class="footer-motto">食品包装 · 设备 / 包材 / 方案</span>
    </div>
  </footer>`
}

function backToTop() {
  return `<button class="back-to-top" type="button" aria-label="回到顶部">
    <svg class="back-to-top__ring" viewBox="0 0 52 52" aria-hidden="true">
      <circle class="back-to-top__track" cx="26" cy="26" r="21" />
      <circle class="back-to-top__bar" cx="26" cy="26" r="21" stroke-dasharray="131.95" stroke-dashoffset="131.95" />
    </svg>
    <svg class="back-to-top__icon" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <polyline points="18 15 12 9 6 15" />
    </svg>
  </button>`
}

function sectionHeading({ eyebrow, title, description, theme = 'dark', align = 'left', size = 'md', link = null }) {
  const descHtml = description
    ? (link
        ? `<div class="section-heading__right"><p>${description}</p><a class="section-heading__link" href="${link.href}">${link.text}</a></div>`
        : `<div class="section-heading__right"><p>${description}</p></div>`)
    : (link
        ? `<div class="section-heading__right"><a class="section-heading__link" href="${link.href}">${link.text}</a></div>`
        : '')
  return `<div class="section-heading reveal section-heading--theme-${theme} section-heading--align-${align} section-heading--size-${size}">
    <div class="section-heading__left"><p class="eyebrow ${theme === 'light' ? 'light' : 'dark'}"><span></span>${eyebrow}</p><h2>${title}</h2></div>
    ${descHtml}
  </div>`
}

function siteCta({ eyebrow = 'START YOUR PROJECT', title = '告诉我们您的包装需求', description = '提供产品、容器、目标产能或样品信息，我们的工程师将协助您梳理适合的设备与工艺方案。', actionText = '获取包装方案', actionTo = '/contact.html#inquiry', id = 'cta' } = {}) {
  return `<section class="site-cta site-cta--dark" id="${id}">
    <div class="site-cta__inner reveal">
      <p class="eyebrow light"><span></span> ${eyebrow}</p>
      <h2>${title}</h2>
      <p class="site-cta__desc">${description}</p>
      <div class="site-cta__actions"><a class="button white lg" href="${actionTo}">${actionText} <span>→</span></a></div>
    </div>
    <div class="site-cta__rings"></div>
  </section>`
}

function pageHero({ eyebrow, title, description, image, primary = null, secondary = null, twoCol = false, side = '' }) {
  const actions = []
  if (primary) actions.push(`<a class="button primary" href="${primary.href}">${primary.text} <span class="arrow"> →</span></a>`)
  if (secondary) actions.push(`<a class="button ghost" href="${secondary.href}">${secondary.text} <span class="arrow"> →</span></a>`)
  return `<section class="page-hero page-hero--dark page-hero--md${twoCol ? ' page-hero--two-col' : ''}" style="--page-hero-image:url(${image})">
    <div class="page-hero__media" aria-hidden="true"></div>
    <div class="hero-grid"></div>
    <div class="hero-copy reveal">
      ${eyebrow ? `<p class="eyebrow light"><span></span>${eyebrow}</p>` : ''}
      <h1>${title}</h1>
      ${description ? `<div class="hero-body"><p>${description}</p></div>` : ''}
      ${actions.length ? `<div class="hero-actions">${actions.join('')}</div>` : ''}
    </div>
    ${twoCol ? `<div class="hero-side reveal">${side}</div>` : ''}
  </section>`
}

function productCard(p) {
  const subtitle = cardSubtitle(p)
  return `<a class="catalog-card catalog-card--cover" href="${cardLink(p)}">
    <div class="catalog-image">
      <img loading="lazy" decoding="async" src="${cardImage(p)}" sizes="(max-width: 980px) 50vw, (max-width: 1200px) 33vw, 25vw" alt="${esc(p.name)}" />
      <div class="catalog-card__name-plate">
        <small class="catalog-card__eyebrow">${esc(subtitle)}</small>
        <h3 class="catalog-card__name">${esc(p.name)}</h3>
      </div>
    </div>
  </a>`
}

function shell({ title, description, navKey, body, canonical, jsonLd = '' }) {
  const canonicalUrl = canonical || `${SITE}/${navKey === 'home' ? '' : navKey === 'products' ? 'products.html' : navKey + '.html'}`
  return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>${esc(title)}</title>
  <meta name="description" content="${esc(description)}" />
  <link rel="canonical" href="${canonicalUrl}" />
  <meta property="og:type" content="website" />
  <meta property="og:title" content="${esc(title)}" />
  <meta property="og:description" content="${esc(description)}" />
  <meta property="og:image" content="${SITE}${LOGO}" />
  <meta property="og:site_name" content="${brandName}包装" />
  <meta property="og:locale" content="zh_CN" />
  <link rel="icon" href="${LOGO}" type="image/png" />
  <link rel="stylesheet" href="/assets/css/style.css" />
  <script defer src="/assets/js/site.js"></script>
  ${jsonLd ? `<script type="application/ld+json">${jsonLd}</script>` : ''}
</head>
<body data-page="${navKey}">
  <div id="top" class="site-page${navKey === 'home' ? ' site-page--home' : ''}">
    ${header(navKey)}
    <main id="main-content" tabindex="-1">${body}</main>
    ${footer()}
    ${backToTop()}
  </div>
</body>
</html>`
}

/* ---------- JSON-LD ---------- */
function orgSchema() {
  return JSON.stringify({
    '@context': 'https://schema.org', '@type': 'Organization',
    name: companyName, url: SITE, logo: `${SITE}${LOGO}`,
    telephone: `+86-${phoneRaw}`, email,
    address: { '@type': 'PostalAddress', streetAddress: address, addressLocality: '温州市', addressRegion: '浙江省', addressCountry: 'CN' },
    foundingDate: company.establishmentDate || '2013-08-12',
  })
}
function productSchema(p) {
  return JSON.stringify({
    '@context': 'https://schema.org', '@type': 'Product',
    name: p.name, description: p.summary || '',
    brand: { '@type': 'Brand', name: brandName },
    category: p.category, url: `${SITE}/products/${(p.slug || p.id)}.html`,
    image: `${SITE}${cardImage(p)}`,
    sku: p.slug || undefined,
  })
}

/* ============================================================
   页面构建
   ============================================================ */

/* ---------- 首页 ---------- */
function buildHome() {
  const heroBanners = (banners.HERO || []).map((b) => ({
    title: b.title || '',
    subtitle: b.subtitle || '',
    description: b.description || '',
    buttonText: b.buttonText || '探索产品',
    buttonUrl: b.buttonUrl || '/products.html',
    secondaryButtonText: b.secondaryButtonText || '获取包装方案',
    secondaryButtonUrl: '/contact.html#inquiry',
    image: b.imageUrl ? mediaUrl((b.imageUrl.match(/media\/(\d+)\/content/) || [])[1]) : LOGO,
  }))
  const heroList = heroBanners.length ? heroBanners : [{
    title: '包装设备与包材，<em>围绕您的产品配置</em>',
    subtitle: 'PACKAGING EQUIPMENT & MATERIALS',
    description: '从封口设备、气调包装机到配套包材，利强围绕您的产品、容器和产能目标完成包装配置。',
    buttonText: '探索产品', buttonUrl: '/products.html',
    secondaryButtonText: '获取包装方案', secondaryButtonUrl: '/contact.html#inquiry',
    image: LOGO,
  }]

  const heroData = heroList.map((b) => ({
    title: b.title, subtitle: b.subtitle, description: b.description,
    primaryText: b.buttonText, primaryUrl: b.buttonUrl,
    secondaryText: b.secondaryButtonText, secondaryUrl: b.secondaryButtonUrl,
  }))

  const slides = heroList.map((b, i) =>
    `<div class="hero-slide${i === 0 ? ' is-active' : ''} is-loaded" style="--hero-background:url(${b.image})" ${i === 0 ? '' : 'aria-hidden="true"'}></div>`
  ).join('')
  const dots = heroList.map((b, i) =>
    `<button type="button" class="hero-dot${i === 0 ? ' is-active' : ''}" role="tab" aria-selected="${i === 0}" aria-label="第 ${i + 1} 张 Banner"></button>`
  ).join('')

  const heroCopy = heroData[0]
  const heroCopyHtml = `<div class="hero-copy reveal"><div class="hero-copy__inner">
    ${heroCopy.subtitle ? `<p class="hero-eyebrow">${heroCopy.subtitle}</p>` : ''}
    ${heroCopy.title ? `<h1>${heroCopy.title}</h1>` : ''}
    ${heroCopy.description ? `<p class="hero-description">${heroCopy.description}</p>` : ''}
    <div class="hero-actions">
      <a class="button primary" href="${heroCopy.primaryUrl}">${heroCopy.primaryText} <span>→</span></a>
      <a class="button ghost" href="${heroCopy.secondaryUrl}">${heroCopy.secondaryText}</a>
    </div>
  </div></div>`

  const hero = `<section class="hero hero-carousel" aria-roledescription="carousel" aria-label="首页 Banner">
    <div class="hero-slides">${slides}</div>
    <div class="hero-grid"></div>
    ${heroCopyHtml}
    <button type="button" class="hero-nav hero-nav--prev" aria-label="上一张"><svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M15 18l-6-6 6-6"/></svg></button>
    <button type="button" class="hero-nav hero-nav--next" aria-label="下一张"><svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M9 18l6-6-6-6"/></svg></button>
    <div class="hero-dots" role="tablist" aria-label="Banner 分页">${dots}</div>
  </section>`

  const strength = `<section class="home-strength" aria-label="企业实力">
    <div class="home-strength__intro"><span>LIQIANG AT A GLANCE</span><strong>扎根制造现场，提供设备与包材配套</strong></div>
    <div class="home-strength__facts">
      <a href="/about.html"><strong>${establishedYear}</strong><span>创立于温州</span></a>
      <a href="/products.html"><strong>${home.productCount || products.length}</strong><span>公开设备型号</span></a>
      <a href="/about.html#patents"><strong>—</strong><span>专利与知识产权</span></a>
      <a href="/about.html"><strong>配套</strong><span>设备 · 包材 · 方案</span></a>
    </div>
  </section>`

  const featured = (home.products || products).slice(0, 12)
  const productsSection = `<section class="section products" id="products">
    ${sectionHeading({ eyebrow: 'CORE EQUIPMENT', title: '核心设备', description: '从实际产品出发，选择更适合您的包装方式与生产节奏。', link: { href: '/products.html', text: '全部产品 →' } })}
    <div class="product-carousel" tabindex="0" role="region" aria-label="核心设备">
      <div class="live-catalog" aria-live="polite">${featured.map(productCard).join('')}</div>
    </div>
  </section>`

  /* 制造能力图廊：复用产品实拍图 3 张 */
  const showcaseImgs = featured.slice(0, 3)
  const showcaseItems = [
    { title: '包装设备制造', to: '/products.html', img: showcaseImgs[0] ? cardImage(showcaseImgs[0]) : LOGO, large: true },
    { title: '包材耗材配套', to: '/packaging.html', img: showcaseImgs[1] ? cardImage(showcaseImgs[1]) : LOGO, large: false },
    { title: '工厂现场与交付', to: '/about.html', img: showcaseImgs[2] ? cardImage(showcaseImgs[2]) : LOGO, large: false },
  ]
  const showcase = `<section class="section manufacturing-showcase" id="manufacturing-showcase">
    ${sectionHeading({ eyebrow: 'MANUFACTURING', title: '包装设备，来自自己的制造现场', description: '从设备装配到整机测试，我们在自己的生产现场完成关键制造与交付环节。', size: 'lg' })}
    <div class="manufacturing-gallery">
      ${showcaseItems.map((it) =>
        `<a href="${it.to}" class="manufacturing-card reveal${it.large ? ' manufacturing-card--large' : ''}">
          <img src="${it.img}" alt="${esc(it.title)}" loading="lazy" decoding="async" />
          <div class="manufacturing-card__overlay"><h3>${it.title}</h3><span>了解更多</span></div>
        </a>`).join('')}
    </div>
  </section>`

  const body = `${hero}${strength}${productsSection}${showcase}${siteCta({ eyebrow: 'START YOUR PROJECT', id: 'cta' })}`
  const html = shell({
    title: `${brandName}包装 · ${core.split('/')[1] || '食品包装设备与包材'}`,
    description: `${companyName}（${brandName}包装）提供封口机、气调包装机、真空包装机、热收缩设备、包装容器、封口膜、模具及配件的一站式包装解决方案。`,
    navKey: 'home', body, jsonLd: orgSchema(),
  })
  // 注入 hero 轮播数据脚本（在 site.js 前）
  const heroScript = `<script>window.__HERO=${JSON.stringify(heroData)}</script>`
  return html.replace('<script defer src="/assets/js/site.js"></script>', heroScript + '\n  <script defer src="/assets/js/site.js"></script>')
}

/* ---------- 产品中心 ---------- */
function buildProducts() {
  const tabBtns = ['', ...categories].map((c) =>
    `<button type="button" class="catalog-tab${c === '' ? ' active' : ''}" data-cat-filter="${esc(c)}">${c === '' ? '全部' : esc(c)}</button>`
  ).join('')

  const option = (val) => `<option value="${esc(val)}">${esc(val)}</option>`
  const selHtml = (key, label) =>
    `<label><span>${label}</span><select data-select-filter="${key}"><option value="">不限</option>${dims[key].map(option).join('')}</select></label>`

  const grid = products.map((p) =>
    productCard(p).replace('<a class="catalog-card catalog-card--cover"',
      `<a class="catalog-card catalog-card--cover" data-category="${esc(p.category || '')}" data-name="${esc(p.name)}" data-applicationTags="${esc((p.applicationTags || []).join('|'))}" data-containerTags="${esc((p.containerTags || []).join('|'))}" data-processTags="${esc((p.processTags || []).join('|'))}" data-capacityLevel="${esc(p.capacityLevel || '')}"`)
  ).join('')

  const body = `
    ${pageHero({
      eyebrow: 'PRODUCT CENTER',
      title: '产品中心',
      description: '覆盖封口、气调保鲜、灌装封口、贴体包装等包装设备，支持样品试封与非标定制。',
      image: mediaUrl('2'),
      primary: { text: '获取包装方案', href: '/contact.html#inquiry' },
      secondary: { text: '查看包材耗材', href: '/packaging.html' },
    })}
    <section class="section product-catalog" id="catalog">
      ${sectionHeading({ eyebrow: 'CATALOG', title: '全部设备型号', description: `当前共收录 ${products.length} 款公开设备型号，可按分类或标签筛选。`, size: 'lg' })}
      <div class="page-toolbar" id="filter-bar">
        <div class="product-filter__search">
          <input id="product-search" type="search" placeholder="搜索设备名称 / 型号…" aria-label="搜索产品" />
        </div>
        <div class="product-filter__tabs catalog-tabs" role="tablist" aria-label="产品分类">${tabBtns}</div>
        <div class="selection-filter">
          ${selHtml('applicationTags', '适用产品')}${selHtml('containerTags', '适用容器')}${selHtml('processTags', '包装工艺')}${selHtml('capacityLevel', '产能')}
          <button type="button" id="clear-filters" class="filter-clear">清除筛选</button>
        </div>
      </div>
      <div class="catalog-overview__meta"><span>共 <strong id="product-count">${products.length}</strong> 款</span></div>
      <div class="catalog-layout">
        <div class="catalog-grid" id="products-grid">${grid}</div>
        <div class="catalog-empty" id="products-empty" style="display:none"><p>没有符合当前筛选条件的产品，试试调整筛选或直接联系我们。</p></div>
      </div>
    </section>
    ${siteCta({ eyebrow: 'PRODUCT CONSULTATION', title: '不确定选哪款设备？', description: '提供产品、容器尺寸与目标产能，工程师将协助核对设备与包材配置。', actionText: '咨询选型', actionTo: '/contact.html#inquiry', id: 'cta' })}`

  return shell({
    title: `产品中心 | ${brandName}包装`,
    description: `浏览${brandName}包装全部 ${products.length} 款包装设备型号，涵盖封口机、封切机、气调包装机、气调封口机、贴体包装机、托盒封口机、灌装封口生产线、辅助设备与配件。`,
    navKey: 'products', body, canonical: `${SITE}/products.html`, jsonLd: orgSchema(),
  })
}

/* ---------- 产品详情 ---------- */
function buildProductDetail(p) {
  const slug = p.slug || p.id
  const detail = detailBySlug[slug]
  const gallery = []
  if (detail && Array.isArray(detail.gallery)) {
    for (const g of detail.gallery) if (g && g.mediaId) gallery.push({ url: mediaUrl(g.mediaId), alt: g.altText || p.name })
  }
  if (!gallery.length) gallery.push({ url: cardImage(p), alt: p.name })

  const main = gallery[0]
  const thumbs = gallery.length > 1
    ? `<div class="detail-thumbnails" aria-label="产品图片选择">${gallery.map((g, i) =>
        `<button type="button" class="detail-thumbnail${i === 0 ? ' active' : ''}" data-index="${i}" data-src="${g.url}" aria-label="查看第 ${i + 1} 张产品图片"><img src="${g.url}" alt="${esc(g.alt)}" loading="lazy" decoding="async" /></button>`).join('')}</div>`
    : ''

  const selectionFacts = [
    { label: '适用产品 / 场景', value: (p.applicationTags || []).join('、') || undefined },
    { label: '适用容器', value: (p.containerTags || []).join('、') || undefined },
    { label: '支持工艺', value: (p.processTags || []).join('、') || undefined },
    { label: '目标产能', value: p.capacityLevel || undefined },
  ].filter((f) => f.value)
  const keyFacts = selectionFacts.slice(0, 4)

  const introHtml = (detail && detail.descriptionHtml) || ''
  const technical = detail && detail.technicalParameters && typeof detail.technicalParameters === 'object'
    ? Object.entries(detail.technicalParameters).filter(([, v]) => v)
    : []

  const sections = []
  if (introHtml) sections.push(`<section id="introduction" class="detail-section"><div class="detail-section__heading"><span>OVERVIEW</span><h2>产品介绍</h2></div><div class="detail-section__content"><div class="detail-text">${introHtml}</div></div></section>`)
  if (selectionFacts.length) sections.push(`<section id="applications" class="detail-section"><div class="detail-section__heading"><span>APPLICATIONS</span><h2>适用行业与产品</h2></div><div class="detail-section__content"><dl class="application-facts">${selectionFacts.map((f) => `<div><dt>${f.label}</dt><dd>${esc(f.value)}</dd></div>`).join('')}</dl></div></section>`)
  if (technical.length) sections.push(`<section id="specifications" class="detail-section"><div class="detail-section__heading"><span>SPECIFICATIONS</span><h2>技术参数</h2></div><div class="detail-section__content"><dl class="detail-key-facts spec-facts">${technical.map(([k, v]) => `<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>`).join('')}</dl></div></section>`)
  if (!sections.length) sections.push(`<section class="detail-section"><div class="detail-section__content"><p class="detail-text">该型号的详细规格与参数正在持续完善中，如需完整参数或样品测试，欢迎联系工程师。</p></div></section>`)

  const modelRow = [p.publicModel, p.category].filter(Boolean).map((x) => `<span>${esc(x)}</span>`).join('')

  const body = `
    <nav class="breadcrumb"><a href="/index.html">首页</a><span>/</span><a href="/products.html">产品中心</a><span>/</span><strong>${esc(p.name)}</strong></nav>
    <div class="detail-container">
      <div class="detail-gallery">
        <div class="detail-main-image"><img src="${main.url}" alt="${esc(p.name)}" decoding="async" /></div>
        ${thumbs}
      </div>
      <div class="detail-info">
        ${modelRow ? `<div class="detail-heading-meta">${modelRow}</div>` : ''}
        <h1>${esc(p.name)}</h1>
        ${p.summary ? `<p class="detail-summary">${esc(p.summary)}</p>` : ''}
        ${keyFacts.length ? `<div class="detail-selection-facts"><span class="detail-selection-label">关键选型信息</span><dl class="detail-key-facts">${keyFacts.map((f) => `<div><dt>${f.label}</dt><dd>${esc(f.value)}</dd></div>`).join('')}</dl></div>` : ''}
        <div class="detail-cta">
          <a class="button primary" href="/contact.html#inquiry">咨询设备配置 <span>→</span></a>
          <a class="button ghost" href="/contact.html#inquiry">申请样品试封</a>
        </div>
        ${p.category ? `<div class="chips"><span>${esc(p.category)}</span>${(p.processTags || []).map((t) => `<span>${esc(t)}</span>`).join('')}${p.capacityLevel ? `<span>${esc(p.capacityLevel)}</span>` : ''}</div>` : ''}
      </div>
    </div>
    <div class="detail-sections">${sections.join('')}</div>
    ${siteCta({ eyebrow: 'PRODUCT CONSULTATION', title: `确认「${p.name}」是否适合您的产品`, description: '提供产品、容器尺寸和目标产能，工程师将协助核对设备与包材配置。', actionText: '咨询该产品', actionTo: '/contact.html#inquiry', id: 'cta' })}`

  const title = [p.name, p.publicModel].filter(Boolean).join('｜') || '产品详情'
  return shell({
    title: `${title} | ${brandName}包装`,
    description: (p.seoDescription || p.summary || `${p.name}｜${p.category || '食品包装设备'}，支持非标定制与样品试封。`).slice(0, 160),
    navKey: 'products', body,
    canonical: `${SITE}/products/${slug}.html`, jsonLd: productSchema(p),
  })
}

/* ---------- 解决方案 ---------- */
function buildSolutions() {
  const solGroup = solutions.find((g) => g.section === 'SOLUTION')
  const procGroup = solutions.find((g) => g.section === 'PROCESS')
  const dirs = (solGroup && solGroup.items) || []
  const steps = (procGroup && procGroup.items) || []

  const dirCards = dirs.length
    ? `<div class="solution-direction-grid">${dirs.map((d) =>
        `<article class="solution-direction-card reveal">
          <div class="dir-icon">${(d.icon || 'gear').charAt(0).toUpperCase()}</div>
          <h3>${esc(d.title)}</h3>
          <p>${esc(d.summary || '')}</p>
          <ul>${(d.points || []).map((pt) => `<li>${esc(pt)}</li>`).join('')}</ul>
        </article>`).join('')}</div>`
    : ''

  const stepsHtml = steps.length
    ? `<div class="process-steps">${steps.map((s, i) =>
        `<article class="process-step reveal"><div class="step-no">0${i + 1}</div><h3>${esc(s.title)}</h3><p>${esc(s.summary || '')}</p></article>`).join('')}</div>`
    : ''

  const body = `
    ${pageHero({
      eyebrow: 'SOLUTIONS',
      title: '包装解决方案',
      description: '围绕锁鲜气调、封口、自动化产线与耗材模具，为不同产品匹配设备、模具与包材组合。',
      image: mediaUrl('3'),
      primary: { text: '获取包装方案', href: '/contact.html#inquiry' },
      secondary: { text: '浏览产品中心', href: '/products.html' },
    })}
    <section class="section section--alt">
      ${sectionHeading({ eyebrow: 'APPROACH', title: '解决方案方向', description: '从实际产品出发，覆盖常见包装工艺与产线需求。', size: 'lg' })}
      ${dirCards}
    </section>
    <section class="section">
      ${sectionHeading({ eyebrow: 'PROCESS', title: '合作流程', description: '从需求确认到交付支持，五个环节依次推进。', size: 'lg' })}
      ${stepsHtml}
    </section>
    <section class="section section--alt solution-library">
      ${sectionHeading({ eyebrow: 'IN-DEPTH', title: '深入阅读', description: '更多针对具体场景的方案文章正在整理中。' })}
      <div class="solution-empty reveal">
        <p class="eyebrow light"><span></span>COMING SOON</p>
        <h2>方案内容正在整理中</h2>
        <p>针对锁鲜气调、封口工艺与自动化产线的详细方案文章正在持续编写，您可以先浏览产品中心或直接咨询工程师。</p>
        <div class="solution-empty__actions">
          <a class="button primary" href="/products.html">浏览产品 <span>→</span></a>
          <a class="button ghost" href="/contact.html#inquiry">咨询工程师</a>
        </div>
      </div>
    </section>
    ${siteCta({ eyebrow: 'CUSTOM SOLUTION', title: '需要定制化包装方案？', description: '提供产品、容器、产能与现有产线信息，我们协助完成设备与工艺组合设计。', actionText: '提交需求', actionTo: '/contact.html#inquiry', id: 'cta' })}`

  return shell({
    title: `解决方案 | ${brandName}包装`,
    description: `${brandName}包装提供锁鲜气调、封口包装、自动化产线与耗材模具的整体包装解决方案，支持定制化设备与工艺设计。`,
    navKey: 'solutions', body, canonical: `${SITE}/solutions.html`, jsonLd: orgSchema(),
  })
}

/* ---------- 包材耗材 ---------- */
function buildPackaging() {
  const families = [
    { title: '封口膜', desc: '片膜与卷膜，适配不同容器口径与封口工艺。', icon: '膜' },
    { title: '托盒与容器', desc: '多种规格托盒与容器，匹配贴体、气调与封口包装。', icon: '盒' },
    { title: '其他配套耗材', desc: '模具、易损件与配套耗材，保障设备持续运行。', icon: '材' },
  ]
  const checks = [
    { title: '确定包装方式', desc: '封口 / 气调 / 贴体 / 真空，对应不同设备与膜材组合。' },
    { title: '测量容器与膜宽', desc: '容器口径、深度与膜宽直接影响模具与封口尺寸选型。' },
    { title: '进行样品试封', desc: '提供样品试封验证封合效果，确认后再批量配置。' },
  ]
  const body = `
    ${pageHero({
      eyebrow: 'PACKAGING SUPPLIES',
      title: '包材耗材',
      description: '封口膜、包装容器与定制模具协同匹配，让设备更快投入生产。',
      image: mediaUrl('2'),
      primary: { text: '获取包装方案', href: '/contact.html#inquiry' },
      secondary: { text: '浏览设备', href: '/products.html' },
    })}
    <section class="section section--alt">
      ${sectionHeading({ eyebrow: 'FAMILIES', title: '材料家族', description: '围绕封口与气调包装的主要耗材类型。', size: 'lg' })}
      <div class="packaging-family-grid">${families.map((f) =>
        `<article class="reveal"><div>${f.icon}</div><h3>${f.title}</h3><p>${f.desc}</p></article>`).join('')}</div>
    </section>
    <section class="section">
      ${sectionHeading({ eyebrow: 'CATALOG', title: '包材目录', description: '具体封口膜、容器与模具系列正在持续整理中。' })}
      <div class="packaging-catalog-state"><strong>具体系列持续整理中</strong><p>完整的包材选型目录与规格参数正在完善，可先联系工程师获取当前可配套的膜材与容器信息。</p></div>
    </section>
    <section class="section section--alt">
      ${sectionHeading({ eyebrow: 'VERIFY', title: '选购前验证 3 步', description: '包材与设备需结合样品实测确认适配性。', size: 'lg' })}
      <div class="packaging-selection-grid">${checks.map((c, i) =>
        `<article class="reveal"><div class="step-no">0${i + 1}</div><h3>${c.title}</h3><p>${c.desc}</p></article>`).join('')}</div>
    </section>
    ${siteCta({ eyebrow: 'PACKAGING SUPPLIES', title: '需要包材与模具配套？', description: '说明您的容器、产品与包装方式，我们协助匹配封口膜、容器与模具。', actionText: '咨询配套', actionTo: '/contact.html#inquiry', id: 'cta' })}`

  return shell({
    title: `包材耗材 | ${brandName}包装`,
    description: `${brandName}包装提供封口膜、包装容器与定制模具等包材耗材配套，与封口、气调、贴体包装设备协同匹配。`,
    navKey: 'supplies', body, canonical: `${SITE}/packaging.html`, jsonLd: orgSchema(),
  })
}

/* ---------- 关于利强 ---------- */
function buildAbout() {
  const splitList = (raw) => (raw || '').split(/[、，,；;]+/).map((s) => s.trim()).filter(Boolean)
  const businessList = splitList(mainBusiness)
  const manufacturingStrengthList = strengths
  const yearsOfExperience = (year - parseInt(establishedYear)) + '年'
  const productCount = home.productCount || products.length

  const heroStats = [
    { label: '行业经验', value: yearsOfExperience },
    { label: '产品系列', value: `${productCount}+` },
    { label: '客户覆盖', value: '全国' },
    { label: '官网展示资质', value: '—' },
  ]
  const heroSide = `<div class="hero-stats">${heroStats.map((s) =>
    `<div class="stat-item"><span class="stat-value">${s.value}</span><span class="stat-label">${s.label}</span></div>`).join('')}</div>`

  const introFacts = [
    { v: establishedYear, l: '成立年份' },
    { v: productCount, l: '公开设备型号' },
    { v: '—', l: '公开知识产权' },
  ]
  const intro = `<section class="section about-intro">
    <div class="about-profile">
      <div class="about-profile__copy reveal">
        <p class="eyebrow dark"><span></span> WHO WE ARE</p>
        <h2>从包装设备制造出发，<br />把包材适配一起考虑</h2>
        <p class="intro-desc">${esc(mainBusiness || '')}，依托源头研发制造与非标定制能力，为食品包装客户提供一体化包装方案。</p>
        <div class="about-profile__business">
          <span>主营业务</span>
          <div>${businessList.map((b) => `<b>${esc(b)}</b>`).join('')}</div>
        </div>
        <div class="about-profile__facts">
          ${introFacts.map((f) => `<div><strong>${f.v}</strong><span>${f.l}</span></div>`).join('')}
        </div>
      </div>
      <div class="about-profile__visual reveal">
        <figure class="about-profile__image-main"><img src="${mediaUrl('2')}" alt="利强包装工厂环境" loading="lazy" decoding="async" /></figure>
      </div>
    </div>
  </section>`

  const mfgItems = manufacturingStrengthList.length
    ? manufacturingStrengthList.map((item, idx) =>
        `<div class="mfg-item"><b>${String(idx + 1).padStart(2, '0')}</b><div><strong>${esc(item.title)}</strong><p>${esc(item.description || '')}</p></div></div>`).join('')
    : ''
  const manufacturing = `<section class="section about-manufacturing" id="manufacturing">
    ${sectionHeading({ eyebrow: 'MANUFACTURING', title: '厂房设备', description: '通过生产、装配与调试现场，展示设备从制造到交付验证的主要环节。' })}
    <div class="manufacturing-grid">
      <div class="manufacturing-image reveal">
        <img src="${mediaUrl('3')}" alt="利强包装生产车间" loading="lazy" decoding="async" />
        <div class="image-label"><span>MADE IN WENZHOU</span><strong>制造，从细节开始</strong></div>
      </div>
      <div class="manufacturing-list reveal">${mfgItems}</div>
    </div>
  </section>`

  const milestones = (home.milestones || []).map((m) =>
    `<article class="milestone-item reveal"><div class="milestone-year"><span>${esc(String(m.year))}</span></div><div class="milestone-node"></div><div class="milestone-content"><h3>${esc(m.title)}</h3><p>${esc(m.description || '')}</p></div></article>`).join('')
  const milestonesSection = `<section class="section about-milestones">
    ${sectionHeading({ eyebrow: 'OUR JOURNEY', title: '企业历程', description: '以下为利强发展历程中的关键节点。', theme: 'light' })}
    <div class="milestones-timeline">${milestones}</div>
  </section>`

  const placeholderCerts = [1, 2, 3, 4].map((n) =>
    `<article class="cert-thumb reveal placeholder-thumb"><div class="cert-thumb__badge">盾</div><div class="cert-thumb__caption"><span class="cert-thumb__cat">ISO · CE · 检测报告</span><strong class="cert-thumb__name">管理体系与产品合规认证</strong></div></article>`).join('')
  const credentials = `<section class="section about-credentials section--alt" id="credentials">
    ${sectionHeading({ eyebrow: 'CERTIFICATIONS', title: '企业荣誉与资质认证', description: '资质信息持续整理中，具体文件可联系我们获取。' })}
    <div class="credentials-preview about-showcase-track">${placeholderCerts}</div>
  </section>`

  const patents = `<section class="section about-patents" id="patents">
    ${sectionHeading({ eyebrow: 'PATENTS &amp; IP', title: '专利与知识产权', description: '专利信息持续整理中，具体资料可联系我们获取。' })}
    <div class="patents-preview about-showcase-track"><div class="patents-empty"><strong>暂无公开展示的知识产权档案</strong><span>专利信息持续整理中，完整知识产权清单请直接联系我们获取。</span></div></div>
  </section>`

  const contact = `<section id="contact" class="section about-contact section--alt">
    ${sectionHeading({ eyebrow: 'CONTACT LIQIANG', title: '联系利强', description: '您可以直接电话、邮件或通过官方账号联系我们；方案和样品需求可提交咨询表单。' })}
    <div class="about-contact-layout">
      <div class="about-contact-grid">
        <article><span>咨询电话</span><strong>${esc(phoneText)}</strong><small>工作日 08:30–17:30</small><button type="button" data-copy="${esc(phoneRaw)}">复制电话 →</button></article>
        <article><span>商务邮箱</span><strong>${esc(email)}</strong><small>用于商务合作与资料往来</small><button type="button" data-copy="${esc(email)}">复制邮箱 →</button></article>
        <article><span>公司地址</span><strong>${esc(address)}</strong><small>${esc(companyName)}</small><a href="https://www.amap.com/search?query=${encodeURIComponent(companyName + ' ' + address)}" target="_blank" rel="noopener noreferrer">查看路线 →</a></article>
      </div>
      <div class="about-contact-map">
        <div class="about-contact-map__viewport">
          <a class="about-contact-map__fallback" href="https://www.amap.com/search?query=${encodeURIComponent(companyName + ' ' + address)}" target="_blank" rel="noopener noreferrer">
            <strong>查看公司位置</strong><span>在高德地图中打开路线 →</span>
          </a>
        </div>
        <div class="about-contact-map__caption"><span>${esc(address)}</span><a href="https://www.amap.com/search?query=${encodeURIComponent(companyName + ' ' + address)}" target="_blank" rel="noopener noreferrer">在高德地图中查看 →</a></div>
      </div>
    </div>
  </section>`

  const body = `
    ${pageHero({
      eyebrow: 'ABOUT LIQIANG', title: '扎根包装制造，<br /><em>围绕真实生产需求持续积累</em>',
      description: '了解利强的企业定位、制造能力、发展历程，以及公开展示的资质认证与知识产权成果。',
      image: mediaUrl('2'),
      primary: { text: '获取包装方案', href: '/contact.html#inquiry' },
      twoCol: true, side: heroSide,
    })}
    ${intro}${manufacturing}${milestonesSection}${credentials}${patents}${contact}
    ${siteCta({ eyebrow: 'WORK WITH US', title: '准备好开始包装项目了吗？', description: '告诉我们您的产品与需求，从样品试封到量产交付一路配合。', actionText: '联系利强', actionTo: '/contact.html#inquiry', id: 'cta' })}`

  return shell({
    title: `关于利强 | ${brandName}包装`,
    description: `了解${companyName}（${brandName}包装）：成立于 ${establishedYear} 年，专注食品包装设备与包材的一站式供应商，覆盖封口、气调、灌装封口等设备。`,
    navKey: 'about', body, canonical: `${SITE}/about.html`, jsonLd: orgSchema(),
  })
}

/* ---------- 联系页 ---------- */
function buildContact() {
  const body = `
    ${pageHero({
      eyebrow: 'CONTACT',
      title: '联系我们',
      description: '告诉我们您的包装需求，工程师将协助梳理适合的设备与工艺方案。',
      image: mediaUrl('3'),
    })}
    <section class="contact-section" id="inquiry">
      <div class="contact-layout">
        <div class="contact-cards">
          <article class="contact-card reveal"><div class="contact-icon">电</div><div><h3>服务热线</h3><div class="contact-value">${esc(phoneText)}</div><button type="button" class="contact-action" data-copy="${esc(phoneText)}">复制号码</button></div></article>
          <article class="contact-card reveal"><div class="contact-icon">邮</div><div><h3>企业邮箱</h3><div class="contact-value">${esc(email)}</div><button type="button" class="contact-action" data-copy="${esc(email)}">复制邮箱</button></div></article>
          <article class="contact-card reveal"><div class="contact-icon">址</div><div><h3>公司地址</h3><div class="contact-value">${esc(address)}</div><p class="contact-note">浙江省温州市瓯海区</p></div></article>
        </div>
        <div class="inquiry-panel reveal">
          <p class="inquiry-panel__badge">INQUIRY FORM</p>
          <h2>提交包装需求</h2>
          <p class="inquiry-panel__desc">填写表单后将通过邮件发送给我们；或直接拨打服务热线沟通。样品试封、非标定制均可咨询。</p>
          <form class="inquiry-form" id="inquiry-form" data-mailto="${esc(email)}" novalidate>
            <div class="form-row">
              <div><label for="f-name">您的称呼</label><input id="f-name" name="name" type="text" placeholder="怎么称呼您" /></div>
              <div><label for="f-company">公司名称</label><input id="f-company" name="company" type="text" placeholder="选填" /></div>
            </div>
            <div class="form-row">
              <div><label for="f-phone">联系电话</label><input id="f-phone" name="phone" type="tel" placeholder="方便工程师与您沟通" /></div>
              <div><label for="f-email">联系邮箱</label><input id="f-email" name="email" type="email" placeholder="接收方案回复" /></div>
            </div>
            <div><label for="f-product">关注的产品 / 需求</label><input id="f-product" name="product" type="text" placeholder="例如：气调封口机 / 托盒封口机 / 封口膜" /></div>
            <div><label for="f-desc">需求描述</label><textarea id="f-desc" name="description" rows="4" placeholder="请描述您的产品、容器、目标产能或需要解决的问题"></textarea></div>
            <button type="submit" class="submit-button">提交需求 <span>→</span></button>
            <div class="inquiry-success" id="inquiry-success">已为您打开邮件发送窗口，请确认后发送；如未弹出，请直接拨打 ${esc(phoneText)}。</div>
            <div class="inquiry-privacy"><span>提交即表示同意我们与您联系沟通包装方案。</span></div>
          </form>
        </div>
      </div>
    </section>
    ${siteCta({ eyebrow: 'GET STARTED', title: '还有其他问题？', description: '设备、包材、模具或方案相关问题，欢迎随时来电咨询。', actionText: '拨打热线', actionTo: `tel:${phoneRaw}`, id: 'cta' })}`

  return shell({
    title: `联系我们 | ${brandName}包装`,
    description: `联系${brandName}包装：${esc(phoneText)} / ${esc(email)}，地址 ${esc(address)}。提供设备、包材、模具与整体包装方案咨询。`,
    navKey: 'about', body, canonical: `${SITE}/contact.html`, jsonLd: orgSchema(),
  })
}

/* ---------- 404 ---------- */
function buildNotFound() {
  const body = `
    <main class="not-found-page">
      <div class="not-found-inner">
        <div class="not-found-code">404</div>
        <h1>页面走丢了</h1>
        <p>您访问的页面不存在或已下架，可能链接已失效。</p>
        <p class="not-found-hint">不用担心，以下入口可以帮您回到正轨：</p>
        <div class="not-found-actions">
          <a href="/index.html" class="button primary">返回首页 <span>→</span></a>
          <a href="/products.html" class="button secondary">浏览产品</a>
          <a href="/contact.html#inquiry" class="text-link">联系获取帮助 <span>→</span></a>
        </div>
      </div>
    </main>`
  return shell({ title: `页面未找到 | ${brandName}包装`, description: '您访问的页面不存在或已下架，请返回利强包装官网首页继续浏览。', navKey: 'home', body, canonical: `${SITE}/404.html` })
}

/* ============================================================
   输出
   ============================================================ */
function writeOut() {
  fs.rmSync(OUT, { recursive: true, force: true })
  fs.mkdirSync(path.join(OUT, 'products'), { recursive: true })
  copyStatic()

  const writes = []
  writes.push(['index.html', buildHome()])
  writes.push(['products.html', buildProducts()])
  for (const p of products) writes.push([`products/${(p.slug || p.id)}.html`, buildProductDetail(p)])
  writes.push(['solutions.html', buildSolutions()])
  writes.push(['packaging.html', buildPackaging()])
  writes.push(['about.html', buildAbout()])
  writes.push(['contact.html', buildContact()])
  writes.push(['404.html', buildNotFound()])

  for (const [rel, html] of writes) {
    const abs = path.join(OUT, rel)
    fs.writeFileSync(abs, html, 'utf8')
  }

  /* robots.txt */
  fs.writeFileSync(path.join(OUT, 'robots.txt'),
    `User-agent: *\nAllow: /\nSitemap: ${SITE}/sitemap.xml\n`, 'utf8')

  /* sitemap.xml */
  const urls = [
    `${SITE}/`, `${SITE}/products.html`, `${SITE}/solutions.html`,
    `${SITE}/packaging.html`, `${SITE}/about.html`, `${SITE}/contact.html`,
    ...products.map((p) => `${SITE}/products/${(p.slug || p.id)}.html`),
  ]
  const sitemap = `<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n${urls.map((u) => `  <url><loc>${u}</loc></url>`).join('\n')}\n</urlset>\n`
  fs.writeFileSync(path.join(OUT, 'sitemap.xml'), sitemap, 'utf8')

  /* _redirects：无 .html 后缀的旧路由兼容 */
  fs.writeFileSync(path.join(OUT, '_redirects'),
    `/products/*  /products/:splat.html  200\n/solutions  /solutions.html  200\n/packaging  /packaging.html  200\n/about  /about.html  200\n/contact  /contact.html  200\n/products  /products.html  200\n/index.html  /  301\n`, 'utf8')

  /* 部署说明 */
  fs.writeFileSync(path.join(ROOT, 'DEPLOY-Cloudflare.md'),
`# 利强包装 · 静态官网部署到 Cloudflare Pages

本目录下的 \`dist/\` 是**完全自包含的纯静态多页站**（无后端、无数据库、无构建依赖），可直接部署到 Cloudflare Pages 免费版。

## 本地预览
\`\`\`bash
cd liqiang-static-site
npx serve dist          # 或任意静态服务器
# 打开 http://localhost:3000
\`\`\`

## 部署步骤（Cloudflare Pages · 免费）
1. 登录 Cloudflare 控制台 → Workers & Pages → Create → Pages → Connect to Git（推荐，自动构建）
   - 若用 Git 连接，把 \`dist\` 目录放进仓库；Framework preset 选 **None**，Build command 留空，
     Build output directory 填 \`dist\`（或直接用 Wrangler 上传 dist）。
2. 或者直接拖拽上传：Workers & Pages → Create → Pages → Upload assets，选择 \`dist\` 文件夹。
3. 绑定自定义域 \`liqiangpacking.com\`（域名已在阿里云注册，按 Cloudflare 提示添加 CNAME/NS 记录）。
4. 首次部署后，在 Pages 项目的域设置里把 \`liqiangpacking.com\` 设为自定义域名，DNS 指向 Pages。

## 说明
- 全部页面为真实 .html（利于 SEO），已附带 \`sitemap.xml\` 与 \`robots.txt\`。
- \`_redirects\` 已配置：\`/products/{slug}\` 等无后缀路由会 200 转发到对应 .html，兼容旧书签。
- 询盘表单为**无后端降级方案**：提交时通过 \`mailto:\` 组装邮件发送到 ${email}，不依赖任何服务端接口。
- 图片已按 Web 优化（最大边 1400px、JPEG q85），单个文件均远低于 Cloudflare 25MB 限制。
- 若日后接入真实表单后端/CRM，可替换 \`contact.html\` 中表单的提交逻辑即可。
`, 'utf8')

  console.log(`✔ 已生成 ${writes.length} 个页面到 dist/`)
  console.log(`  · 页面：${writes.length}（首页 1 + 产品中心 1 + 产品详情 ${products.length} + 方案 1 + 包材 1 + 关于 1 + 联系 1 + 404 1）`)
  console.log(`  · 图片：${fs.readdirSync(IMG_DIR).length} 张（assets/images）`)
}

writeOut()
