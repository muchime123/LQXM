/** Build-time public content snapshot for the first Cloudflare Pages release. */
import mediaFiles from '../data/websiteMediaManifest.js'

const env = import.meta.env || {}
export const snapshotMode = env.VITE_WEBSITE_CONTENT_SOURCE === 'snapshot'
  || (Boolean(env.PROD) && env.VITE_WEBSITE_CONTENT_SOURCE !== 'api')

const contentRoot = `${env.BASE_URL || '/'}content/`

export function snapshotMediaUrl(id) {
  const filename = mediaFiles[String(id)]
  return filename ? `${contentRoot}media/${filename}` : ''
}

async function readSnapshot(name) {
  const response = await fetch(`${contentRoot}${name}.json`)
  if (!response.ok) throw new Error(`Snapshot ${name}: HTTP ${response.status}`)
  return rewriteMediaUrls(await response.json())
}

function rewriteMediaUrls(value) {
  return JSON.parse(JSON.stringify(value).replace(/\/api\/website\/media\/(\d+)\/content/g,
    (_url, id) => snapshotMediaUrl(id)))
}

function values(rows, field) {
  return [...new Set(rows.flatMap((row) => {
    const value = row[field]
    return Array.isArray(value) ? value : value ? [value] : []
  }))]
}

function productMatches(product, query) {
  const keyword = query.get('keyword')?.trim().toLocaleLowerCase()
  if (keyword && ![product.name, product.publicModel, product.category, product.summary]
    .some((text) => String(text || '').toLocaleLowerCase().includes(keyword))) return false
  if (query.get('category') && product.category !== query.get('category')) return false
  for (const [param, field] of [
    ['application', 'applicationTags'], ['container', 'containerTags'],
    ['process', 'processTags'],
  ]) {
    if (query.get(param) && !(product[field] || []).includes(query.get(param))) return false
  }
  return !query.get('capacity') || product.capacityLevel === query.get('capacity')
}

export async function snapshotGet(path) {
  const url = new URL(path, 'https://snapshot.local')
  const pathname = url.pathname
  if (pathname === '/api/website/home') return readSnapshot('home')
  if (pathname === '/api/website/company-detail') return readSnapshot('company')
  if (pathname === '/api/website/official-media-accounts') return []
  if (pathname === '/api/website/packaging') return []
  if (pathname === '/api/website/banners/public') return readSnapshot('banners')
  if (pathname === '/api/website/solutions/public') return readSnapshot('solutions')
  if (pathname === '/api/website/solutions/public/articles') {
    const groups = await readSnapshot('solutions')
    return groups.find((group) => group.section === 'ARTICLE')?.items || []
  }
  if (pathname.startsWith('/api/website/solutions/public/articles/')) {
    const slug = decodeURIComponent(pathname.slice('/api/website/solutions/public/articles/'.length))
    const articles = await snapshotGet('/api/website/solutions/public/articles')
    const article = articles.find((item) => item.slug === slug)
    if (!article) throw new Error('HTTP 404')
    return article
  }
  if (pathname.startsWith('/api/website/page-sections/')) {
    const pageKey = pathname.slice('/api/website/page-sections/'.length).toUpperCase()
    return (await readSnapshot('page-sections')).filter((row) => row.pageKey === pageKey)
  }
  if (pathname === '/api/website/products/featured') {
    const limit = Math.max(1, Math.min(Number(url.searchParams.get('limit')) || 500, 500))
    return (await readSnapshot('featured')).slice(0, limit)
  }
  if (pathname === '/api/website/product-categories') {
    const products = await readSnapshot('products')
    return {
      equipmentCategories: values(products, 'category'),
      productLines: values(products, 'category'),
      applications: values(products, 'applicationTags'),
      containers: values(products, 'containerTags'),
      processes: values(products, 'processTags'),
      capacityLevels: values(products, 'capacityLevel'),
    }
  }
  if (pathname === '/api/website/products') {
    const products = (await readSnapshot('products')).filter((row) => productMatches(row, url.searchParams))
    const size = Math.max(1, Math.min(100, Number(url.searchParams.get('size')) || 12))
    const totalPages = Math.ceil(products.length / size)
    const requested = Math.max(0, Number(url.searchParams.get('page')) || 0)
    /* 快照自身也要收敛页码：page=9999 这类异常链接不应返回空列表且无法自愈 */
    const page = totalPages > 0 ? Math.min(requested, totalPages - 1) : 0
    return { items: products.slice(page * size, (page + 1) * size), total: products.length,
      totalPages, page }
  }
  if (pathname.startsWith('/api/website/products/')) {
    const slug = decodeURIComponent(pathname.slice('/api/website/products/'.length))
    const detail = (await readSnapshot('product-details')).find((row) => row.product?.slug === slug)
    if (!detail) throw new Error('HTTP 404')
    return detail
  }
  throw new Error(`No public snapshot for ${pathname}`)
}
