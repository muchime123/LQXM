<template>
  <el-drawer :model-value="modelValue" size="90%" :with-header="false" append-to-body @update:model-value="emit('update:modelValue', $event)">
  <main class="tracking-review" v-loading="loading">
    <header class="review-head">
      <div><h1>跟单周复盘</h1><p>周期：{{ periodLabel }}<template v-if="data"> · 数据截至 {{ data.asOf }}</template></p></div>
      <div class="review-head-actions"><el-button @click="load">刷新数据</el-button><el-button aria-label="关闭周复盘" @click="emit('update:modelValue', false)">关闭</el-button></div>
    </header>

    <section class="weekly-filter" aria-label="复盘周期">
      <strong>周期</strong>
      <el-button v-for="preset in weekPresets" :key="preset.key" size="small"
        :type="preset.key === activePreset ? 'primary' : 'default'" :plain="preset.key !== activePreset"
        @click="applyPreset(preset.key)">{{ preset.label }}</el-button>
      <el-date-picker v-model="fromDate" type="date" value-format="YYYY-MM-DD" placeholder="周期起" clearable @change="onCustomRange" />
      <span>至</span>
      <el-date-picker v-model="toDate" type="date" value-format="YYYY-MM-DD" placeholder="周期止" clearable @change="onCustomRange" />
      <el-select v-model="workshop" clearable filterable placeholder="全部车间" @change="load"><el-option v-for="name in workshopOptions" :key="name" :label="name" :value="name" /></el-select>
      <el-button type="primary" @click="load">复盘</el-button>
    </section>
    <p class="weekly-note">统计口径与「生产交期达成分析」完全一致，按当前有效车间预计完成日期归集；
      交期调整后任务会在周期之间移动，回看过往周期时数字会随之变化。周期支持自然周，也支持 10.01-10.11 这类月初分段。</p>

    <el-alert v-if="displayError" :title="displayError" type="error" :closable="false" />

    <div class="review-layout">
    <div class="review-main">

    <template v-if="data">
      <section class="weekly-section">
        <h2>一、本周数据汇总</h2>
        <MetricGrid :columns="4" density="compact">
          <MetricCard title="本周需完成" :value="data.summary.dueTasks" unit="单" tone="primary" hint="按当前有效车间预计完成日期落在本周期" :clickable="true" @click="openDrilldown('dueTasks', '本周需完成')" />
          <MetricCard title="本周按期完工" :value="data.summary.onTime" unit="单" tone="success" :hint="`按期完工率 ${percent(data.summary.onTimeAchievementRate)}`" :clickable="true" @click="openDrilldown('onTime', '本周按期完工')" />
          <MetricCard title="本周逾期" :value="data.summary.overdue" unit="单" tone="danger" :hint="overdueHint" :clickable="true" @click="openDrilldown('overdue', '本周逾期')" />
          <MetricCard title="未完工" :value="data.summary.open" unit="单" tone="warning" :hint="openHint" :clickable="true" @click="openDrilldown('open', '未完工')" />
        </MetricGrid>
        <p class="weekly-definition">应完成＝按期完工＋逾期＋未完工，三者互斥；已确认取消
          <button class="weekly-link" @click="openDrilldown('cancelled', '已确认取消')">{{ data.summary.cancelled }} 单</button>不计入履约，
          <button class="weekly-link" @click="openDrilldown('stopped', '已停产')">已停产 {{ data.summary.stopped }} 单</button>暂不计入履约，恢复生产后重新纳入。
          另有 <button class="weekly-link" @click="openDrilldown('missingExpected', '缺少车间预计日期')">{{ data.summary.missingExpected }} 单</button>缺少车间预计日期，未纳入任何周期。</p>
      </section>

      <section class="weekly-section">
        <h2>二、本周订单逾期问题复盘（{{ data.overdueTotal }} 单）</h2>
        <h3>（一）按逾期天数分类</h3>
        <el-table :data="data.overdueLevels" stripe empty-text="本周期没有逾期完工">
          <el-table-column label="逾期等级" width="130">
            <template #default="{ row }"><button class="weekly-link" @click="openDrilldown(levelBucket(row.key), `${row.label}逾期`)">{{ row.label }}</button></template>
          </el-table-column>
          <el-table-column prop="range" label="逾期天数范围" width="130" />
          <el-table-column label="订单数量" width="110">
            <template #default="{ row }"><button class="weekly-link" @click="openDrilldown(levelBucket(row.key), `${row.label}逾期`)">{{ row.count }} 单</button></template>
          </el-table-column>
          <el-table-column label="占比" min-width="220">
            <template #default="{ row }">
              <div class="rate-cell"><div class="rate-track"><b :style="{ width: barWidth(row.count, data!.overdueTotal) }" /></div><span>{{ percent(row.rate) }}</span></div>
            </template>
          </el-table-column>
        </el-table>
        <h3>（二）按逾期原因分类</h3>
        <ReasonTable :items="data.overdueReasons" :total="data.overdueTotal" bucket="overdueReasonCategory"
          empty-text="本周期没有逾期完工" @select="openReasonDrilldown" />
        <p class="weekly-definition">原因来自跟单员在「时间核实」里登记的原文，系统按词典归纳为六类；
          未登记原因的记入「原因未记录」，词典未收录的写法记入「其他原因」—— 这两项正是下一步要跟跟单员确认措辞的地方。</p>
      </section>

      <section class="weekly-section">
        <h2>三、本周订单延期问题复盘（{{ data.extensionTotal }} 单）</h2>
        <ReasonTable :items="data.extensionReasons" :total="data.extensionTotal" bucket="extensionReasonCategory"
          empty-text="本周期没有协商延后的任务" @select="openReasonDrilldown" />
        <p class="weekly-definition">延期＝跟单员与车间 / 业务员协商后，把车间预计完成日期往后调过（累计
          {{ data.summary.extensionDays }} 天）；它与「逾期」是两件事 —— 逾期是交期没守住的结果，延期是交期被重新约定。
          原因优先取跟单状态行的「延期原因」，未填时取「车间交期」核实记录。</p>
      </section>

      <section class="weekly-section">
        <h2>四、下周重点跟进 · 未完工 {{ data.summary.open }} 单</h2>
        <el-table v-loading="openLoading" :data="openItems" stripe empty-text="本周期没有未完工任务" @row-click="openDrilldownOrder">
          <el-table-column label="生产任务单" min-width="170">
            <template #default="{ row }"><button class="weekly-link" @click.stop="openDrilldownOrder(row)">{{ row.orderNo }} ↗</button></template>
          </el-table-column>
          <el-table-column prop="productName" label="产品" min-width="150" show-overflow-tooltip />
          <el-table-column label="业务员" width="100"><template #default="{ row }"><TrackingAnalyticsSalespersonCell :salesperson="row.salesperson" /></template></el-table-column>
          <el-table-column label="产品规格 / 模具出数" min-width="240" show-overflow-tooltip><template #default="{ row }"><TrackingAnalyticsSpecCell :specification="row.specification" :mold-layout="row.moldLayout" /></template></el-table-column>
          <el-table-column prop="workshop" label="车间" min-width="125" show-overflow-tooltip />
          <el-table-column prop="baseline" label="当前预计" width="115" />
          <el-table-column label="当前超期" width="110">
            <template #default="{ row }">{{ row.unfinishedOverdueDays == null ? '未到期' : `已超 ${row.unfinishedOverdueDays} 天` }}</template>
          </el-table-column>
        </el-table>
        <p class="weekly-definition">其中已超预计日期
          <button class="weekly-link" @click="openDrilldown('openOverdue', '未完工且已超期')">{{ data.summary.openOverdue }} 单</button>，
          累计超期 {{ data.summary.openOverdueDays }} 天 —— 这是下周要先处理的部分。</p>
      </section>

      <section class="weekly-section weekly-summary">
        <h2>五、本周总结</h2>
        <p class="summary-text">{{ summaryText }}</p>
        <p class="weekly-definition">这段文字由系统按本周期数据自动生成，可直接选中后贴进周报；目标与对策仍需跟单员补充。</p>
      </section>
    </template>
    <TrackingReviewHistory :workshop="workshop" />
    </div>
    <aside class="review-drill-col">
      <TrackingAnalyticsOrdersTable v-if="ddActive && drillState"
        :items="ddItems" :total="ddTotal" :page="ddPage" :size="ddSize" :loading="ddLoading"
        :title="`${drillState.label} · 对应工单 ${ddTotal} 单`"
        @update:page="onPageChange" @open-order="id => emit('open-order', id)" @close="closeDrilldown">
        <template #extra>
          <el-table-column prop="baseline" label="当前预计" width="115" />
          <el-table-column prop="deliveredDate" label="整单完工日" width="125" />
          <el-table-column label="状态" width="110"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
          <el-table-column label="完工逾期" width="95"><template #default="{ row }">{{ row.overdueDays == null ? '—' : `${row.overdueDays} 天` }}</template></el-table-column>
          <el-table-column label="未完工超期" width="110"><template #default="{ row }">{{ row.unfinishedOverdueDays == null ? '—' : `${row.unfinishedOverdueDays} 天` }}</template></el-table-column>
        </template>
      </TrackingAnalyticsOrdersTable>
      <TrackingDrillEmpty v-else />
    </aside>
    </div>
  </main>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import MetricCard from '@/components/common/MetricCard.vue'
