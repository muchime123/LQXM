<script setup>
/**
 * 关于我们页面。
 * - Hero：品牌主张
 * - 公司简介：从接口拉取 mainBusiness + advantages + 核心定位
 * - 企业历程：里程碑时间线
 * - 工商信息：从接口拉取工商字段
 * - CTA：跳转到资质页和联系页
 */
import { ref, reactive, onMounted, onBeforeUnmount, nextTick, computed, watchEffect } from 'vue'
import { fetchHome } from '../api/website'
import { certifications, patents } from '../data/qualifications'
import { useCompany } from '../composables/useCompany'
import { usePageSections } from '../composables/usePageSections'
import { useCmsPreview } from '../composables/useCmsPreview'
import { useOfficialMedia } from '../composables/useOfficialMedia'
import { useCopy } from '../composables/useCopy'
import { useAMap } from '../composables/useAMap'
import { setSchema, createLocalBusinessSchema } from '../composables/useSchema'
import AppIcon from '../components/AppIcon.vue'
import PageHero from '../components/common/PageHero.vue'
import SectionHeading from '../components/common/SectionHeading.vue'
import SiteCTA from '../components/common/SiteCTA.vue'

const loading = ref(true)
const error = ref(null)
const { company } = useCompany()
const { findByPlatform } = useOfficialMedia()
const { copyText } = useCopy()

/* 关于页区块标题/描述：优先编辑器内存草稿，其次后台「页面内容」配置。
   使用 usePageSections 提供的 sectionRows / isEnabled 统一实现。 */
const pageSections = usePageSections('ABOUT')
const cmsPreview = useCmsPreview('ABOUT')
const sectionEnabled = (key) => pageSections.isEnabled(key)
const sectionRows = (key) => pageSections.sectionRows(key)
const aboutHeadings = computed(() => {
  const s = (key) => pageSections.section(key)
  const d = (key) => cmsPreview.draft(key)
  return {
    hero: {
      title: d('hero').title || s('hero')?.title || '',
      description: d('hero').subtitle || s('hero')?.subtitle || '了解利强的企业定位、制造能力、发展历程，以及公开展示的资质认证与知识产权成果。',
      image: d('hero').imageUrl || s('hero')?.imageUrl || '',
    },
    intro: {
      title: d('intro').title || s('intro')?.title || '从包装设备制造出发，<br />把模具与耗材一起考虑',
      description: d('intro').subtitle || s('intro')?.subtitle || '从包装设备制造出发，把模具与耗材一起考虑',
      image: d('intro').imageUrl || s('intro')?.imageUrl || '/images/factory.webp',
    },
    manufacturing: {
      title: d('manufacturing').title || s('manufacturing')?.title || '厂房设备',
      description: d('manufacturing').subtitle || s('manufacturing')?.subtitle || '通过生产、装配与调试现场，展示设备从制造到交付验证的主要环节。',
      image: d('manufacturing').imageUrl || s('manufacturing')?.imageUrl || '/images/workshop.webp',
    },
    credentials: {
      title: d('credentials').title || s('credentials')?.title || '企业荣誉与资质认证',
      description: d('credentials').subtitle || s('credentials')?.subtitle || '',
    },
    patents: {
      title: d('patents').title || s('patents')?.title || '专利与知识产权',
      description: d('patents').subtitle || s('patents')?.subtitle || '',
    },
    contact: {
      title: d('contact').title || s('contact')?.title || '联系利强',
      description: d('contact').subtitle || s('contact')?.subtitle || '您可以直接电话、邮件或通过官方账号联系我们；方案和样品需求请使用全站咨询弹窗提交。',
    },
  }
})

/* Intro 辅图（结构化内容，支持编辑器中编辑） */
const introImageError = ref(false)
const introSecondary = computed(() => {
  const rows = sectionRows('intro')
  if (!rows.length) return { image: '/images/workshop.webp', caption: '温州 · 制造现场', hasData: false }
  const row = rows[0]
  const image = row.image || '/images/workshop.webp'
  return {
    image,
    caption: row.caption || '温州 · 制造现场',
    hasData: !!(row.image || row.caption),
  }
})

function onIntroImageError() {
  introImageError.value = true
}

const showIntroSecondary = computed(() => {
  if (introImageError.value) return false
  return !!introSecondary.value.image
})

