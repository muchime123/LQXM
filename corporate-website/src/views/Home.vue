<script setup>
/**
 * 首页。
 * - 数据通过 useWebsiteData 拉取（/api/website/home 聚合接口）
 * - Hero 使用官网已发布 Banner；无 Banner 时显示无图文案
 * - 精选产品来自后台 website_featured=true 的产品，无精选时回退到已发布产品
 * - 公司数据从 useCompany 单例获取，与 Footer/About/Contact 共享
 * - reveal 动画由 v-reveal 指令接管
 * - 注入 Organization 结构化数据支持搜索引擎理解
 */
import { computed, onMounted, onUnmounted, ref, watch, watchEffect, reactive, nextTick } from 'vue'
import DOMPurify from 'dompurify'
import { useWebsiteData } from '../composables/useWebsiteData'
import { useCompany } from '../composables/useCompany'
import { usePageSections } from '../composables/usePageSections'
import { useInquiryModal } from '../composables/useInquiryModal'
import { fetchSolutions, websiteProductMediaUrl } from '../api/website'
import { patents } from '../data/qualifications'
import { FALLBACK_IMAGE_URL } from '../utils/fallbackImage'
import { setSchema, createOrganizationSchema, createLocalBusinessSchema } from '../composables/useSchema'
import ProductCard from '../components/ProductCard.vue'
import AppIcon from '../components/AppIcon.vue'
import SectionHeading from '../components/common/SectionHeading.vue'
import SiteCTA from '../components/common/SiteCTA.vue'

const {
  loading,
  error,
  products,
  productCount,
  banners,
  load,
} = useWebsiteData()

const { company } = useCompany()
const { openInquiry } = useInquiryModal()
const solutionGroups = ref([])

async function loadHomeSolutions() {
  try {
    solutionGroups.value = await fetchSolutions()
  } catch (err) {
    console.warn('Home solution scenes unavailable:', err)
  }
}

watchEffect(() => {
  if (company.value) {
    const orgData = {
      ...company.value,
      logo: company.value.logoMediaId ? websiteProductMediaUrl(company.value.logoMediaId)
        : company.value.logoFileName ? `/images/${company.value.logoFileName}` : null,
    }
    /* Organization（品牌标识）+ LocalBusiness（营业信息/地址/营业时间）双 schema */
    setSchema(createOrganizationSchema(orgData))
    const lb = createLocalBusinessSchema(orgData)
    if (lb) setSchema(lb)
  }
})

onMounted(() => {
  load()
  loadHomeSolutions()
  pageSections.load()
  updateCarouselVisible()
  motionMedia = window.matchMedia('(prefers-reduced-motion: reduce)')
  prefersReducedMotion.value = motionMedia.matches
  motionMedia.addEventListener?.('change', updateMotionPreference)
  window.addEventListener('resize', updateCarouselVisible, { passive: true })
  document.addEventListener('visibilitychange', onVisibilityChange)
  carouselObserver = new IntersectionObserver(([entry]) => {
    carouselInView.value = entry.isIntersecting && entry.intersectionRatio >= 0.35
  }, { threshold: [0, 0.35] })
  if (productCarouselEl.value) carouselObserver.observe(productCarouselEl.value)
  carouselTimer = window.setInterval(() => {
    const hasRecentInteraction = Date.now() - lastCarouselInteraction < 6500
    if (!carouselPaused.value && carouselInView.value && !document.hidden && !prefersReducedMotion.value && !hasRecentInteraction) moveCarousel(1)
  }, 4200)
  /* 行业卡片背景图懒加载：进入视口附近（提前 200px）时才设置背景 URL，避免一次性加载所有卡片图片 */
  industryObserver = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        const idx = Number(entry.target.dataset.industryIdx)
        if (!Number.isNaN(idx)) visibleIndustries.add(idx)
        industryObserver.unobserve(entry.target)
      }
    })
  }, { rootMargin: '200px' })
  nextTick(() => {
    industryItemRefs.value.forEach((el, idx) => {
      if (!el) return
      el.dataset.industryIdx = String(idx)
      industryObserver?.observe(el)
    })
  })
})

onUnmounted(() => {
  window.removeEventListener('resize', updateCarouselVisible)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  motionMedia?.removeEventListener?.('change', updateMotionPreference)
  carouselObserver?.disconnect()
  heroObserver?.disconnect()
  industryObserver?.disconnect()
  window.clearInterval(carouselTimer)
})

