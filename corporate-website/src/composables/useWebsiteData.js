/**
 * 官网首页数据获取（单例）。
 * - 加载 /api/website/home 聚合数据
 * - 加载 /api/website/banners/public Banner数据
 * - 失败时返回降级默认值，不阻塞页面渲染
 * - 响应式 refs 全局共享，多页面切换复用同一份缓存 + http 层 TTL 缓存双重保障
 */
import { ref } from 'vue'
import { fetchHome, fetchBanners, fetchFeaturedProducts } from '../api/website'

const loading = ref(true)
const error = ref(null)
const company = ref({})
const products = ref([])
const milestones = ref([])
const productCount = ref(0)
const banners = ref({})
const loadedOnce = ref(false)
const inFlight = ref(null)

async function load({ force = false } = {}) {
  if (!force && inFlight.value) return inFlight.value
  if (!force && loadedOnce.value && !error.value) {
    loading.value = false
    return Promise.resolve()
  }

  loading.value = true
  error.value = null
  const task = (async () => {
    try {
      const [data, featuredProducts, bannerData] = await Promise.all([
        fetchHome(),
        // 核心产品属于后台高频运营配置，每次加载首页都取最新值，不使用 10 分钟缓存。
        fetchFeaturedProducts(12, { bypass: true }).catch(() => []),
        fetchBanners().catch(() => ({}))
      ])
      company.value = data.company || {}
      // 核心产品使用独立接口，避免首页聚合接口的历史数量上限影响轮播。
      products.value = (featuredProducts.length ? featuredProducts : (data.products || [])).slice(0, 12)
      milestones.value = data.milestones || []
      productCount.value = data.productCount || products.value.length || 0
      banners.value = bannerData || {}
      loadedOnce.value = true
    } catch (err) {
      error.value = err
      console.warn('Website data source unavailable:', err)
    } finally {
      loading.value = false
      inFlight.value = null
    }
  })()
  inFlight.value = task
  return task
}

export function useWebsiteData() {
  return {
    loading,
    error,
    company,
    products,
    milestones,
    productCount,
    banners,
    load,
  }
}
