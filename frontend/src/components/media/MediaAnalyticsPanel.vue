<template>
  <LqDialog
    :model-value="visible"
    title="数据分析"
    size="lg"
    mode="detail"
    class="media-analytics-dialog"
    @update:model-value="handleClose"
  >
    <div v-if="analyticsLoading" class="analytics-loading">
          <div class="loading-spinner"></div>
          <p>加载分析数据...</p>
        </div>
        <div v-else class="analytics-content">
          <div class="analytics-overview">
            <div class="analytics-metric">
              <span>统计周期</span>
              <strong>{{ analyticsDays }} 天</strong>
            </div>
            <div class="analytics-metric">
              <span>更新次数</span>
              <strong>{{ analyticsTotalUpdates }}</strong>
            </div>
            <div class="analytics-metric">
              <span>活跃平台</span>
              <strong>{{ analyticsActivePlatforms }}</strong>
            </div>
            <div class="analytics-metric attention">
              <span>待关注账号</span>
              <strong>{{ analyticsStaleAccounts.length }}</strong>
            </div>
          </div>
          <div class="analytics-header">
            <div class="analytics-tabs">
              <button
                v-for="tab in analyticsTabs"
                :key="tab.key"
                :class="['tab-btn', { active: activeTab === tab.key }]"
                @click="activeTab = tab.key"
              >
                {{ tab.label }}
              </button>
            </div>
            <div class="analytics-days-selector">
              <button
                v-for="d in [7, 14, 30, 60, 90]"
                :key="d"
                :class="['days-btn', { active: analyticsDays === d }]"
                @click="onAnalyticsDaysChange(d)"
              >
                {{ d }}天
              </button>
            </div>
          </div>
          <div v-if="activeTab === 'trend'" class="analytics-section">
            <div class="section-title">更新趋势</div>
            <div v-if="hasTrend" class="chart-card">
              <canvas :id="canvasId('trend')" style="height: 220px"></canvas>
            </div>
            <div v-else class="analytics-empty">当前周期内暂无更新趋势数据</div>
            <div class="section-title compact">最近更新</div>
            <div v-if="recentUpdates.length" class="analytics-list">
              <button
                v-for="item in recentUpdates.slice(0, 8)"
                :key="item.id"
                type="button"
                class="analytics-list-item"
                @click="
                  item.accountId &&
                  $emit('open-detail', {
                    id: item.accountId,
                    accountName: item.accountName,
                    platform: item.platform
                  })
                "
              >
                <span :class="['platform-badge', platformClass(item.platform)]">{{ item.platform || '-' }}</span>
                <strong>{{ item.accountName || '未命名账号' }}</strong>
                <span>{{ item.updateDate || '-' }}</span>
                <em>{{ item.title || item.note || '未填写标题' }}</em>
              </button>
            </div>
            <div v-else class="analytics-empty">暂无最近更新记录</div>
          </div>
          <div v-if="activeTab === 'platform'" class="analytics-section">
            <div class="section-title">平台对比</div>
            <div v-if="hasPlatform" class="chart-card">
              <canvas :id="canvasId('platform')" style="height: 200px"></canvas>
            </div>
            <div v-else class="analytics-empty">暂无平台更新对比数据</div>
            <div v-if="analyticsData?.platformCompare?.length" class="analytics-rank-list">
              <div v-for="p in analyticsData.platformCompare" :key="p.platform" class="analytics-rank-item">
                <span :class="['platform-badge', platformClass(p.platform)]">{{ p.platform }}</span>
                <strong>本周 {{ p.weekCount || 0 }}</strong>
                <em>本月 {{ p.monthCount || 0 }}</em>
              </div>
            </div>
          </div>
          <div v-if="activeTab === 'operator'" class="analytics-section">
            <div class="section-title">运营排行</div>
            <div v-if="hasOperator" class="chart-card">
              <canvas :id="canvasId('operator')" style="height: 200px"></canvas>
            </div>
            <div v-else class="analytics-empty">暂无运营人员更新数据</div>
            <div v-if="analyticsData?.operatorRank?.length" class="analytics-rank-list">
              <div v-for="op in analyticsData.operatorRank" :key="op.operator" class="analytics-rank-item">
                <span>{{ op.operator || '未设置运营' }}</span>
                <strong>更新 {{ op.updateCount || 0 }}</strong>
                <em>待关注 {{ op.staleCount || 0 }}</em>
              </div>
            </div>
          </div>
          <div v-if="activeTab === 'frequency'" class="analytics-section">
            <div class="section-title">更新频率</div>
            <div v-if="analyticsStaleAccounts.length" class="analytics-alert-list">
              <button
                v-for="acct in analyticsStaleAccounts.slice(0, 10)"
                :key="acct.id"
                type="button"
                class="analytics-alert-item"
                @click="$emit('open-detail', acct)"
              >
                <span :class="['platform-badge', platformClass(acct.platform)]">{{ acct.platform }}</span>
                <strong>{{ acct.accountName }}</strong>
                <em>{{ acct.lastUpdatedAt || '从未更新' }}</em>
              </button>
            </div>
            <div v-else class="analytics-empty">当前没有待关注账号</div>
            <div class="frequency-filter">
              <select v-model="freqPlatform" @change="loadFrequencies">
                <option value="">全部平台</option>
                <option v-for="p in platforms" :key="p.name" :value="p.name">{{ p.name }}</option>
              </select>
            </div>
            <div v-if="freqData.length" class="frequency-list">
              <div v-for="item in freqData" :key="item.accountId" class="frequency-item">
                <span :class="['platform-badge', platformClass(item.platform)]">{{ item.platform }}</span
                ><span class="account-name">{{ item.accountName }}</span
                ><span class="freq-value">{{ item.frequency }}</span>
              </div>
            </div>
            <div v-else class="empty-state">暂无频率数据</div>
          </div>
        </div>
  </LqDialog>
