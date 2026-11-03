<script setup>
/**
 * 产品详情页。
 * - 按 slug 查询 /api/website/products/{slug}
 * - 左侧图库（主图 + 缩略图）+ 右侧信息（名称/型号/摘要/CTA）
 * - 下方 sections：产品介绍 / 技术参数 / 配置规格 / 适用行业 / 包装与交付
 * - slug 不存在或未发布显示 404 错误页
 * - 注入 Product 结构化数据支持搜索引擎理解产品信息
 */
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import DOMPurify from 'dompurify'
import { fetchProductDetail, fetchProducts, websiteProductMediaUrl } from '../api/website'
import { apiBase } from '../api/http'
import { FALLBACK_IMAGE_URL } from '../utils/fallbackImage'
import { reviewedSummary } from '../utils/publicSummary'
import { setSeo, SITE_URL } from '../composables/useSeo'
import { setSchema, createProductSchema } from '../composables/useSchema'
import { useCompany } from '../composables/useCompany'
import { useInquiryModal } from '../composables/useInquiryModal'
import SiteCTA from '../components/common/SiteCTA.vue'
import ProductCard from '../components/ProductCard.vue'

const route = useRoute()
const router = useRouter()
const { company } = useCompany()
const { openInquiry } = useInquiryModal()
const loading = ref(true)
const notFound = ref(false)
const detail = ref(null)
const relatedProducts = ref([])
let detailRequest = 0

const activeImageIndex = ref(0)
const previewOpen = ref(false)
function moveImage(direction) {
  activeImageIndex.value = (activeImageIndex.value + direction + galleryImages.value.length) % galleryImages.value.length
}
function onPreviewKeydown(event) {
  if (!previewOpen.value) return
  if (event.key === 'Escape') previewOpen.value = false
  if (event.key === 'ArrowLeft') moveImage(-1)
  if (event.key === 'ArrowRight') moveImage(1)
}

// 图库：优先 main_image → 其他展示类图片 → 分类默认图 → 全站 placeholder
const galleryImages = computed(() => {
  if (!detail.value) return []
  if (detail.value.product) {
    const product = detail.value.product
    const gallery = (detail.value.gallery || []).filter((item) => item.mediaId)
      .sort((a, b) => {
        const priority = (item) => item.role === 'HERO' || item.mediaId === product.heroMediaId ? 0 : 1
        return priority(a) - priority(b) || (a.sortOrder ?? 0) - (b.sortOrder ?? 0)
      })
    if (gallery.length) return gallery.map((item) => ({
      id: item.id,
      url: websiteProductMediaUrl(item.mediaId),
      srcset: '',
      fileName: item.altText || product.name,
    }))
    if (product.heroMediaId || product.cardMediaId) return [{
      id: 'website-hero',
      url: websiteProductMediaUrl(product.heroMediaId || product.cardMediaId),
      srcset: '',
      fileName: product.name,
    }]
    return [{ id: 'website-fallback', url: FALLBACK_IMAGE_URL, srcset: '', fileName: product.name }]
  }
  const assets = detail.value.gallery || []

  const buildAssetUrl = (id, size) =>
    `${apiBase}/api/file-assets/product_model/assets/${id}/thumbnail?size=${size}`
  const buildSrcSet = (id) =>
    `${buildAssetUrl(id, 400)} 400w, ${buildAssetUrl(id, 800)} 800w, ${buildAssetUrl(id, 1200)} 1200w`

  // 优先级 1：main_image
  const mainImages = assets.filter((a) => a.category === 'main_image')
  if (mainImages.length) {
    return mainImages.map((a) => ({
      id: a.id,
      url: buildAssetUrl(a.id, 800),
      srcset: buildSrcSet(a.id),
      fileName: a.fileName,
    }))
  }

  // 优先级 2：其他展示类图片（scene/detail/parameter/sku_image）
  const displayImages = assets.filter((a) =>
    ['scene_image', 'detail_image', 'parameter_image', 'sku_image'].includes(a.category)
  )
  if (displayImages.length) {
    return displayImages.map((a) => ({
      id: a.id,
      url: buildAssetUrl(a.id, 800),
      srcset: buildSrcSet(a.id),
      fileName: a.fileName,
    }))
  }

  // 优先级 3：按设备分类匹配默认图
  return [{ id: 'fallback', url: FALLBACK_IMAGE_URL, srcset: '', fileName: '默认图' }]
})

const activeImage = computed(() => galleryImages.value[activeImageIndex.value] || galleryImages.value[0])

