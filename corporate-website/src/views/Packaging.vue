<script setup>
import { computed, onMounted, ref } from 'vue'
import PageHero from '../components/common/PageHero.vue'
import SectionHeading from '../components/common/SectionHeading.vue'
import SiteCTA from '../components/common/SiteCTA.vue'
import AppIcon from '../components/AppIcon.vue'
import { fetchPackaging, packagingImageUrl } from '../api/website'
import { usePageSections } from '../composables/usePageSections'
import { useCmsPreview } from '../composables/useCmsPreview'
import { useInquiryModal } from '../composables/useInquiryModal'

const { openInquiry } = useInquiryModal()
const pageSections = usePageSections('PACKAGING')
const cmsPreview = useCmsPreview('PACKAGING')
function sectionEnabled(key) {
  const draftEnabled = cmsPreview.draft(key).enabled
  return typeof draftEnabled === 'boolean' ? draftEnabled : pageSections.isEnabled(key)
}
const heroContent = computed(() => {
  const s = (key) => pageSections.section(key)
  const d = (key) => cmsPreview.draft(key)
  return {
    title: d('hero').title || s('hero')?.title || '',
    description: d('hero').subtitle || s('hero')?.subtitle || '从托盒与膜材的实际组合出发，围绕密封、展示、储运和生产节拍选择包装耗材。',
    image: d('hero').imageUrl || s('hero')?.imageUrl || '',
  }
})

const archiveItems = ref([])
const archiveError = ref(false)
const loading = ref(true)
const activeKind = ref('all')

const kindMeta = {
  sealing_film: { title: '封口膜', icon: 'film' },
  container: { title: '包装容器', icon: 'container' },
  bowl: { title: '餐盒与碗', icon: 'container' },
}

const fallbackFamilies = [
  { icon: 'film', title: '封口膜', description: '根据托盒材质、封口方式与储运要求，匹配片膜或卷膜。', detail: '关注热封层、膜材形式、阻隔与开启体验' },
  { icon: 'container', title: '托盒与容器', description: '围绕产品装量、展示方式和温度条件选择容器。', detail: '关注材质、口径、法兰、深度与尺寸公差' },
  { icon: 'consumables', title: '其他配套耗材', description: '根据包装线实际工序配置标签、内衬等配套物料。', detail: '关注设备接口、使用节拍与批次稳定性' },
]

const selectionChecks = [
  { no: '01', title: '提供真实样品', description: '尽量提供量产使用的盒、膜和产品，而不是只提供外形尺寸。' },
  { no: '02', title: '确认包装条件', description: '说明普通封口、气调、真空贴体或耐高温等实际工艺要求。' },
  { no: '03', title: '完成设备试封', description: '在目标速度和装量条件下验证密封、外观与连续运行表现。' },
]

onMounted(async () => {
  pageSections.load()
  try {
    archiveItems.value = await fetchPackaging()
  } catch {
    archiveError.value = true
  } finally {
    loading.value = false
  }
})

const archiveKinds = computed(() => {
  const seen = new Map()
  archiveItems.value.forEach((item) => {
    const key = item.packagingKind || 'other'
    if (!seen.has(key)) seen.set(key, kindMeta[key]?.title || key || '其他耗材')
  })
  return [{ key: 'all', label: '全部类别' }, ...Array.from(seen, ([key, label]) => ({ key, label }))]
})

const filteredArchiveItems = computed(() => activeKind.value === 'all'
  ? archiveItems.value
  : archiveItems.value.filter((item) => (item.packagingKind || 'other') === activeKind.value))
</script>

