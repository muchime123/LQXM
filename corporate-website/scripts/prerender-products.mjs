import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import mediaFiles from '../src/data/websiteMediaManifest.js'
import { reviewedSummary } from '../src/utils/publicSummary.js'
import { SITE_URL } from './env.mjs'

const projectRoot = fileURLToPath(new URL('../', import.meta.url))
const outputArg = process.argv.indexOf('--outDir')
const outDir = resolve(projectRoot, outputArg >= 0 ? process.argv[outputArg + 1] : 'dist')
/* 与 useSeo.js、内容校验同一取值口径（见 scripts/env.mjs） */
const siteUrl = SITE_URL
const escapeHtml = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
})[char])
const escapeXml = escapeHtml

function setMeta(html, key, value, attribute = 'name') {
  const escaped = escapeHtml(value)
  const pattern = new RegExp(`<meta ${attribute}="${key}" content="[^"]*" \\/>`)
  const tag = `<meta ${attribute}="${key}" content="${escaped}" />`
  return pattern.test(html) ? html.replace(pattern, tag) : html.replace('</head>', `    ${tag}\n  </head>`)
}

function productHtml(template, product, detail) {
  const name = product.name || '包装设备'
  const slug = product.slug
  const title = product.seoTitle || name
  const fullTitle = title.includes('利强包装') ? title : `${title}｜利强包装`
  const summary = reviewedSummary(name, product.summary)
  const seoDescription = reviewedSummary(name, product.seoDescription)
    || summary || `${name}｜${product.category || '食品包装设备'}。查看设备图片与已公开技术参数。`
  const mediaId = product.heroMediaId || product.cardMediaId
  const mediaFile = mediaFiles[String(mediaId)]
  const imagePath = mediaFile ? `/content/media/${mediaFile}` : '/images/logo-only.png'
  const imageUrl = `${siteUrl}${imagePath}`
  const canonical = `${siteUrl}/products/${encodeURIComponent(slug)}`
  let html = template.replace(/<title>[^<]*<\/title>/, `<title>${escapeHtml(fullTitle)}</title>`)
  for (const key of ['description', 'twitter:description']) html = setMeta(html, key, seoDescription)
  html = setMeta(html, 'og:description', seoDescription, 'property')
  html = setMeta(html, 'og:title', fullTitle, 'property')
  html = setMeta(html, 'og:url', canonical, 'property')
  html = setMeta(html, 'twitter:title', fullTitle)
  html = setMeta(html, 'og:image', imageUrl, 'property')
  html = setMeta(html, 'twitter:image', imageUrl)
  html = setMeta(html, 'og:type', 'product', 'property')
  html = html.replace('</head>', `    <link rel="canonical" href="${escapeHtml(canonical)}" />\n  </head>`)
  const schema = JSON.stringify({
    '@context': 'https://schema.org', '@type': 'Product', name,
    image: imageUrl, description: seoDescription, url: canonical,
    brand: { '@type': 'Brand', name: '利强包装' },
  }).replace(/</g, '\\u003c')
  html = html.replace('</head>', `    <script type="application/ld+json">${schema}</script>\n  </head>`)
  const intro = detail?.descriptionHtml ? '' : summary
  const body = `<main><h1>${escapeHtml(name)}</h1><p>${escapeHtml(intro || product.category || '食品包装设备')}</p>`
    + `<img src="${escapeHtml(imagePath)}" alt="${escapeHtml(name)}" />`
    + `<p><a href="/products">浏览全部包装设备</a></p></main>`
  return html.replace('<div id="app"></div>', `<div id="app">${body}</div>`)
}

export async function generateStaticProducts(destination = outDir) {
  const template = await readFile(join(destination, 'index.html'), 'utf8')
  const products = JSON.parse(await readFile(join(projectRoot, 'public/content/products.json'), 'utf8'))
  const details = JSON.parse(await readFile(join(projectRoot, 'public/content/product-details.json'), 'utf8'))
  const detailBySlug = new Map(details.map((detail) => [detail.product?.slug, detail]))
  const slugs = new Set()
  for (const product of products) {
    if (!product.slug || !/^[a-z0-9][a-z0-9-]*$/.test(product.slug) || slugs.has(product.slug)) {
      throw new Error(`Invalid or duplicate public product slug: ${product.slug}`)
    }
    if (!detailBySlug.has(product.slug)) throw new Error(`Missing product detail: ${product.slug}`)
    slugs.add(product.slug)
    const path = join(destination, 'products', `${product.slug}.html`)
    await mkdir(join(destination, 'products'), { recursive: true })
    await writeFile(path, productHtml(template, product, detailBySlug.get(product.slug)))
  }
  const urls = ['/', '/products', '/solutions', '/about', ...products.map((p) => `/products/${p.slug}`)]
  const sitemap = '<?xml version="1.0" encoding="UTF-8"?>\n'
    + '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n'
    + urls.map((path) => `  <url><loc>${escapeXml(siteUrl + path)}</loc></url>`).join('\n')
    + '\n</urlset>\n'
  await writeFile(join(destination, 'sitemap.xml'), sitemap)
  return products.length
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const count = await generateStaticProducts()
  process.stdout.write(`Generated ${count} product HTML pages and sitemap.xml\n`)
}
