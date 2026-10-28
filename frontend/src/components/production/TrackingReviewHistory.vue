<template>
  <section class="review-history-section">
    <div class="section-head">
      <div><h2>历史趋势 · 已固化周期</h2><p>过去周期首次复盘后固化，交期调整不再改变其数字；下方图表与表格同源，可按车间筛选。</p></div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" style="margin-top:10px" />
    <div v-loading="loading" class="history-body">
      <el-empty v-if="!loading && !items.length" description="暂无历史快照 —— 过去周期被复盘过一次后才会固化，届时在此出现趋势。" />
      <template v-else-if="items.length">
        <div ref="chartEl" class="history-chart" aria-label="历史周期趋势图" />
        <el-table :data="items" stripe size="small" empty-text="没有已固化的历史周期">
          <el-table-column label="周期" min-width="150"><template #default="{ row }">{{ periodLabel(row) }}</template></el-table-column>
          <el-table-column label="车间" width="110"><template #default="{ row }">{{ row.workshop || '全部' }}</template></el-table-column>
          <el-table-column label="需完成" width="82" align="right"><template #default="{ row }">{{ row.totals?.dueTasks ?? '—' }}</template></el-table-column>
          <el-table-column label="按期完工率" min-width="150">
            <template #default="{ row }"><div class="rate-cell"><div class="rate-track"><b :style="{ width: pctWidth(row.totals?.onTimeAchievementRate) }" /></div><span>{{ percent(row.totals?.onTimeAchievementRate) }}</span></div></template>
          </el-table-column>
          <el-table-column label="完工率" min-width="140">
            <template #default="{ row }"><div class="rate-cell"><div class="rate-track"><b :style="{ width: pctWidth(row.totals?.completionRate) }" /></div><span>{{ percent(row.totals?.completionRate) }}</span></div></template>
          </el-table-column>
          <el-table-column label="逾期" width="74" align="right"><template #default="{ row }">{{ row.totals?.overdue ?? '—' }}</template></el-table-column>
          <el-table-column label="延期" width="74" align="right"><template #default="{ row }">{{ row.totals?.extended ?? '—' }}</template></el-table-column>
          <el-table-column label="未完工" width="82" align="right"><template #default="{ row }">{{ row.totals?.open ?? '—' }}</template></el-table-column>
          <el-table-column label="固化于" min-width="160"><template #default="{ row }">{{ row.generatedAt?.slice(0, 16).replace('T', ' ') }}</template></el-table-column>
        </el-table>
      </template>
    </div>
  </section>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ECharts } from 'echarts'
import { fetchTrackingWeeklyReviewHistory, type TrackingWeeklyReviewHistoryItem } from '@/api/productionTracking'
import { percent } from '@/utils/tracking/format'

const props = defineProps<{ workshop?: string }>()

const items = ref<TrackingWeeklyReviewHistoryItem[]>([])
const loading = ref(false), error = ref('')

const chartEl = ref<HTMLElement | null>(null)
let chart: ECharts | null = null
let echartsPromise: Promise<typeof import('echarts')> | null = null
let resizeObserver: ResizeObserver | null = null

function loadEcharts() {
  echartsPromise ||= import('echarts')
  return echartsPromise
}

/** 占比条宽度：本期没有该指标时为 0%（与 percent 的「样本不足」互补，避免除零与溢出）。 */
const pctWidth = (value: number | null | undefined) =>
  value === null || value === undefined ? '0%' : `${Math.min(Math.max(value, 0), 100)}%`
const periodLabel = (row: TrackingWeeklyReviewHistoryItem) =>
  `${row.periodFrom.slice(5).replace('-', '.')} - ${row.periodTo.slice(5).replace('-', '.')}`

async function load() {
  loading.value = true; error.value = ''
  try {
    items.value = await fetchTrackingWeeklyReviewHistory({ workshop: props.workshop || '' })
    await renderChart()
  } catch (e) { error.value = e instanceof Error ? e.message : '历史趋势加载失败' }
  finally { loading.value = false }
}

async function renderChart() {
  if (!chartEl.value) return
  const echarts = await loadEcharts()
  await nextTick()
  if (!chartEl.value || !chartEl.value.isConnected) return
  chart?.dispose()
  chart = echarts.init(chartEl.value)
  chart.setOption(buildOption())
  if (typeof ResizeObserver !== 'undefined') {
    resizeObserver?.disconnect()
    resizeObserver = new ResizeObserver(() => chart?.resize())
    resizeObserver.observe(chartEl.value)
  }
}

/** 图表与表格同一份 items：按期完工率/完工率折线（左轴 %），逾期/延期单数柱（右轴）。 */
function buildOption() {
  const rows = items.value
  return {
    animation: false,
    tooltip: { trigger: 'axis' },
    legend: { data: ['按期完工率', '完工率', '逾期单数', '延期单数'] },
    grid: { left: 44, right: 44, bottom: 28, top: 40 },
    xAxis: { type: 'category', data: rows.map(periodLabel) },
    yAxis: [
      { type: 'value', name: '完工率 %', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
      { type: 'value', name: '单数', minInterval: 1 }
    ],
    series: [
      { name: '按期完工率', type: 'line', smooth: true, connectNulls: false, yAxisIndex: 0, data: rows.map(r => r.totals?.onTimeAchievementRate ?? null), itemStyle: { color: '#087c72' } },
      { name: '完工率', type: 'line', smooth: true, connectNulls: false, yAxisIndex: 0, data: rows.map(r => r.totals?.completionRate ?? null), itemStyle: { color: '#4f7cff' } },
      { name: '逾期单数', type: 'bar', yAxisIndex: 1, data: rows.map(r => r.totals?.overdue ?? 0), itemStyle: { color: '#db9869' } },
      { name: '延期单数', type: 'bar', yAxisIndex: 1, data: rows.map(r => r.totals?.extended ?? 0), itemStyle: { color: '#b0b7c3' } }
    ]
  }
}

// 车间筛选变化时刷新；初始 onMounted 加载一次
watch(() => props.workshop, () => void load())

onMounted(() => { void load() })
onBeforeUnmount(() => {
  chart?.dispose(); chart = null
  resizeObserver?.disconnect(); resizeObserver = null
})
</script>

<style scoped>
.review-history-section { margin-top: 18px; padding: 18px; border: 1px solid #e3e8ef; border-radius: 12px; background: #fff; }
.section-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
.section-head h2 { margin: 0; font-size: 18px; }
.section-head p { margin: 4px 0 0; color: #64748b; font-size: 13px; }
.history-body { margin-top: 8px; }
.history-chart { width: 100%; height: 280px; margin-bottom: 14px; }
.rate-cell { display: flex; align-items: center; gap: 10px; }
.rate-track { flex: 1; min-width: 70px; height: 8px; border-radius: 6px; background: #e9eef1; overflow: hidden; }
.rate-track b { display: block; height: 100%; background: #087c72; }
</style>