/* Manufacturing 图廊（结构化内容，支持编辑器中编辑） */
const galleryImageErrors = reactive(new Set())
const manufacturingGallery = computed(() => {
  const rows = sectionRows('manufacturing')
  if (!rows.length) {
    return [
      { image: '/images/factory.webp', caption: '生产与装配区域' },
      { image: '/images/workshop.webp', caption: '设备制造现场' },
      { image: '/images/manual-sealer.jpg', caption: '装机调试与验证' },
    ]
  }
  return rows.map((row) => ({
    image: row.image || '/images/workshop.webp',
    caption: row.caption || '制造现场',
  }))
})

function onGalleryImageError(idx) {
  galleryImageErrors.add(idx)
}

const visibleManufacturingGallery = computed(() => {
  return manufacturingGallery.value.filter((_, idx) => !galleryImageErrors.has(idx))
})

const milestones = ref([])
const productCount = ref(0)
/* 资质与知识产权为官网自有展示内容，见 src/data/qualifications.js */
const certificationCount = computed(() => certifications.length)
const patentCount = computed(() => patents.length)
const certificationCarousel = ref(null)
const patentCarousel = ref(null)

function scrollShowcase(target, direction) {
  target.value?.scrollBy({ left: direction * target.value.clientWidth * 0.82, behavior: 'smooth' })
}

const contactPhone = computed(() => company.value?.phone || '4009008878')
const contactPhoneText = computed(() => contactPhone.value.replace(/(\d{4})(\d{3})(\d{4})/, '$1 $2 $3'))
const contactEmail = computed(() => company.value?.email || '768963166@qq.com')
const contactAddress = computed(() => company.value?.factoryAddress || company.value?.officeAddress || company.value?.registeredAddress || '浙江省温州市瓯海区新桥街道新桥创新路85号')
const douyinAccount = computed(() => findByPlatform('抖音'))
const contactMapLink = computed(() => `https://www.amap.com/search?query=${encodeURIComponent(`${companyName.value} ${contactAddress.value}`)}`)

const { load: loadAMap, geocode, placeSearch, hasKey: hasAMapKey } = useAMap()
const contactMapRef = ref(null)
const contactMapLoading = ref(false)
const contactMapError = ref(false)
let contactMapInstance = null
let contactMapResizeObserver = null
let contactMapWindowResize = null
let contactMapResizeTimers = []

async function initContactMap() {
  contactMapLoading.value = true
  if (!hasAMapKey) {
    contactMapError.value = true
    contactMapLoading.value = false
    return
  }

  try {
    const AMap = await loadAMap()
    await nextTick()
    if (!contactMapRef.value) return

    let location
    try {
      location = await placeSearch(companyName.value, '温州')
    } catch {
      location = await geocode(`${companyName.value} ${contactAddress.value}`)
    }

    const center = [location.lng, location.lat]
    contactMapInstance = new AMap.Map(contactMapRef.value, {
      center,
      zoom: 16,
      viewMode: '2D',
      animateEnable: false,
      resizeEnable: true,
    })
    contactMapInstance.add(new AMap.Marker({ position: center, anchor: 'bottom-center' }))

    const keepCentered = () => {
      contactMapInstance?.resize()
      contactMapInstance?.setZoomAndCenter(16, center)
    }
    contactMapInstance.on('complete', keepCentered)
    contactMapResizeTimers = [80, 350, 900].map((delay) => window.setTimeout(keepCentered, delay))
    contactMapWindowResize = keepCentered
    window.addEventListener('resize', contactMapWindowResize)
    if (typeof ResizeObserver !== 'undefined') {
      contactMapResizeObserver = new ResizeObserver(() => requestAnimationFrame(keepCentered))
      contactMapResizeObserver.observe(contactMapRef.value)
    }
  } catch (err) {
    console.warn('About contact map unavailable:', err)
    contactMapError.value = true
  } finally {
    contactMapLoading.value = false
  }
}

/* About 页本地注入 LocalBusiness（与全局 Organization 合并为 @graph） */
watchEffect(() => {
  if (company.value) {
    const lb = createLocalBusinessSchema({
      ...company.value,
      logo: company.value.logoFileName ? `/images/${company.value.logoFileName}` : null,
    })
    if (lb) setSchema(lb)
  }
})

