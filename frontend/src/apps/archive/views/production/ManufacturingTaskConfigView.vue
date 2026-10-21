<template>
  <div class="task-config-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">MANUFACTURING MASTER</p>
        <h1>任务单产品与配置</h1>
        <p>产品名称与可选配置＝氚云生产任务单对应下拉字典的镜像，<b>本地只读</b>，也不决定它们在氚云能否被选中。点选产品行，右侧显示该产品的历史任务、有效样本与各节点参考间隔；中间的「历史任务单 / 跟单记录」两个数字也可点开看明细，未发布间隔的节点沿用车间基准。</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" :loading="refreshing" @click="reload">刷新</el-button>
      </div>
    </header>

    <el-alert v-if="masterError" type="error" :closable="false" title="主数据加载失败" :description="masterError" />

    <section class="status-bar">
      <div class="stats">
        <span class="stat"><b>{{ coverage?.total ?? '—' }}</b> 张历史任务</span>
        <span class="stat" :class="{ warn: !coverage?.trackedTotal }"><b>{{ coverage?.trackedTotal ?? '—' }}</b> 条跟单记录（样本基数）</span>
        <span class="stat" :class="matchClass"><b>{{ coverage?.productMatched ?? '—' }}</b> 张匹配到产品配置</span>
        <span class="stat"><b>{{ coverage?.benchmarkMatched ?? '—' }}</b> 张命中产品已发布间隔</span>
      </div>
      <p class="note">参考日期口径：按「车间 + 产品名」精确匹配到唯一产品配置，该产品该节点已发布间隔就用它，否则沿用车间基准。节点序列按车间类别（封口机 / 其他机器，按车间名是否含「封口」判定）。与氚云工序报工无关（工序不按时报工）。<b>节点间隔的样本只取「跟单记录」，不取全部历史任务单。</b>产品没有启用/停用状态：氚云在用的产品这边一律参与匹配，避免本地开关让参考工期静默退回车间基准。</p>
    </section>

    <div class="layout">
      <WorkshopSelector :workshops="workshopCards" v-model="selectedWorkshopId" />
      <ManufacturingTaskConfigPanel
        :workshop="currentWorkshop"
        :configs="configsInCurrentWorkshop"
        :selected-task-config-id="selectedTaskConfigId"
        :matrix="matrix"
        :benchmarks="benchmarks"
        @update:selected-task-config-id="selectedTaskConfigId = $event"
        @show-orders="orderDrawerVisible = true"
        @show-samples="openSamplesDrawer"
        @changed="reload"
      />
      <section class="duration-panel">
        <template v-if="selectedProduct">
          <!-- 产品画像：历史任务 / 已建跟单 / 有效样本段，三个数字都是入口（点历史任务看明细，点已建跟单看每段间隔）。 -->
          <ProductProfileCard
            :task-config-id="selectedProduct.id"
            :product-name="selectedProduct.configName"
            :workshop-name="selectedProduct.workshopName"
            :published="publishedCount"
            :stage-total="stageTotal"
            :sample-enabled="canManageBaseline"
            @show-orders="orderDrawerVisible = true"
            @show-samples="openSamplesDrawer"
          />
          <el-alert v-if="!canManageBaseline" type="info" :closable="false" title="无节点参考间隔权限"
            description="维护间隔与查看样本明细需要 manufacturing.baseline.manage 权限；历史任务明细与配置字典仍可查看，未发布的节点沿用车间基准。" />
          <el-alert v-else-if="benchmarkError" type="error" :closable="false" title="节点参考间隔加载失败" :description="benchmarkError" />
          <TrackingProductBenchmarkPanel
            v-else
            ref="benchmarkPanelRef"
            :task-config-id="selectedProduct.id"
            :product-name="selectedProduct.configName"
            :workshop-name="selectedProduct.workshopName"
            :matrix="matrix"
            :rows="benchmarkRows"
            :peers="peerProducts"
            @changed="reload"
          />
        </template>
        <template v-else>
          <header class="duration-head"><h2>节点参考间隔</h2></header>
          <el-alert v-if="!canManageBaseline" type="info" :closable="false" title="无节点参考间隔权限"
            description="维护间隔需要 manufacturing.baseline.manage 权限；产品与可选配置字典仍可查看与补录，删除仅限本地补录行（氚云字典镜像不可删），未发布的节点沿用车间基准。" />
          <el-empty :image-size="80" description="选择中间「任务单产品」表里的产品行，右侧会显示该产品的历史任务、有效样本与节点参考间隔" />
        </template>
      </section>
    </div>

    <ProductOrderHistoryDrawer v-model="orderDrawerVisible" :task-config-id="selectedProduct?.id ?? null"
      :product-name="selectedProduct?.configName ?? ''" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import WorkshopSelector, { type WorkshopCard } from '@/components/production/WorkshopSelector.vue'
