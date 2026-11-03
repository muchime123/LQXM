/**
 * B1 官网静态内容数据完整性校验（只读，不修改任何业务数据）。
 *
 * 覆盖：
 *   1. 产品列表 slug -> 详情一一对应，列表/详情字段一致
 *   2. 全站内容引用的每个官网媒体 ID -> 清单 -> 物理文件
 *   3. 产品文案、型号、参数中的占位宣传语与未审核承诺
 *   4. 已下线内容（包材路由 / 精选 / sitemap / 导航 / 区块数据）
 *   5. 发布审核闸门：无业务签署文件时，不得判定为可发布
 *
 * 用法：
 *   npm run validate:content
 *   node scripts/validate-content.mjs --out <report.json> --strict
 *
 * --strict 在存在 error 级问题或未通过发布闸门时以退出码 1 结束，供 CI 使用。
 * 单独的 npm test 只断言结构性不变量，不受业务待审核项影响。
 *
 * 构建产物（dist / website-build-check）不入库，由 npm run build 生成；
 * 产物不存在时，依赖产物的检查标记为 SKIP 而不是报警，干净检出即可直接跑内容校验。
 */
import { readFile, readdir, stat, writeFile, mkdir } from 'node:fs/promises'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import mediaFiles from '../src/data/websiteMediaManifest.js'
import { reviewedSummary } from '../src/utils/publicSummary.js'
import { SITE_URL } from './env.mjs'

const projectRoot = fileURLToPath(new URL('../', import.meta.url))
const repoRoot = resolve(projectRoot, '..')
const contentDir = join(projectRoot, 'public', 'content')
const mediaDir = join(contentDir, 'media')
const reviewManifestPath = join(projectRoot, 'content-review.json')
/* 站点主域名见 scripts/env.mjs：与 useSeo.js、预渲染脚本同一取值口径。 */
const RETIRED_PATHS = ['/packaging', '/supplies', '/molds']
const CONTENT_FILES = [
  'banners', 'company', 'featured', 'home',
  'page-sections', 'product-details', 'products', 'solutions',
]
const MEDIA_ID_KEYS = new Set(['mediaId', 'heroMediaId', 'cardMediaId', 'logoMediaId'])
const MEDIA_URL_RE = /\/api\/website\/media\/(\d+)\/content/g
const SNAPSHOT_MEDIA_RE = /\/content\/media\/media_(\d+)\.(?:png|jpe?g|webp)/g
const PLACEHOLDER_CONTENT_RE = /^.+，专业[^，。]*，品质保障。$/
const UNSUBSTANTIATED_CLAIMS = ['支持非标定制与样品试封', '24 小时内响应', '免费', 'ISO9001', 'CE 认证']
const CLAIM_PHRASES = [
  '24 小时内响应', '免费', '非标定制', '样品试封', '品质保障',
  'ISO9001', 'CE 认证', '支持定制', '响应快', '一站式',
]
const SOURCE_FILES = [
  'src/views/Home.vue', 'src/views/About.vue', 'src/views/Solutions.vue',
  'src/views/Products.vue', 'src/views/ProductDetail.vue',
  'src/components/common/InquiryFormModal.vue', 'src/components/common/SiteCTA.vue',
  'src/components/AppFooter.vue', 'src/components/AppHeader.vue',
  'src/router/index.js', 'src/composables/useSchema.js', 'src/api/website.js',
]

/** 递归遍历 JSON 值，回调收到 (叶子值, 路径, 最近字段名)。 */
function walk(node, cb, path = '', key = '') {
  if (Array.isArray(node)) {
    node.forEach((value, index) => walk(value, cb, `${path}[${index}]`, key))
    return
  }
  if (node && typeof node === 'object') {
    Object.entries(node).forEach(([childKey, value]) =>
      walk(value, cb, path ? `${path}.${childKey}` : childKey, childKey))
    return
  }
  cb(node, path, key)
}

function truncate(text, length = 120) {
  const value = String(text).replace(/\s+/g, ' ').trim()
  return value.length > length ? `${value.slice(0, length)}…` : value
}

function matchAll(text, regex) {
  return [...String(text).matchAll(regex)].map((match) => match[1])
}

async function readJsonFile(path) {
  try {
    return { data: JSON.parse(await readFile(path, 'utf8')), error: null }
  } catch (error) {
    return { data: null, error: error.message }
  }
}

async function readText(path) {
  try {
    return await readFile(path, 'utf8')
  } catch {
    return null
  }
}

async function fileSize(path) {
  try {
    return (await stat(path)).size
  } catch {
    return -1
  }
}

/**
 * 执行全量校验。
 * @returns {Promise<object>} 校验报告（同时作为 JSON 交付件）
 */