/* ============ Banner 数据 ============ */
const EMPTY_HERO_BANNERS = [
  {
    title: '包装设备与包材，<em>围绕您的产品配置</em>',
    subtitle: 'PACKAGING EQUIPMENT & MATERIALS',
    description: '从封口设备、气调包装机到配套包材，利强围绕您的产品、容器和产能目标完成包装配置。',
    buttonText: '探索产品',
    buttonUrl: '/products',
    secondaryButtonText: '获取包装方案',
    secondaryButtonUrl: '',
    imageUrl: '',
  },
]

const pageSections = usePageSections('HOME')

/* 首页 Hero 只展示已发布 Banner；没有内容时显示无图的品牌文案。 */
function mapHeroBanner(b) {
  return {
    id: b.id,
    title: b.title ?? '',
    subtitle: b.subtitle ?? '',
    description: b.description ?? '',
    buttonText: b.buttonText ?? '探索产品',
    buttonUrl: b.buttonUrl ?? '/products',
    secondaryButtonText: b.secondaryButtonText || '获取包装方案',
    secondaryButtonUrl: b.secondaryButtonUrl || '',
    imageUrl: b.imageUrl ?? '',
  }
}
const heroBanners = computed(() => {
  const validCms = (banners.value?.HERO || []).filter((b) => b?.imageUrl)
  return validCms.length ? validCms.map(mapHeroBanner) : EMPTY_HERO_BANNERS
})
const safeHeroTitle = computed(() => DOMPurify.sanitize(currentHero.value?.title || '', {
  ALLOWED_TAGS: ['em', 'br'], ALLOWED_ATTR: [],
}))

/* 首页各区块标题/描述：优先后台「页面内容」配置，未配置时回退到前端默认文案。
   使用 usePageSections 提供的 sectionRows / isEnabled 统一实现。 */
const homeSectionEnabled = (key) => pageSections.isEnabled(key)
const sectionRows = (key) => pageSections.sectionRows(key)
const parseSectionRows = pageSections.parseSectionRows
const publicCopy = (value, fallback) => value || fallback
const homeHeadings = computed(() => {
  const s = (key) => pageSections.section(key)
  return {
    strength: {
      title: publicCopy(s('strength')?.title, '扎根制造现场，提供设备与包材配套'),
    },
    products: {
      title: s('products')?.title || '核心设备',
      description: s('products')?.subtitle || '从实际产品出发，选择更适合您的包装方式与生产节奏。',
    },
    showcase: {
      title: publicCopy(s('showcase')?.title, '包装设备，来自自己的制造现场'),
      description: publicCopy(s('showcase')?.subtitle, '从设备装配到整机测试，我们在自己的生产现场完成关键制造与交付环节。'),
    },
    process: {
      title: s('process')?.title || '合作流程',
      description: s('process')?.subtitle || '从需求确认到交付支持，四个环节依次推进。',
    },
    industries: {
      title: s('industries')?.title || '按应用场景选择方案',
      description: s('industries')?.subtitle || '不同产品对保鲜、产能与封装方式有不同要求，从实际场景开始选择。',
    },
    selector: {
      title: s('selector')?.title || '不知道选哪种？两条路径帮您快速定位',
      description: s('selector')?.subtitle || '可以从包装方式出发，也可以从应用场景出发；如果仍不确定，工程师可协助判断。',
    },
    cases: {
      title: s('cases')?.title || '典型配置参考',
      description: publicCopy(s('cases')?.subtitle, '以下为常见应用条件下的配置思路，具体设备、包材与工艺参数需结合样品评估。'),
    },
    proof: {
      title: s('proof')?.title || '交付事实',
      description: publicCopy(s('proof')?.subtitle, '围绕包装设备与包材配套的交付事实，便于您判断我们是否覆盖您的场景。'),
    },
    service: {
      title: s('service')?.title || '服务与交付保障',
      description: s('service')?.subtitle || '质保、响应与长期配套的明确承诺，降低设备采购与使用过程中的不确定性。',
    },
  }
})
/* 首页轮播展示所有符合官网核心展示条件的产品，不在前端再次截断。 */
const homeProducts = computed(() => products.value)
const hasProducts = computed(() => homeProducts.value.length > 0)