import ManufacturingTaskConfigPanel from '@/components/production/ManufacturingTaskConfigPanel.vue'
import TrackingProductBenchmarkPanel from '@/components/production/TrackingProductBenchmarkPanel.vue'
import ProductProfileCard from '@/components/production/ProductProfileCard.vue'
import ProductOrderHistoryDrawer from '@/components/production/ProductOrderHistoryDrawer.vue'
import { fetchTrackingNodeBenchmarks, fetchTrackingProductBenchmarks, type TrackingBenchmarkWorkshop, type TrackingProductBenchmarkRow } from '@/api/productionTracking'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'

/** externalSource='MANUAL' 表示本地补录。没有 active：产品与可选配置都是氚云任务单下拉字典的镜像。 */
interface ConfigRow { id: number; workshopId: number; configName: string; workshopName: string; configType: string; externalSource: string; historicalTaskCount: number; trackedTaskCount: number; sameNameCount: number }
interface WorkshopRow { id: number; workshopName: string; enabled: boolean; defaultProcessTemplateId: number | null }
interface TemplateRow { id: number; templateName: string; active: boolean }
interface Coverage { total: number; trackedTotal: number; productMatched: number; benchmarkMatched: number }

const refreshing = ref(false)
/** 主数据（车间/产品/覆盖率）与基准数据分开加载：基准需要独立权限，失败只降级右栏，不能拖垮整页。 */
const masterError = ref('')
const benchmarkError = ref('')
const { can } = useCurrentUser()
const canManageBaseline = computed(() => can('manufacturing.baseline.manage'))

/** 历史任务明细抽屉：中栏「历史任务单」数字与右栏画像卡打开的是同一个。 */
const orderDrawerVisible = ref(false)
/** 右栏基准面板内的样本抽屉：中栏「跟单记录」数字直接复用，不另写一套明细。 */
const benchmarkPanelRef = ref<InstanceType<typeof TrackingProductBenchmarkPanel> | null>(null)
function openSamplesDrawer() {
  if (!canManageBaseline.value || !benchmarkPanelRef.value) {
    ElMessage.info('查看每段样本明细需要节点参考间隔权限（manufacturing.baseline.manage）')
    return
  }
  benchmarkPanelRef.value.openSamples()
}

const workshops = ref<WorkshopRow[]>([])
const configs = ref<ConfigRow[]>([])
const templates = ref<TemplateRow[]>([])
const matrix = ref<TrackingBenchmarkWorkshop[]>([])
const benchmarks = ref<TrackingProductBenchmarkRow[]>([])
const coverage = ref<Coverage | null>(null)
const selectedWorkshopId = ref<number | null>(null)
const selectedTaskConfigId = ref<number | null>(null)

