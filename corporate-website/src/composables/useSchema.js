/**
 * 结构化数据(Schema.org)管理
 * - setSchema / mergeSchema / clearSchema 统一写入 #schema-org-data
 * - 多次调用时合并为 @graph 数组，支持一页多 Schema（如 BreadcrumbList + Organization + Product）
 * - 不同类型通过 @type 去重，后注入覆盖先注入
 */

const SCRIPT_ID = 'schema-org-data'
const CONTEXT = 'https://schema.org'
const STORE_KEY = '__schemaStore__'

/* 用全局 Map 按 @type 存当前页的 schema pieces，避免重复 */
function getStore() {
  if (!window[STORE_KEY]) window[STORE_KEY] = new Map()
  return window[STORE_KEY]
}

function write() {
  const pieces = Array.from(getStore().values()).filter(Boolean)
  let script = document.getElementById(SCRIPT_ID)
  if (pieces.length === 0) {
    if (script) script.remove()
    return
  }
  if (!script) {
    script = document.createElement('script')
    script.type = 'application/ld+json'
    script.id = SCRIPT_ID
    document.head.appendChild(script)
  }
  const payload = pieces.length === 1
    ? { '@context': CONTEXT, ...pieces[0] }
    : { '@context': CONTEXT, '@graph': pieces }
  script.textContent = JSON.stringify(payload)
}

/** 注入 schema（按 @type 覆盖同名类型，非空则保留） */
export function setSchema(schema) {
  if (!schema) return
  const nodes = schema['@graph'] || [schema]
  nodes.forEach((node) => {
    if (!node || !node['@type']) return
    const key = Array.isArray(node['@type']) ? node['@type'].join('+') : node['@type']
    getStore().set(key, node)
  })
  write()
}

/** 清除指定 @type 或全部 */
export function clearSchema(types) {
  const store = getStore()
  if (!types) {
    store.clear()
  } else {
    const list = Array.isArray(types) ? types : [types]
    list.forEach((t) => store.delete(t))
  }
  write()
}

/* ----------------- Schema 工厂 ----------------- */

/**
 * 创建 Organization 结构化数据（品牌/公司）
 */
export function createOrganizationSchema(data) {
  return {
    '@type': 'Organization',
    name: data.companyName || '',
    alternateName: data.brandName || '',
    description: data.mainBusiness || '',
    url: data.website || '',
    telephone: data.phone || '',
    email: data.email || '',
    address: {
      '@type': 'PostalAddress',
      streetAddress: data.factoryAddress || data.officeAddress || '',
      addressCountry: 'CN',
    },
    ...(data.logo ? { logo: data.logo } : {}),
    ...(data.establishmentDate ? { foundingDate: data.establishmentDate } : {}),
  }
}

/**
 * 创建 LocalBusiness 结构化数据（首页/关于/联系页推荐使用）。
 * 基于 CompanyDetail 字段完整映射：openingHours / areaServed / priceRange / makesOffer。
 */
export function createLocalBusinessSchema(data) {
  if (!data) return null
  const address = data.factoryAddress || data.officeAddress || ''
  const schema = {
    '@type': ['LocalBusiness', 'Organization'],
    name: data.companyName || data.brandName || '',
    alternateName: data.brandName || '',
    description: data.mainBusiness || data.corePositioning || '',
    url: data.website || '',
    telephone: data.phone || '',
    email: data.email || '',
    priceRange: '$$',
    address: address ? {
      '@type': 'PostalAddress',
      streetAddress: address,
      addressCountry: 'CN',
      addressRegion: extractRegion(address),
    } : undefined,
    ...(data.establishmentDate ? { foundingDate: data.establishmentDate } : {}),
    ...(data.logoFileName || data.logo ? {
      logo: data.logo || `/images/${data.logoFileName}`,
    } : {}),
    openingHoursSpecification: {
      '@type': 'OpeningHoursSpecification',
      dayOfWeek: ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday'],
      opens: '08:30',
      closes: '17:30',
    },
    areaServed: ['浙江省', '全国', '出口'],
  }
  /* 如果有主要产品，注入 makesOffer（至少体现主营） */
  if (data.mainBusiness) {
    const items = data.mainBusiness.split(/[，,、；;\s]+/).filter(Boolean).slice(0, 8)
    if (items.length) {
      schema.hasOfferCatalog = {
        '@type': 'OfferCatalog',
        name: '包装设备与耗材产品目录',
        itemListElement: items.map((name) => ({
          '@type': 'Offer',
          itemOffered: { '@type': 'Product', name },
        })),
      }
    }
  }
  return schema
}

function extractRegion(addr) {
  if (!addr) return ''
  const m = String(addr).match(/([\u4e00-\u9fa5]{2,6}(省|市))/)
  return m ? m[1] : ''
}

/**
 * 创建 Product 结构化数据（无条件含 offers + 价格兜底）。
 * - 没价格时标 price: '询价' 不合法，改为不写 price，保留 priceCurrency/availability
 * - 但 Google 富媒体要求 offer，所以一定会生成 offers 节点
 */
export function createProductSchema(product) {
  const offer = {
    '@type': 'Offer',
    url: product.url || '',
    priceCurrency: 'CNY',
    availability: 'https://schema.org/InStock',
    itemCondition: 'https://schema.org/NewCondition',
    businessFunction: 'https://purl.org/goodrelations/v1#Sell',
  }
  if (product.price != null && product.price !== '') {
    offer.price = Number(product.price)
  } else if (product.minPrice != null) {
    offer.lowPrice = Number(product.minPrice)
    offer.highPrice = product.maxPrice != null ? Number(product.maxPrice) : undefined
    offer.price = Number(product.minPrice)
    offer['@type'] = 'AggregateOffer'
  }
  return {
    '@type': 'Product',
    name: product.name || '',
    description: product.websiteSummary || product.introductionCn || '',
    image: product.defaultImage ? [product.defaultImage] : [],
    sku: product.modelCode || '',
    mpn: product.modelCode || '',
    ...(product.productLineName || product.equipmentCategory ? {
      category: [product.productLineName, product.equipmentCategory].filter(Boolean).join(' / '),
    } : {}),
    ...(product.brand ? {
      brand: { '@type': 'Brand', name: product.brand },
    } : {}),
    offers: offer,
  }
}

/**
 * 创建 BreadcrumbList 结构化数据
 */
export function createBreadcrumbSchema(items) {
  if (!items || items.length === 0) return null
  return {
    '@type': 'BreadcrumbList',
    itemListElement: items.map((item, idx) => ({
      '@type': 'ListItem',
      position: idx + 1,
      name: item.name,
      ...(item.url ? { item: item.url } : {}),
    })),
  }
}
