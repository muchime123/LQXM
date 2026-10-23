<template>
  <div class="task-config-page">
    <header class="page-head">
      <h1>生产参考工期</h1>
      <div class="head-actions">
        <el-popover placement="bottom-end" :width="520" trigger="click">
          <template #reference><el-button text size="small">口径说明</el-button></template>
          <p class="caliber-note">参考日期按「车间 + 产品名」唯一匹配；产品已发布的节点参考天数优先，未发布时沿用通用参考。节点按封口机与其他机器两类计算，与氚云工序报工无关。</p>
          <p class="caliber-note">样本只取跟单记录。产品参考工期为排产至入库各必经节点的生效天数逐段取整后相加；实测 P50 只取有排产锚点且走完的跟单，申请日兜底的单不计入。</p>
          <p class="caliber-note">产品和可选配置来自氚云任务单字典，本地只读；产品没有启用状态。点击统计数字可查看车间及产品分布。</p>
        </el-popover>
        <!-- 通用节点参考工期（原生产跟单页的「基准设置」）迁到这里：产品参考优先、通用参考兜底，
             两个入口放在同一页，避免「判据散落在两个页面」。 -->
        <el-button v-if="canManageBaseline" @click="baselineVisible = true">通用参考设置</el-button>
        <el-button :icon="Refresh" :loading="refreshing" @click="reload">刷新</el-button>
      </div>
    </header>

    <el-alert v-if="masterError" type="error" :closable="false" title="主数据加载失败" :description="masterError" />

    <section class="status-bar" aria-label="任务与产品匹配概览">
      <div class="stats">
        <!-- 四个数字都是入口：点开「按车间+产品」的分布，再点行直接跳到该产品（右栏节点间隔）。 -->
        <button type="button" class="stat" title="按车间与产品展开全部历史任务（含未建档产品）" @click="openCoverage('all')">
          <b>{{ coverage?.total ?? '—' }}</b> 张历史任务
        </button>
        <button type="button" class="stat" :class="{ warn: !coverage?.trackedTotal }" title="只看已建跟单记录的产品——节点间隔的样本只来自这些单" @click="openCoverage('tracked')">
          <b>{{ coverage?.trackedTotal ?? '—' }}</b> 条跟单记录
        </button>
        <button type="button" class="stat" :class="matchClass" title="只看唯一匹配到产品配置的（重名与未建档会退回通用参考）" @click="openCoverage('matched')">
          <b>{{ coverage?.productMatched ?? '—' }}</b> 张匹配产品
        </button>
        <button type="button" class="stat" title="只看该产品已有发布节点间隔的" @click="openCoverage('published')">
          <b>{{ coverage?.benchmarkMatched ?? '—' }}</b> 张命中产品参考
        </button>
      </div>
    </section>

    <div class="layout">
      <WorkshopSelector :workshops="workshopCards" v-model="selectedWorkshopId" />
      <ManufacturingTaskConfigPanel
        :workshop="currentWorkshop"
        :configs="configsInCurrentWorkshop"
        :selected-task-config-id="selectedTaskConfigId"
        :matrix="matrix"
        :benchmarks="benchmarks"
        :durations="productDurations"
        :can-manage-baseline="canManageBaseline"
        :batch-working="batchWorking"
        :batch-progress="batchProgress"
        @update:selected-task-config-id="selectedTaskConfigId = $event"
        @show-orders="openOrders"
        @show-samples="openSamples"
        @batch-calculate="batchCalculateAndFill"
        @changed="reload"
      />
      <section class="duration-panel">
        <template v-if="selectedProduct">
          <el-alert v-if="!canManageBaseline" type="info" :closable="false" title="无产品参考工期权限"
            description="维护产品参考天数与查看样本明细需要 manufacturing.baseline.manage 权限；历史任务明细与配置字典仍可查看，未发布的节点沿用通用参考。" />
          <el-alert v-else-if="benchmarkError" type="error" :closable="false" title="产品节点参考工期加载失败" :description="benchmarkError" />
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
            @open-order="openTrackingOrder"
          />
        </template>
        <template v-else>
          <el-alert v-if="!canManageBaseline" type="info" :closable="false" title="无产品参考工期权限"
            description="维护产品参考天数需要 manufacturing.baseline.manage 权限；产品与可选配置字典仍可查看与补录，删除仅限本地补录行（氚云字典镜像不可删），未发布的节点沿用通用参考。" />
          <el-empty :image-size="80" description="选择中间「任务单产品」表里的产品行，右侧会显示该产品的历史任务、有效样本与产品节点参考工期" />
        </template>
      </section>
    </div>

    <!-- 车间基础数据：2026-10-23 随制造主数据清理从「制程与工序管理」页迁入（该页已下线）。
         车间保留，默认制程等制程关联概念已移除。 -->
    <section class="workshop-section"><ManufacturingWorkshopPanel /></section>

    <ProductOrderHistoryDrawer v-model="orderDrawerVisible" :task-config-id="selectedProduct?.id ?? null"
      :product-name="selectedProduct?.configName ?? ''" @open-order="openTrackingOrder" />
    <CoverageBreakdownDrawer v-model="coverageDrawerVisible" :initial-filter="coverageFilter" @pick="pickCoverageRow" />
    <!-- 通用参考：产品级参考的兜底值。保存后重载矩阵，右栏「通用参考」列与产品表「已发布节点」当场跟着变。 -->
    <TrackingGeneralBaselineDialog v-model="baselineVisible" @saved="onGeneralBaselineSaved" />
    <!-- 跟单详情：与生产跟单页同一个抽屉组件，在本页打开而不跳转。保存后刷新本页统计（节点进度会影响已发布/覆盖率）。 -->
    <TrackingDetailDrawer v-model="trackingDetailVisible" :order-id="trackingOrderId" @saved="reload" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import WorkshopSelector, { type WorkshopCard } from '@/components/production/WorkshopSelector.vue'