/** 车间卡片：把 templates 与 products 计数算好传给左侧选择器，避免在子组件里再算一遍。 */
const workshopCards = computed<WorkshopCard[]>(() => workshops.value.map(w => {
  const mine = configs.value.filter(c => c.workshopId === w.id)
  return {
    id: w.id,
    workshopName: w.workshopName,
    enabled: w.enabled,
    defaultProcessTemplateId: w.defaultProcessTemplateId,
    defaultTemplateName: templates.value.find(t => t.id === w.defaultProcessTemplateId)?.templateName ?? null,
    productCount: mine.filter(c => c.configType === 'PRODUCT_NAME').length,
    configCount: mine.filter(c => c.configType !== 'PRODUCT_NAME').length
  }
}))

const currentWorkshop = computed(() => workshopCards.value.find(w => w.id === selectedWorkshopId.value) ?? null)
const configsInCurrentWorkshop = computed(() => configs.value.filter(c => c.workshopId === selectedWorkshopId.value))
const selectedProduct = computed(() => {
  if (selectedTaskConfigId.value == null) return null
  const row = configs.value.find(c => c.id === selectedTaskConfigId.value)
  return row && row.configType === 'PRODUCT_NAME' ? row : null
})
const benchmarkRows = computed(() => benchmarks.value.filter(row => row.taskConfigId === selectedTaskConfigId.value))
/** 按需工序节点集合：已发布数只数必经节点，与分母同源。 */
const optionalStageCodes = computed(() => {
  if (!selectedProduct.value) return new Set<string>()
  // 车间名来自 workshops 关联；workshop_id 为空的历史行会是 null，兜空避免判类别时炸掉
  const kind = (selectedProduct.value.workshopName ?? '').includes('封口') ? 'SEALER' : 'MACHINE'
  return new Set((matrix.value.find(item => item.workshopKind === kind)?.stages ?? [])
    .filter(s => s.optionalStage).map(s => s.stageCode))
})

/** 分母＝必经节点（按需工序不算缺），与产品表「已发布节点」同源。 */
const stageTotal = computed(() => {
  if (!selectedProduct.value) return 0
  // 车间名来自 workshops 关联；workshop_id 为空的历史行会是 null，兜空避免判类别时炸掉
  const kind = (selectedProduct.value.workshopName ?? '').includes('封口') ? 'SEALER' : 'MACHINE'
  const stages = matrix.value.find(item => item.workshopKind === kind)?.stages ?? []
  return stages.filter(s => !s.optionalStage).length
})
const publishedCount = computed(() => benchmarks.value.filter(b =>
  b.taskConfigId === selectedTaskConfigId.value && b.typicalDays != null
  && b.stageCode != null && !optionalStageCodes.value.has(b.stageCode)).length)

/** 「复制间隔」的来源候选：同车间其它产品（后端再校验两者车间类别一致）。不按启用过滤——产品没有启用状态。 */
const peerProducts = computed(() => configs.value
  .filter(c => c.workshopId === selectedWorkshopId.value && c.configType === 'PRODUCT_NAME'
    && c.id !== selectedTaskConfigId.value)
  .map(c => ({ id: c.id, configName: c.configName })))

/** 匹配率是治理信号：低于 100% 说明还有历史任务的产品没建档。 */
const matchClass = computed(() => {
  if (!coverage.value?.total) return ''
  return coverage.value.productMatched < coverage.value.total ? 'warn' : 'ok'
})

/** 默认落到第一条启用产线，避免首屏空白。 */
watch(workshopCards, cards => {
  if (selectedWorkshopId.value == null || !cards.some(c => c.id === selectedWorkshopId.value)) {
    selectedWorkshopId.value = (cards.find(c => c.enabled) ?? cards[0])?.id ?? null
  }
}, { immediate: true })

/** 切产线时把高亮迁到该产线下第一个产品；没产品时清空。产品不分启用/停用，取第一条即可。 */
watch(selectedWorkshopId, () => {
  const products = configs.value.filter(c => c.workshopId === selectedWorkshopId.value && c.configType === 'PRODUCT_NAME')
  if (products.some(p => p.id === selectedTaskConfigId.value)) return
  selectedTaskConfigId.value = products[0]?.id ?? null
})

async function reload() {
  refreshing.value = true
  try { await Promise.all([loadMaster(), loadBenchmarks()]) } finally { refreshing.value = false }
}

