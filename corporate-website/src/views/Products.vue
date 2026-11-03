<script setup>
/**
 * 产品中心页。
 * - 顶部 Hero + 搜索栏 + 分类 Tab（sticky）
 * - 卡片网格（4 列）+ 分页
 * - 数据来自官网独立公开内容；生产静态版读取内容快照
 * - 分类来自 /api/website/product-categories
 */
import { computed, ref, onMounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchProducts, fetchProductCategories } from '../api/website'
import {
  normalizePage, clampPage, buildProductQuery, sameQuery, createLatestRequestGuard,
} from '../utils/productQuery'
import ProductCard from '../components/ProductCard.vue'
import PageHero from '../components/common/PageHero.vue'
import SearchBar from '../components/common/SearchBar.vue'
import Pagination from '../components/common/Pagination.vue'
import SiteCTA from '../components/common/SiteCTA.vue'
import { usePageSections } from '../composables/usePageSections'

const route = useRoute()
const router = useRouter()
const pageSections = usePageSections('PRODUCTS')
const sectionEnabled = (key) => pageSections.isEnabled(key)
const pageContent = computed(() => {
  const s = (key) => pageSections.section(key)
  return {
    hero: {
      title: s('hero')?.title || '查看包装设备，找到适合生产需求的产品',
      description: s('hero')?.subtitle || '按设备类别或产品名称查找包装设备，进入产品详情查看适用范围与具体信息。',
      image: s('hero')?.imageUrl || '',
    },
    catalog: {
      title: s('catalog')?.title || '全部包装设备',
      description: s('catalog')?.subtitle || '可按设备类型、包装产品、容器、工艺与目标产能筛选。',
    },
  }
})

const loading = ref(true)
const error = ref(null)
const products = ref([])
const total = ref(0)
const totalPages = ref(0)
const page = ref(normalizePage(route.query.page))
const pageSize = 12
/* 加载策略：切换筛选时保留上一批结果避免闪烁，但必须显式标记为「更新中」，
   并禁止基于旧结果的分页操作，防止用户误认为列表已经刷新。 */
const refreshing = computed(() => loading.value && products.value.length > 0)

/* inputKeyword 跟随输入框，keyword 是「已生效」的关键词：只有防抖到期、
   点击分类/筛选/翻页或清除时才把输入值提交为条件，避免 350ms 内的输入被
   其它操作连带写入 URL，产生重复请求或状态互相覆盖。 */
const inputKeyword = ref(route.query.keyword || '')
const keyword = ref(route.query.keyword || '')
const activeCategory = ref(route.query.equipmentCategory || '')
const categories = ref([])
const categoriesError = ref(false)
const application = ref(route.query.application || '')
const container = ref(route.query.container || '')
const process = ref(route.query.process || '')
const capacity = ref(route.query.capacity || '')
const selectionOptions = ref({ applications: [], containers: [], processes: [], capacityLevels: [] })
const hasActiveFilters = computed(() => Boolean(keyword.value || activeCategory.value || application.value || container.value || process.value || capacity.value))
const advancedFilterCount = computed(() => [application, container, process, capacity].filter((field) => field.value).length)
const advancedOpen = ref(advancedFilterCount.value > 0)
const hasAdvancedOptions = computed(() => Object.values(selectionOptions.value).some((items) => items.length > 0) || advancedFilterCount.value > 0)
const requestGuard = createLatestRequestGuard()
const categoryTabsRef = ref(null)

/* 已选条件展示：让客户清楚当前到底筛了什么，并可逐项移除（而不是只能全部清除）。 */
const FILTER_META = {
  keyword: '搜索',
  equipmentCategory: '分类',
  application: '包装产品',
  container: '包装容器',
  process: '包装工艺',
  capacity: '目标产能',
}
const activeFilterChips = computed(() => {
  const selected = {
    keyword: keyword.value,
    equipmentCategory: activeCategory.value,
    application: application.value,
    container: container.value,
    process: process.value,
    capacity: capacity.value,
  }
  return Object.entries(selected)
    .filter(([, value]) => Boolean(value))
    .map(([key, value]) => ({ key, label: `${FILTER_META[key]}：${value}` }))
})

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
    categoriesError.value = false
  } catch (err) {
    /* 分类来自数据源，单个接口失败不应让整页不可用：保留搜索与筛选并给出可见降级说明 */
    categoriesError.value = true
    console.warn('Categories unavailable:', err)
  }
}