import ManufacturingWorkshopPanel from '@/components/production/ManufacturingWorkshopPanel.vue'
import ManufacturingTaskConfigPanel from '@/components/production/ManufacturingTaskConfigPanel.vue'
import TrackingProductBenchmarkPanel from '@/components/production/TrackingProductBenchmarkPanel.vue'
import ProductOrderHistoryDrawer from '@/components/production/ProductOrderHistoryDrawer.vue'
import CoverageBreakdownDrawer from '@/components/production/CoverageBreakdownDrawer.vue'
import TrackingGeneralBaselineDialog from '@/components/production/TrackingGeneralBaselineDialog.vue'
import TrackingDetailDrawer from '@/components/production/TrackingDetailDrawer.vue'
import { applyTrackingProductBenchmarkP50, fetchProductDuration, fetchTrackingNodeBenchmarks, fetchTrackingProductBenchmarks, refreshTrackingProductBenchmarkCandidates, type ProductDuration, type TrackingBenchmarkWorkshop, type TrackingProductBenchmarkRow } from '@/api/productionTracking'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'

/** externalSource='MANUAL' 表示本地补录。没有 active：产品与可选配置都是氚云任务单下拉字典的镜像。 */
interface ConfigRow { id: number; workshopId: number; configName: string; workshopName: string; configType: string; externalSource: string; historicalTaskCount: number; trackedTaskCount: number; sameNameCount: number }
interface WorkshopRow { id: number; workshopName: string; enabled: boolean }
interface Coverage { total: number; trackedTotal: number; productMatched: number; benchmarkMatched: number }

const refreshing = ref(false)
/** 主数据（车间/产品/覆盖率）与基准数据分开加载：基准需要独立权限，失败只降级右栏，不能拖垮整页。 */
const masterError = ref('')
const benchmarkError = ref('')
const { can } = useCurrentUser()
const canManageBaseline = computed(() => can('manufacturing.baseline.manage'))
/** 通用参考设置弹窗（原生产跟单页「基准设置」）。 */
const baselineVisible = ref(false)
/** 通用参考保存后：矩阵（右栏「通用参考」列）与已发布节点数都要重算，直接整页重载最省心。 */
async function onGeneralBaselineSaved() { await loadBenchmarks(); await loadProductDurations() }

/**
 * 下钻到底：**在本页打开**该单的跟单详情抽屉（节点进度 / 报工 / 时间核实 / 跟单历史）。
 * 抽屉与生产跟单页共用同一个组件（TrackingDetailDrawer），所以看到的就是跟单页那套内容，
 * 但不需要跳走——在这里看完关掉，仍然停在原产品与原行上。
 */
const trackingDetailVisible = ref(false)
const trackingOrderId = ref<number | null>(null)
function openTrackingOrder(orderId: number) {
  orderDrawerVisible.value = false
  trackingOrderId.value = orderId
  trackingDetailVisible.value = true
}

/** 历史任务明细抽屉：中栏「历史任务单」数字与右栏画像卡打开的是同一个。 */
const orderDrawerVisible = ref(false)
/** 右栏基准面板内的样本抽屉：中栏「跟单记录」数字直接复用，不另写一套明细。 */
const benchmarkPanelRef = ref<InstanceType<typeof TrackingProductBenchmarkPanel> | null>(null)

/**
 * 中栏数字必须落到「被点的那一行」：这两个按钮都 stopPropagation，不会触发 row-click 选中，
 * 所以要先手动把选中项改成该行再开抽屉——否则点 B 行看到的是 A 行的数据，且不报任何错（2026-10-22 修）。
 */
