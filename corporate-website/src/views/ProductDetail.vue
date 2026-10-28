<script setup>
/**
 * 产品详情页。
 * - 按 slug 查询 /api/website/products/{slug}
 * - 左侧图库（主图 + 缩略图）+ 右侧信息（名称/型号/摘要/CTA）
 * - 下方 sections：产品介绍 / 技术参数 / 配置规格 / 适用行业 / 包装与交付
 * - slug 不存在或未发布显示 404 错误页
 * - 注入 Product 结构化数据支持搜索引擎理解产品信息
 */
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import DOMPurify from 'dompurify'
import { fetchProductDetail, websiteProductMediaUrl } from '../api/website'
import { apiBase } from '../api/http'
import { setSeo, SITE_URL } from '../composables/useSeo'
import { setSchema, createProductSchema } from '../composables/useSchema'
import { useCompany } from '../composables/useCompany'
import { useInquiryModal } from '../composables/useInquiryModal'
import SiteCTA from '../components/common/SiteCTA.vue'

const route = useRoute()
const { company } = useCompany()
const { openInquiry } = useInquiryModal()
const loading = ref(true)
const notFound = ref(false)
const detail = ref(null)

const activeImageIndex = ref(0)

// 图库：优先 main_image → 其他展示类图片 → 分类默认图 → 全站 placeholder
const galleryImages = computed(() => {
  if (!detail.value) return []
  if (detail.value.product) {
    const product = detail.value.product
    const gallery = (detail.value.gallery || []).filter((item) => item.mediaId)
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
  const categoryFallback = getCategoryFallbackImage(model.value.equipmentCategory)
  if (categoryFallback) {
    return [{ id: 'category-fallback', url: categoryFallback, srcset: '', fileName: '分类默认图' }]
  }

  // 优先级 4：全站 placeholder
  return [{ id: 'fallback', url: '/images/manual-sealer.jpg', srcset: '', fileName: '默认图' }]
})

/**
 * 按设备分类返回对应的默认图路径。
 * 后台上传真实产品图后，此回退不会被触发。
 */
function getCategoryFallbackImage(category) {
  if (!category) return null
  const map = {
    '气调包装机': '/images/hero-machine.png',
    '封口机': '/images/manual-sealer.jpg',
    '真空包装机': '/images/hero-machine.png',
    '全自动包装机': '/images/hero-machine.png',
  }
  return map[category] || null
}

const activeImage = computed(() => galleryImages.value[activeImageIndex.value] || galleryImages.value[0])

const model = computed(() => {
  if (!detail.value?.product) return detail.value?.model || {}
  const product = detail.value.product
  return {
    name: product.name,
    modelCode: product.publicModel,
    equipmentCategory: product.category,
    websiteSlug: product.slug,
    websiteSummary: product.summary,
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
const hasApplicable = computed(() =>
  model.value.applicableIndustry || model.value.applicableProduct ||
  model.value.applicationTags?.length || model.value.containerTags?.length ||
  model.value.packagingProcessTags?.length || model.value.capacityLevel
)
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
    { label: '模具形式', value: variant.boxOrMoldShape },
    { label: '封口口径', value: variant.moldSizeLabel || variant.mouthSpec || variant.filmOrBoxSize },
    { label: '包装速度', value: variant.packagingSpeed },
    { label: '电源 / 功率', value: [variant.voltage, variant.power].filter(Boolean).join(' · ') },
    { label: '设备尺寸', value: variant.dimensions || variant.deviceSize || variant.equipmentSizeSpec },
  ].filter((item) => item.value),
  variantName: variant.name || variant.variantName,
  specKey: variant.publicSku || variant.specKey,
})).filter((variant) => variant.variantName || variant.facts.length).slice(0, 6))

const compatiblePackaging = computed(() => detail.value?.compatiblePackaging || [])
const compatibleMolds = computed(() => detail.value?.compatibleMolds || [])
const hasCompatibility = computed(() => compatiblePackaging.value.length > 0 || compatibleMolds.value.length > 0)

function moldRelationLabel(value) {
  return ({ main: '主配模具', backup: '备用模具', paired: '配套模具' })[value] || '可配模具'
}