import MetricGrid from '@/components/common/MetricGrid.vue'
import ReasonTable from '@/components/production/TrackingWeeklyReasonTable.vue'
import TrackingReviewHistory from '@/components/production/TrackingReviewHistory.vue'
import TrackingAnalyticsOrdersTable from '@/components/production/TrackingAnalyticsOrdersTable.vue'
import TrackingDrillEmpty from '@/components/production/TrackingDrillEmpty.vue'
import TrackingAnalyticsSalespersonCell from '@/components/production/TrackingAnalyticsSalespersonCell.vue'
import TrackingAnalyticsSpecCell from '@/components/production/TrackingAnalyticsSpecCell.vue'
import {
  fetchTrackingAnalyticsOrders, fetchTrackingWeeklyReview,
  type TrackingAnalyticsOrder, type TrackingWeeklyReview
} from '@/api/productionTracking'
import { isoWeek, weekRange } from '@/utils/tracking/week'
import { useTrackingDrilldown } from '@/composables/tracking/useTrackingDrilldown'
// percent / barWidth / statusLabel 与生产分析 / 节点分析共用同一份口径（见 utils/tracking/format.ts）。
import { percent, barWidth, statusLabel } from '@/utils/tracking/format'

/**
 * 跟单周复盘：与「生产分析」同一个抽屉形态（size 90%），挂在「周复盘」按钮下。
 * 与生产分析的区别只在组织方式 —— 那份是按主题摊开，这份按跟单员手写周报的表格结构排。
 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; 'open-order': [id: number] }>()

const fromDate = ref(''), toDate = ref(''), workshop = ref('')
const workshopOptions = ref<string[]>([])
const data = ref<TrackingWeeklyReview | null>(null)
const loading = ref(false), error = ref('')

/** 周期预设：offset 为相对本周的周数偏移；custom 表示用户手改了起止日期。 */
type PresetKey = 'this' | 'last' | 'previous' | 'custom'
const weekPresets: { key: PresetKey; label: string; offset?: number }[] = [
  { key: 'this', label: '本周', offset: 0 },
  { key: 'last', label: '上周', offset: -1 },
  { key: 'previous', label: '前周', offset: -2 }
]
const activePreset = ref<PresetKey>('last')