/* ============ Hero 轮播图 ============ */
const heroIndex = ref(0)
watch(() => heroBanners.value.length, (length) => {
  if (heroIndex.value >= length) heroIndex.value = 0
})
const heroTransitioning = ref(false)
const heroPaused = ref(false)
const heroSectionEl = ref(null)
const heroInView = ref(true)
let heroTimer = null
let heroObserver = null

function gotoHero(i) {
  if (heroTransitioning.value) return
  const total = heroBanners.value.length
  if (!total) return
  heroTransitioning.value = true
  heroIndex.value = (i + total) % total
  setTimeout(() => { heroTransitioning.value = false }, 700)
}
function prevHero() { resetHeroTimer(); gotoHero(heroIndex.value - 1) }
function nextHero() { resetHeroTimer(); gotoHero(heroIndex.value + 1) }
function resetHeroTimer() {
  if (heroTimer) { window.clearInterval(heroTimer); heroTimer = null }
  heroTimer = window.setInterval(() => {
    if (!document.hidden && heroInView.value && !heroPaused.value && !prefersReducedMotion.value) gotoHero(heroIndex.value + 1)
  }, 5200)
}
onMounted(() => {
  heroObserver = new IntersectionObserver(([entry]) => {
    heroInView.value = entry.isIntersecting && entry.intersectionRatio >= 0.2
  }, { threshold: [0, 0.2] })
  if (heroSectionEl.value) heroObserver.observe(heroSectionEl.value)
  resetHeroTimer()
})
onUnmounted(() => {
  if (heroTimer) { window.clearInterval(heroTimer); heroTimer = null }
})
const foundedYear = computed(() => company.value?.establishmentDate?.slice(0, 4) || '—')

const carouselIndex = ref(0)
const carouselVisible = ref(4)
const carouselPaused = ref(false)
const carouselPointerStart = ref(null)
const prefersReducedMotion = ref(false)
const productCarouselEl = ref(null)
const carouselInView = ref(false)
let carouselTimer = null
let motionMedia = null
let carouselObserver = null
let lastCarouselInteraction = 0
const carouselMaxIndex = computed(() => Math.max(0, homeProducts.value.length - carouselVisible.value))

function updateCarouselVisible() {
  const width = window.innerWidth
  carouselVisible.value = width <= 640 ? 1 : width <= 980 ? 2 : 4
  carouselIndex.value = Math.min(carouselIndex.value, carouselMaxIndex.value)
}

function moveCarousel(direction) {
  if (!carouselMaxIndex.value) return
  const next = carouselIndex.value + direction
  carouselIndex.value = next > carouselMaxIndex.value ? 0 : next < 0 ? carouselMaxIndex.value : next
}

function moveCarouselManually(direction) {
  lastCarouselInteraction = Date.now()
  moveCarousel(direction)
}

function onCarouselKeydown(event) {
  if (event.key === 'ArrowLeft') moveCarouselManually(-1)
  if (event.key === 'ArrowRight') moveCarouselManually(1)
}

function onCarouselPointerDown(event) {
  if (event.pointerType === 'mouse') return
  carouselPointerStart.value = { x: event.clientX, y: event.clientY }
  carouselPaused.value = true
}

function onCarouselPointerUp(event) {
  if (carouselPointerStart.value == null) return
  const distance = event.clientX - carouselPointerStart.value.x
  const vertical = event.clientY - carouselPointerStart.value.y
  if (Math.abs(distance) > 42 && Math.abs(distance) > Math.abs(vertical) * 1.25) {
    moveCarouselManually(distance < 0 ? 1 : -1)
  }
  carouselPointerStart.value = null
  carouselPaused.value = false
}

function onVisibilityChange() {
  carouselPaused.value = document.hidden
}

function updateMotionPreference(event) {
  prefersReducedMotion.value = event.matches
}

/* ============ Hero 操作按钮（图片本身承载标题与说明） ============ */
const currentHero = computed(() => heroBanners.value[heroIndex.value] || heroBanners.value[0])
const heroButtonText = computed(() => currentHero.value?.buttonText || '探索产品')
const heroButtonUrl = computed(() => currentHero.value?.buttonUrl || '/products')
const heroSecondaryText = computed(() => currentHero.value?.secondaryButtonText || '获取包装方案')
/* 次按钮：若后台配置了 URL 则跳转；默认打开询盘弹窗 */
const heroSecondaryUrl = computed(() => currentHero.value?.secondaryButtonUrl || '')
const heroSecondaryUseModal = computed(() => !heroSecondaryUrl.value)