function openOrders(row?: { id: number }) {
  if (row) selectedTaskConfigId.value = row.id
  orderDrawerVisible.value = true
}

/** 样本抽屉挂在右栏面板里（数据是「当前产品」的候选段），要等新的 taskConfigId 传进子组件后再开。 */
async function openSamples(row?: { id: number }) {
  if (row) selectedTaskConfigId.value = row.id
  if (!canManageBaseline.value) {
    ElMessage.info('查看每段样本明细需要产品参考工期权限（manufacturing.baseline.manage）')
    return
  }
  await nextTick()
  benchmarkPanelRef.value?.openSamples()
}

/* ---------------- 页头统计 → 覆盖率明细（按车间 + 产品） ---------------- */

type CoverageFilter = 'all' | 'tracked' | 'matched' | 'gap' | 'published'
const coverageDrawerVisible = ref(false)
const coverageFilter = ref<CoverageFilter>('all')
function openCoverage(filter: CoverageFilter) { coverageFilter.value = filter; coverageDrawerVisible.value = true }

/** 明细行 → 切到该车间与该产品，右栏随即显示它的节点间隔（下钻的最后一跳）。 */
function pickCoverageRow(row: { workshopName: string | null; configId: number | null }) {
  coverageDrawerVisible.value = false
  const target = workshops.value.find(w => w.workshopName === row.workshopName)
  if (target) selectedWorkshopId.value = target.id
  if (row.configId != null) selectedTaskConfigId.value = row.configId
}

const workshops = ref<WorkshopRow[]>([])
const configs = ref<ConfigRow[]>([])
const matrix = ref<TrackingBenchmarkWorkshop[]>([])
const benchmarks = ref<TrackingProductBenchmarkRow[]>([])
const productDurations = ref<Record<number, ProductDuration>>({})
const coverage = ref<Coverage | null>(null)
const selectedWorkshopId = ref<number | null>(null)
const selectedTaskConfigId = ref<number | null>(null)
const batchWorking = ref(false)
const batchProgress = ref('')

/** 当前车间批量重算候选并发布合格 P50；每产品独立处理，失败不阻断其他产品。 */
async function batchCalculateAndFill() {
  if (batchWorking.value || !canManageBaseline.value || !currentWorkshop.value) return
  const rows = configs.value.filter(c => c.workshopId === selectedWorkshopId.value && c.configType === 'PRODUCT_NAME')
  const targets = rows.filter(c => c.sameNameCount === 1 && c.trackedTaskCount > 0)
  if (!targets.length) { ElMessage.info('当前车间没有可计算的唯一匹配产品跟单记录'); return }
  try {
    await ElMessageBox.confirm(
      `将计算「${currentWorkshop.value.workshopName}」下 ${targets.length} 个产品的历史跟单样本，并把达标节点的 P50 发布为参考天数。重名产品、无跟单产品跳过；已发布节点不覆盖。发布后会立即影响跟单参考日期。`,
      '批量计算并填充', { type: 'warning', confirmButtonText: '开始处理', cancelButtonText: '取消' }
    )
  } catch { return }
  batchWorking.value = true
  let calculated = 0, publishedProducts = 0, publishedStages = 0
  const failed: string[] = []
  try {
    for (const [index, row] of targets.entries()) {
      batchProgress.value = `${index + 1}/${targets.length}`
      try {
        await refreshTrackingProductBenchmarkCandidates(row.id)
        calculated++
        const published = await applyTrackingProductBenchmarkP50(row.id)
        if (published.length) { publishedProducts++; publishedStages += published.length }
      } catch { failed.push(row.configName) }
    }
  } finally {
    batchWorking.value = false
    batchProgress.value = ''
    await reload()
  }
  const summary = `已计算 ${calculated} 个产品，发布 ${publishedProducts} 个产品的 ${publishedStages} 个节点；跳过 ${rows.length - targets.length} 个重名或无跟单产品`
  if (failed.length) ElMessage.warning(`${summary}；失败 ${failed.length} 个：${failed.join('、')}`)
  else ElMessage.success(summary)
}

/** 左侧车间导航：封口机置顶、称重机置底，其余保留原有顺序。 */
function workshopCardRank(name: string) {
  if (name === '封口机车间') return -1
  if (name === '称重机车间') return 1
  return 0
}
const workshopCards = computed<WorkshopCard[]>(() => workshops.value.map(w => {
  const mine = configs.value.filter(c => c.workshopId === w.id)
  return {
    id: w.id,
    workshopName: w.workshopName,
    enabled: w.enabled,
    productCount: mine.filter(c => c.configType === 'PRODUCT_NAME').length,
    configCount: mine.filter(c => c.configType !== 'PRODUCT_NAME').length
  }
}).sort((a, b) => workshopCardRank(a.workshopName) - workshopCardRank(b.workshopName)))

