<script setup>
/**
 * 产品中心页。
 * - 顶部 Hero + 搜索栏 + 分类 Tab（sticky）
 * - 卡片网格（4 列）+ 分页
 * - 数据来自 /api/website/products，仅展示 website_enabled=true 的产品
 * - 分类来自 /api/website/product-categories
 */
import { computed, ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchProducts, fetchProductCategories } from '../api/website'
import ProductCard from '../components/ProductCard.vue'
import PageHero from '../components/common/PageHero.vue'
import SearchBar from '../components/common/SearchBar.vue'
import Pagination from '../components/common/Pagination.vue'
import SiteCTA from '../components/common/SiteCTA.vue'
import { usePageSections } from '../composables/usePageSections'
import { useCmsPreview } from '../composables/useCmsPreview'

const route = useRoute()
const router = useRouter()
const pageSections = usePageSections('PRODUCTS')
const cmsPreview = useCmsPreview('PRODUCTS')
const sectionEnabled = (key) => pageSections.isEnabled(key)
const pageContent = computed(() => {
  const s = (key) => pageSections.section(key)
  const d = (key) => cmsPreview.draft(key)
  return {
    hero: {
      title: d('hero').title || s('hero')?.title || '查看包装设备，找到适合生产需求的产品',
      description: d('hero').subtitle || s('hero')?.subtitle || '按设备类别或产品名称查找包装设备，进入产品详情查看适用范围与具体信息。',
      image: d('hero').imageUrl || s('hero')?.imageUrl || '',
    },
    catalog: {
      title: d('catalog').title || s('catalog')?.title || '全部包装设备',
      description: d('catalog').subtitle || s('catalog')?.subtitle || '可按设备类型、包装产品、容器、工艺与目标产能筛选。',
    },
  }
})

const loading = ref(true)
const error = ref(null)
const products = ref([])
const total = ref(0)
const totalPages = ref(0)
const page = ref(parseInt(route.query.page) || 0)
const pageSize = 12

const keyword = ref(route.query.keyword || '')
const activeCategory = ref(route.query.equipmentCategory || '')
const categories = ref([])
const application = ref(route.query.application || '')
const container = ref(route.query.container || '')
const process = ref(route.query.process || '')
const capacity = ref(route.query.capacity || '')
const selectionOptions = ref({ applications: [], containers: [], processes: [], capacityLevels: [] })
const hasActiveFilters = computed(() => Boolean(keyword.value || activeCategory.value || application.value || container.value || process.value || capacity.value))

async function loadCategories() {
  try {
    const data = await fetchProductCategories()
    categories.value = data.equipmentCategories || []
    selectionOptions.value = {
      applications: data.applications || [],
      containers: data.containers || [],
      processes: data.processes || [],
      capacityLevels: data.capacityLevels || [],
    }
  } catch (err) {
    console.warn('Categories unavailable:', err)
  }
}

async function loadProducts() {
  loading.value = true
  error.value = null
  try {
    const data = await fetchProducts({
      keyword: keyword.value,
      equipmentCategory: activeCategory.value || undefined,
      application: application.value || undefined,
      container: container.value || undefined,
      process: process.value || undefined,
      capacity: capacity.value || undefined,
      page: page.value,
      size: pageSize,
    })
    products.value = data.items || []
    total.value = data.total || 0
    totalPages.value = data.totalPages || 0
  } catch (err) {
    error.value = err
    console.warn('Products unavailable:', err)
  } finally {
    loading.value = false
  }
}

function syncQuery() {
  const query = {}
  if (keyword.value) query.keyword = keyword.value
  if (activeCategory.value) query.equipmentCategory = activeCategory.value
  if (application.value) query.application = application.value
  if (container.value) query.container = container.value
  if (process.value) query.process = process.value
  if (capacity.value) query.capacity = capacity.value
  if (page.value > 0) query.page = page.value
  router.replace({ query })
}

function applySelection() {
  page.value = 0
  syncQuery()
}

function setCategory(cat) {
  activeCategory.value = cat
  page.value = 0
  syncQuery()
}

let searchTimer = null
function onSearchInput() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    page.value = 0
    syncQuery()
  }, 350)
}

function clearFilters() {
  clearTimeout(searchTimer)
  router.replace({ query: {} })
}