const model = computed(() => {
  if (!detail.value?.product) return detail.value?.model || {}
  const product = detail.value.product
  return {
    name: product.name,
    modelCode: product.publicModel,
    equipmentCategory: product.category,
    productLineName: product.productLineName || product.category,
    websiteSlug: product.slug,
    websiteSummary: reviewedSummary(product.name, product.summary),
    applicationTags: product.applicationTags,
    containerTags: product.containerTags,
    packagingProcessTags: product.processTags,
    capacityLevel: product.capacityLevel,
    ...detail.value.technicalParameters,
  }
})
const nativeDescriptionHtml = computed(() => detail.value?.product && detail.value.descriptionHtml
  ? DOMPurify.sanitize(detail.value.descriptionHtml)
  : '')

// 规格参数（基线）
const specRows = computed(() => {
  if (!model.value) return []
  const m = model.value
  return [
    { label: '电压', value: m.voltage },
    { label: '频率', value: m.frequency },
    { label: '功率', value: m.power },
    { label: '包装速度', value: m.packagingSpeed },
    { label: '设备尺寸', value: m.deviceSize },
    { label: '设备重量', value: m.deviceWeightKg ? `${m.deviceWeightKg} kg` : '' },
    { label: '最大膜宽', value: m.maxFilmWidth },
    { label: '卷膜直径', value: m.rollFilmDiameter },
    { label: '最大封口尺寸', value: m.maxSealingSize },
    { label: '气压要求', value: m.airPressureRequirement },
    { label: '气体置换率', value: m.gasReplacementRate },
    { label: '残氧量', value: m.residualOxygen },
    { label: '包装尺寸', value: m.packageSize },
    { label: '包装重量', value: m.packageWeightKg ? `${m.packageWeightKg} kg` : '' },
  ].filter((row) => row.value)
})

const hasSpecs = computed(() => specRows.value.length > 0)

const hasIntroduction = computed(() => nativeDescriptionHtml.value || model.value.introductionCn || model.value.applicableScope)
const hasAdvantages = computed(() => model.value.equipmentAdvantages)
const hasApplicable = computed(() => Boolean(model.value.applicableProduct))
const hasPackaging = computed(
  () => model.value.packagingMethodCn || model.value.shippingMethods || model.value.leadTimeStock
)

const keyFacts = computed(() => {
  const m = model.value
  return [
    { label: '包装速度', value: m.packagingSpeed },
    { label: '自动化程度', value: m.automationLevel || m.operationMode },
    { label: '最大封口尺寸', value: m.maxSealingSize },
    { label: '电源 / 功率', value: [m.voltage, m.power].filter(Boolean).join(' · ') },
  ].filter((item) => item.value).slice(0, 4)
})

const selectionFacts = computed(() => {
  const m = model.value
  return [
    { label: '适用产品 / 场景', value: m.applicationTags?.join('、') || m.applicableIndustry },
    { label: '适用容器', value: m.containerTags?.join('、') },
    { label: '支持工艺', value: m.packagingProcessTags?.join('、') || m.packagingMethodCn },
    { label: '目标产能', value: m.capacityLevel },
  ].filter((item) => item.value)
})

const variantCards = computed(() => (detail.value?.variants || []).map((variant) => ({
  ...variant,
  facts: [
    { label: '适用产品', value: variant.applicableProduct },
    { label: '封口口径', value: variant.mouthSpec || variant.filmOrBoxSize },
    { label: '包装速度', value: variant.packagingSpeed },
    { label: '电源 / 功率', value: [variant.voltage, variant.power].filter(Boolean).join(' · ') },
    { label: '设备尺寸', value: variant.dimensions || variant.deviceSize || variant.equipmentSizeSpec },
  ].filter((item) => item.value),
  variantName: variant.name || variant.variantName,
  specKey: variant.publicSku || variant.specKey,
})).filter((variant) => variant.variantName || variant.facts.length).slice(0, 6))

const detailNav = computed(() => [
  hasIntroduction.value && { id: 'introduction', label: '产品介绍' },
  hasApplicable.value && { id: 'applications', label: '适用场景' },
  variantCards.value.length && { id: 'variants', label: '可选规格' },
  hasAdvantages.value && { id: 'advantages', label: '核心优势' },
  hasSpecs.value && { id: 'specifications', label: '技术参数' },
  hasPackaging.value && { id: 'delivery', label: '交付信息' },
].filter(Boolean))