const currentWorkshop = computed(() => workshopCards.value.find(w => w.id === selectedWorkshopId.value) ?? null)
const configsInCurrentWorkshop = computed(() => configs.value.filter(c => c.workshopId === selectedWorkshopId.value))
const selectedProduct = computed(() => {
  if (selectedTaskConfigId.value == null) return null
  const row = configs.value.find(c => c.id === selectedTaskConfigId.value)
  return row && row.configType === 'PRODUCT_NAME' ? row : null
})
const benchmarkRows = computed(() => benchmarks.value.filter(row => row.taskConfigId === selectedTaskConfigId.value))
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
  void loadProductDurations()
  if (products.some(p => p.id === selectedTaskConfigId.value)) return
  selectedTaskConfigId.value = products[0]?.id ?? null
})

/** 列表内显示工期和每节点生效参考值；控制并发，避免一个车间的产品同时打满接口。 */
let durationLoadVersion = 0
async function loadProductDurations() {
  const version = ++durationLoadVersion
  const ids = configs.value.filter(c => c.workshopId === selectedWorkshopId.value && c.configType === 'PRODUCT_NAME').map(c => c.id)
  productDurations.value = {}
  let cursor = 0
  async function worker() {
    while (cursor < ids.length && version === durationLoadVersion) {
      const id = ids[cursor++]
      try {
        const value = await fetchProductDuration(id)
        if (version === durationLoadVersion) productDurations.value = { ...productDurations.value, [id]: value }
      } catch { /* 单个产品失败时保留其他行数据 */ }
    }
  }
  await Promise.all(Array.from({ length: Math.min(4, ids.length) }, () => worker()))
}

async function reload() {
  refreshing.value = true
  try { await Promise.all([loadMaster(), loadBenchmarks()]); await loadProductDurations() } finally { refreshing.value = false }
}

/** 主数据：车间 / 产品 / 覆盖率。失败要显式提示，不能留白屏让用户以为没数据。 */
async function loadMaster() {
  masterError.value = ''
  try {
    const [configRes, wsRes, covRes] = await Promise.all([
      http.get<ConfigRow[]>('/manufacturing/master/task-configs'),
      http.get<WorkshopRow[]>('/manufacturing/master/workshops'),
      http.get<Coverage>('/manufacturing/master/duration-coverage')
    ])
    configs.value = configRes.data
    workshops.value = wsRes.data
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
    benchmarkError.value = e?.message || '产品节点参考工期加载失败'
  }
}

onMounted(reload)
</script>

<style scoped>
.task-config-page { display: grid; gap: 10px; }

.page-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 36px; }
.page-head h1 { margin: 0; font-size: 20px; color: var(--color-text-heading, #0f172a); }
.head-actions { display: flex; align-items: center; gap: 6px; }
.caliber-note { margin: 0 0 8px; font-size: 12px; line-height: 1.6; }
.caliber-note:last-child { margin-bottom: 0; }

.status-bar { background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-2xl, 12px); padding: 7px 12px; box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.04)); }
.stats { display: flex; gap: 20px; flex-wrap: wrap; align-items: center; }
.stat { font-size: 13px; color: var(--color-text-secondary, #5f6f82); }
/* 统计数字是入口：做成按钮但不带按钮外观，只在悬停时暗示可点。 */
button.stat { font: inherit; font-size: 13px; padding: 0; border: 0; background: none; cursor: pointer; color: var(--color-text-secondary, #5f6f82); }
button.stat:hover { color: var(--color-primary, #0f766e); text-decoration: underline; text-underline-offset: 3px; }
button.stat:hover b { color: var(--color-primary, #0f766e); }
.stat b { font-size: 15px; color: var(--color-text-heading, #0f172a); margin-right: 2px; }
.stat.warn b { color: var(--color-warning, #d97706); }
.stat.ok b { color: var(--color-success, #059669); }

.layout { display: grid; grid-template-columns: 210px minmax(0, 1fr); gap: var(--space-lg-xl, 16px); align-items: start; }
.layout > :nth-child(2), .duration-panel { grid-column: 2; }

.duration-panel { position: sticky; top: 16px; display: grid; gap: 12px; min-width: 0; }

.duration-panel { position: static; }
.workshop-section { background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-2xl, 12px); padding: 12px; box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.04)); }
@media (max-width: 900px) {
  .layout { grid-template-columns: 1fr; }
  .layout > :nth-child(2), .duration-panel { grid-column: 1; }
}
</style>
