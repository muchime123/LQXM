<script setup>
import { computed, onMounted, ref } from 'vue'
import PageHero from '../components/common/PageHero.vue'
import SectionHeading from '../components/common/SectionHeading.vue'
import SiteCTA from '../components/common/SiteCTA.vue'
import { fetchMolds } from '../api/website'
import { usePageSections } from '../composables/usePageSections'
import { useCmsPreview } from '../composables/useCmsPreview'
import { useInquiryModal } from '../composables/useInquiryModal'

const { openInquiry } = useInquiryModal()
const pageSections = usePageSections('MOLDS')
const cmsPreview = useCmsPreview('MOLDS')
function sectionEnabled(key) {
  const draftEnabled = cmsPreview.draft(key).enabled
  return typeof draftEnabled === 'boolean' ? draftEnabled : pageSections.isEnabled(key)
}
const heroContent = computed(() => {
  const s = (key) => pageSections.section(key)
  const d = (key) => cmsPreview.draft(key)
  return {
    title: d('hero').title || s('hero')?.title || '',
    description: d('hero').subtitle || s('hero')?.subtitle || '模具连接设备、容器与膜材。我们根据盒型、设备和封切要求完成标准配置或定制开发。',
    image: d('hero').imageUrl || s('hero')?.imageUrl || '',
  }
})

const activeCategory = ref('all')
const archiveMolds = ref([])
const archiveError = ref(false)
const loading = ref(true)

const baseCategories = [
  { key: 'all', label: '全部规格' },
  { key: 'square', label: '方盒模具' },
  { key: 'round', label: '圆杯模具' },
  { key: 'bowl', label: '碗类模具' },
  { key: 'bucket', label: '桶类模具' },
  { key: 'lid', label: '盖类模具' },
]

const categoryMeta = {
  square: { label: '方盒', application: '餐盒、预制菜盒与方形托盒' },
  round: { label: '圆杯', application: '杯装、汤品与圆形容器' },
  bowl: { label: '碗类', application: '餐碗、蒸煮碗与即食容器' },
  bucket: { label: '桶类', application: '大容量食品与桶装产品' },
  lid: { label: '盖类', application: '杯盖、盒盖与配套封盖' },
  other: { label: '其他', application: '其他容器与非标结构' },
}

const fallbackMolds = [
  { code: 'M-SQUARE', category: 'square', shape: '方盒模具', size: '按盒型匹配', cavities: '模腔数可选', desc: '适配常见方形餐盒与托盒，具体结构需结合容器样品确认。' },
  { code: 'M-ROUND', category: 'round', shape: '圆杯模具', size: '按口径匹配', cavities: '模腔数可选', desc: '适配杯、罐等圆形容器，支持按设备工作区域评估配置。' },
  { code: 'M-BOWL', category: 'bowl', shape: '碗类模具', size: '按碗口匹配', cavities: '模腔数可选', desc: '适配餐碗与即食容器，重点核对碗口、法兰与高度。' },
  { code: 'M-CUSTOM', category: 'other', shape: '非标定制模具', size: '按样品设计', cavities: '按节拍评估', desc: '针对异形容器或特殊封切要求，完成样品测绘与结构确认。' },
]

const workflow = [
  { no: '01', title: '样品与需求确认', description: '提供容器、膜材、产品信息、设备型号和目标生产节拍。' },
  { no: '02', title: '结构与适配评估', description: '核对容器尺寸、公差、封口区域、模腔数量和换模条件。' },
  { no: '03', title: '设计与加工', description: '确认结构方案后进入制造，并记录对应设备与容器信息。' },
  { no: '04', title: '装机试模', description: '通过实际盒膜和生产条件验证封合、切膜与连续运行状态。' },
]

function categoryKey(value) {
  if (!value) return 'other'
  if (value.includes('方') || value.includes('矩形')) return 'square'
  if (value.includes('圆杯') || value.includes('杯')) return 'round'
  if (value.includes('碗')) return 'bowl'
  if (value.includes('桶')) return 'bucket'
  if (value.includes('盖')) return 'lid'
  return 'other'
}

onMounted(async () => {
  pageSections.load()
  try {
    archiveMolds.value = await fetchMolds()
  } catch {
    archiveError.value = true
  } finally {
    loading.value = false
  }
})

const normalizedMolds = computed(() => archiveMolds.value.length
  ? archiveMolds.value.map((item) => ({
      code: item.moldCode,
      category: categoryKey(item.moldCategory),
      shape: item.moldCategory || item.modelName || '模具',
      size: [item.representativeCaliber, item.representativeHeight].filter(Boolean).join(' × ') || '规格详询',
      cavities: item.cavityCount ? `一出${item.cavityCount}` : (item.compartmentCount || '模腔详询'),
      desc: [item.groupName, item.surfaceStructure].filter(Boolean).join(' · ') || `${item.modelName || '容器'}配套模具`,
    }))
  : fallbackMolds)