/* 询盘弹窗：带入产品上下文 */
const productSlug = computed(() => route.params.slug || '')
const productNameForInquiry = computed(() => {
  const m = model.value
  return [m.name, m.modelCode].filter(Boolean).join(' ') || ''
})
function openConsultInquiry() {
  openInquiry({
    type: 'consult',
    source: 'product-detail',
    productSlug: productSlug.value,
    productName: productNameForInquiry.value,
  })
}
function openSampleInquiry() {
  openInquiry({
    type: 'sample',
    source: 'product-detail',
    productSlug: productSlug.value,
    productName: productNameForInquiry.value,
  })
}

async function load() {
  const request = ++detailRequest
  previewOpen.value = false
  relatedProducts.value = []
  loading.value = true
  notFound.value = false
  detail.value = null
  activeImageIndex.value = 0
  try {
    const slug = route.params.slug
    const data = await fetchProductDetail(slug)
    if (request !== detailRequest) return
    if (!data) {
      notFound.value = true
    } else {
      detail.value = data
      // 动态 SEO：基于产品名称和型号生成 title 和 description
      const m = data.product ? {
        name: data.product.name,
        modelCode: data.product.publicModel,
        equipmentCategory: data.product.category,
        websiteSummary: reviewedSummary(data.product.name, data.product.summary),
        introductionCn: reviewedSummary(data.product.name, data.product.summary),
        defaultImage: null,
      } : (data.model || {})
      const titleParts = [m.name, m.modelCode].filter(Boolean)
      const seoTitle = data.product?.seoTitle || (titleParts.length > 0 ? titleParts.join('｜') : '产品详情')
      const rawSeoDesc = data.product?.seoDescription
      const seoDesc = reviewedSummary(data.product?.name, rawSeoDesc) || (m.introductionCn
        ? m.introductionCn.slice(0, 150)
        : `${m.name || '利强包装设备'}｜${m.equipmentCategory || '食品包装设备'}。查看设备图片与已公开技术参数。`)
      setSeo(seoTitle, seoDesc, {
        ogType: 'product',
        ogImage: data.product?.heroMediaId ? websiteProductMediaUrl(data.product.heroMediaId) : data.imageUrl,
        canonical: `${SITE_URL}/products/${route.params.slug}`,
      })
      
      // 注入 Product 结构化数据
      const productSchema = createProductSchema({
        name: m.name,
        modelCode: m.modelCode,
        productLineName: m.productLineName,
        equipmentCategory: m.equipmentCategory,
        websiteSummary: m.websiteSummary,
        introductionCn: m.introductionCn,
        defaultImage: data.product?.heroMediaId
          ? websiteProductMediaUrl(data.product.heroMediaId)
          : (m.defaultImage ? `${apiBase}${m.defaultImage}` : null),
        brand: company.value?.brandName || '利强包装',
        url: window.location.href
      })
      setSchema(productSchema)
      const category = data.product?.category
      if (category) {
        try {
          const related = await fetchProducts({ equipmentCategory: category, page: 0, size: 12 })
          if (request === detailRequest) relatedProducts.value = (related.items || [])
            .filter((item) => item.slug !== slug && item.websiteSlug !== slug)
            .slice(0, 3)
        } catch (error) {
          console.warn('Related products unavailable:', error)
        }
      }
    }
  } catch (err) {
    if (request !== detailRequest) return
    console.warn('Product detail unavailable:', err)
    notFound.value = true
  } finally {
    if (request === detailRequest) loading.value = false
  }
}

function handleImageError(event) {
  event.target.src = FALLBACK_IMAGE_URL
}

onMounted(() => {
  load()
  window.addEventListener('keydown', onPreviewKeydown)
})
onUnmounted(() => window.removeEventListener('keydown', onPreviewKeydown))
watch(() => route.params.slug, load)

/**
 * 返回产品中心时优先回到来源列表（保留筛选条件与页码、复用历史滚动位置），
 * 直接打开详情链接时退回默认产品中心，不会因为无历史而离开站点。
 */
