<template>
  <div v-loading="loading" class="node-body">
    <el-alert v-if="displayError" :title="displayError" type="error" :closable="false" style="margin-bottom:14px" />
    <div class="node-layout">
      <section class="node-overview">
        <div class="overview-block primary">
          <span class="overview-label">覆盖任务</span>
          <div class="overview-value">{{ data?.meta.covered ?? '—' }}<em>单</em></div>
          <small>跟单覆盖、有节点记录</small>
        </div>
        <div class="overview-block accent">
          <span class="overview-label">排产等待 3 天及以上</span>
          <button class="overview-value" type="button" @click="drillWaitRange('排产等待 3 天及以上', 3)">{{ waitThreePlus ?? '—' }}<em>单</em> ↗</button>
          <!-- 这是「提交→排产」的历史等待分布，绝大多数是早就完工的单：不写明会被当成当前待办数。 -->
          <small>提交→排产 ≥3 天（含已完工任务，不是当前待办数）</small>
        </div>
        <p class="overview-hint">统计范围：当前跟单覆盖的全部任务（含已完工），不按周期筛选 —— 节点完成率是「已记录节点的完成比例」，不代表在制进度。点左栏图表（节点条形 / 排产等待柱）下钻任务清单，点任务行打开跟单详情</p>
      </section>

      <div class="node-charts">
        <template v-for="(g, i) in byWorkshop" :key="g.workshop">
          <section class="node-section">
            <div class="section-head"><h3>{{ g.workshop }} · 节点完成率 · 样本 {{ g.sample }} 单</h3><span class="node-tip">点击节点查看任务</span></div>
            <div class="chart" :ref="(el:any) => setChartEl('node' + i, el as HTMLElement)" aria-label="节点完成率" />
          </section>
        </template>
        <section class="node-section">
          <div class="section-head"><h3>排产等待分布（提交→排产，天）</h3><span class="node-tip">点击柱形查看任务</span></div>
          <!-- 有排产时间才进分布：缺失排产时间的单被静默排除，不写会让「覆盖任务」数与分布基数对不上。 -->
          <p class="section-note">分布基数 {{ waitCovered }} 单（覆盖任务 {{ data?.meta.covered ?? '—' }} 单）<template v-if="waitMissing">，另有 {{ waitMissing }} 单无排产时间未计入</template>。</p>
          <div ref="waitEl" class="chart" :style="{ height: `${waitChartHeight}px` }" aria-label="排产等待分布" />
        </section>
      </div>

      <aside class="node-drill">
        <template v-if="ddActive && drillState">
          <TrackingAnalyticsOrdersTable :items="ddItems" :total="ddTotal" :page="ddPage" :size="ddSize"
            :loading="ddLoading" :title="`${drillState.label} · 对应任务 ${ddTotal} 单`"
            @update:page="onPageChange" @open-order="id => emit('open-order', id)" @close="closeDrilldown">
            <template #toolbar>
              <div v-if="drillState.type === 'node'" class="status-filter">
                <span>该节点状态：</span>
                <el-button v-for="s in statusOptions" :key="s.value" size="small"
                  :type="nodeStatus === s.value ? 'primary' : 'default'" @click="setNodeStatus(s.value)">{{ s.label }}</el-button>
              </div>
            </template>
            <template #extra>
              <!-- 交期两列放在最前：清单里先要能看出哪单已经超期，提交/排产属于过程信息，靠后。 -->
              <el-table-column prop="expectedDate" label="当前预计" width="115" />
              <el-table-column label="当前超期" width="110">
                <template #default="{ row }">{{ row.openOverdueDays == null ? '—' : `已超 ${row.openOverdueDays} 天` }}</template>
              </el-table-column>
              <el-table-column prop="submittedDate" label="提交" width="132" show-overflow-tooltip />
              <el-table-column prop="scheduleTime" label="排产" width="132" show-overflow-tooltip />
              <el-table-column prop="waitDays" label="等待(天)" width="95" align="center" />
              <el-table-column v-if="drillState.type === 'node'" prop="stageStatus" label="该节点" width="105">
                <template #default="{ row }">{{ row.stageStatus === 'DONE' ? '已完成' : '未完成' }}</template>
              </el-table-column>
            </template>
          </TrackingAnalyticsOrdersTable>
        </template>
        <TrackingDrillEmpty v-else text="点击左栏图表（节点条形 / 排产等待柱）" subtext="在右侧查看对应任务清单" />
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import type { ECharts } from 'echarts'
import {
  fetchTrackingNodeAnalytics, fetchTrackingNodeAnalyticsOrders,
  type TrackingNodeAnalytics, type TrackingNodeAnalyticsOrder, type TrackingNodeCompletionItem
} from '@/api/productionTracking'
import TrackingAnalyticsOrdersTable from './TrackingAnalyticsOrdersTable.vue'
import TrackingDrillEmpty from './TrackingDrillEmpty.vue'
import { useTrackingDrilldown } from '@/composables/tracking/useTrackingDrilldown'