function applyPreset(key: PresetKey) {
  const preset = weekPresets.find(item => item.key === key)
  if (!preset || preset.offset === undefined) return
  const range = weekRange(new Date(), preset.offset)
  fromDate.value = range.from; toDate.value = range.to
  activePreset.value = key
  void load()
}
/** 手改日期即脱离预设 —— 10.01-10.11 这类月初分段周期就是这样选出来的。 */
function onCustomRange() { activePreset.value = 'custom' }

const periodLabel = computed(() => {
  if (!fromDate.value || !toDate.value) return '未设定周期'
  const week = isoWeek(new Date(`${fromDate.value}T00:00:00`))
  return `${fromDate.value.slice(5).replace('-', '.')} - ${toDate.value.slice(5).replace('-', '.')}（${week.year} 年第 ${week.week} 周）`
})

const levelBucket = (key: string) => key === 'MINOR' ? 'overdue1to3' : key === 'MEDIUM' ? 'overdue4to7' : 'overdue8plus'

const overdueHint = computed(() => {
  const summary = data.value?.summary
  if (!summary) return ''
  const average = summary.averageOverdueDays === null ? '—' : `${summary.averageOverdueDays.toFixed(1)} 天`
  return `累计 ${summary.overdueDays} 天，平均 ${average}`
})
const openHint = computed(() => {
  const summary = data.value?.summary
  return summary ? `其中已超预计日期 ${summary.openOverdue} 单` : ''
})