async function loadProducts(query) {
  const request = requestGuard.start()
  const requestedPage = normalizePage(query.page)
  let redirected = false
  loading.value = true
  error.value = null
  try {
    const data = await fetchProducts({
      keyword: query.keyword || '',
      equipmentCategory: query.equipmentCategory || undefined,
      application: query.application || undefined,
      container: query.container || undefined,
      process: query.process || undefined,
      capacity: query.capacity || undefined,
      page: requestedPage,
      size: pageSize,
    })
    /* 快速切换筛选时，只有最新请求的结果允许落地 */
    if (!requestGuard.isLatest(request)) return
    const pages = Number(data.totalPages) || 0
    const effectivePage = clampPage(requestedPage, pages)
    if (effectivePage !== requestedPage) {
      /* 旧书签 / 手工 URL / 筛选后页数变少导致当前页越界：
         用 replace 修正地址栏（不新增历史），watch 会重新加载有效页。 */
      page.value = effectivePage
      redirected = true
      syncQuery('replace')
      return
    }
    products.value = data.items || []
    total.value = Number(data.total) || 0
    totalPages.value = pages
  } catch (err) {
    if (!requestGuard.isLatest(request)) return
    error.value = err
    console.warn('Products unavailable:', err)
  } finally {
    if (requestGuard.isLatest(request) && !redirected) loading.value = false
  }
}

/** 重试必须沿用当前 URL 条件，不能把点击事件对象当作 query 传入。 */
function retry() {
  loadProducts({ ...route.query })
}

/* 交互契约：搜索输入用 replace（不产生连续历史），分类 / 筛选 / 翻页 / 清除用 push。 */
function syncQuery(mode = 'push') {
  const query = buildProductQuery({
    keyword: keyword.value,
    equipmentCategory: activeCategory.value,
    application: application.value,
    container: container.value,
    process: process.value,
    capacity: capacity.value,
    page: page.value,
  })
  /* 条件没有变化时不要往浏览器历史里塞一条相同 URL */
  if (mode === 'push' && sameQuery(query, route.query)) return
  router[mode]({ query })
}

let searchTimer = null

/** 把输入框里的关键词提交为生效条件；返回是否发生变化。 */
function commitKeyword() {
  clearTimeout(searchTimer)
  if (inputKeyword.value === keyword.value) return false
  keyword.value = inputKeyword.value
  page.value = 0
  return true
}

function applySelection() {
  commitKeyword()
  page.value = 0
  syncQuery()
}

function setCategory(cat) {
  commitKeyword()
  activeCategory.value = cat
  page.value = 0
  syncQuery()
}

function onSearchInput() {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    if (!commitKeyword()) return
    syncQuery('replace')
  }, 350)
}

/** 只清空关键词，保留分类与高级筛选；作为主动操作使用 push，便于返回上一步搜索。 */
function clearKeyword() {
  clearTimeout(searchTimer)
  inputKeyword.value = ''
  keyword.value = ''
  page.value = 0
  syncQuery()
}

function clearFilters() {
  clearTimeout(searchTimer)
  inputKeyword.value = ''
  if (sameQuery({}, route.query)) return
  router.push({ query: {} })
}

/** 逐项移除已选条件，避免只能「清除筛选」全部清空。 */
function removeFilter(key) {
  if (key === 'keyword') {
    clearTimeout(searchTimer)
    inputKeyword.value = ''
    keyword.value = ''
  } else if (key === 'equipmentCategory') {
    activeCategory.value = ''
  } else if (key === 'application') {
    application.value = ''
  } else if (key === 'container') {
    container.value = ''
  } else if (key === 'process') {
    process.value = ''
  } else if (key === 'capacity') {
    capacity.value = ''
  }
  page.value = 0
  syncQuery()
}