const emit = defineEmits<{ 'open-order': [id: number] }>()

const data = ref<TrackingNodeAnalytics | null>(null)
const loading = ref(false), error = ref('')

const waitEl = ref<HTMLElement | null>(null)
const chartEls = new Map<string, HTMLElement | null>()
const charts = new Map<string, ECharts>()
let echartsPromise: Promise<typeof import('echarts')> | null = null
let resizeObserver: ResizeObserver | null = null

/**
 * 按车间分组，并给出该车间的样本量（＝该车间节点里最大的 involved）。
 * 排序改为**按样本量降序**：1 单的车间与 625 单的车间此前占同样宽的图块，
 * 而只有 1 单的车间的完成率全是 100%，容易被当成同样有代表性的结论。
 */
const byWorkshop = computed(() => {
  const groups: { workshop: string; items: TrackingNodeCompletionItem[]; sample: number }[] = []
  for (const it of data.value?.nodeCompletion ?? []) {
    let g = groups.find(x => x.workshop === it.workshop)
    if (!g) { g = { workshop: it.workshop, items: [], sample: 0 }; groups.push(g) }
    g.items.push(it)
    g.sample = Math.max(g.sample, it.involved)
  }
  return groups.sort((a, b) => b.sample - a.sample || a.workshop.localeCompare(b.workshop, 'zh-CN'))
})

/** 排产等待聚合桶：0–2 天逐天列出，3 天及以上合并为一组（避免冗长的稀疏柱）。 */
const waitBuckets = computed(() => {
  const wait = data.value?.scheduleWait ?? []
  const normal = wait.filter(x => x.day < 3).map(x => ({ day: x.day, count: x.count, isOver: false }))
  const over = wait.filter(x => x.day >= 3).reduce((s, x) => s + x.count, 0)
  return over > 0 ? [...normal, { day: 999, count: over, isOver: true }] : normal
})

const waitChartHeight = computed(() => Math.max(200, waitBuckets.value.length * 30 + 50))

/**
 * 排产等待 3 天及以上的单数（由逐天分布累加）。
 * 口径与分布图的合并柱（day >= 3）、下钻条件（waitDaysMin=3）保持一致，避免两处数字对不上。
 */
const waitThreePlus = computed(() => {
  const wait = data.value?.scheduleWait ?? []
  return wait.filter(x => x.day >= 3).reduce((s, x) => s + x.count, 0)
})

/**
 * 分布基数：只有「提交时间与排产时间都不为空」的单才进分布，被排除的单此前无任何提示，
 * 于是「覆盖任务 N 单」与分布各柱之和对不上，容易被当成漏统计。
 */
const waitCovered = computed(() => (data.value?.scheduleWait ?? []).reduce((s, x) => s + x.count, 0))
const waitMissing = computed(() => Math.max(0, (data.value?.meta.covered ?? 0) - waitCovered.value))