async function load() {
  loading.value = true
  error.value = null
  try {
    /* 一次 fetchHome 获取里程碑 + 产品数量。
       资质与知识产权取自官网本地数据，company 由 useCompany 单例提供（App.vue 已加载）。 */
    const data = await fetchHome()
    milestones.value = data.milestones || []
    productCount.value = data.productCount || 0
  } catch (err) {
    error.value = err
    console.warn('About page data unavailable:', err)
  } finally {
    loading.value = false
  }
}

const companyName = computed(() => company.value?.companyName || '浙江利强包装科技有限公司')
const companyNameEn = computed(() => company.value?.companyNameEn || 'ZHEJIANG LIQIANG PACKAGING TECHNOLOGY')
const mainBusiness = computed(() => company.value?.mainBusiness || '封口机、气调包装机、真空包装机、热收缩设备、包装容器、封口膜、模具及配件')
const advantages = computed(() => company.value?.advantages || '设备 + 耗材 + 模具一体化；支持定制；响应快；可提供样品测试与方案建议')
const establishmentDate = computed(() => {
  const d = company.value?.establishmentDate
  if (!d) return '2013'
  const date = new Date(d)
  return isNaN(date) ? d : date.getFullYear()
})
const yearsOfExperience = computed(() => {
  const y = parseInt(establishmentDate.value)
  if (isNaN(y)) return '10+'
  return new Date().getFullYear() - y + '年'
})

const legalRep = computed(() => company.value?.legalRepresentative || '于士强')
const registeredCapital = computed(() => company.value?.registeredCapital || '1000万元人民币')
const socialCode = computed(() => company.value?.unifiedSocialCode || '9133030207622289XP')
const enterpriseScale = computed(() => company.value?.enterpriseScale || '')
const factoryAddress = computed(() =>
  company.value?.factoryAddress || company.value?.registeredAddress || company.value?.officeAddress ||
  '浙江省温州市瓯海区新桥街道新桥创新路85号'
)
const website = computed(() => company.value?.website || 'http://www.liqiangbz.com/')

/* 优势解析（用分号/顿号/逗号切分） */
const advantageList = computed(() => {
  const raw = advantages.value
  if (!raw) return []
  return raw.split(/[；;、，,]+/).filter(s => s.trim())
})

/* 制造实力解析（JSON格式） */
const manufacturingStrengthList = computed(() => {
  const raw = company.value?.manufacturingStrengths
  if (!raw) return []
  try {
    const list = JSON.parse(raw)
    if (!Array.isArray(list)) return []
    return list.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
  } catch {
    return []
  }
})

/* 主营业务解析 */
const businessList = computed(() => {
  const raw = mainBusiness.value
  if (!raw) return []
  return raw.split(/[、，,；;]+/).filter(s => s.trim())
})

/* 统计数据 */
const stats = computed(() => [
  { label: '行业经验', value: yearsOfExperience.value },
  { label: '产品系列', value: productCount.value ? `${productCount.value}+` : '10+' },
  { label: '客户覆盖', value: '全国' },
  { label: '资质认证', value: certificationCount.value ? `${certificationCount.value}+` : '27+' },
])

/* 若里程碑为空，提供默认展示数据（可在管理后台 Company > 里程碑维护后替换） */
const displayMilestones = computed(() => {
  if (milestones.value.length > 0) {
    return [...milestones.value].sort((a, b) => (a.year || 0) - (b.year || 0))
  }
  return [
    { year: 2013, title: '品牌创立', description: '利强包装正式注册，专注食品包装设备研发与制造。' },
    { year: 2018, title: '产品升级', description: '推出台式气调保鲜包装系列，切入锁鲜包装赛道。' },
    { year: 2021, title: '产线拓展', description: '部署自动化产线与非标定制能力，覆盖大中型客户。' },
    { year: 2024, title: '服务升级', description: '建立客户服务体系，支持样品试封与长期技术服务。' },
  ]
})

onMounted(() => {
  load()
  pageSections.load()
  initContactMap()
})

onBeforeUnmount(() => {
  if (contactMapWindowResize) window.removeEventListener('resize', contactMapWindowResize)
  contactMapWindowResize = null
  contactMapResizeTimers.forEach((timer) => window.clearTimeout(timer))
  contactMapResizeTimers = []
  contactMapResizeObserver?.disconnect()
  contactMapResizeObserver = null
  contactMapInstance?.destroy()
  contactMapInstance = null
})
</script>

