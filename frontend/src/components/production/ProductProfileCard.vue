<template>
  <section class="profile-card">
    <header class="head">
      <div class="title">
        <h2>
          {{ productName }}
          <el-tag size="small" effect="plain">{{ workshopName }}</el-tag>
        </h2>
        <p>氚云生产任务单「产品名称」下拉字典项 · 名称本地只读</p>
      </div>
      <el-tag size="small" :type="publishedAll ? 'success' : 'info'" effect="plain" class="publish-tag">
        已发布 {{ published }} / {{ stageTotal }} 节点
      </el-tag>
    </header>

    <div class="metrics">
      <button type="button" class="metric" title="点击查看该产品的全部历史生产任务" @click="$emit('show-orders')">
        <b>{{ orderCount }}</b>
        <span>历史任务<small v-if="cancelled"> · 含 {{ cancelled }} 张已取消</small></span>
      </button>
      <button type="button" class="metric" title="点击查看每张单的每个节点间隔与排除原因" @click="$emit('show-samples')">
        <b>{{ trackedCount }}</b>
        <span>已建跟单</span>
      </button>
      <div class="metric static" :title="sampleHint">
        <b :class="{ warn: sampleReady && sampleCount === 0 }">{{ sampleCount }}</b>
        <span>有效样本段<small v-if="excludedCount"> · 排除 {{ excludedCount }}</small></span>
      </div>
    </div>

    <p class="caliber">
      样本只取<b>跟单记录</b>（{{ trackedCount }} 条），不取全部历史任务；一段＝相邻两个节点之间（或排产日→首节点）的自然日间隔。
      <template v-if="applicationAnchors">其中 {{ applicationAnchors }} 段因缺排产日退到申请日，参考价值低于排产锚点。</template>
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
import {
  fetchProductBenchmarkSamples, fetchProductOrderHistory,
  type ProductBenchmarkSamples, type ProductOrderHistory, type ProductOrderHistoryRow
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

const orderCount = computed(() => history.value?.summary.total ?? 0)
const trackedCount = computed(() => history.value?.summary.tracked ?? 0)
const cancelled = computed(() => history.value?.summary.cancelled ?? 0)
const requiredStageCount = computed(() => history.value?.requiredStageCount ?? props.stageTotal)
const recent = computed<ProductOrderHistoryRow[]>(() => history.value?.orders ?? [])
const publishedAll = computed(() => props.stageTotal > 0 && props.published >= props.stageTotal)

/** 有效样本段＝各单里 used=true 的段之和；排除数同样按段计，与样本抽屉的绿/灰色块一一对应。 */
const sampleReady = computed(() => props.sampleEnabled !== false && samples.value != null)
const sampleCount = computed(() => sampleReady.value
  ? (samples.value?.orders ?? []).reduce((sum, order) => sum + order.stages.filter(s => s.used).length, 0)
  : '—')
const excludedCount = computed(() => sampleReady.value
  ? (samples.value?.orders ?? []).reduce((sum, order) => sum + order.stages.filter(s => !s.used).length, 0)
  : 0)
const applicationAnchors = computed(() => samples.value?.summary.applicationAnchorCount ?? 0)
const sampleHint = computed(() => props.sampleEnabled === false
  ? '读取样本统计需要 manufacturing.baseline.manage 权限'
  : samples.value
    ? `来自 ${samples.value.summary.matchedOrders} 条跟单记录；点「已建跟单」看每段明细`
    : '样本统计加载失败或暂无跟单记录')

watch(() => props.taskConfigId, async id => {
  if (id == null) return
  loading.value = true
  error.value = ''
  history.value = null
  samples.value = null
  try {
    // 两次请求互不牵连：样本统计失败不能把「历史任务」也拖成空白。
    const [historyResult, samplesResult] = await Promise.allSettled([
      fetchProductOrderHistory(id, '', 5),
      props.sampleEnabled === false ? Promise.reject(new Error('no-permission')) : fetchProductBenchmarkSamples(id)
    ])
    if (historyResult.status === 'fulfilled') history.value = historyResult.value
    else error.value = historyResult.reason?.message || '历史任务加载失败'
    if (samplesResult.status === 'fulfilled') samples.value = samplesResult.value
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
.head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.head h2 { margin: 0 0 4px; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 19px; color: var(--color-text-heading, #0f172a); }
.head p { margin: 0; font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.publish-tag { flex: none; }

.metrics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.metric { display: grid; gap: 2px; text-align: left; padding: 10px 12px; border-radius: 10px; border: 1px solid var(--color-border, #e2e8f0); background: var(--color-surface, #fff); font: inherit; }
.metric:not(.static) { cursor: pointer; transition: border-color .15s, box-shadow .15s; }
.metric:not(.static):hover { border-color: var(--color-primary, #0f766e); box-shadow: 0 2px 8px rgba(15, 118, 110, .12); }
.metric b { font-size: 22px; line-height: 1.1; color: var(--color-text-heading, #0f172a); font-variant-numeric: tabular-nums; }
.metric b.warn { color: var(--color-warning, #d97706); }
.metric span { font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.metric small { font-size: 11px; color: var(--color-text-secondary, #94a3b8); }

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
  .metrics { grid-template-columns: 1fr; }
}
</style>