type Drilldown =
  | { type: 'node'; workshop: string; stage: string; stageName: string; label: string }
  | { type: 'wait'; waitDays: number; label: string }
  | { type: 'waitMin'; waitDaysMin: number; label: string }
const drillState = ref<Drilldown | null>(null)
const { active: ddActive, items: ddItems, total: ddTotal, page: ddPage, size: ddSize,
  loading: ddLoading, error: ddError, open, close, load: drillLoad, scrollIntoView } = useTrackingDrilldown<TrackingNodeAnalyticsOrder>()
const displayError = computed(() => error.value || ddError.value)
const nodeStatus = ref<'ALL' | 'DONE' | 'OPEN'>('ALL')
// 'OPEN' 只是「未完成」的语义标签，不是里程碑里的真实状态值：后端按 <> 'DONE' 判定
// （实际状态为 DONE / PENDING / IN_PROGRESS），所以这里不能再按 status === 'OPEN' 去渲染。
const statusOptions = [
  { value: 'ALL', label: '全部' }, { value: 'DONE', label: '已完成' }, { value: 'OPEN', label: '未完成' }
] as const

/** 下钻请求：读取当前 drillState 与 nodeStatus，分页共用同一份 loader。 */
function drillLoader(page: number, size: number) {
  const d = drillState.value
  const params: { workshop?: string; stage?: string; status?: 'ALL' | 'DONE' | 'OPEN'; waitDays?: number; waitDaysMin?: number; page: number; size: number } = { page, size }
  if (!d) return Promise.resolve({ items: [] as TrackingNodeAnalyticsOrder[], total: 0 })
  if (d.type === 'node') { params.workshop = d.workshop; params.stage = d.stage; params.status = nodeStatus.value }
  else if (d.type === 'wait') { params.waitDays = d.waitDays }
  else { params.waitDaysMin = d.waitDaysMin }
  return fetchTrackingNodeAnalyticsOrders(params)
}
function setNodeStatus(value: 'ALL' | 'DONE' | 'OPEN') { nodeStatus.value = value; ddPage.value = 1; void drillLoad(drillLoader) }
function onPageChange(p: number) { ddPage.value = p; void drillLoad(drillLoader) }
function closeDrilldown() { drillState.value = null; close() }
/** 抽屉关闭时清空下钻：下次打开不残留上一次的节点筛选与清单（含节点状态筛选回到「全部」）。 */
function clearDrilldown() { drillState.value = null; nodeStatus.value = 'ALL'; close() }
/** 按等待天数下限下钻（用于分布图合并组 / 概览卡）。 */
function drillWaitRange(label: string, minDays: number) {
  drillState.value = { type: 'waitMin', waitDaysMin: minDays, label }; open(); void drillLoad(drillLoader); void scrollIntoView()
}
function drillWait(day: number) {
  drillState.value = { type: 'wait', waitDays: day, label: `排产等待 ${day} 天` }; open(); void drillLoad(drillLoader); void scrollIntoView()
}
function drillNode(workshop: string, stage: string, stageName: string) {
  drillState.value = { type: 'node', workshop, stage, stageName, label: `${workshop} · ${stageName}` }
  nodeStatus.value = 'ALL'; open(); void drillLoad(drillLoader); void scrollIntoView()
}

function setChartEl(key: string, el: HTMLElement | null) { chartEls.set(key, el) }
function loadEcharts() { echartsPromise ||= import('echarts'); return echartsPromise }

async function load() {
  loading.value = true; error.value = ''
  try {
    data.value = await fetchTrackingNodeAnalytics()
    await renderAll()
    // 刷新后若右侧已展开下钻，必须按新数据重查一次：否则左图是新的、右表还是上一次的结果且不刷新。
    if (drillState.value) await drillLoad(drillLoader)
  } catch (e) { error.value = e instanceof Error ? e.message : '分析加载失败' }
  finally { loading.value = false }
}