export async function validateContent() {
  const checks = []
  const content = {}
  const readErrors = []

  for (const name of CONTENT_FILES) {
    const { data, error } = await readJsonFile(join(contentDir, `${name}.json`))
    if (error) readErrors.push(`${name}.json: ${error}`)
    content[name] = data
  }
  const add = (code, title, severity, issues, extra = {}) => {
    const status = issues.length === 0 ? 'pass'
      : severity === 'error' ? 'fail' : severity === 'info' ? 'info' : 'warn'
    checks.push({ code, title, severity, status, count: issues.length, issues, ...extra })
  }
  /* 依赖构建产物的检查在未构建时标记 SKIP，而不是用 WARN/FAIL 冒充问题：
     dist 不入库（见根 .gitignore），干净检出后必须先 npm run build 才有产物。 */
  const addSkipped = (code, title, reason, extra = {}) => {
    checks.push({
      code, title, severity: 'info', status: 'skip', count: 0, issues: [], skipped: reason, ...extra,
    })
  }

  /* ---------- 内容文件可读 ---------- */
  add('content.files', '内容快照文件读取与 JSON 合法性', 'error', readErrors)

  const products = Array.isArray(content.products) ? content.products : []
  const details = Array.isArray(content['product-details']) ? content['product-details'] : []
  const featured = Array.isArray(content.featured) ? content.featured : []
  const homeProducts = Array.isArray(content.home?.products) ? content.home.products : []
  const company = content.company || {}
  const pageSections = Array.isArray(content['page-sections']) ? content['page-sections'] : []
  const solutions = Array.isArray(content.solutions) ? content.solutions : []
  const banners = content.banners || {}
  const productSlugs = new Set(products.map((product) => product.slug))

  /* ---------- 产品 slug 与详情 ---------- */
  const slugIssues = []
  const seenSlugs = new Set()
  const seenIds = new Set()
  for (const product of products) {
    const slug = product.slug
    if (!slug || !/^[a-z0-9][a-z0-9-]*$/.test(slug)) slugIssues.push(`${product.id}: 非法 slug ${JSON.stringify(slug)}`)
    if (seenSlugs.has(slug)) slugIssues.push(`${product.id}: 重复 slug ${slug}`)
    seenSlugs.add(slug)
    if (seenIds.has(product.id)) slugIssues.push(`${product.id}: 重复产品 ID`)
    seenIds.add(product.id)
  }
  add('product.slug', '产品列表 slug / ID 唯一性与格式', 'error', slugIssues)

  const detailBySlug = new Map()
  const detailIssues = []
  for (const detail of details) {
    const slug = detail?.product?.slug
    if (!slug) {
      detailIssues.push('存在无 product.slug 的详情记录')
      continue
    }
    if (detailBySlug.has(slug)) detailIssues.push(`详情 slug 重复: ${slug}`)
    detailBySlug.set(slug, detail)
  }
  for (const product of products) {
    if (!detailBySlug.has(product.slug)) detailIssues.push(`列表产品缺少详情: ${product.slug}`)
  }
  for (const slug of detailBySlug.keys()) {
    if (!productSlugs.has(slug)) detailIssues.push(`详情未出现在发布列表: ${slug}`)
  }
  add('product.detail', '每个发布产品 slug 均有详情（双向）', 'error', detailIssues)

  /* ---------- 列表 / 详情字段一致性 + 字段完整性 ---------- */
  const fieldIssues = []
  const copyIssues = []
  const productMediaIssues = []
  const comparedFields = [
    'name', 'slug', 'category', 'publicModel', 'summary',
    'seoTitle', 'seoDescription', 'heroMediaId', 'cardMediaId',
  ]
  let emptyTechnicalParameters = 0
  let emptyVariants = 0
  let emptyDescriptionHtml = 0
  let emptyApplicationTags = 0
  let emptyContainerTags = 0

  for (const product of products) {
    const slug = product.slug
    const detail = detailBySlug.get(slug)
    if (!detail) continue

    for (const field of comparedFields) {
      if (JSON.stringify(detail.product?.[field]) !== JSON.stringify(product[field])) {
        const severityNote = ['name', 'slug', 'category', 'heroMediaId', 'cardMediaId'].includes(field)
          ? '结构性字段' : '文案字段'
        fieldIssues.push(`${slug}: 列表/详情 ${field} 不一致（${severityNote}）`)
      }
    }
    if (!product.name || !product.category) fieldIssues.push(`${slug}: 名称或设备分类为空`)
    if (typeof product.publicModel === 'string' && product.publicModel !== product.publicModel.trim()) {
      fieldIssues.push(`${slug}: 公开型号含首尾空格 ${JSON.stringify(product.publicModel)}`)
    }
    if (product.capacityLevel === '') fieldIssues.push(`${slug}: capacityLevel 为空字符串（应为 null）`)
    if (!product.applicationTags?.length) emptyApplicationTags++
    if (!product.containerTags?.length) emptyContainerTags++
    if (!Object.keys(detail.technicalParameters || {}).length) emptyTechnicalParameters++
    if (!detail.variants?.length) emptyVariants++
    if (!detail.descriptionHtml) emptyDescriptionHtml++

    const placeholderFields = [['summary', product.summary], ['seoDescription', product.seoDescription]]
      .filter(([, value]) => value && reviewedSummary(product.name, value) === '')
      .map(([field]) => field)
    if (placeholderFields.length) {
      copyIssues.push(`${slug}: ${placeholderFields.join(' / ')} 为生成式占位宣传句「${truncate(product.summary, 60)}」（运行时被 reviewedSummary 隐藏，SEO 描述退化为通用兜底）`)
    }
    const description = String(detail.descriptionHtml || '')
    if (PLACEHOLDER_CONTENT_RE.test(description.trim())) copyIssues.push(`${slug}: descriptionHtml 为占位宣传句`)
    for (const claim of UNSUBSTANTIATED_CLAIMS) {
      for (const [field, value] of [
        ['summary', product.summary], ['seoTitle', product.seoTitle],
        ['seoDescription', product.seoDescription], ['descriptionHtml', description],
      ]) {
        if (typeof value === 'string' && value.includes(claim)) {
          copyIssues.push(`${slug}: ${field} 含待业务确认承诺「${claim}」`)
        }
      }
    }

    for (const [field, id] of [['cardMediaId', product.cardMediaId], ['heroMediaId', product.heroMediaId]]) {
      if (!id) {
        productMediaIssues.push(`${slug}: 缺少 ${field}`)
        continue
      }
      if (!mediaFiles[String(id)]) productMediaIssues.push(`${slug}: ${field}=${id} 不在媒体清单`)
    }
    for (const item of detail.gallery || []) {
      if (!item.mediaId) {
        productMediaIssues.push(`${slug}: 图库项 ${item.id} 缺少 mediaId`)
      } else if (!mediaFiles[String(item.mediaId)]) {
        productMediaIssues.push(`${slug}: 图库 mediaId=${item.mediaId} 不在媒体清单`)
      }
    }
  }
  add('product.media', '产品卡片/主图/图库媒体 ID 均在清单中', 'error', productMediaIssues)
  add('product.copy', '产品文案不含占位宣传句与未经确认的承诺', 'error', copyIssues)
  add('product.fields', '产品展示字段完整性（型号/标签/参数/规格/正文）', 'warning', fieldIssues, {
    coverage: {
      products: products.length,
      emptyTechnicalParameters,
      emptyVariants,
      emptyDescriptionHtml,
      emptyApplicationTags,
      emptyContainerTags,
    },
  })

  /* ---------- 精选 / 首页产品 / 产品数 ---------- */
  const featuredIssues = []
  for (const [source, rows] of [['featured.json', featured], ['home.json', homeProducts]]) {
    for (const row of rows) {
      if (!productSlugs.has(row.slug)) featuredIssues.push(`${source}: 引用未发布产品 ${row.slug}`)
      if (RETIRED_PATHS.includes(row.slug)) featuredIssues.push(`${source}: 引用已下线内容 ${row.slug}`)
    }
  }
  if (content.home?.productCount !== products.length) {
    featuredIssues.push(`home.json productCount=${content.home?.productCount}，实际产品列表 ${products.length}`)
  }
  add('featured.slugs', '精选/首页产品均指向已发布 slug', 'error', featuredIssues)

  const featuredFlagCount = products.filter((product) => product.featured === true).length
  const featuredFlagIssues = featuredFlagCount === featured.length
    ? [] : [`products.json 中 featured=true 共 ${featuredFlagCount} 条，精选快照 featured.json 为 ${featured.length} 条；需确认发布口径`]
  add('featured.flags', '精选标记与精选快照条数口径一致', 'warning', featuredFlagIssues)

  /* ---------- 全站媒体引用 ---------- */
  const mediaRefs = new Map()
  const recordRef = (id, source) => {
    const key = String(id)
    if (!mediaRefs.has(key)) mediaRefs.set(key, new Set())
    mediaRefs.get(key).add(source)
  }
  const archiveIssues = []
  for (const name of CONTENT_FILES) {
    const data = content[name]
    if (!data) continue
    const serialized = JSON.stringify(data)
    if (serialized.includes('/api/file-assets/')) archiveIssues.push(`${name}.json 含内部档案资源路径 /api/file-assets/`)
    if (serialized.includes('defaultImageAssetId')) archiveIssues.push(`${name}.json 含旧档案图片字段 defaultImageAssetId`)
    for (const id of matchAll(serialized, MEDIA_URL_RE)) recordRef(id, `${name}.json:URL`)
    for (const id of matchAll(serialized, SNAPSHOT_MEDIA_RE)) recordRef(id, `${name}.json:snapshot`)
    walk(data, (value, path, key) => {
      if (!MEDIA_ID_KEYS.has(key)) return
      if (value === null || value === undefined || value === '') return
      recordRef(value, `${name}.json:${path}`)
    })
  }
  add('content.archiveUrls', '公开内容不含内部档案资源引用', 'error', archiveIssues)

  const unresolvedMedia = []
  for (const [id, sources] of [...mediaRefs].sort((a, b) => Number(a[0]) - Number(b[0]))) {
    const filename = mediaFiles[id]
    if (!filename) {
      unresolvedMedia.push({ id: Number(id), reason: '媒体清单无此 ID', sources: [...sources] })
      continue
    }
    const size = await fileSize(join(mediaDir, filename))
    if (size <= 0) unresolvedMedia.push({ id: Number(id), reason: `文件缺失或为空: ${filename}`, sources: [...sources] })
  }
  add('media.references', '全站内容引用的每个媒体 ID 都能找到实际文件', 'error', unresolvedMedia, {
    referencedMediaIds: mediaRefs.size,
  })

  const manifestIssues = []
  const manifestFilenames = new Set()
  for (const [id, filename] of Object.entries(mediaFiles)) {
    if (!/^media_\d+\.(?:png|jpe?g|webp)$/.test(filename)) manifestIssues.push(`ID ${id}: 文件名格式异常 ${filename}`)
    if (manifestFilenames.has(filename)) manifestIssues.push(`文件名重复: ${filename}`)
    manifestFilenames.add(filename)
    if (!filename.startsWith(`media_${id}.`)) manifestIssues.push(`ID ${id} 与文件名 ${filename} 不对应`)
    const size = await fileSize(join(mediaDir, filename))
    if (size <= 0) manifestIssues.push(`ID ${id}: 文件缺失或为空 ${filename}`)
  }
  add('media.manifest', '媒体清单条目均有对应且非空物理文件', 'error', manifestIssues)

  let orphanFiles = []
  try {
    const files = await readdir(mediaDir)
    orphanFiles = files.filter((file) => !manifestFilenames.has(file))
  } catch (error) {
    orphanFiles = [`无法读取媒体目录: ${error.message}`]
  }
  add('media.orphans', '静态媒体目录中不在清单内的游离文件', 'warning', orphanFiles)

  /* ---------- 已下线内容隔离 ---------- */
  const retiredContentIssues = []
  for (const name of CONTENT_FILES) {
    const data = content[name]
    if (!data) continue
    walk(data, (value, path, key) => {
      if (typeof value !== 'string') return
      if (key === 'pageKey' && value === 'PACKAGING') {
        retiredContentIssues.push(`${name}.json:${path} 仍含已下线页面区块 PACKAGING`)
      }
      for (const retired of RETIRED_PATHS) {
        /* 区块内容可能是内嵌 JSON 字符串，不能只判断对象级 link 字段 */
        const token = new RegExp(`["']${retired.replace('/', '\\/')}["']`).test(value)
        const exact = RETIRED_PATHS.includes(value)
        if (token || exact) {
          retiredContentIssues.push(`${name}.json:${path} 仍引用已下线路由 ${retired}`)
        }
      }
    })
  }
  add('retired.content', '已下线内容不进入页面区块与内容链接', 'warning', retiredContentIssues)

  const routerText = await readText(join(projectRoot, 'src/router/index.js')) || ''
  const headerText = await readText(join(projectRoot, 'src/components/AppHeader.vue')) || ''
  const footerText = await readText(join(projectRoot, 'src/components/AppFooter.vue')) || ''
  const routeIssues = []
  for (const retired of RETIRED_PATHS) {
    const match = routerText.match(new RegExp(`path: '${retired.replace('/', '\\/')}',[\\s\\S]*?\\n  \\},`))
    if (!match) {
      routeIssues.push(`路由缺少 ${retired} 的兼容跳转`)
      continue
    }
    if (!/redirect:\s*\{\s*path:\s*'\/products'\s*\}/.test(match[0])) routeIssues.push(`${retired} 未跳转 /products`)
    if (/component:/.test(match[0])) routeIssues.push(`${retired} 仍挂载页面组件`)
  }
  for (const [source, text] of [['AppHeader.vue', headerText], ['AppFooter.vue', footerText]]) {
    for (const retired of RETIRED_PATHS) {
      if (text.includes(`'${retired}'`) || text.includes(`"${retired}"`)) {
        routeIssues.push(`${source} 导航/链接仍含 ${retired}`)
      }
    }
  }
  add('retired.routes', '包材旧路由仅做 /products 跳转且不在导航中', 'error', routeIssues)

  const snapshotText = await readText(join(projectRoot, 'src/api/snapshot.js')) || ''
  const snapshotIssues = []
  if (!/pathname === '\/api\/website\/packaging'\) return \[\]/.test(snapshotText)) {
    snapshotIssues.push('snapshot.js 未对 /api/website/packaging 返回空列表')
  }
  add('retired.snapshot', '静态快照对包材公开接口返回空列表', 'error', snapshotIssues)

  const sitemapSources = [
    ['public/sitemap.xml', join(projectRoot, 'public/sitemap.xml')],
    ['dist/sitemap.xml', join(projectRoot, 'dist/sitemap.xml')],
    ['website-build-check/sitemap.xml', join(repoRoot, 'website-build-check/sitemap.xml')],
  ]
  const sitemapIssues = []
  const sitemapUrls = []
  for (const [label, path] of sitemapSources) {
    const text = await readText(path)
    if (text === null) continue
    const urls = matchAll(text, /<loc>([^<]+)<\/loc>/g)
    sitemapUrls.push({ source: label, count: urls.length })
    for (const url of urls) {
      if (!url.startsWith(`${SITE_URL}/`)) sitemapIssues.push(`${label}: 非中文站域名 URL ${url}`)
      const pathname = url.replace(SITE_URL, '')
      if (RETIRED_PATHS.some((retired) => pathname === retired || pathname.startsWith(`${retired}/`))) {
        sitemapIssues.push(`${label}: 含已下线路由 ${url}`)
      }
      if (pathname.startsWith('/products/')) {
        const slug = pathname.slice('/products/'.length).replace(/\/$/, '')
        if (!productSlugs.has(slug)) sitemapIssues.push(`${label}: 含未发布产品 ${slug}`)
      }
    }
  }
  add('retired.sitemap', 'sitemap 不含已下线路由或未发布产品', 'error', sitemapIssues, { sitemapUrls })

  const buildOutputs = [
    { label: 'dist', root: join(projectRoot, 'dist') },
    { label: 'website-build-check', root: join(repoRoot, 'website-build-check') },
  ]
  const prerenderIssues = []
  const prerenderCoverage = []
  for (const output of buildOutputs) {
    const sitemap = await readText(join(output.root, 'sitemap.xml'))
    if (!sitemap) continue
    const listed = new Set(matchAll(sitemap, /<loc>[^<]+\/products\/([^/<]+)<\/loc>/g))
    let htmlCount = 0
    for (const slug of productSlugs) {
      if (await fileSize(join(output.root, 'products', `${slug}.html`)) > 0) htmlCount++
    }
    prerenderCoverage.push({ output: output.label, sitemapProductUrls: listed.size, productHtmlFiles: htmlCount })
  }
  const complete = prerenderCoverage.some((row) =>
    row.sitemapProductUrls === products.length && row.productHtmlFiles === products.length)
  if (prerenderCoverage.length === 0) {
    addSkipped('seo.prerenderCoverage', '每个发布产品都有预渲染 HTML 与 sitemap 条目',
      '未发现 dist / website-build-check 产物，跳过产物覆盖检查。构建后（npm run build）再运行即生效', {
        prerenderCoverage,
      })
  } else {
    if (!complete) prerenderIssues.push('构建输出未同时覆盖全部产品的预渲染 HTML 与 sitemap 条目（检查 npm run build 产物）')
    add('seo.prerenderCoverage', '每个发布产品都有预渲染 HTML 与 sitemap 条目', 'warning', prerenderIssues, {
      prerenderCoverage,
    })
  }

  /* ---------- 解决方案：分组与页面读取口径一致性 ---------- */
  /* Solutions.vue 只读取 INDUSTRY（应用场景）与 ARTICLE（方案文章），
     快照缺这两组时页面会静默退化为空状态，必须在发布前显式暴露。 */
  const solutionItems = (section) => solutions.find((group) => group.section === section)?.items || []
  const sceneItems = solutionItems('INDUSTRY')
  const articleItems = solutionItems('ARTICLE')
  const articleCount = articleItems.length
  const solutionGroupIssues = []
  if (sceneItems.length === 0) {
    solutionGroupIssues.push('solutions.json 无 INDUSTRY 分组：/solutions 不展示应用场景，首页场景入口无法落地')
  }
  if (articleItems.length === 0) {
    solutionGroupIssues.push('solutions.json 无 ARTICLE 分组：/solutions 方案内容区与 /solutions/:slug 无可用文章')
  }
  add('content.solutionGroups', '方案快照分组与页面读取的 section 一致', 'warning', solutionGroupIssues, {
    groups: solutions.map((group) => ({ section: group.section, items: (group.items || []).length })),
  })

  const sceneKeys = new Set(sceneItems.map((item) => item.itemKey).filter(Boolean))
  const solutionArticleIssues = []
  const articleSlugs = new Set()
  for (const item of articleItems) {
    const slug = item.slug
    if (!slug || !/^[a-z0-9][a-z0-9-]*$/.test(slug)) {
      solutionArticleIssues.push(`方案文章 ${item.id}: 缺少或非法 slug ${JSON.stringify(slug)}`)
    } else if (articleSlugs.has(slug)) {
      solutionArticleIssues.push(`方案文章 ${item.id}: 重复 slug ${slug}`)
    }
    if (slug) articleSlugs.add(slug)
    if (!item.coverImageUrl) {
      solutionArticleIssues.push(`方案文章 ${slug || item.id}: 缺少 coverImageUrl，卡片与详情页会退化为占位图`)
    }
    for (const key of item.relatedSceneKeys || []) {
      if (!sceneKeys.has(key)) {
        solutionArticleIssues.push(`方案文章 ${slug || item.id}: relatedSceneKeys 引用不存在的场景 ${key}`)
      }
    }
  }
  add('content.solutionArticleRefs', '方案文章 slug 唯一且场景引用有效', 'error', solutionArticleIssues, {
    scenes: sceneItems.length,
    articles: articleItems.length,
  })

  /* 场景是首页场景入口与文章关联的落点：itemKey 为空或重复会让 /solutions?scene= 定位错位 */
  const sceneKeyIssues = []
  const sceneKeySeen = new Set()
  for (const scene of sceneItems) {
    const key = scene.itemKey
    if (!key) sceneKeyIssues.push(`场景 ${scene.id}: 缺少 itemKey，/solutions?scene= 无法定位`)
    else if (sceneKeySeen.has(key)) sceneKeyIssues.push(`场景 ${scene.id}: 重复 itemKey ${key}`)
    if (key) sceneKeySeen.add(key)
    if (!scene.coverImageUrl) sceneKeyIssues.push(`场景 ${key || scene.id}: 缺少封面图，卡片会退化为占位图`)
  }
  add('solution.sceneKeys', '方案场景 itemKey 唯一且含封面', 'error', sceneKeyIssues, {
    scenes: sceneItems.length,
  })

  /* Banner 是首屏唯一点击区，按钮目标失效等同死链，必须在发布前拦住 */
  const bannerIssues = []
  for (const [position, rows] of Object.entries(banners)) {
    for (const row of Array.isArray(rows) ? rows : []) {
      const label = `${position}#${row.id ?? row.sortOrder}`
      for (const field of ['buttonUrl', 'secondaryButtonUrl']) {
        const target = String(row[field] || '').trim()
        if (!target) continue
        if (!/^(\/|https?:\/\/|tel:|mailto:)/.test(target)) {
          bannerIssues.push(`${label}: ${field} 无法跳转 ${target}`)
          continue
        }
        if (!target.startsWith('/') || target.startsWith('//')) continue
        const path = target.split(/[?#]/)[0].replace(/\/$/, '') || '/'
        if (RETIRED_PATHS.some((retired) => path === retired || path.startsWith(`${retired}/`))) {
          bannerIssues.push(`${label}: ${field} 指向已下线路由 ${target}`)
        }
      }
    }
  }
  add('banner.links', 'Banner 按钮目标可用且不含已下线路由', 'error', bannerIssues)

  /* 证书与专利图片由官网静态目录直接维护（不走媒体清单），需单独确认物理文件存在 */
  const qualificationIssues = []
  const qualificationText = (await readText(join(projectRoot, 'src/data/qualifications.js'))) || ''
  const qualificationImages = [...new Set(matchAll(qualificationText, /image:\s*'([^']+)'/g))]
  for (const imagePath of qualificationImages) {
    let exists = false
    try {
      exists = (await stat(join(projectRoot, 'public', imagePath.replace(/^\//, '')))).size > 0
    } catch {
      exists = false
    }
    if (!exists) qualificationIssues.push(`证书/专利图片缺失或为空: ${imagePath}`)
  }
  add('qualification.media', '证书/专利图片文件存在', 'error', qualificationIssues, {
    images: qualificationImages.length,
  })

  const companyGapIssues = []
  for (const field of ['brandSlogan', 'mission', 'vision', 'values', 'factoryAddress', 'enterpriseScale']) {
    if (!company[field]) companyGapIssues.push(`company.json ${field} 为空`)
  }
  add('content.companyGaps', '公司资料空字段（需业务核实，不自动补全）', 'info', companyGapIssues)

  /* ---------- 联系方式与公开承诺清单 ---------- */
  const modalText = await readText(join(projectRoot, 'src/components/common/InquiryFormModal.vue')) || ''
  const fallbackPhone = modalText.match(/company\.value\?\.phone \|\| '([^']+)'/)?.[1] || ''
  const fallbackEmail = modalText.match(/company\.value\?\.email \|\| '([^']+)'/)?.[1] || ''
  const contactIssues = []
  if (fallbackPhone || fallbackEmail) {
    contactIssues.push(`InquiryFormModal.vue 硬编码兜底电话 ${fallbackPhone || '无'} / 邮箱 ${fallbackEmail || '无'}；建议移入官网内容配置并由业务确认`)
  }
  if (fallbackPhone && fallbackPhone !== company.phone) contactIssues.push(`兜底电话与 company.json 不一致: ${fallbackPhone} vs ${company.phone}`)
  if (fallbackEmail && fallbackEmail !== company.email) contactIssues.push(`兜底邮箱与 company.json 不一致: ${fallbackEmail} vs ${company.email}`)
  add('contact.fallback', '公开联系方式不依赖代码兜底值', 'warning', contactIssues, {
    configuredPhone: company.phone || '', configuredEmail: company.email || '',
  })

  const claimFindings = []
  for (const name of CONTENT_FILES) {
    if (!content[name]) continue
    walk(content[name], (value, path) => {
      if (typeof value !== 'string' || !value.trim()) return
      for (const phrase of CLAIM_PHRASES) {
        if (value.includes(phrase)) {
          claimFindings.push({ source: `${name}.json:${path}`, phrase, text: truncate(value, 90) })
          break
        }
      }
    })
  }
  for (const file of SOURCE_FILES) {
    const text = await readText(join(projectRoot, file))
    if (!text) continue
    text.split(/\r?\n/).forEach((line, index) => {
      for (const phrase of CLAIM_PHRASES) {
        if (line.includes(phrase)) {
          claimFindings.push({ source: `${file}:${index + 1}`, phrase, text: truncate(line, 90) })
          break
        }
      }
    })
  }
  const claimGroups = new Map()
  for (const finding of claimFindings) {
    if (!claimGroups.has(finding.phrase)) claimGroups.set(finding.phrase, [])
    claimGroups.get(finding.phrase).push(finding)
  }
  const claimInventory = [...claimGroups].map(([phrase, findings]) => ({
    phrase,
    count: findings.length,
    sources: [...new Set(findings.map((row) => row.source.split(':')[0]))].slice(0, 8),
    samples: findings.slice(0, 3).map((row) => `${row.source} — ${row.text}`),
  }))
  add('claims.inventory', '需业务逐条确认的公开承诺/资质表述清单（按短语汇总）', 'info', claimInventory, {
    totalMatches: claimFindings.length,
  })

  const seoText = await readText(join(projectRoot, 'src/composables/useSeo.js')) || ''
  const prerenderText = await readText(join(projectRoot, 'scripts/prerender-products.mjs')) || ''
  const seoIssues = []
  /* 站点地址必须来自配置（env.mjs / import.meta.env），不允许在页面或脚本里写死 */
  const configurable = (text) => /VITE_|import\.meta\.env|from '\.\/env\.mjs'/.test(text)
  if (!configurable(seoText)) seoIssues.push('useSeo.js 站点地址写死，未跟随部署域名；英文站不得复用中文 canonical')
  if (!configurable(prerenderText)) seoIssues.push('prerender-products.mjs 站点地址写死，sitemap/canonical 需与部署域名一致')

  /* 静态部署资产（robots / sitemap）最容易在换域名时被忘记，与 SITE_URL 对不上就明确报出 */
  const robotsText = (await readText(join(projectRoot, 'public/robots.txt'))) || ''
  const sitemapText = (await readText(join(projectRoot, 'public/sitemap.xml'))) || ''
  const robotsHosts = new Set(matchAll(robotsText, /Sitemap:\s*(\S+?)\/sitemap\.xml/g))
  for (const host of robotsHosts) {
    if (host !== SITE_URL) seoIssues.push(`public/robots.txt 声明的 sitemap 域名 ${host} 与站点地址 ${SITE_URL} 不一致`)
  }
  if (sitemapText && !sitemapText.includes(`${SITE_URL}/`)) {
    seoIssues.push(`public/sitemap.xml 未包含站点地址 ${SITE_URL}，换域名后需重新生成`)
  }
  add('seo.siteUrl', 'canonical / sitemap 站点地址可按部署环境配置', 'warning', seoIssues)

  /* ---------- 发布审核闸门 ---------- */
  const { data: review, error: reviewError } = await readJsonFile(reviewManifestPath)
  const reviewIssues = []
  if (reviewError || !review) {
    reviewIssues.push('缺少 corporate-website/content-review.json 业务审核签署文件；当前所有产品、联系方式与服务承诺均视为「待审核」，不得判定为可发布')
  } else {
    const approved = new Set(review.approvedProductSlugs || [])
    for (const slug of productSlugs) {
      if (!approved.has(slug)) reviewIssues.push(`审核清单未覆盖产品 ${slug}`)
    }
    for (const slug of approved) {
      if (!productSlugs.has(slug)) reviewIssues.push(`审核清单含未发布产品 ${slug}`)
    }
    if (!review.reviewedAt || !review.reviewer) reviewIssues.push('审核清单缺少 reviewedAt / reviewer')
    if (review.approvedContact !== true) reviewIssues.push('审核清单未确认公开联系方式（approvedContact=true）')
    if (!Array.isArray(review.approvedClaims) || review.approvedClaims.length === 0) {
      reviewIssues.push('审核清单未确认公开服务承诺（approvedClaims）')
    }
    if (review.approvedPhone !== company.phone || review.approvedEmail !== company.email) {
      reviewIssues.push('审核清单联系方式与 company.json 不一致')
    }
  }
  add('review.manifest', '发布审核闸门（业务签署后方可放行）', 'error', reviewIssues, {
    reviewManifestPath: 'corporate-website/content-review.json',
    expectedSchema: {
      reviewedAt: 'ISO 日期', reviewer: '业务审核人',
      approvedProductSlugs: ['全部已发布产品 slug'],
      approvedContact: true, approvedPhone: 'company.json.phone', approvedEmail: 'company.json.email',
      approvedClaims: ['已确认的公开承诺 ID/文案'],
    },
  })

  const errorFailures = checks.filter((check) => check.severity === 'error' && check.status === 'fail')
  const report = {
    generatedAt: new Date().toISOString(),
    scope: {
      projectRoot: 'corporate-website',
      contentFiles: CONTENT_FILES.map((name) => `public/content/${name}.json`),
      mediaManifest: 'src/data/websiteMediaManifest.js',
      staticMediaDir: 'public/content/media',
      note: '只读校验；不对产品名称、参数、公司事实做真伪判定，仅标记需业务审核项。',
    },
    totals: {
      products: products.length,
      details: details.length,
      featured: featured.length,
      homeProducts: homeProducts.length,
      manifestEntries: Object.keys(mediaFiles).length,
      referencedMediaIds: mediaRefs.size,
      solutionScenes: sceneItems.length,
      solutionArticles: articleCount,
      claimMatches: claimFindings.length,
    },
    checks,
    gate: {
      passed: errorFailures.length === 0,
      blockers: errorFailures.map((check) => check.code),
      rule: '全部 error 级检查通过且存在有效业务审核签署文件后，才允许进入正式发布产物。',
    },
  }
  return report
}

/** 控制台可读摘要。 */
export function formatConsole(report) {
  const lines = [
    `官网静态内容校验 ${report.generatedAt}`,
    `产品 ${report.totals.products} / 详情 ${report.totals.details} / 清单媒体 ${report.totals.manifestEntries} / 被引媒体 ${report.totals.referencedMediaIds}`,
    '',
  ]
  /* info 之前被当成 WARN 显示，会把"需业务核实"的清单误读成告警；按状态如实标注 */
  const MARKERS = { pass: 'PASS', fail: 'FAIL', warn: 'WARN', skip: 'SKIP', info: 'INFO' }
  for (const check of report.checks) {
    const marker = MARKERS[check.status] || 'INFO'
    lines.push(`[${marker}] ${check.code.padEnd(24)} ${String(check.count).padStart(3)} 项  ${check.title}`)
    if (check.status === 'skip' && check.skipped) lines.push(`       ${' '.repeat(24)} ${check.skipped}`)
  }
  lines.push('', `发布闸门: ${report.gate.passed ? '通过' : `未通过（阻断项 ${report.gate.blockers.join(', ')}）`}`)
  return lines.join('\n')
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const outArg = process.argv.indexOf('--out')
  const outPath = outArg >= 0
    ? resolve(process.cwd(), process.argv[outArg + 1])
    : join(repoRoot, 'docs/website-content-validation-20261102.json')
  const strict = process.argv.includes('--strict')
  const report = await validateContent()
  await mkdir(dirname(outPath), { recursive: true })
  await writeFile(outPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
  process.stdout.write(`${formatConsole(report)}\n报告: ${outPath}\n`)
  if (strict && !report.gate.passed) process.exitCode = 1
}