</template>

<script setup>
import LqDialog from '../common/LqDialog.vue'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { fetchAnalytics, fetchRecentUpdates, fetchUpdateFrequencies } from '../../api/media'
import { notify } from '../../composables/ui/useToast'
import { createAbortController } from '../../utils/common/abort'
import { CHART_FONT, ChartManager } from '../../utils/chartManager'
import { formatDate } from '../../utils/common/dateUtils'
import { getPlatformConfig, platformClass } from '../../utils/media/platformUtils'

// 为避免同页面多个实例 ID 冲突，使用实例唯一前缀
const instanceId = Math.random().toString(36).slice(2, 8)
const canvasId = key => `chart-${key}-${instanceId}`

const props = defineProps({
  visible: {
    type: Boolean,
    default: false
  },
  platforms: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['update:visible', 'open-detail'])

function handleClose() {
  emit('update:visible', false)
}

/* ==================== Tab 切换 ==================== */
const analyticsTabs = [
  { key: 'trend', label: '趋势' },
  { key: 'platform', label: '平台' },
  { key: 'operator', label: '运营' },
  { key: 'frequency', label: '频率' }
]
const activeTab = ref('trend')

/* ==================== 状态 ==================== */
const analyticsDays = ref(30)
const analyticsLoading = ref(false)
const analyticsData = ref(null)
const freqData = ref([])
const freqPlatform = ref('')
const recentUpdates = ref([])

const chartManager = new ChartManager()

let analyticsAbortController = null

const analyticsTotalUpdates = computed(() =>
  (analyticsData.value?.dailyTrend || []).reduce((sum, item) => sum + (item.count || 0), 0)
)
const analyticsActivePlatforms = computed(
  () => new Set((analyticsData.value?.dailyTrend || []).map(item => item.platform).filter(Boolean)).size
)
const analyticsStaleAccounts = computed(() => analyticsData.value?.staleAccounts || [])
const hasTrend = computed(() => (analyticsData.value?.dailyTrend || []).length > 0)
const hasPlatform = computed(() =>
  (analyticsData.value?.platformCompare || []).some(item => item.weekCount || item.monthCount)
)
const hasOperator = computed(() => (analyticsData.value?.operatorRank || []).length > 0)

/* ==================== 数据加载 ==================== */
function onAnalyticsDaysChange(val) {
  analyticsDays.value = val
  loadAnalytics()
}

async function loadAnalytics() {
  if (analyticsAbortController) {
    analyticsAbortController.abort()
  }
  analyticsAbortController = createAbortController()
  const thisController = analyticsAbortController

  analyticsLoading.value = true
  chartManager.destroyAll()
  try {
    const data = await fetchAnalytics(analyticsDays.value, { signal: thisController.signal })
    if (thisController.signal.aborted) return
    analyticsData.value = data
    loadFrequencies()
    try {
      const rec = await fetchRecentUpdates(30, 0, 100, { signal: thisController.signal })
      recentUpdates.value = rec.content || []
    } catch {
      recentUpdates.value = []
    }
    if (analyticsAbortController === thisController) {
      analyticsLoading.value = false
    }
    await nextTick()
    scheduleAllAnalyticsCharts()
  } catch (err) {
    if (err.name === 'AbortError') return
    console.warn('加载分析数据失败', err)
    notify.errorWithDetail('加载分析数据失败', err)
  } finally {
    if (analyticsAbortController === thisController) {
      analyticsLoading.value = false
    }
  }
}

async function loadFrequencies() {
  try {
    const p = freqPlatform.value || undefined
    freqData.value = await fetchUpdateFrequencies(p)
  } catch (err) {
    console.warn('加载更新频率失败', err)
  }
}

/* ==================== 图表渲染 ==================== */
function scheduleAllAnalyticsCharts() {
  const idMap = {
    trend: canvasId('trend'),
    platform: canvasId('platform'),
    operator: canvasId('operator')
  }
  const renderMap = {
    [idMap.trend]: () => {
      if (analyticsData.value) renderTrendChart(analyticsData.value)
    },
    [idMap.operator]: () => {
      if (analyticsData.value) renderOperatorChart(analyticsData.value)
    },
    [idMap.platform]: () => {
      if (analyticsData.value) renderPlatformChart(analyticsData.value)
    }
  }
  const rendered = new Set()

  if (typeof IntersectionObserver !== 'undefined') {
    const observer = new IntersectionObserver(
      entries => {
        entries.forEach(entry => {
          if (entry.isIntersecting) {
            const id = entry.target.id
            if (!rendered.has(id) && renderMap[id]) {
              rendered.add(id)
              try {
                renderMap[id]()
              } catch (e) {
                console.error('图表渲染异常', id, e)
              }
            }
            observer.unobserve(entry.target)
          }
        })
      },
      { rootMargin: '100px' }
    )

    Object.values(idMap).forEach(id => {
      const canvas = document.getElementById(id)
      if (canvas && canvas.clientWidth > 10) {
        observer.observe(canvas)
      }
    })

    setTimeout(() => {
      Object.values(idMap).forEach(id => {
        if (!rendered.has(id) && renderMap[id]) {
          rendered.add(id)
          try {
            renderMap[id]()
          } catch (e) {
            console.error('图表兜底渲染异常', id, e)
          }
        }
      })
    }, 2000)
  } else {
    setTimeout(() => {
      Object.values(idMap).forEach(id => {
        if (renderMap[id]) {
          try {
            renderMap[id]()
          } catch (e) {
            console.error('图表渲染异常', id, e)
          }
        }
      })
    }, 200)
  }
}

function renderTrendChart(data) {
  const canvas = document.getElementById(canvasId('trend'))
  if (!canvas) return

  const days = analyticsDays.value
  const today = new Date()
  const dates = []
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today)
    d.setDate(d.getDate() - i)
    dates.push(formatDate(d))
  }

  const trendMap = {}
  data.dailyTrend.forEach(p => {
    const key = `${p.date}|${p.platform}`
    trendMap[key] = p.count
  })

  const activePlatforms = [...new Set(data.dailyTrend.map(t => t.platform))].filter(Boolean)

  const datasets = []
  datasets.push({
    label: '全部',
    data: dates.map(d => {
      let total = 0
      activePlatforms.forEach(p => {
        total += trendMap[`${d}|${p}`] || 0
      })
      return total
    }),
    borderColor: 'var(--color-primary)',
    backgroundColor: 'rgba(15,118,110,0.08)',
    borderWidth: 2.5,
    fill: true,
    tension: 0.3
  })

  activePlatforms.forEach(p => {
    const cfg = getPlatformConfig(p)
    datasets.push({
      label: p,
      data: dates.map(d => trendMap[`${d}|${p}`] || 0),
      borderColor: cfg.color,
      backgroundColor: 'transparent',
      borderWidth: 1.5,
      tension: 0.3,
      pointRadius: 0
    })
  })

  const labels = dates.map(d => d.slice(5))

  chartManager.create('trend', canvas, {
    type: 'line',
    data: { labels, datasets },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: { legend: { display: false } },
      scales: {
        y: { beginAtZero: true, ticks: { stepSize: 1, font: CHART_FONT } },
        x: { ticks: { maxRotation: 45, autoSkip: true, maxTicksLimit: 15, font: CHART_FONT } }
      }
    }
  })
}