function goToPage(p) {
  if (p < 0 || p >= totalPages.value || p === page.value) return
  page.value = p
  syncQuery()
  document.getElementById('product-catalog')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

onMounted(async () => {
  await Promise.all([loadCategories(), pageSections.load()])
})

// URL 作为唯一状态源：任何 query 变化（用户交互 / 浏览器前进后退）都触发数据重新加载
watch(() => route.query, (q) => {
  keyword.value = q.keyword || ''
  activeCategory.value = q.equipmentCategory || ''
  application.value = q.application || ''
  container.value = q.container || ''
  process.value = q.process || ''
  capacity.value = q.capacity || ''
  page.value = parseInt(q.page) || 0
  loadProducts()
}, { immediate: true })
</script>

<template>
  <main class="products-page">
    <!-- Hero -->
    <PageHero
      v-if="sectionEnabled('hero')"
      data-cms-page="PRODUCTS"
      data-cms-section="hero"
      eyebrow="PRODUCT CENTER"
      :description="pageContent.hero.description"
      :image="pageContent.hero.image"
      :primary-action="{ text: '浏览全部设备', to: '#product-catalog' }"
    >
      <template #title><span>{{ pageContent.hero.title }}</span></template>
    </PageHero>

    <section v-if="sectionEnabled('catalog')" id="product-catalog" class="product-catalog" data-cms-page="PRODUCTS" data-cms-section="catalog" data-cms-source="product">
      <div class="page-toolbar product-filter">
        <div class="product-filter__search">
          <SearchBar v-model="keyword" placeholder="产品名称 / 型号 / 类别" @input="onSearchInput" />
        </div>
        <div class="product-filter__categories">
          <div class="category-tabs" role="tablist" aria-label="设备类型筛选">
            <button type="button" role="tab" :aria-selected="!activeCategory" :class="{ active: !activeCategory }" @click="setCategory('')">全部设备</button>
            <button
              v-for="cat in categories"
              :key="cat"
              type="button"
              role="tab"
              :aria-selected="activeCategory === cat"
              :class="{ active: activeCategory === cat }"
              @click="setCategory(cat)"
            >{{ cat }}</button>
          </div>
        </div>
      </div>

      <div class="catalog-layout">
        <aside class="catalog-sidebar">
          <div class="selection-filter" aria-label="按应用条件筛选产品">
            <div class="selection-filter__intro">
              <p>FIND YOUR MACHINE</p>
              <strong>按应用条件缩小范围</strong>
              <span>不确定设备名称时，可以从包装对象、容器、工艺和产能开始。</span>
            </div>
            <label>
              <span>包装产品</span>
              <select v-model="application" @change="applySelection">
                <option value="">不限产品</option>
                <option v-for="item in selectionOptions.applications" :key="item" :value="item">{{ item }}</option>
              </select>
            </label>
            <label>
              <span>包装容器</span>
              <select v-model="container" @change="applySelection">
                <option value="">不限容器</option>
                <option v-for="item in selectionOptions.containers" :key="item" :value="item">{{ item }}</option>
              </select>
            </label>
            <label>
              <span>包装工艺</span>
              <select v-model="process" @change="applySelection">
                <option value="">不限工艺</option>
                <option v-for="item in selectionOptions.processes" :key="item" :value="item">{{ item }}</option>
              </select>
            </label>
            <label>
              <span>目标产能</span>
              <select v-model="capacity" @change="applySelection">
                <option value="">不限产能</option>
                <option v-for="item in selectionOptions.capacityLevels" :key="item" :value="item">{{ item }}</option>
              </select>
            </label>
          </div>
        </aside>

        <div class="catalog-main">
          <div class="catalog-overview" aria-live="polite">
            <div class="catalog-overview__title">
              <p class="catalog-overview__eyebrow">EQUIPMENT CATALOG</p>
              <h2>{{ activeCategory || pageContent.catalog.title }}</h2>
            </div>
            <div class="catalog-overview__meta">
              <span v-if="!loading">共 <strong>{{ total }}</strong> 款产品</span>
              <span v-else>正在加载产品…</span>
              <button v-if="hasActiveFilters" type="button" @click="clearFilters">清除筛选</button>
            </div>
          </div>

          <!-- 产品网格 -->
          <div class="products-grid">
            <!-- 加载骨架屏 -->
            <div v-if="loading && !products.length" class="products-loading">
              <div v-for="i in 8" :key="i" class="skeleton-card">
                <div class="skeleton-image"></div>
                <div class="skeleton-body">
                  <div class="skeleton-line short"></div>
                  <div class="skeleton-line long"></div>
                  <div class="skeleton-line long"></div>
                </div>
              </div>
            </div>

            <!-- 产品卡片（图片加载失败由全局保底机制接管） -->
            <template v-else-if="products.length">
              <ProductCard
                v-for="product in products"
                :key="product.id"
                :product="product"
              />
            </template>

            <!-- 空状态 -->
            <div v-else-if="error" class="empty-state">
              <h3>暂时无法加载产品</h3>
              <p>请稍后再试，或直接联系我们获取产品资料。</p>
              <button type="button" class="button primary" @click="loadProducts">重新加载</button>
            </div>
            <div v-else class="empty-state">
              <h3>暂无匹配的产品</h3>
              <p>尝试更换关键词或分类筛选。</p>
              <button v-if="hasActiveFilters" type="button" class="button ghost" @click="clearFilters">查看全部产品</button>
            </div>
          </div>

          <!-- 分页 -->
          <Pagination
            :page="page"
            :total-pages="totalPages"
            :info="`共 ${total} 款产品`"
            @change="goToPage"
          />
        </div>
      </div>
    </section>

    <SiteCTA
      id="cta"
      title="不确定应该选择哪种设备？"
      description="准备好产品、容器尺寸和目标产能，我们可以先帮您梳理设备、模具和耗材的匹配方向。"
      action-text="获取选型建议"
      inquiry-source="products-bottom"
    />
  </main>
</template>