/** 主数据：车间 / 产品 / 制程 / 覆盖率。失败要显式提示，不能留白屏让用户以为没数据。 */
async function loadMaster() {
  masterError.value = ''
  try {
    const [configRes, wsRes, tplRes, covRes] = await Promise.all([
      http.get<ConfigRow[]>('/manufacturing/master/task-configs'),
      http.get<WorkshopRow[]>('/manufacturing/master/workshops'),
      http.get<TemplateRow[]>('/manufacturing/master/templates'),
      http.get<Coverage>('/manufacturing/master/duration-coverage')
    ])
    configs.value = configRes.data
    workshops.value = wsRes.data
    templates.value = tplRes.data.filter(t => t.active)
    coverage.value = covRes.data
  } catch (e: any) {
    masterError.value = e?.message || '主数据加载失败'
  }
}

/** 基准数据：需要 manufacturing.baseline.manage；无权限或请求失败都只降级右栏，主流程照常可用。 */
async function loadBenchmarks() {
  benchmarkError.value = ''
  matrix.value = []
  benchmarks.value = []
  if (!canManageBaseline.value) return
  try {
    const [matrixRes, benchRes] = await Promise.all([fetchTrackingNodeBenchmarks(), fetchTrackingProductBenchmarks()])
    matrix.value = matrixRes.workshops
    benchmarks.value = benchRes
  } catch (e: any) {
    benchmarkError.value = e?.message || '节点参考间隔加载失败'
  }
}

onMounted(reload)
</script>

<style scoped>
.task-config-page { display: grid; gap: var(--space-lg-xl, 16px); }

.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.page-head .eyebrow { margin: 0 0 4px; font-size: 12px; color: var(--color-text-secondary, #94a3b8); letter-spacing: .08em; }
.page-head h1 { margin: 0 0 6px; font-size: 22px; color: var(--color-text-heading, #0f172a); }
.page-head p { margin: 0; color: var(--color-text-secondary, #5f6f82); max-width: 720px; }

.status-bar { background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-2xl, 12px); padding: 12px 16px; display: grid; gap: 6px; box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.04)); }
.stats { display: flex; gap: 24px; flex-wrap: wrap; }
.stat { font-size: 13px; color: var(--color-text-secondary, #5f6f82); }
.stat b { font-size: 18px; color: var(--color-text-heading, #0f172a); margin-right: 2px; }
.stat.warn b { color: var(--color-warning, #d97706); }
.stat.ok b { color: var(--color-success, #059669); }
.note { margin: 0; font-size: 12px; color: var(--color-text-secondary, #5f6f82); line-height: 1.6; }

/* 三列布局：产线固定窄列，中间与右侧按比例弹性分配——
   右列不设固定 420px，否则宽屏下中间列独占剩余宽度，产品表被拉得稀疏而间隔表被挤窄。 */
.layout { display: grid; grid-template-columns: 210px minmax(560px, 0.82fr) minmax(460px, 1fr); gap: var(--space-lg-xl, 16px); align-items: start; }

.duration-panel { position: sticky; top: 16px; display: grid; gap: 12px; min-width: 0; }
.duration-head { padding: 16px 20px; background: linear-gradient(135deg, var(--color-primary-bg-light, #f0fdfa) 0%, var(--color-surface-raised, #fbfcfd) 100%); border: 1px solid var(--color-primary-border, #a7f3d0); border-radius: var(--radius-2xl, 12px); }
.duration-head h2 { margin: 0 0 6px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 20px; color: var(--color-text-heading, #0f172a); }
.duration-head p { margin: 0; font-size: 12px; color: var(--color-text-secondary, #5f6f82); }

@media (max-width: 1500px) {
  .layout { grid-template-columns: 210px minmax(0, 1fr); }
  .duration-panel { grid-column: 1 / -1; position: static; }
}
@media (max-width: 900px) {
  .layout { grid-template-columns: 1fr; }
}
</style>