function renderPlatformChart(data) {
  const canvas = document.getElementById(canvasId('platform'))
  if (!canvas || !data?.platformCompare?.length) return

  const labels = data.platformCompare.map(p => p.platform)
  const weekData = data.platformCompare.map(p => p.weekCount)
  const monthData = data.platformCompare.map(p => p.monthCount)

  chartManager.create('platform', canvas, {
    type: 'bar',
    data: {
      labels,
      datasets: [
        { label: '本周', data: weekData, backgroundColor: 'var(--color-primary-light)', borderRadius: 4 },
        { label: '本月', data: monthData, backgroundColor: 'var(--color-primary)', borderRadius: 4 }
      ]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { position: 'bottom', labels: { boxWidth: 14, font: CHART_FONT } }
      },
      scales: {
        y: { beginAtZero: true, ticks: { stepSize: 1, font: CHART_FONT } },
        x: { ticks: { font: CHART_FONT } }
      }
    }
  })
}

function renderOperatorChart(data) {
  const canvas = document.getElementById(canvasId('operator'))
  if (!canvas || !data?.operatorRank?.length) return

  const labels = data.operatorRank.map(p => p.operator)
  const updateData = data.operatorRank.map(p => p.updateCount)
  const staleData = data.operatorRank.map(p => p.staleCount)

  chartManager.create('operator', canvas, {
    type: 'bar',
    data: {
      labels,
      datasets: [
        { label: '更新次数', data: updateData, backgroundColor: 'var(--color-primary)', borderRadius: 4 },
        { label: '待关注账号', data: staleData, backgroundColor: 'var(--color-danger)', borderRadius: 4 }
      ]
    },
    options: {
      indexAxis: 'y',
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { position: 'bottom', labels: { boxWidth: 14, font: CHART_FONT } }
      },
      scales: {
        x: { beginAtZero: true, ticks: { stepSize: 1, font: CHART_FONT } },
        y: { ticks: { font: CHART_FONT } }
      }
    }
  })
}