/**
 * 总结文字：按周报的写法把本周期数据串成一句话。
 * 只陈述数据，不下结论 —— 「下周重点跟进改善」这类对策仍留给跟单员写。
 */
const summaryText = computed(() => {
  if (!data.value) return ''
  const { dueTasks, overdue, open, onTimeAchievementRate, extended } = data.value.summary
  if (!dueTasks) return `${periodLabel.value}：区间内没有应交的生产任务。`
  let text = `本周需完成订单 ${dueTasks} 单，按期完工率 ${percent(onTimeAchievementRate)}。`
  if (!overdue && !extended) return `${text}本周期无逾期、无延期。`
  const named = [...new Set([
    ...topReasons(data.value.overdueReasons), ...topReasons(data.value.extensionReasons)
  ])].slice(0, 3)
  let tail = overdue ? `存在 ${overdue} 单逾期` : ''
  if (extended) tail += tail ? `、${extended} 单延期` : `${extended} 单延期`
  if (named.length) tail += `，主要为${named.join('、')}`
  text += tail + '。'
  if (open) text += `另有 ${open} 单未完工，需下周继续跟进。`
  return text
})
/** 排名最前的原因类别；「原因未记录 / 其他原因」不算原因，只作提示。 */
function topReasons(items: { key: string; label: string }[]) {
  return items.filter(item => item.key !== 'UNRECORDED' && item.key !== 'OTHER').slice(0, 2).map(item => item.label.replace('原因', ''))
}

type Drilldown = { bucket: string; label: string; reasonCategory?: string }
const drillState = ref<Drilldown | null>(null)
const { active: ddActive, items: ddItems, total: ddTotal, page: ddPage, size: ddSize,
  loading: ddLoading, error: ddError, open, close, load: drillLoad, scrollIntoView } = useTrackingDrilldown<TrackingAnalyticsOrder>()
const displayError = computed(() => error.value || ddError.value)

/** 下钻请求：读取当前 drillState，分页共用同一份 loader。 */
function drillLoader(page: number, size: number) {
  const d = drillState.value
  return fetchTrackingAnalyticsOrders({
    fromDate: fromDate.value, toDate: toDate.value, workshop: workshop.value,
    bucket: d?.bucket ?? '', reasonCategory: d?.reasonCategory,
    page, size
  })
}
function openDrilldown(bucket: string, label: string, extra: Partial<Drilldown> = {}) {
  drillState.value = { bucket, label, ...extra }
  open()
  void drillLoad(drillLoader)
  void scrollIntoView()
}
function openReasonDrilldown(bucket: string, category: { key: string; label: string }) {
  openDrilldown(bucket, `${category.label}`, { reasonCategory: category.key })
}
function onPageChange(p: number) { ddPage.value = p; void drillLoad(drillLoader) }
function closeDrilldown() { drillState.value = null; close() }
/** 点工单直接开跟单详情 —— 与生产分析抽屉同一条链路，不再让用户复制任务号去搜。 */
function openDrilldownOrder(row: TrackingAnalyticsOrder) { emit('open-order', row.id) }