/** 只清除高级筛选，保留关键词与设备分类。 */
function clearAdvanced() {
  commitKeyword()
  application.value = ''
  container.value = ''
  process.value = ''
  capacity.value = ''
  page.value = 0
  syncQuery()
}

/** 小屏筛选面板收起并回到结果区。 */
function closeAdvanced() {
  advancedOpen.value = false
  document.getElementById('product-results')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function goToPage(p) {
  /* 加载中禁止基于上一批结果翻页，避免误认为列表已经刷新 */
  if (loading.value || p < 0 || p >= totalPages.value || p === page.value) return
  /* 输入框里还有未提交的关键词时先提交；条件变了就回第 1 页，而不是跳到旧结果集的页码 */
  const keywordChanged = commitKeyword()
  page.value = keywordChanged ? 0 : normalizePage(p)
  syncQuery()
  document.getElementById('product-catalog')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

onMounted(async () => {
  await Promise.all([loadCategories(), pageSections.load()])
})

// URL 作为唯一状态源：任何 query 变化（用户交互 / 浏览器前进后退）都触发数据重新加载
watch(() => route.query, (q) => {
  const normalizedPage = normalizePage(q.page)
  if (q.page !== undefined && String(q.page) !== String(normalizedPage)) {
    /* 负数 / 非数字 / 超上限的 URL 页码先规范化，再由下一次 watch 加载 */
    router.replace({ query: buildProductQuery({ ...q, page: normalizedPage }) })
    return
  }
  inputKeyword.value = q.keyword || ''
  keyword.value = q.keyword || ''
  activeCategory.value = q.equipmentCategory || ''
  application.value = q.application || ''
  container.value = q.container || ''
  process.value = q.process || ''
  capacity.value = q.capacity || ''
  page.value = normalizedPage
  loadProducts({ ...q })
}, { immediate: true })

/* 只有高级筛选条件本身发生变化时才自动展开面板；
   用户手动收起后翻页 / 改关键词不应被重新弹开。 */
watch(
  () => [route.query.application, route.query.container, route.query.process, route.query.capacity],
  (next, previous) => {
    if (next.some(Boolean) && next.join('|') !== previous.join('|')) advancedOpen.value = true
  },
)

/* 分类横向滚动时，保持当前选中项在可视区域内，避免手机上选中项被滚出屏幕 */
watch(activeCategory, async () => {
  await nextTick()
  categoryTabsRef.value?.querySelector('.active')?.scrollIntoView({ block: 'nearest', inline: 'center' })
})
</script>

<template>
  <main class="products-page">
    <!-- Hero -->
    <PageHero
      v-if="sectionEnabled('hero')"
      eyebrow="PRODUCT CENTER"
      :description="pageContent.hero.description"
      :image="pageContent.hero.image"
      :primary-action="{ text: '浏览全部设备', to: '#product-catalog' }"
    >
      <template #title><span>{{ pageContent.hero.title }}</span></template>
    </PageHero>

    <section v-if="sectionEnabled('catalog')" id="product-catalog" class="product-catalog">
      <div class="page-toolbar product-filter">
        <div class="product-filter__search">
          <SearchBar
            v-model="inputKeyword"
            placeholder="产品名称 / 型号 / 类别"
            clearable
            @input="onSearchInput"
            @clear="clearKeyword"
          />
        </div>
        <div class="product-filter__categories">
          <div ref="categoryTabsRef" class="category-tabs" role="tablist" aria-label="设备类型筛选">
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
          <p v-if="categoriesError" class="category-tabs__error" role="status">
            设备分类暂时无法加载，可先用搜索或「更多筛选」定位设备。
            <button type="button" @click="loadCategories">重试</button>
          </p>
        </div>
      </div>

      <div v-if="hasAdvancedOptions" class="advanced-filter-toggle-wrap">
        <button
          type="button"
          class="advanced-filter-toggle"
          :aria-expanded="advancedOpen"
          aria-controls="product-advanced-filters"
          @click="advancedOpen = !advancedOpen"
        >{{ advancedOpen ? '收起筛选' : '更多筛选' }}<span v-if="advancedFilterCount">（{{ advancedFilterCount }}）</span><span aria-hidden="true">{{ advancedOpen ? '−' : '+' }}</span></button>
      </div>

      <div class="catalog-layout" :class="{ 'catalog-layout--filtered': advancedOpen }">
        <aside v-if="advancedOpen" id="product-advanced-filters" class="catalog-sidebar" role="region" aria-label="更多筛选条件">
          <div class="selection-filter" aria-label="按应用条件筛选产品">
            <div class="selection-filter__intro">
              <p>FIND YOUR MACHINE</p>
              <strong>按应用条件缩小范围</strong>
              <span>不确定设备名称时，可以从包装对象、容器、工艺和产能开始。</span>
            </div>
            <label v-if="selectionOptions.applications.length || application">
              <span>包装产品</span>
              <select v-model="application" @change="applySelection">
                <option value="">不限产品</option>
                <option v-for="item in selectionOptions.applications" :key="item" :value="item">{{ item }}</option>
              </select>
            </label>
            <label v-if="selectionOptions.containers.length || container">
              <span>包装容器</span>
              <select v-model="container" @change="applySelection">
                <option value="">不限容器</option>
                <option v-for="item in selectionOptions.containers" :key="item" :value="item">{{ item }}</option>
              </select>
            </label>
            <label v-if="selectionOptions.processes.length || process">
              <span>包装工艺</span>
              <select v-model="process" @change="applySelection">
                <option value="">不限工艺</option>
                <option v-for="item in selectionOptions.processes" :key="item" :value="item">{{ item }}</option>
              </select>
            </label>
            <label v-if="selectionOptions.capacityLevels.length || capacity">
              <span>目标产能</span>
              <select v-model="capacity" @change="applySelection">
                <option value="">不限产能</option>
                <option v-for="item in selectionOptions.capacityLevels" :key="item" :value="item">{{ item }}</option>
              </select>
            </label>
          </div>
          <!-- 小屏筛选面板的操作区：桌面端由 CSS 隐藏，保留原有折叠按钮即可 -->
          <div class="advanced-filter-actions">
            <span>已选 {{ advancedFilterCount }} 项条件</span>
            <div class="advanced-filter-actions__buttons">
              <button type="button" @click="clearAdvanced">清除条件</button>
              <button type="button" class="is-primary" @click="closeAdvanced">查看结果</button>
            </div>
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
              <span v-else-if="refreshing">正在更新产品列表…</span>
              <span v-else>正在加载产品…</span>
              <button v-if="hasActiveFilters" type="button" @click="clearFilters">清除筛选</button>
            </div>
          </div>

          <!-- 已选条件：显示到底筛了什么，并支持逐项移除 -->
          <ul v-if="activeFilterChips.length" class="active-filter-chips" aria-label="当前已选筛选条件">
            <li v-for="chip in activeFilterChips" :key="chip.key">
              <span>{{ chip.label }}</span>
              <button
                type="button"
                :aria-label="`移除筛选条件 ${chip.label}`"
                @click="removeFilter(chip.key)"
              >×</button>
            </li>
          </ul>

          <!-- 加载策略：切换筛选时保留上一批结果避免闪烁，但显式标记为「更新中」 -->
          <div v-if="refreshing" class="catalog-refreshing" role="status">
            <span class="catalog-refreshing__dot" aria-hidden="true"></span>
            正在更新产品列表，当前显示的是上一次筛选结果
          </div>

          <!-- 产品网格 -->
          <div id="product-results" class="products-grid" :aria-busy="refreshing" :data-refreshing="refreshing">
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

            <!-- 请求失败：错误优先于旧结果，避免把上一批卡片误认为新结果 -->
            <div v-else-if="error" class="empty-state">
              <h3>暂时无法加载产品</h3>
              <p>请稍后再试，或直接联系我们获取产品资料。</p>
              <button type="button" class="button primary" @click="retry">重新加载</button>
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
            :busy="refreshing"
            @change="goToPage"
          />
        </div>
      </div>
    </section>

    <SiteCTA
      id="cta"
      title="不确定应该选择哪种设备？"
      description="准备好产品、容器尺寸和目标产能，我们可以先帮您梳理设备与包材的匹配方向。"
      action-text="获取选型建议"
      inquiry-source="products-bottom"
    />
  </main>
</template>