<template>
  <main class="about-page">
    <!-- Hero -->
    <PageHero
      v-if="sectionEnabled('hero')"
      data-cms-page="ABOUT"
      data-cms-section="hero"
      eyebrow="ABOUT LIQIANG"
      :description="aboutHeadings.hero.description"
      :image="aboutHeadings.hero.image"
      :primary-action="{ text: '获取包装方案', inquiry: { type: 'consult', source: 'about-hero' } }"
      two-col
    >
      <template #title>
        <span v-if="aboutHeadings.hero.title">{{ aboutHeadings.hero.title }}</span>
        <template v-else>扎根包装制造，<br /><em>围绕真实生产需求持续积累</em></template>
      </template>
      <template #side>
        <div class="hero-stats">
          <div v-for="s in stats" :key="s.label" class="stat-item">
            <span class="stat-value">{{ typeof s.value === 'string' ? s.value : s.value }}</span>
            <span class="stat-label">{{ s.label }}</span>
          </div>
        </div>
      </template>
    </PageHero>

    <!-- 公司简介 + 主营业务 -->
    <section v-if="sectionEnabled('intro')" class="section about-intro" data-cms-page="ABOUT" data-cms-section="intro">
      <div class="about-profile">
        <div class="about-profile__copy reveal" v-reveal>
          <p class="eyebrow dark"><span></span> WHO WE ARE</p>
          <h2>{{ aboutHeadings.intro.title }}</h2>
          <p class="intro-desc">{{ aboutHeadings.intro.description }}</p>
          <div class="about-profile__business">
            <span>主营业务</span>
            <div><b v-for="biz in businessList" :key="biz">{{ biz }}</b></div>
          </div>
          <div class="about-profile__facts">
            <div><strong>{{ establishmentDate }}</strong><span>成立年份</span></div>
            <div><strong>{{ productCount || '—' }}</strong><span>公开设备型号</span></div>
            <div><strong>{{ patentCount || '—' }}</strong><span>公开知识产权</span></div>
          </div>
        </div>
        <div class="about-profile__visual reveal" v-reveal>
          <figure class="about-profile__image-main"><img :src="aboutHeadings.intro.image" alt="利强工厂环境" loading="lazy" decoding="async" /></figure>
          <figure v-if="showIntroSecondary" class="about-profile__image-secondary"><img :src="introSecondary.image" alt="利强设备生产现场" loading="lazy" decoding="async" @error="onIntroImageError" /><figcaption>{{ introSecondary.caption }}</figcaption></figure>
        </div>
      </div>
    </section>

    <!-- 制造实力（首页 #manufacturing 锚点跳转目标） -->
    <section v-if="sectionEnabled('manufacturing')" class="section about-manufacturing" id="manufacturing" data-cms-page="ABOUT" data-cms-section="manufacturing">
      <SectionHeading
        eyebrow="MANUFACTURING"
        :title="aboutHeadings.manufacturing.title"
        :description="aboutHeadings.manufacturing.description"
      />
      <div class="manufacturing-grid">
        <div class="manufacturing-image reveal" v-reveal>
          <img :src="aboutHeadings.manufacturing.image" alt="利强包装生产车间" loading="lazy" decoding="async" />
          <div class="image-label">
            <span>MADE IN WENZHOU</span>
            <strong>制造，从细节开始</strong>
          </div>
        </div>
        <div class="manufacturing-list reveal" v-reveal>
          <template v-if="manufacturingStrengthList.length">
            <div class="mfg-item" v-for="(item, idx) in manufacturingStrengthList" :key="idx">
              <b>{{ String(idx + 1).padStart(2, '0') }}</b>
              <div>
                <strong>{{ item.title }}</strong>
                <p>{{ item.description }}</p>
              </div>
            </div>
          </template>
          <template v-else>
            <div class="mfg-item">
              <b>01</b>
              <div>
                <strong>源头研发制造</strong>
                <p>核心设备自主研发与制造，从设计到装配全流程可控。</p>
              </div>
            </div>
            <div class="mfg-item">
              <b>02</b>
              <div>
                <strong>非标定制能力</strong>
                <p>根据客户容器、产能与产线布局进行定制化设计与调试。</p>
              </div>
            </div>
            <div class="mfg-item">
              <b>03</b>
              <div>
                <strong>样品试封服务</strong>
                <p>提供样品测试与方案验证，确保设备适配实际生产需求。</p>
              </div>
            </div>
            <div class="mfg-item">
              <b>04</b>
              <div>
                <strong>长期技术服务</strong>
                <p>从交付培训到持续维护，保障设备长期稳定运行。</p>
              </div>
            </div>
          </template>
        </div>
      </div>
      <div class="workshop-gallery" aria-label="厂房与制造设备">
        <figure v-for="(item, idx) in visibleManufacturingGallery" :key="idx"><img :src="item.image" :alt="item.caption" loading="lazy" decoding="async" @error="onGalleryImageError(idx)" /><figcaption>{{ item.caption }}</figcaption></figure>
      </div>
    </section>

    <!-- 企业历程 / 里程碑 -->
    <section class="section about-milestones">
      <SectionHeading
        eyebrow="OUR JOURNEY"
        title="企业历程"
        :description="milestones.length ? '以下为利强发展历程中的关键节点。' : '企业发展历程持续整理中。'"
        theme="light"
      />
      <div class="milestones-timeline">
        <article
          v-for="(m, idx) in displayMilestones"
          :key="m.year + m.title"
          class="milestone-item reveal"
          v-reveal
        >
          <div class="milestone-year">
            <span>{{ m.year }}</span>
          </div>
          <div class="milestone-node"></div>
          <div class="milestone-content">
            <h3>{{ m.title }}</h3>
            <p>{{ m.description }}</p>
          </div>
        </article>
      </div>
    </section>

    <!-- 企业资质（首页 TrustBar 与 /credentials 路由的锚点目标） -->
    <section v-if="sectionEnabled('credentials')" class="section about-credentials section--alt" id="credentials" data-cms-page="ABOUT" data-cms-section="credentials" data-cms-source="static">
      <SectionHeading
        eyebrow="CERTIFICATIONS"
        :title="aboutHeadings.credentials.title"
        :description="aboutHeadings.credentials.description || (certifications.length ? '集中展示利强公开的质量体系、产品认证与相关资质证明。' : '资质信息持续整理中，具体文件可联系我们获取。')"
      />
      <div class="about-showcase-controls" aria-label="资质轮播控制">
        <button type="button" aria-label="上一组资质" @click="scrollShowcase(certificationCarousel, -1)">←</button>
        <button type="button" aria-label="下一组资质" @click="scrollShowcase(certificationCarousel, 1)">→</button>
      </div>
      <!-- 资质证书轮播 -->
      <div ref="certificationCarousel" class="credentials-preview about-showcase-track">
        <template v-if="certifications.length">
          <article
            v-for="cert in certifications.slice(0, 6)"
            :key="cert.id || cert.name"
            class="cert-thumb reveal"
            v-reveal
            :class="{ 'has-image': cert.image }"
          >
            <div v-if="cert.image" class="cert-thumb__image">
              <img
                :src="cert.image"
                :alt="cert.name"
                loading="lazy"
                decoding="async"
                @error="(e) => (e.target.style.display = 'none')"
              />
            </div>
            <div v-else class="cert-thumb__badge">
              <AppIcon name="shield" :size="24" />
            </div>
            <div class="cert-thumb__caption">
              <span class="cert-thumb__cat">{{ cert.category || '企业资质' }}</span>
              <strong class="cert-thumb__name">{{ cert.name }}</strong>
            </div>
          </article>
        </template>
        <template v-else>
          <div class="cert-thumb placeholder-thumb" v-for="n in 6" :key="n">
            <div class="cert-thumb__badge">
              <AppIcon name="shield" :size="22" />
            </div>
            <div class="cert-thumb__caption">
              <span class="cert-thumb__cat">ISO · CE · 检测报告</span>
              <strong class="cert-thumb__name">管理体系与产品合规认证</strong>
            </div>
          </div>
        </template>
      </div>
    </section>

    <!-- 专利与知识产权 -->
    <section v-if="sectionEnabled('patents')" class="section about-patents" id="patents" data-cms-page="ABOUT" data-cms-section="patents" data-cms-source="static">
      <SectionHeading
        eyebrow="PATENTS &amp; IP"
        :title="aboutHeadings.patents.title"
        :description="aboutHeadings.patents.description || (patents.length ? `当前共公开 ${patentCount} 项知识产权成果，以下展示部分代表性证书。` : '专利信息持续整理中，具体资料可联系我们获取。')"
      />
      <div class="about-showcase-controls" aria-label="专利轮播控制">
        <button type="button" aria-label="上一组专利" @click="scrollShowcase(patentCarousel, -1)">←</button>
        <button type="button" aria-label="下一组专利" @click="scrollShowcase(patentCarousel, 1)">→</button>
      </div>
      <!-- 专利证书轮播 -->
      <div ref="patentCarousel" class="patents-preview about-showcase-track">
        <template v-if="patents.length">
          <article
            v-for="p in patents.slice(0, 6)"
            :key="p.id"
            class="patent-thumb reveal"
            v-reveal
          >
            <div v-if="p.image" class="patent-thumb__image">
              <img :src="p.image" :alt="p.name" loading="lazy" decoding="async" @error="(e) => (e.target.style.display = 'none')" />
            </div>
            <div class="patent-thumb__head">
              <span class="patent-thumb__cat">{{ p.assetCategory || '知识产权' }}</span>
              <span v-if="p.patentType" class="patent-thumb__type">{{ p.patentType }}</span>
            </div>
            <strong class="patent-thumb__name">{{ p.name }}</strong>
            <div class="patent-thumb__meta">
              <span v-if="p.patentNo">{{ p.patentNo }}</span>
              <span v-else-if="p.applicant">{{ p.applicant }}</span>
            </div>
          </article>
        </template>
        <template v-else>
          <div class="patents-empty">
            <strong>暂无公开展示的知识产权档案</strong>
            <span>专利信息持续整理中，完整知识产权清单请直接联系我们获取。</span>
          </div>
        </template>
      </div>
    </section>

    <!-- 联系信息整合：咨询表单由全站弹窗承担，此处只保留长期有效的企业联系方式与位置。 -->
    <section v-if="sectionEnabled('contact')" id="contact" class="section about-contact section--alt" data-cms-page="ABOUT" data-cms-section="contact" data-cms-source="company">
      <SectionHeading
        eyebrow="CONTACT LIQIANG"
        :title="aboutHeadings.contact.title"
        :description="aboutHeadings.contact.description"
      />
      <div class="about-contact-layout">
        <div class="about-contact-grid">
          <article>
          <AppIcon name="phone" :size="22" />
          <span>咨询电话</span>
          <strong>{{ contactPhoneText }}</strong>
          <small>工作日 08:30–17:30</small>
          <button type="button" @click="copyText(contactPhone)">复制电话 →</button>
          </article>
          <article>
          <AppIcon name="mail" :size="22" />
          <span>商务邮箱</span>
          <strong>{{ contactEmail }}</strong>
          <small>用于商务合作与资料往来</small>
          <button type="button" @click="copyText(contactEmail)">复制邮箱 →</button>
          </article>
          <article>
          <AppIcon name="location" :size="22" />
          <span>公司地址</span>
          <strong>{{ contactAddress }}</strong>
          <small>{{ companyName }}</small>
          <a :href="contactMapLink" target="_blank" rel="noopener noreferrer">查看路线 →</a>
          </article>
          <article>
          <AppIcon name="douyin" :size="22" />
          <span>官方抖音</span>
          <strong>{{ douyinAccount?.accountName || '利强科技' }}</strong>
          <small>产品动态、设备演示与行业资讯</small>
          <a v-if="douyinAccount?.link" :href="douyinAccount.link" target="_blank" rel="noopener noreferrer">前往抖音 →</a>
          </article>
        </div>
        <div class="about-contact-map">
          <div class="about-contact-map__viewport">
            <div ref="contactMapRef" class="about-contact-map__surface"></div>
            <div v-if="contactMapLoading" class="about-contact-map__status">地图加载中…</div>
            <a
              v-else-if="contactMapError"
              class="about-contact-map__fallback"
              :href="contactMapLink"
              target="_blank"
              rel="noopener noreferrer"
            >
              <AppIcon name="location" :size="25" />
              <strong>查看公司位置</strong>
              <span>在高德地图中打开路线 →</span>
            </a>
          </div>
          <div class="about-contact-map__caption">
            <span>{{ contactAddress }}</span>
            <a :href="contactMapLink" target="_blank" rel="noopener noreferrer">在高德地图中查看 →</a>
          </div>
        </div>
      </div>
    </section>

    <!-- 底部统一 CTA -->
    <SiteCTA id="cta" />
  </main>
</template>
