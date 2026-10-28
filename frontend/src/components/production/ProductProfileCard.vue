<template>
  <section class="profile-card">
    <header class="head">
      <h2>
        {{ productName }}
        <el-tag size="small" effect="plain">{{ workshopName }}</el-tag>
        <!-- 产品来源介绍不占版面，收成悬停注释。 -->
        <el-tooltip content="氚云生产任务单「产品名称」下拉字典项 · 名称本地只读" placement="top">
          <el-icon class="info"><InfoFilled /></el-icon>
        </el-tooltip>
      </h2>

      <!-- 三个计数与产品名同行：数字仍是入口（点历史任务/点样本明细），但不各占一张卡。 -->
      <div class="quick">
        <button type="button" class="quick-item" title="点击查看该产品的全部历史生产任务" @click="$emit('show-orders')">
          <b>{{ orderCount }}</b>
          <span>历史任务<small v-if="cancelled">·含 {{ cancelled }} 取消</small></span>
        </button>
        <button type="button" class="quick-item" title="点击查看每张单的每个节点间隔与排除原因" @click="$emit('show-samples')">
          <b>{{ trackedCount }}</b>
          <span>已建跟单</span>
        </button>
        <button type="button" class="quick-item" :title="`${sampleHint}；点击看每段明细`" @click="$emit('show-samples')">
          <b :class="{ warn: sampleReady && sampleCount === 0 }">{{ sampleCount }}</b>
          <span>有效样本段<small v-if="excludedCount">·排除 {{ excludedCount }}</small></span>
        </button>
      </div>

      <!-- 发布进度已在参考工期的小字里（已发布 x/y 节点），头部不再重复一块徽章挤占同行空间。 -->
    </header>

    <!-- 生产工期：产品级唯一一个「整段」数字。参考＝各节点生效参考值相加（与跟单推算同源），
         实测＝跟单记录里真实走完的天数。两个数差得多，说明配置与车间实际节奏脱钩。 -->
    <div class="duration">
      <div class="duration-item">
        <b :class="{ muted: referenceDays == null }">{{ referenceDays ?? '—' }}</b>
        <span>参考工期（天）<small>{{ referenceHint }}</small></span>
      </div>
      <div class="duration-item">
        <b :class="{ muted: actualDays == null }">{{ actualDays ?? '—' }}</b>
        <span>实测工期 P50（天）<small>{{ actualHint }}</small></span>
      </div>
    </div>

    <p class="caliber">
      样本只取<b>跟单记录</b>（{{ trackedCount }} 条），不取全部历史任务；一段＝相邻两个节点之间（或排产日→首节点）的自然日间隔。
    </p>

    <div class="recent">
      <div class="recent-head">
        <h3>最近的历史任务</h3>
        <el-button link type="primary" :disabled="!orderCount" @click="$emit('show-orders')">查看全部 {{ orderCount }} 张</el-button>
      </div>
      <el-skeleton v-if="loading" :rows="3" animated />
      <p v-else-if="error" class="error">{{ error }}</p>
      <el-empty v-else-if="!recent.length" :image-size="46" description="该产品还没有历史任务" />
      <el-table v-else :data="recent" size="small" class="recent-table">
        <el-table-column label="任务单" min-width="132">
          <template #default="{ row }">
            <div class="order-no">{{ row.orderNo || '—' }}</div>
            <small class="order-sub">{{ day(row.applicationDate) }}</small>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="78">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="statusType(row.status)">{{ row.status || '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="跟单" width="96">
          <template #default="{ row }">
            <span v-if="row.tracked" class="tracked">{{ row.doneStages }} / {{ requiredStageCount }} 节点</span>
            <span v-else class="none">未跟单</span>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { InfoFilled } from '@element-plus/icons-vue'
import {
  fetchProductBenchmarkSamples, fetchProductDuration, fetchProductOrderHistory,
  type ProductBenchmarkSamples, type ProductDuration, type ProductOrderHistory, type ProductOrderHistoryRow
} from '@/api/productionTracking'

/**
 * 产品画像卡：把「这个产品做过多少单、跟过多少单、能撑起多少有效样本、发布到什么程度」放在右栏顶部。
 * 数字都是入口——点历史任务看明细，点已建跟单看每单每段间隔。
 */
const props = defineProps<{
  taskConfigId: number
  productName: string
  workshopName: string
  /** 已发布必经节点数 / 必经节点总数（父级已按同源口径算好）。 */
  published: number
  stageTotal: number
  /** 是否可读样本统计（需要 baseline.manage）。没权限时不发请求，指标显示「—」而不是误导性的 0。 */
  sampleEnabled?: boolean
}>()
defineEmits<{ 'show-orders': []; 'show-samples': [] }>()

const loading = ref(false)
const error = ref('')
const history = ref<ProductOrderHistory | null>(null)
const samples = ref<ProductBenchmarkSamples | null>(null)
/** 生产工期（参考累加 + 历史实测）。权限是 any-of（baseline.manage / template.manage），
 *  所以不跟着 sampleEnabled 走——能进本页就一定能读。 */
const duration = ref<ProductDuration | null>(null)

const orderCount = computed(() => history.value?.summary.total ?? 0)
const trackedCount = computed(() => history.value?.summary.tracked ?? 0)
const cancelled = computed(() => history.value?.summary.cancelled ?? 0)
const requiredStageCount = computed(() => history.value?.requiredStageCount ?? props.stageTotal)
const recent = computed<ProductOrderHistoryRow[]>(() => history.value?.orders ?? [])

/** 有效样本段＝各单里 used=true 的段之和；排除数同样按段计，与样本抽屉的绿/灰色块一一对应。 */
const sampleReady = computed(() => props.sampleEnabled !== false && samples.value != null)
const sampleCount = computed(() => sampleReady.value
  ? (samples.value?.orders ?? []).reduce((sum, order) => sum + order.stages.filter(s => s.used).length, 0)
  : '—')
const excludedCount = computed(() => sampleReady.value
  ? (samples.value?.orders ?? []).reduce((sum, order) => sum + order.stages.filter(s => !s.used).length, 0)
  : 0)
const sampleHint = computed(() => props.sampleEnabled === false
  ? '读取样本统计需要 manufacturing.baseline.manage 权限'
  : samples.value
    ? `来自 ${samples.value.summary.matchedOrders} 条跟单记录；点「已建跟单」看每段明细`
    : '样本统计加载失败或暂无跟单记录')

/**
 * 参考工期：主数字用 chainDays（逐段取整后相加，与跟单推算的入库参考日期同源）。
 * 全部节点都没配值时显示「—」而不是 0——0 会被读成「当天做完」，真实含义是「一个参考值都没有」。
 */
const referenceDays = computed(() => {
  const value = duration.value
  if (!value) return null
  if (value.stageCount > 0 && value.missingStages.length >= value.stageCount) return null
  return String(value.chainDays)
})
const referenceHint = computed(() => {
  const value = duration.value
  if (!value) return '加载中'
  const span = `排产 → ${value.lastStageLabel}`
  if (value.missingStages.length >= value.stageCount) return `${span} · 节点参考值一个都没配`
  const parts = [span, `已发布 ${value.productStageCount}/${value.stageCount} 节点`]
  if (value.missingStages.length) parts.push(`缺 ${value.missingStages.join('、')}`)
  // 有半天值时取整会差一点，说清楚为什么合计和主数字不是一个值，免得被当成算错。
  if (Math.abs(value.referenceDays - value.chainDays) >= 0.05) parts.push(`各段合计 ${value.referenceDays.toFixed(1)} 天按整天取整`)
  return parts.join(' · ')
})
const actualDays = computed(() => duration.value?.actualP50Days == null ? null : duration.value.actualP50Days.toFixed(1))
/** 实测的小字要说清「几个单撑起这个数」——样本少的 P50 不该被当成结论。 */
const actualHint = computed(() => {
  const value = duration.value
  if (!value) return '加载中'
  const parts = [`${value.actualSampleCount} 单走完`]
  if (value.actualP75Days != null) parts.push(`P75 ${value.actualP75Days.toFixed(1)}`)
  if (value.openOrderCount) parts.push(`${value.openOrderCount} 单未走完`)
  return parts.join(' · ')
})

watch(() => props.taskConfigId, async id => {
  if (id == null) return
  loading.value = true
  error.value = ''
  history.value = null
  samples.value = null
  duration.value = null
  try {
    // 三个请求互不牵连：样本统计失败不能把「历史任务」也拖成空白，工期同理。
    const [historyResult, samplesResult, durationResult] = await Promise.allSettled([
      fetchProductOrderHistory(id, '', 5),
      props.sampleEnabled === false ? Promise.reject(new Error('no-permission')) : fetchProductBenchmarkSamples(id),
      fetchProductDuration(id)
    ])
    if (historyResult.status === 'fulfilled') history.value = historyResult.value
    else error.value = historyResult.reason?.message || '历史任务加载失败'
    if (samplesResult.status === 'fulfilled') samples.value = samplesResult.value
    if (durationResult.status === 'fulfilled') duration.value = durationResult.value
  } finally { loading.value = false }
}, { immediate: true })

function day(value: string | null) { return value ? String(value).slice(0, 10) : '—' }
function statusType(value: string | null): 'success' | 'warning' | 'danger' | 'info' {
  if (value === '进行中') return 'warning'
  if (value === '已取消') return 'danger'
  if (value === '已生效') return 'success'
  return 'info'
}
</script>

<style scoped>
.profile-card { background: linear-gradient(135deg, var(--color-primary-bg-light, #f0fdfa) 0%, var(--color-surface, #fff) 55%); border: 1px solid var(--color-primary-border, #a7f3d0); border-radius: var(--radius-2xl, 12px); padding: 16px; display: grid; gap: 12px; }
.head { display: flex; align-items: baseline; gap: 4px 14px; flex-wrap: wrap; }
.head h2 { margin: 0; display: inline-flex; align-items: center; gap: 8px; white-space: nowrap; font-size: 18px; color: var(--color-text-heading, #0f172a); }
.head .info { font-size: 14px; color: var(--color-text-secondary, #94a3b8); cursor: help; }

.quick { display: flex; align-items: baseline; gap: 2px; flex-wrap: wrap; min-width: 0; }
.quick-item { display: inline-flex; align-items: baseline; gap: 5px; padding: 2px 9px; border: none; background: none; font: inherit; cursor: pointer; border-radius: 6px; transition: background .15s; white-space: nowrap; }
.quick-item + .quick-item { border-left: 1px solid var(--color-border, #e2e8f0); border-top-left-radius: 0; border-bottom-left-radius: 0; }
.quick-item:hover { background: rgba(15, 118, 110, .08); }
.quick-item b { font-size: 16px; line-height: 1.2; color: var(--color-text-heading, #0f172a); font-variant-numeric: tabular-nums; }
.quick-item b.warn { color: var(--color-warning, #d97706); }
.quick-item span { font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.quick-item small { font-size: 11px; color: var(--color-text-secondary, #94a3b8); }

/* 工期是产品画像里唯一一个「整段」指标，给一档比 metrics 更重的底色，避免和三个计数卡混为一谈。 */
.duration { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 16px; padding: 12px; border-radius: 10px; border: 1px solid var(--color-primary-border, #a7f3d0); background: var(--color-primary-bg-light, #f0fdfa); }
.duration-item { display: grid; gap: 2px; min-width: 0; }
.duration-item b { font-size: 24px; line-height: 1.1; color: var(--color-primary, #0f766e); font-variant-numeric: tabular-nums; }
.duration-item b.muted { color: var(--color-text-secondary, #94a3b8); }
.duration-item span { font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.duration-item small { display: block; margin-top: 2px; font-size: 11px; line-height: 1.5; color: var(--color-text-secondary, #94a3b8); }

.caliber { margin: 0; font-size: 12px; line-height: 1.6; color: var(--color-text-secondary, #5f6f82); }

.recent { display: grid; gap: 6px; }
.recent-head { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.recent-head h3 { margin: 0; font-size: 13px; color: var(--color-text-heading, #0f172a); }
.recent-table :deep(th.el-table__cell) { background: transparent; font-weight: 600; }
.order-no { font-weight: 600; color: var(--color-text-heading, #0f172a); }
.order-sub { color: var(--color-text-secondary, #94a3b8); font-size: 11px; }
.tracked { font-size: 12px; color: var(--color-success, #059669); font-variant-numeric: tabular-nums; }
.none { font-size: 12px; color: var(--color-text-secondary, #94a3b8); }
.error { margin: 0; font-size: 12px; color: var(--color-danger, #dc2626); }

@media (max-width: 640px) {
  .quick { gap: 0; }
  .quick-item { padding: 2px 8px; }
  .duration { grid-template-columns: 1fr; }
}
</style>