const categories = computed(() => {
  const available = new Set(normalizedMolds.value.map((item) => item.category))
  const visible = baseCategories.filter((item) => item.key === 'all' || available.has(item.key))
  if (available.has('other')) visible.push({ key: 'other', label: '其他模具' })
  return visible.map((item) => ({
    ...item,
    count: item.key === 'all' ? normalizedMolds.value.length : normalizedMolds.value.filter((mold) => mold.category === item.key).length,
  }))
})

const filteredMolds = computed(() => activeCategory.value === 'all'
  ? normalizedMolds.value
  : normalizedMolds.value.filter((mold) => mold.category === activeCategory.value))
</script>

<template>
  <main class="molds-page">
    <PageHero
      v-if="sectionEnabled('hero')"
      data-cms-page="MOLDS"
      data-cms-section="hero"
      eyebrow="MOLD & TOOLING"
      :description="heroContent.description"
      :image="heroContent.image"
      :primary-action="{ text: '查看模具规格', to: '#mold-catalog' }"
      :secondary-action="{ text: '提交容器样品', inquiry: { type: 'sample', source: 'molds-hero' } }"
    >
      <template #title><span v-if="heroContent.title">{{ heroContent.title }}</span><template v-else>从容器样品出发，<br /><em>完成模具与设备适配</em></template></template>
    </PageHero>

    <section class="section mold-capability">
      <div class="mold-capability__image"><img src="/images/workshop.webp" alt="利强制造现场" loading="lazy" decoding="async" /></div>
      <div class="mold-capability__copy">
        <p class="eyebrow dark"><span></span> TOOLING CAPABILITY</p>
        <h2>模具不是孤立产品</h2>
        <p>模具结构需要同时适配设备工作区域、容器外形与公差、膜材形式、封切方式和生产节拍。正式制造前，应先完成样品与使用条件确认。</p>
        <dl>
          <div><dt>适配对象</dt><dd>设备型号 · 容器 · 膜材</dd></div>
          <div><dt>配置方式</dt><dd>常用规格 · 非标定制</dd></div>
          <div><dt>验证环节</dt><dd>装机 · 试封 · 连续运行</dd></div>
        </dl>
      </div>
    </section>

    <section id="mold-catalog" class="section section--alt mold-catalog-section">
      <SectionHeading
        eyebrow="MOLD CATALOG"
        title="常用模具与公开档案"
        :description="archiveMolds.length ? `当前展示 ${archiveMolds.length} 套公开模具档案，可按容器形式筛选。` : '当前展示常用模具类型示例，具体档案与适配关系持续整理中。'"
      />
      <div class="mold-tabs" role="tablist" aria-label="模具分类筛选">
        <button v-for="cat in categories" :key="cat.key" type="button" class="mold-tab" :class="{ active: activeCategory === cat.key }" role="tab" :aria-selected="activeCategory === cat.key" @click="activeCategory = cat.key">{{ cat.label }} <span>{{ cat.count }}</span></button>
      </div>
      <div v-if="loading" class="mold-catalog-state">正在加载模具档案…</div>
      <div v-else class="mold-editorial-grid">
        <article v-for="mold in filteredMolds" :key="mold.code">
          <div class="mold-editorial-card__head">
            <span>{{ (categoryMeta[mold.category] || categoryMeta.other).label }}</span>
            <small>{{ mold.code }}</small>
          </div>
          <h3>{{ mold.shape }}</h3>
          <p>{{ mold.desc }}</p>
          <dl><div><dt>参考规格</dt><dd>{{ mold.size }}</dd></div><div><dt>模腔配置</dt><dd>{{ mold.cavities }}</dd></div></dl>
          <small>适用：{{ (categoryMeta[mold.category] || categoryMeta.other).application }}</small>
          <button type="button" class="mold-card-inquiry" @click="openInquiry({ type: 'consult', source: 'molds', productName: mold.code })">咨询适配 <b>→</b></button>
        </article>
      </div>
      <p v-if="archiveError" class="catalog-source-note">当前无法读取公开档案，页面展示的是模具类型示例，不代表现货库存。</p>
    </section>

    <section class="section mold-workflow">
      <SectionHeading
        eyebrow="CUSTOM TOOLING"
        title="从样品确认到装机试模"
        description="定制模具应以真实容器、实际膜材和对应设备为依据，按阶段确认，减少制造后的适配偏差。"
      />
      <div class="mold-workflow-grid">
        <article v-for="item in workflow" :key="item.no"><span>{{ item.no }}</span><h3>{{ item.title }}</h3><p>{{ item.description }}</p></article>
      </div>
    </section>

    <SiteCTA
      title="有容器样品，需要确认模具方案？"
      description="提供容器样品或尺寸图、设备型号、膜材形式与目标节拍，我们将协助完成初步适配判断。"
      action-text="提交模具需求"
      inquiry-source="molds-bottom"
    />
  </main>
</template>
