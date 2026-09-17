/**
 * HTTP 请求封装。
 * - 本地开发：Vite 已把 /api 代理到 127.0.0.1:8081，直接走相对路径即可，避免跨域
 * - 生产部署：走相对路径，由反向代理转发到后端
 * - 错误统一抛出 Error，调用方自行 catch 处理降级
 * - GET 请求支持内存级 Promise 缓存（同一路径并发去重 + 会话内复用）
 */

export const apiBase = ''

/* ----------------- 内存缓存 ----------------- */
/**
 * Map<cacheKey, { expireAt:number, promise:Promise<any> | null, value?:any }>
 * - promise 存在表示进行中（并发去重）
 * - value 存在表示已完成可复用（直到 expireAt）
 */
const cache = new Map()

function defaultTtlFor(path) {
  /* 公司信息 / 分类 / 解决方案 / 里程碑 / Banner / 页面区块：1 小时 */
  if (/\/(company-detail|product-categories|certification-categories|solutions|milestones|banners|page-sections)/.test(path)) {
    return 60 * 60 * 1000
  }
  /* 首页聚合 / 精选产品：10 分钟 */
  if (/\/(home|featured)/.test(path)) return 10 * 60 * 1000
  /* 产品详情：30 分钟（内容变更低频） */
  if (/\/products\/[^/?]+$/.test(path)) return 30 * 60 * 1000
  /* 其他列表 / 分页接口：默认不缓存，TTL=0 */
  return 0
}

/**
 * 带缓存的 GET。
 * @param {string} path
 * @param {{ttl?:number, cacheKey?:string, bypass?:boolean}} [opts]
 */
export async function getJson(path, opts = {}) {
  const { ttl, bypass = false } = opts
  const key = opts.cacheKey ?? path
  const effectiveTtl = ttl != null ? ttl : defaultTtlFor(path)

  if (!bypass && effectiveTtl > 0) {
    const cached = cache.get(key)
    const now = Date.now()
    if (cached) {
      if (cached.expireAt > now) {
        if (cached.promise) return cached.promise
        if (cached.value !== undefined) return Promise.resolve(cached.value)
      }
      /* 过期或无效，移除 */
      cache.delete(key)
    }
    /* 新建条目：先挂 promise，实现并发去重 */
    const entry = { expireAt: now + effectiveTtl, promise: null, value: undefined }
    entry.promise = (async () => {
      try {
        const val = await rawGet(path)
        entry.value = val
        return val
      } finally {
        entry.promise = null
      }
    })()
    cache.set(key, entry)
    return entry.promise
  }

  return rawGet(path)
}

async function rawGet(path) {
  const response = await fetch(`${apiBase}${path}`)
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  return response.json()
}

/**
 * 发起 POST 请求。
 * @param {string} path 路径
 * @param {object} body 请求体
 * @returns {Promise<any>} 解析后的 JSON 数据
 */
export async function postJson(path, body) {
  const response = await fetch(`${apiBase}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  return response.json()
}