async function renderAll() {
  const echarts = await loadEcharts()
  await nextTick()
  byWorkshop.value.forEach((g, i) => {
    const el = chartEls.get('node' + i)
    if (!el || !el.isConnected) return
    charts.get('node' + i)?.dispose()
    const chart = echarts.init(el)
    chart.setOption(nodeOption(g))
    chart.on('click', (p: any) => { const d = p.data; if (d && d.stage) drillNode(d.workshop, d.stage, d.stageName) })
    charts.set('node' + i, chart)
  })
  const wel = waitEl.value
  if (wel && wel.isConnected) {
    charts.get('wait')?.dispose()
    const chart = echarts.init(wel)
    chart.setOption(waitOption())
    chart.on('click', (p: any) => { const d = p.data; if (!d) return; if (d.isOver) drillWaitRange('排产等待 3 天及以上', 3); else if (Number.isFinite(d.day)) drillWait(d.day) })
    charts.set('wait', chart)
  }
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver?.disconnect()
    resizeObserver = new ResizeObserver(() => charts.forEach(c => c.resize()))
    chartEls.forEach(el => { if (el) resizeObserver!.observe(el) })
    if (waitEl.value) resizeObserver.observe(waitEl.value)
  }
}

/** 青绿渐变柱（与项目主色 #127b71 一致）；>3 天用暖橙警示渐变。 */
const tealGradient = { type: 'linear', x: 0, y: 0, x2: 1, y2: 0, colorStops: [{ offset: 0, color: '#3cc3b5' }, { offset: 1, color: '#127b71' }] }
const warnGradient = { type: 'linear', x: 0, y: 0, x2: 1, y2: 0, colorStops: [{ offset: 0, color: '#e99a68' }, { offset: 1, color: '#c45620' }] }