const detailNav = computed(() => [
  hasIntroduction.value && { id: 'introduction', label: '产品介绍' },
  hasApplicable.value && { id: 'applications', label: '适用场景' },
  variantCards.value.length && { id: 'variants', label: '可选规格' },
  hasCompatibility.value && { id: 'compatibility', label: '兼容包材' },
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
  loading.value = true
  notFound.value = false
  detail.value = null
  activeImageIndex.value = 0
  try {
    const slug = route.params.slug
    const data = await fetchProductDetail(slug)
    if (!data) {
      notFound.value = true
    } else {
      detail.value = data
      // 动态 SEO：基于产品名称和型号生成 title 和 description
      const m = data.product ? {
        name: data.product.name,
        modelCode: data.product.publicModel,
        equipmentCategory: data.product.category,
        websiteSummary: data.product.summary,
        introductionCn: data.product.summary,
        defaultImage: null,
      } : (data.model || {})
      const titleParts = [m.name, m.modelCode].filter(Boolean)
      const seoTitle = data.product?.seoTitle || (titleParts.length > 0 ? titleParts.join('｜') : '产品详情')
      const seoDesc = data.product?.seoDescription || (m.introductionCn
        ? m.introductionCn.slice(0, 150)
        : `${m.name || '利强包装设备'}｜${m.equipmentCategory || '食品包装设备'}，支持非标定制与样品试封。`)
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
    }
  } catch (err) {
    console.warn('Product detail unavailable:', err)
    notFound.value = true
  } finally {
    loading.value = false
  }
}

function handleImageError(event) {
  event.target.src = '/images/manual-sealer.jpg'
}

onMounted(load)
watch(() => route.params.slug, load)
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
        <RouterLink to="/products">产品中心</RouterLink>
        <span>/</span>
        <strong>{{ model.name }}</strong>
      </nav>

      <!-- 主体：左图库 + 右信息 -->
      <div class="detail-container">
        <div class="detail-gallery">
          <div class="detail-main-image">
            <img
              v-if="activeImage"
              :src="activeImage.url"
              :srcset="activeImage.srcset"
              sizes="(max-width: 980px) 100vw, 60vw"
              :alt="model.name"
              decoding="async"
              @error="handleImageError"
            />
          </div>
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
          <div class="detail-heading-meta">
            <span>{{ model.productLineName || model.equipmentCategory || '包装设备' }}</span>
            <span v-if="model.modelCode">{{ model.modelCode }}</span>
          </div>
          <h1>{{ model.name }}</h1>

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
            <dl v-if="selectionFacts.length" class="application-facts">
              <div v-for="item in selectionFacts" :key="item.label"><dt>{{ item.label }}</dt><dd>{{ item.value }}</dd></div>
            </dl>
            <p v-if="model.applicableIndustry" class="detail-text-applicable">{{ model.applicableIndustry }}</p>
            <p v-if="model.applicableProduct" class="detail-text-applicable">{{ model.applicableProduct }}</p>
          </div>
        </section>

        <section v-if="variantCards.length" id="variants" class="detail-section">
          <div class="detail-section__heading">
            <span>CONFIGURATIONS</span><h2>可选规格</h2>
            <p>展示最多 6 个代表性规格。不同模具口径、产能和电气配置可能对应不同方案，最终以样品和容器实测为准。</p>
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

        <section v-if="hasCompatibility" id="compatibility" class="detail-section compatibility-section">
          <div class="detail-section__heading">
            <span>COMPATIBILITY</span><h2>兼容包材与包材配套模具</h2>
            <p>产品只关联兼容包材；模具信息由对应包材继续关联获得，不代表产品与模具建立了直接关系。</p>
          </div>
          <div class="detail-section__content compatibility-columns">
            <div v-if="compatiblePackaging.length" class="compatibility-panel">
              <header><div><small>PACKAGING</small><h3>兼容包材类型</h3></div><strong>{{ compatiblePackaging.length }}</strong></header>
              <article v-for="item in compatiblePackaging" :key="`${item.productVariantId}-${item.packagingVariantId}`" class="compatibility-card">
                <div class="compatibility-card__title">
                  <div><small>{{ item.productVariantName || '产品规格' }}</small><h4>{{ item.packagingModelName }}</h4></div>
                  <span v-if="item.customizable">支持定制</span>
                </div>
                <p v-if="item.packagingVariantName">{{ item.packagingVariantName }}</p>
                <ul>
                  <li v-if="item.packagingKind">{{ item.packagingKind }}</li>
                  <li v-if="item.material">{{ item.material }}</li>
                  <li v-if="item.shapeType">{{ item.shapeType }}</li>
                  <li v-if="item.apertureLabel">{{ item.apertureLabel }}</li>
                  <li v-if="item.capacityMl">{{ item.capacityMl }} ml</li>
                </ul>
              </article>
            </div>

            <div v-if="compatibleMolds.length" class="compatibility-panel">
              <header><div><small>PACKAGING TOOLING</small><h3>包材配套模具</h3></div><strong>{{ compatibleMolds.length }}</strong></header>
              <article v-for="item in compatibleMolds" :key="`${item.packagingVariantId}-${item.moldId}`" class="compatibility-card">
                <div class="compatibility-card__title">
                  <div><small>{{ [item.packagingModelName, item.packagingVariantName].filter(Boolean).join(' · ') || '兼容包材' }}</small><h4>{{ item.moldCategory || '配套模具' }}</h4></div>
                  <span>{{ item.defaultMold ? '推荐配置' : moldRelationLabel(item.relationType) }}</span>
                </div>
                <p>该模具关联于上述包材规格，用于说明包材体系的配套能力。</p>
                <ul>
                  <li v-if="item.apertureLabel">{{ item.apertureLabel }}</li>
                  <li v-if="item.cavityCount">{{ item.cavityCount }} 腔</li>
                </ul>
              </article>
            </div>
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
      <SiteCTA
        eyebrow="PRODUCT CONSULTATION"
        :title="`确认 ${model.name || '这款设备'} 是否适合您的产品`"
        description="提供产品、容器尺寸和目标产能，工程师将协助核对设备、模具与包材配置。"
        action-text="咨询该产品"
        inquiry-source="product-detail-bottom"
      />
    </template>
  </main>
</template>