function openHeroInquiry() {
  openInquiry({ type: 'consult', source: 'home-hero' })
}

function onHeroSecondaryClick() {
  if (heroSecondaryUseModal.value) openHeroInquiry()
}

/* 判断链接是否为内部路由（走 RouterLink），否则走原生 <a>（外部链接、tel:、mailto:） */
function isInternalRoute(url) {
  if (!url) return false
  if (url.startsWith('http://') || url.startsWith('https://')) return false
  if (url.startsWith('tel:') || url.startsWith('mailto:')) return false
  return true
}

/* 判断某张 Hero slide 是否应该加载背景图（当前/相邻 slide 立即加载，其余延迟） */
function shouldPreloadSlide(i) {
  const total = heroBanners.value.length
  if (!total) return false
  if (i === heroIndex.value) return true
  // 预加载下一张（即将切换到）
  if (i === (heroIndex.value + 1) % total) return true
  return false
}

/* ============ 行业应用场景 ============ */
/* 行业卡片背景图懒加载：仅在卡片进入视口附近时才设置 CSS 背景图 URL，避免一次性加载全部图片 */
const visibleIndustries = reactive(new Set())
const industryItemRefs = ref([])
let industryObserver = null

/* 行业场景全部来自后台「官网运营 · 解决方案」INDUSTRY 分组；
   后台清空时隐藏首页场景区，不再使用内置兜底数据。 */
const industryScenarios = computed(() => {
  const scenes = solutionGroups.value.find((group) => group.section === 'INDUSTRY')?.items || []
  const featured = scenes.filter((scene) => scene.websiteFeatured)
  const selected = featured.length ? featured : scenes
  return selected.slice(0, 4).map((scene) => ({
    name: scene.title,
    desc: scene.summary || '查看该生产场景下的包装判断与方案内容',
    scene: scene.itemKey,
    image: scene.coverImageUrl || FALLBACK_IMAGE_URL,
  })).filter((scene) => scene.scene)
})

const manufacturingShowcase = computed(() => {
  const rows = sectionRows('showcase').filter((row) => row.image && row.link !== '/molds')
  if (!rows.length) return []
  return rows.map((row, index) => ({
    key: `cms-showcase-${index}`,
    title: row.title || '制造能力',
    image: row.image,
    to: row.link || '/about',
  }))
})

/* 场景 → 询盘：携带场景标签，让表单“理解”用户来自哪里 */
function openSceneAssistInquiry(sceneName) {
  openInquiry({ type: 'consult', source: 'home-scene-assist', sceneLabel: sceneName || '选型协助' })
}
</script>