<template>
  <main class="packaging-page">
    <PageHero
      v-if="sectionEnabled('hero')"
      data-cms-page="PACKAGING"
      data-cms-section="hero"
      eyebrow="PACKAGING MATERIALS"
      :description="heroContent.description"
      :image="heroContent.image"
      :primary-action="{ text: '查看耗材系列', to: '#packaging-catalog' }"
      :secondary-action="{ text: '咨询材料适配', inquiry: { type: 'consult', source: 'packaging-hero' } }"
    >
      <template #title><span v-if="heroContent.title">{{ heroContent.title }}</span><template v-else>包装耗材不是附件，<br /><em>而是封合效果的一部分</em></template></template>
    </PageHero>

    <section class="section packaging-families">
      <SectionHeading
        eyebrow="MATERIAL FAMILIES"
        title="先按包装对象选择"
        description="材料名称只是起点。真正的适配还需要结合产品、容器、设备和流通条件共同判断。"
      />
      <div class="packaging-family-grid">
        <article v-for="family in fallbackFamilies" :key="family.title">
          <div><AppIcon :name="family.icon" :size="30" /></div>
          <span>{{ family.title }}</span>
          <h3>{{ family.description }}</h3>
          <p>{{ family.detail }}</p>
        </article>
      </div>
    </section>

    <section id="packaging-catalog" class="section section--alt packaging-live-catalog">
      <SectionHeading
        eyebrow="MATERIAL CATALOG"
        title="公开耗材系列"
        :description="archiveItems.length ? `当前展示 ${archiveItems.length} 个公开系列，可按类别查看具体材质与规格。` : '耗材档案正在整理，当前先展示主要材料类别与选配逻辑。'"
      />

      <template v-if="archiveItems.length">
        <div class="catalog-tabs" role="tablist" aria-label="包材类型筛选">
          <button v-for="kind in archiveKinds" :key="kind.key" type="button" role="tab" :aria-selected="activeKind === kind.key" :class="{ active: activeKind === kind.key }" @click="activeKind = kind.key">{{ kind.label }}</button>
        </div>
        <div class="packaging-record-grid" aria-live="polite">
          <article v-for="item in filteredArchiveItems" :key="item.id" class="packaging-record-card">
            <div class="packaging-record-card__visual">
              <img v-if="item.defaultImageAssetId" :src="packagingImageUrl(item)" :alt="item.name" loading="lazy" decoding="async" />
              <AppIcon v-else :name="kindMeta[item.packagingKind]?.icon || 'consumables'" :size="40" />
            </div>
            <div class="packaging-record-card__body">
              <small>{{ kindMeta[item.packagingKind]?.title || item.packagingKind || '包装耗材' }}</small>
              <h3>{{ item.name }}</h3>
              <p>{{ [item.material, item.colorStyle, item.capacityMl].filter(Boolean).join(' · ') || '规格可按实际需求匹配' }}</p>
              <div class="record-tags">
                <span v-if="item.shapeType">{{ item.shapeType }}</span>
                <span v-if="item.filmForm">{{ item.filmForm }}</span>
                <span v-if="item.variantCount">{{ item.variantCount }} 个规格</span>
                <span v-if="item.customizable">支持定制</span>
              </div>
              <p v-if="item.applicable" class="record-applicable">适用：{{ item.applicable }}</p>
              <button type="button" class="record-action" @click="openInquiry({ type: 'consult', source: 'packaging', productName: item.name })">咨询材料适配 <span>→</span></button>
            </div>
          </article>
        </div>
      </template>
      <div v-else-if="loading" class="packaging-catalog-state">正在加载耗材档案…</div>
      <div v-else class="packaging-catalog-state">
        <strong>具体系列持续整理中</strong>
        <p>{{ archiveError ? '当前暂时无法读取公开档案。' : '更多材质与规格将在确认后陆续公开。' }}您可以先提交盒、膜或产品信息进行匹配。</p>
      </div>
    </section>

    <section class="section packaging-selection">
      <SectionHeading
        eyebrow="BEFORE ORDERING"
        title="选购前，先完成适配验证"
        description="耗材是否稳定不能只看材料名称，建议通过真实样品和设备试封确认。"
      />
      <div class="packaging-selection-grid">
        <article v-for="check in selectionChecks" :key="check.no">
          <span>{{ check.no }}</span><h3>{{ check.title }}</h3><p>{{ check.description }}</p>
        </article>
      </div>
    </section>

    <SiteCTA
      title="已有盒型或膜材，需要确认能否适配？"
      description="提供材质、尺寸、样品照片、包装产品和设备型号，我们将协助判断下一步验证方式。"
      action-text="咨询材料适配"
      inquiry-source="packaging-bottom"
    />
  </main>
</template>