function nodeOption(g: { workshop: string; items: TrackingNodeCompletionItem[]; sample: number }) {
  const items = g.items
  return {
    tooltip: { trigger: 'item', triggerOn: 'mousemove|click', renderMode: 'richText', confine: true,
      formatter: function (p: any) {
        // 按需节点（如「氧化/喷特氟龙」）只有部分任务涉及，involved 远小于车间样本量：
        // 完成率看着最低，但柱长只编码百分比，不写清楚会被误读成「最差的环节」。
        const partial = p.data.involved < p.data.sample
          ? '<br/>该节点仅 ' + p.data.involved + ' 单涉及（车间样本 ' + p.data.sample + ' 单），不覆盖全部任务'
          : ''
        return p.name + ': 已完成 ' + p.data.done + ' / 涉及 ' + p.data.involved + ' 单 · ' + p.data.rate + '%'
          + '<br/>含历史已完工单，不代表在制进度' + partial
      } },
    grid: { left: 46, right: 84, top: 8, bottom: 6, containLabel: true },
    xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%', color: '#94a3b8' }, axisLine: { show: false }, axisTick: { show: false }, splitLine: { lineStyle: { color: '#eef2f7' } } },
    yAxis: { type: 'category', inverse: true, data: items.map(x => x.stageName),
      axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#334155' } },
    series: [{
      type: 'bar', barWidth: 16, barBorderRadius: [0, 8, 8, 0],
      itemStyle: { color: tealGradient },
      data: items.map(x => ({ name: x.stageName, value: x.rate ?? 0, done: x.done, involved: x.involved, rate: x.rate, sample: g.sample, workshop: g.workshop, stage: x.stage, stageName: x.stageName })),
      label: { show: true, position: 'right', color: '#334155', formatter: function (p: any) {
        return p.data.done + '/' + p.data.involved + ' · ' + p.data.rate + '%'
      } }
    }]
  }
}

function waitOption() {
  const buckets = waitBuckets.value
  return {
    tooltip: { trigger: 'axis', triggerOn: 'mousemove|click', renderMode: 'richText', confine: true },
    grid: { left: 30, right: 40, top: 8, bottom: 20, containLabel: true },
    xAxis: { type: 'value', name: '单数', minInterval: 1, axisLabel: { color: '#94a3b8' }, axisLine: { show: false }, axisTick: { show: false }, splitLine: { lineStyle: { color: '#eef2f7' } } },
    yAxis: { type: 'category', inverse: true, data: buckets.map(x => x.isOver ? '3 天及以上' : `${x.day} 天`),
      axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#334155' } },
    series: [{ type: 'bar', barWidth: 14, barBorderRadius: [0, 7, 7, 0],
      data: buckets.map(x => ({ name: x.isOver ? '3 天及以上' : `${x.day} 天`, value: x.count, day: x.day, isOver: x.isOver, itemStyle: { color: x.isOver ? warnGradient : tealGradient } })),
      label: { show: true, position: 'right', color: '#334155', formatter: '{c}' } }]
  }
}

onMounted(() => { void load() })
onBeforeUnmount(() => {
  charts.forEach(c => c.dispose()); charts.clear()
  resizeObserver?.disconnect(); resizeObserver = null
})
defineExpose({ load, clearDrilldown })
</script>

<style scoped>
.node-body { padding: 2px 2px 4px; }
.node-layout { display: grid; grid-template-columns: minmax(0, 5fr) minmax(0, 7fr); gap: 16px; align-items: start; }

/* 概览：横跨两栏，两个统计块 + 提示 */
.node-overview { grid-column: 1 / -1; display: flex; gap: 16px; flex-wrap: wrap; align-items: stretch;
  padding: 16px 18px; border: 1px solid #e7ecf3; border-radius: 14px; background: linear-gradient(180deg, #fbfdff, #f6f8fb); }
.overview-block { flex: 1 1 220px; border-radius: 12px; padding: 14px 18px; display: flex; flex-direction: column; gap: 4px; }
.overview-block.primary { background: linear-gradient(135deg, #e8f6f4, #f4fbfa); border: 1px solid #c9e7e1; }
.overview-block.accent { background: linear-gradient(135deg, #fdf0e7, #fbf6f1); border: 1px solid #f2d6c2; }
.overview-label { font-size: 13px; color: #64748b; }
.overview-value { font-size: 30px; line-height: 1.1; font-weight: 700; color: #127b71; border: 0; background: none; padding: 0; cursor: pointer; text-align: left; }
.overview-block.accent .overview-value { color: #c45620; }
.overview-value em { font-style: normal; font-size: 13px; color: #64748b; margin-left: 4px; }
.overview-block small { color: #8a97a8; font-size: 12px; }
.overview-hint { flex: 1 1 100%; margin: 2px 0 0; color: #94a3b8; font-size: 12px; }

/* 左栏分析图 */
.node-charts { display: flex; flex-direction: column; gap: 16px; min-width: 0; }
.node-drill { min-width: 0; align-self: start; }
.node-section { border: 1px solid #e7ecf3; border-radius: 14px; background: #fff; padding: 16px 18px; box-shadow: 0 1px 2px rgba(15, 45, 55, .04); }
.node-section h3 { margin: 0; font-size: 15px; color: #1f2937; display: flex; align-items: center; gap: 8px; }
.node-section h3::before { content: ''; width: 4px; height: 16px; border-radius: 2px; background: linear-gradient(180deg, #3cc3b5, #127b71); }
.section-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.section-head p { margin: 4px 0 0; color: #64748b; font-size: 12px; }
.node-tip { color: #94a3b8; font-size: 12px; }
/* 图表口径补充说明：基数、排除范围这类「为什么两个数字对不上」的说明 */
.section-note { margin: -4px 0 8px; color: #8a97a8; font-size: 12px; }

/* 下钻面板内的状态筛选工具条 */
.status-filter { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.status-filter > span { color: #64748b; font-size: 13px; }

.chart { width: 100%; height: 200px; }
@media (max-width: 1000px) { .node-layout { grid-template-columns: 1fr; } }
</style>