<template>
  <main>
    <!-- ============ Hero ============ -->
    <section
      ref="heroSectionEl"
      class="hero hero-carousel"
      aria-roledescription="carousel"
      aria-label="首页 Banner"
      @mouseenter="heroPaused = true"
      @mouseleave="heroPaused = false"
      @focusin="heroPaused = true"
      @focusout="heroPaused = false"
    >
      <!-- 背景层：各 Banner 图片做淡入淡出切换，仅当前/下一张 slide 加载背景图 -->
      <div class="hero-slides">
        <div
          v-for="(b, i) in heroBanners"
          :key="`hero-slide-${i}`"
          class="hero-slide"
          :class="{ 'is-active': i === heroIndex, 'is-loaded': shouldPreloadSlide(i) }"
          :style="shouldPreloadSlide(i) ? { '--hero-background': b.imageUrl ? `url(${b.imageUrl})` : 'none' } : undefined"
          :data-bg="shouldPreloadSlide(i) ? undefined : b.imageUrl"
          :aria-hidden="i !== heroIndex"
        ></div>
      </div>
      <div class="hero-grid"></div>

      <!-- Hero 文本层：背景图做纯画面承载，标题/描述/按钮全部用 HTML，保证响应式排版和 SEO 可读 -->
      <div class="hero-copy reveal" v-reveal :key="`hero-copy-${heroIndex}`">
        <div class="hero-copy__inner">
          <p v-if="currentHero?.subtitle" class="hero-eyebrow">{{ currentHero.subtitle }}</p>
          <h1 v-if="currentHero?.title" v-html="safeHeroTitle"></h1>
          <p v-if="currentHero?.description" class="hero-description">{{ currentHero.description }}</p>
          <div class="hero-actions">
            <RouterLink v-if="isInternalRoute(heroButtonUrl)" :to="heroButtonUrl" class="button primary">{{ heroButtonText }} <span>→</span></RouterLink>
            <a v-else :href="heroButtonUrl" class="button primary" target="_blank" rel="noopener">{{ heroButtonText }} <span>→</span></a>
            <button
              v-if="heroSecondaryUseModal"
              type="button"
              class="button ghost"
              @click="onHeroSecondaryClick"
            >{{ heroSecondaryText }}</button>
            <RouterLink
              v-else-if="isInternalRoute(heroSecondaryUrl)"
              :to="heroSecondaryUrl"
              class="button ghost"
            >{{ heroSecondaryText }}</RouterLink>
            <a v-else :href="heroSecondaryUrl" class="button ghost" target="_blank" rel="noopener">{{ heroSecondaryText }}</a>
          </div>
        </div>
      </div>

      <button v-if="heroBanners.length > 1" type="button" class="hero-nav hero-nav--prev" @click="prevHero" aria-label="上一张">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M15 18l-6-6 6-6"/></svg>
      </button>
      <button v-if="heroBanners.length > 1" type="button" class="hero-nav hero-nav--next" @click="nextHero" aria-label="下一张">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" d="M9 18l6-6-6-6"/></svg>
      </button>

      <div v-if="heroBanners.length > 1" class="hero-dots" role="tablist" aria-label="Banner 分页">
        <button
          v-for="(b, i) in heroBanners"
          :key="`hero-dot-${i}`"
          type="button"
          class="hero-dot"
          :class="{ 'is-active': i === heroIndex }"
          role="tab"
          :aria-selected="i === heroIndex"
          :aria-label="`第 ${i + 1} 张 Banner`"
          @click="resetHeroTimer(); gotoHero(i)"
        ></button>
      </div>
    </section>

    <!-- 首屏后的企业证明：先建立信任，再进入产品选择 -->
    <section v-if="homeSectionEnabled('strength')" class="home-strength" aria-label="企业实力">
      <div class="home-strength__intro">
        <span>LIQIANG AT A GLANCE</span>
        <strong>{{ homeHeadings.strength.title }}</strong>
      </div>
      <div class="home-strength__facts">
        <RouterLink to="/about"><strong>{{ foundedYear }}</strong><span>创立于温州</span></RouterLink>
        <RouterLink to="/products"><strong>{{ productCount || homeProducts.length || '—' }}</strong><span>公开设备型号</span></RouterLink>
        <RouterLink to="/patents"><strong>{{ patents.length || '—' }}</strong><span>专利证书展示</span></RouterLink>
        <RouterLink to="/about"><strong>配套</strong><span>设备 · 包材 · 方案</span></RouterLink>
      </div>
    </section>

    <!-- 精选产品：让访客先看到实际产品 -->
    <section v-if="homeSectionEnabled('products')" class="section products" id="products">
      <SectionHeading
        eyebrow="CORE EQUIPMENT"
        :title="homeHeadings.products.title"
      >
        <template #description>
          <p>{{ homeHeadings.products.description }}</p>
          <RouterLink to="/products" class="section-heading__link">全部产品 →</RouterLink>
        </template>
      </SectionHeading>
      <div
        ref="productCarouselEl"
        class="product-carousel"
        tabindex="0"
        role="region"
        aria-label="核心设备轮播"
        @keydown="onCarouselKeydown"
        @pointerdown="onCarouselPointerDown"
        @pointerup="onCarouselPointerUp"
        @pointercancel="carouselPointerStart = null; carouselPaused = false"
        @mouseenter="carouselPaused = true"
        @mouseleave="carouselPaused = false"
        @focusin="carouselPaused = true"
        @focusout="carouselPaused = false"
      >
      <div class="live-catalog" aria-live="polite">
        <!-- 产品骨架屏 -->
        <template v-if="loading && !hasProducts">
          <div v-for="n in 4" :key="`skp-${n}`" class="skeleton-card">
            <div class="skeleton-img"></div>
            <div class="skeleton-line l1"></div>
            <div class="skeleton-line l2"></div>
            <div class="skeleton-line l3"></div>
          </div>
        </template>
        <template v-else-if="hasProducts">
          <div class="product-carousel__track" :style="{ '--carousel-index': carouselIndex }">
            <ProductCard v-for="product in homeProducts" :key="product.id" :product="product" />
          </div>
        </template>
        <p v-else-if="error" class="data-loading">产品信息加载异常，请稍后刷新或直接浏览产品中心。</p>
        <p v-else class="data-loading">{{ loading ? '正在加载…' : '产品信息持续更新中，更多设备请浏览产品中心。' }}</p>
      </div>
        <div v-if="homeProducts.length > carouselVisible" class="product-carousel__controls">
          <button type="button" class="product-carousel__prev" aria-label="上一组设备" @click="moveCarouselManually(-1)"><span>‹</span></button>
          <button type="button" class="product-carousel__next" aria-label="下一组设备" @click="moveCarouselManually(1)"><span>›</span></button>
        </div>
      </div>
    </section>

    <!-- 首页核心制造能力图廊 -->
    <section v-if="homeSectionEnabled('showcase') && manufacturingShowcase.length" class="section manufacturing-showcase" id="manufacturing-showcase">
      <SectionHeading
        eyebrow="MANUFACTURING"
        :title="homeHeadings.showcase.title"
        size="lg"
      >
        <template #description>
          <p>{{ homeHeadings.showcase.description }}</p>
          <div class="showcase-proof">
            <RouterLink to="/credentials">查看企业资质</RouterLink>
            <RouterLink v-if="patents.length" to="/patents">查看专利证书</RouterLink>
          </div>
        </template>
      </SectionHeading>
      <div class="manufacturing-gallery">
        <RouterLink
          v-for="(item, index) in manufacturingShowcase"
          :key="item.key"
          :to="item.to"
          class="manufacturing-card reveal"
          :class="{ 'manufacturing-card--large': index === 0 }"
          v-reveal
        >
          <img :src="item.image" :alt="item.title" loading="lazy" decoding="async" />
          <div class="manufacturing-card__overlay">
            <h3>{{ item.title }}</h3>
            <span>了解更多</span>
          </div>
        </RouterLink>
      </div>
    </section>

    <!-- ============ Section 4：典型行业场景 ============ -->
    <section v-if="homeSectionEnabled('industries') && industryScenarios.length" class="section industries section--alt" id="industries">
      <SectionHeading
        eyebrow="APPLICATIONS"
        :title="homeHeadings.industries.title"
      >
        <template #description>
          <p>{{ homeHeadings.industries.description }}</p>
          <RouterLink to="/solutions" class="section-heading__link">浏览全部方案 <span>→</span></RouterLink>
        </template>
      </SectionHeading>
      <div class="industries-grid">
        <RouterLink
          v-for="(ind, idx) in industryScenarios"
          :key="idx"
          :to="{ path: '/solutions', query: { scene: ind.scene } }"
          class="industry-item reveal"
          :style="visibleIndustries.has(idx) ? { '--industry-image': `url(${ind.image})` } : undefined"
          :data-bg="ind.image"
          ref="industryItemRefs"
          :aria-label="`查看${ind.name}应用方案`"
          v-reveal
        >
          <span class="industry-index">0{{ idx + 1 }}</span>
          <div class="industry-content">
            <h4>{{ ind.name }}</h4>
            <p>{{ ind.desc }}</p>
            <span class="industry-link">查看方案 <i>→</i></span>
          </div>
        </RouterLink>
      </div>

      <!-- 场景选型辅助入口：不确定选型时由工程师协助 -->
      <div class="scene-assist reveal" v-reveal>
        <div class="scene-assist__text">
          <h4>不确定适合哪种设备？</h4>
          <p>提供产品和容器信息，工程师可协助判断合适的设备与包材组合。</p>
        </div>
        <button type="button" class="button ghost-light" @click="openSceneAssistInquiry('选型协助')">请求协助选型 <span>→</span></button>
      </div>
    </section>

    <!-- ============ Section 7：获取方案 CTA ============ -->
    <SiteCTA id="cta" inquiry-source="home-bottom" />
  </main>
</template>