/* ==================== Tab 切换 -> 重新渲染图表 ==================== */
watch(activeTab, async () => {
  await nextTick()
  setTimeout(() => {
    if (!props.visible || !analyticsData.value) return
    if (activeTab.value === 'trend') renderTrendChart(analyticsData.value)
    if (activeTab.value === 'platform') renderPlatformChart(analyticsData.value)
    if (activeTab.value === 'operator') renderOperatorChart(analyticsData.value)
  }, 80)
})

/* ==================== 打开抽屉时自动加载数据 ==================== */
watch(
  () => props.visible,
  val => {
    if (val && !analyticsData.value) {
      loadAnalytics()
    }
  }
)

onBeforeUnmount(() => {
  if (analyticsAbortController) analyticsAbortController.abort()
  analyticsAbortController = null
  chartManager.destroyAll()
})
</script>

<style scoped>
@import '../../styles/media-account-view.css';
/* ==================== Analytics Content ==================== */
.analytics-content {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-wide);
}
.analytics-loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: var(--space-3xl);
  gap: var(--space-md);
  color: var(--color-text-muted);
}

.loading-spinner {
  width: 36px;
  height: 36px;
  border: 3px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* ==================== Analytics Content ==================== */
.analytics-content {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-wide);
}

.analytics-overview {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--space-control-gap);
}

.analytics-metric {
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: var(--space-control-gap) var(--space-md);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.analytics-metric span {
  font-size: var(--fs-xs);
  color: var(--color-text-secondary);
}

.analytics-metric strong {
  font-size: var(--fs-xl);
  font-weight: var(--fw-bold);
  color: var(--color-text-heading);
}

.analytics-metric.attention strong {
  color: var(--color-warning);
}

.analytics-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  flex-wrap: wrap;
}

.analytics-tabs {
  display: inline-flex;
  background: var(--color-surface-alt);
  border-radius: var(--radius-pill);
  padding: 3px;
  gap: 2px;
}

.tab-btn {
  border: none;
  background: transparent;
  padding: 6px 14px;
  font-size: var(--fs-sm);
  color: var(--color-text-secondary);
  border-radius: var(--radius-pill);
  cursor: pointer;
  font-weight: var(--fw-medium);
  transition: all 0.15s ease;
}