function backToCatalog() {
  const previous = window.history.state?.back
  if (typeof previous === 'string' && /^\/products(\?|#|$)/.test(previous)) {
    router.back()
    return
  }
  router.push('/products')
}
</script>

<template>
  <main class="product-detail-page">
    <!-- 加载中 -->
    <div v-if="loading" class="detail-error">
      <p>正在加载产品详情…</p>
    </div>

    <!-- 404 -->
    <div v-else-if="notFound" class="detail-error">
      <h1>产品不存在</h1>
      <p>该产品可能已下架或链接有误。</p>
      <RouterLink to="/products" class="button primary">
        返回产品中心 <span>→</span>
      </RouterLink>
    </div>

    <!-- 详情内容 -->
    <template v-else>
      <!-- 面包屑 -->
      <nav class="breadcrumb">
        <RouterLink to="/">首页</RouterLink>
        <span>/</span>
        <a href="/products" @click.prevent="backToCatalog">产品中心</a>
        <span>/</span>
        <strong>{{ model.name }}</strong>
      </nav>

      <!-- 主体：左图库 + 右信息 -->
      <div class="detail-container">
        <div class="detail-mobile-heading">
          <div class="detail-heading-meta">
            <span>{{ model.productLineName || model.equipmentCategory || '包装设备' }}</span>
            <span v-if="model.modelCode">{{ model.modelCode }}</span>
          </div>
          <h1>{{ model.name }}</h1>
        </div>
        <div class="detail-gallery">
          <button type="button" class="detail-main-image" aria-label="放大查看产品图片" @click="previewOpen = true">
            <img
              v-if="activeImage"
              :src="activeImage.url"
              :srcset="activeImage.srcset"
              sizes="(max-width: 980px) 100vw, 60vw"
              :alt="model.name"
              decoding="async"
              @error="handleImageError"
            />
          </button>
          <div v-if="galleryImages.length > 1" class="detail-thumbnails" aria-label="产品图片选择">
            <button
              v-for="(img, idx) in galleryImages"
              :key="img.id"
              type="button"
              class="detail-thumbnail"
              :class="{ active: activeImageIndex === idx }"
              :aria-pressed="activeImageIndex === idx"
              :aria-label="`查看第 ${idx + 1} 张产品图片`"
              @click="activeImageIndex = idx"
            >
              <img :src="img.url" :alt="img.fileName" @error="handleImageError" loading="lazy" decoding="async" />
            </button>
          </div>
        </div>

        <div class="detail-info">
          <div class="detail-heading-meta detail-desktop-heading">
            <span>{{ model.productLineName || model.equipmentCategory || '包装设备' }}</span>
            <span v-if="model.modelCode">{{ model.modelCode }}</span>
          </div>
          <h1 class="detail-desktop-heading">{{ model.name }}</h1>

          <p v-if="model.websiteSummary" class="detail-summary">{{ model.websiteSummary }}</p>

          <div v-if="keyFacts.length" class="detail-selection-facts">
            <span class="detail-selection-label">关键选型信息</span>
            <dl class="detail-key-facts">
              <div v-for="fact in keyFacts" :key="fact.label">
                <dt>{{ fact.label }}</dt>
                <dd>{{ fact.value }}</dd>
              </div>
            </dl>
          </div>

          <div v-if="selectionFacts.length" class="detail-fit-summary">
            <span>适配范围</span>
            <ul>
              <li v-for="item in selectionFacts" :key="item.label"><strong>{{ item.label }}</strong>{{ item.value }}</li>
            </ul>
          </div>

          <div class="detail-cta">
            <button type="button" class="button primary" @click="openConsultInquiry">咨询设备配置 <span>→</span></button>
            <button type="button" class="button ghost" @click="openSampleInquiry">申请样品试封</button>
          </div>

          <!-- 关键标签 -->
          <div v-if="model.equipmentCategory || model.machineType" class="chips">
            <span v-if="model.equipmentCategory">{{ model.equipmentCategory }}</span>
            <span v-if="model.machineType">{{ model.machineType }}</span>
            <span v-if="model.operationMode">{{ model.operationMode }}</span>
            <span v-if="model.automationLevel">{{ model.automationLevel }}</span>
          </div>
        </div>
      </div>

      <nav v-if="detailNav.length" class="detail-anchor-nav" aria-label="产品详情导航">
        <a v-for="item in detailNav" :key="item.id" :href="`#${item.id}`">{{ item.label }}</a>
        <button type="button" class="nav-consult" @click="openConsultInquiry">咨询该产品 →</button>
      </nav>

      <!-- 详情 sections -->
      <div class="detail-sections">

        <!-- 产品介绍 -->
        <section v-if="hasIntroduction" id="introduction" class="detail-section">
          <div class="detail-section__heading"><span>OVERVIEW</span><h2>产品介绍</h2></div>
          <div class="detail-section__content">
            <div v-if="nativeDescriptionHtml" class="detail-text" v-html="nativeDescriptionHtml"></div>
            <p v-if="model.introductionCn" class="detail-text">{{ model.introductionCn }}</p>
            <p v-if="model.applicableScope" class="detail-text">{{ model.applicableScope }}</p>
          </div>
        </section>

        <!-- 适用行业 -->
        <section v-if="hasApplicable" id="applications" class="detail-section">
          <div class="detail-section__heading"><span>APPLICATIONS</span><h2>适用行业与产品</h2></div>
          <div class="detail-section__content">
            <p v-if="model.applicableProduct" class="detail-text-applicable">{{ model.applicableProduct }}</p>
          </div>
        </section>

        <section v-if="variantCards.length" id="variants" class="detail-section">
          <div class="detail-section__heading">
            <span>CONFIGURATIONS</span><h2>可选规格</h2>
            <p>展示最多 6 个代表性规格。不同封口尺寸、产能和电气配置可能对应不同方案，最终以样品和容器实测为准。</p>
          </div>
          <div class="detail-section__content variant-card-grid">
            <article v-for="(variant, index) in variantCards" :key="variant.id || index" class="variant-card">
              <header>
                <small>{{ variant.baseline ? '推荐基准规格' : `规格 ${String(index + 1).padStart(2, '0')}` }}</small>
                <h3>{{ variant.variantName || variant.specKey || '产品配置' }}</h3>
              </header>
              <dl>
                <div v-for="fact in variant.facts" :key="fact.label"><dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd></div>
              </dl>
            </article>
          </div>
        </section>

        <!-- 核心优势 -->
        <section v-if="hasAdvantages" id="advantages" class="detail-section">
          <div class="detail-section__heading"><span>PERFORMANCE</span><h2>核心优势</h2></div>
          <div class="detail-section__content"><p class="detail-text">{{ model.equipmentAdvantages }}</p></div>
        </section>

        <!-- 技术参数 -->
        <section v-if="hasSpecs" id="specifications" class="detail-section">
          <div class="detail-section__heading"><span>SPECIFICATIONS</span><h2>技术参数</h2></div>
          <div class="detail-section__content"><dl class="detail-key-facts spec-facts">
              <div v-for="row in specRows" :key="row.label"><dt>{{ row.label }}</dt><dd>{{ row.value }}</dd></div>
          </dl></div>
        </section>

        <!-- 包装与交付 -->
        <section v-if="hasPackaging" id="delivery" class="detail-section">
          <div class="detail-section__heading"><span>DELIVERY</span><h2>包装与交付</h2></div>
          <div class="detail-section__content"><dl class="detail-key-facts spec-facts">
            <div v-if="model.packagingMethodCn"><dt>包装方式</dt><dd>{{ model.packagingMethodCn }}</dd></div>
            <div v-if="model.shippingMethods"><dt>发货方式</dt><dd>{{ model.shippingMethods }}</dd></div>
            <div v-if="model.leadTimeStock"><dt>现货交期</dt><dd>{{ model.leadTimeStock }}</dd></div>
            <div v-if="model.leadTimeCustom"><dt>定制交期</dt><dd>{{ model.leadTimeCustom }}</dd></div>
          </dl></div>
        </section>

      </div>
      <section v-if="relatedProducts.length" class="detail-related" aria-label="同类设备">
        <div class="detail-related__heading"><h2>看看同类设备</h2><RouterLink to="/products">浏览全部设备 →</RouterLink></div>
        <div class="detail-related__grid"><ProductCard v-for="item in relatedProducts" :key="item.slug || item.id" :product="item" /></div>
      </section>
      <SiteCTA
        eyebrow="PRODUCT CONSULTATION"
        :title="`确认 ${model.name || '这款设备'} 是否适合您的产品`"
        description="提供产品、容器尺寸和目标产能，工程师将协助核对设备与包材配置。"
        action-text="咨询该产品"
        inquiry-source="product-detail-bottom"
        :product-slug="productSlug"
        :product-name="productNameForInquiry"
      />
      <div v-if="previewOpen && activeImage" class="detail-image-preview" role="dialog" aria-modal="true" aria-label="产品图片预览" @click.self="previewOpen = false">
        <button type="button" class="detail-image-preview__close" aria-label="关闭预览" @click="previewOpen = false">×</button>
        <button v-if="galleryImages.length > 1" type="button" aria-label="上一张图片" @click="moveImage(-1)">‹</button>
        <img :src="activeImage.url" :alt="model.name" @error="handleImageError" />
        <button v-if="galleryImages.length > 1" type="button" aria-label="下一张图片" @click="moveImage(1)">›</button>
        <span>{{ activeImageIndex + 1 }} / {{ galleryImages.length }}</span>
      </div>
    </template>
  </main>
</template>