/** 未完工清单：周报第四节要能直接看到是哪几单，故随周期一起取前 20 条。 */
const openItems = ref<TrackingAnalyticsOrder[]>([])
const openLoading = ref(false)
let openRequest = 0
async function loadOpenItems() {
  const request = ++openRequest
  openLoading.value = true
  try {
    const result = await fetchTrackingAnalyticsOrders({
      fromDate: fromDate.value, toDate: toDate.value, workshop: workshop.value,
      bucket: 'open', page: 1, size: 20
    })
    if (request === openRequest) openItems.value = result.items
  } catch { if (request === openRequest) openItems.value = [] }
  finally { if (request === openRequest) openLoading.value = false }
}

async function load() {
  loading.value = true; error.value = ''
  drillState.value = null; close()
  try {
    data.value = await fetchTrackingWeeklyReview({
      fromDate: fromDate.value, toDate: toDate.value, workshop: workshop.value
    })
    // 车间下拉取自同一个响应，不再单独发一条请求
    if (!workshop.value) workshopOptions.value = data.value.workshops
    void loadOpenItems()
  } catch (e) { error.value = e instanceof Error ? e.message : '周复盘数据加载失败' }
  finally { loading.value = false }
}

/**
 * 默认打开上周：周复盘是周初复盘**上一周**，不是看当前这一周（这一周还没结束，
 * 数字每天都在变，复盘它只会得到一份当天就过期的结论）。
 */
const initial = weekRange(new Date(), -1)
fromDate.value = initial.from; toDate.value = initial.to
// 与生产分析抽屉一致：每次打开都重新取数，避免拿旧快照当本周结论。
watch(() => props.modelValue, open => { if (open) void load() }, { immediate: true })
</script>

<style scoped>
.tracking-review { padding: 8px 12px 28px; color: #263448; }
.review-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.review-head h1 { margin: 8px 0 6px; font-size: 24px; }
.review-head p { margin: 0; color: #64748b; }
.review-head-actions { display: flex; gap: 8px; }
.weekly-filter, .weekly-note { margin: 14px 0 0; }
.weekly-filter { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; }
.weekly-filter .el-date-editor { width: 160px; }
.weekly-filter .el-select { width: 180px; }
.weekly-note, .weekly-definition { color: #64748b; font-size: 13px; line-height: 1.6; margin: 8px 0 0; }
.weekly-section { margin-top: 18px; padding: 18px; border: 1px solid #e3e8ef; border-radius: 12px; background: #fff; }
.weekly-section h2 { margin: 0 0 12px; font-size: 18px; }
.weekly-section h3 { margin: 18px 0 8px; font-size: 15px; }
.section-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
.section-head h2 { margin: 0; }
.section-head p { margin: 4px 0 0; color: #64748b; font-size: 13px; }
.weekly-link { border: 0; background: none; padding: 0; color: #087c72; cursor: pointer; font: inherit; text-align: left; }
.weekly-link:hover { text-decoration: underline; }
.rate-cell { display: flex; align-items: center; gap: 10px; }
.rate-track { flex: 1; min-width: 80px; height: 8px; border-radius: 6px; background: #e9eef1; overflow: hidden; }
.rate-track b { display: block; height: 100%; background: #db9869; }
.summary-text { margin: 0; font-size: 15px; line-height: 1.8; }
@media (max-width: 700px) { .weekly-filter .el-date-editor, .weekly-filter .el-select { width: calc(50% - 4px); } }
/* 两栏：左复盘内容、右下钻列表 */
.review-layout{display:grid;grid-template-columns:minmax(0,5fr) minmax(0,7fr);gap:18px;align-items:start}
.review-main{min-width:0}
.review-drill-col{min-width:0;align-self:start}
@media(max-width:1000px){.review-layout{grid-template-columns:1fr}}
</style>