.tab-btn:hover {
  color: var(--color-text-primary);
}

.tab-btn.active {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  box-shadow: 0 2px 6px rgba(15, 118, 110, 0.25);
}

.analytics-days-selector {
  display: inline-flex;
  gap: var(--space-control-gap-tight);
}

.days-btn {
  border: 1px solid var(--color-border-light);
  background: var(--color-surface);
  padding: 5px 10px;
  font-size: var(--fs-xs);
  color: var(--color-text-secondary);
  border-radius: var(--radius-md);
  cursor: pointer;
  font-weight: var(--fw-medium);
  transition: all 0.15s ease;
}

.days-btn:hover {
  border-color: var(--color-primary-border);
  color: var(--color-primary);
}

.days-btn.active {
  background: var(--color-primary-bg);
  border-color: var(--color-primary-border);
  color: var(--color-primary);
}

.analytics-section {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap);
}

.section-title {
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  color: var(--color-text-heading);
  padding-bottom: var(--space-control-gap-tight);
  border-bottom: 1px solid var(--color-border-light);
}

.section-title.compact {
  border-bottom: none;
  padding-bottom: 0;
  margin-top: var(--space-sm);
}

.chart-card {
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: var(--space-md);
}

.analytics-empty {
  padding: var(--space-lg);
  text-align: center;
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  background: var(--color-surface-alt);
  border-radius: var(--radius-md);
  border: 1px dashed var(--color-border-light);
}

.analytics-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
  max-height: 240px;
  overflow-y: auto;
}

.analytics-list-item {
  display: grid;
  grid-template-columns: auto 1fr auto auto;
  gap: var(--space-sm);
  align-items: center;
  padding: 8px var(--space-control-gap);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  cursor: pointer;
  text-align: left;
  transition: all 0.12s ease;
}

.analytics-list-item:hover {
  background: var(--color-surface-alt);
  border-color: var(--color-primary-border);
  transform: translateX(2px);
}

.analytics-list-item strong {
  font-size: var(--fs-sm);
  color: var(--color-text-heading);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 160px;
}

.analytics-list-item > span:nth-child(3) {
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
  font-variant-numeric: tabular-nums;
}

.analytics-list-item em {
  font-size: var(--fs-xs);
  color: var(--color-text-secondary);
  font-style: normal;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 180px;
}

.analytics-rank-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
}

.analytics-rank-item {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: 8px var(--space-control-gap);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}

.analytics-rank-item strong {
  font-size: var(--fs-sm);
  color: var(--color-text-heading);
}

.analytics-rank-item em {
  margin-left: auto;
  font-size: var(--fs-xs);
  color: var(--color-text-secondary);
  font-style: normal;
}

.analytics-alert-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
  max-height: 220px;
  overflow-y: auto;
}

.analytics-alert-item {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: var(--space-sm);
  align-items: center;
  padding: 8px var(--space-control-gap);
  border: 1px solid var(--color-warning-border);
  border-radius: var(--radius-md);
  background: var(--color-warning-bg);
  cursor: pointer;
  text-align: left;
  transition: all 0.12s ease;
}

.analytics-alert-item:hover {
  background: var(--color-warning);
  color: var(--color-text-on-primary);
}

.analytics-alert-item strong {
  font-size: var(--fs-sm);
  color: var(--color-text-heading);
}

.analytics-alert-item em {
  font-size: var(--fs-xs);
  color: var(--color-text-secondary);
  font-style: normal;
  font-variant-numeric: tabular-nums;
}

.frequency-filter {
  margin-top: var(--space-sm);
}

.frequency-filter select {
  width: 180px;
  height: 32px;
  padding: 0 var(--space-sm);
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-md);
  font-size: var(--fs-sm);
  color: var(--color-text-primary);
  background: var(--color-surface);
}

.frequency-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 320px;
  overflow-y: auto;
}

.frequency-item {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: var(--space-sm);
  align-items: center;
  padding: 6px var(--space-control-gap);
  border-bottom: 1px solid var(--color-border-light);
}

.frequency-item .account-name {
  font-size: var(--fs-sm);
  color: var(--color-text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.frequency-item .freq-value {
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  color: var(--color-primary);
  font-variant-numeric: tabular-nums;
}

.empty-state {
  padding: var(--space-lg);
  text-align: center;
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

/* ==================== 响应式 ==================== */
@media (max-width: 900px) {
  .analytics-overview {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
