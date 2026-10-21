<template>
  <el-drawer :model-value="modelValue" :title="`样本明细 · ${productName}`" size="min(1100px, 92vw)"
    @update:model-value="$emit('update:modelValue', $event)">
    <el-skeleton v-if="loading" :rows="6" animated />
    <el-alert v-else-if="error" type="error" :closable="false" :title="error" />
    <template v-else-if="data">
      <div class="summary">
        <span class="stat"><b>{{ data.summary.matchedOrders }}</b> 条跟单记录</span>
        <span class="stat"><b>{{ data.summary.plannedAnchorCount }}</b> 条以车间排产日为锚点</span>
        <span class="stat" :class="{ warn: data.summary.applicationAnchorCount > 0 }"><b>{{ data.summary.applicationAnchorCount }}</b> 条退到申请日</span>
        <span class="stat" :class="{ warn: data.summary.noAnchorCount > 0 }"><b>{{ data.summary.noAnchorCount }}</b> 条无锚点</span>
        <span v-if="data.summary.spanFrom" class="stat">跨度 {{ data.summary.spanFrom }} ~ {{ data.summary.spanTo }}</span>
      </div>
      <p class="hint">
        口径与「计算历史样本」完全一致：样本只取本产品的<b>跟单记录</b>（该产品未建跟单的历史任务单不计入），
        绿色＝计入候选；灰色＝被排除（悬停看原因）；黄色＝间隔 0 天（同一天完成），发布前请抽查是否为批量补录。
        命中数为 0 通常意味着该产品还没有跟单记录，或车间名／产品名与氚云生产任务单不一致。
      </p>
      <el-empty v-if="!data.orders.length" :image-size="70" description="没有命中的跟单记录：请核对车间名与产品名，或该产品尚未在进度表里出现过" />
      <el-table v-else :data="data.orders" size="small" stripe border max-height="calc(100vh - 250px)">
        <el-table-column label="任务单号" min-width="150" fixed>
          <template #default="{ row }">
            <div class="order-no">{{ row.orderNo || '—' }}</div>
            <small class="order-sub">{{ row.submittedAt || '' }}</small>
          </template>
        </el-table-column>
        <el-table-column label="锚点" width="104">
          <template #default="{ row }">
            <el-tag size="small" effect="plain" :type="anchorType(row.anchorKind)">{{ anchorText(row.anchorKind) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-for="code in data.stageCodes" :key="code" :label="stageLabels[code] || code" width="96" align="center">
          <template #default="{ row }">
            <span v-if="cell(row, code)" class="cell" :class="cellClass(cell(row, code) as ProductBenchmarkSampleStage)"
              :title="cell(row, code)?.reason || ''">
              {{ cell(row, code)?.days === null ? '—' : `${cell(row, code)?.days} 天` }}
            </span>
            <span v-else class="cell none">—</span>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { fetchProductBenchmarkSamples, type ProductBenchmarkSampleOrder, type ProductBenchmarkSampleStage, type ProductBenchmarkSamples } from '@/api/productionTracking'

const props = defineProps<{
  modelValue: boolean
  taskConfigId: number | null
  productName: string
  /** 节点 code → 中文名，由调用方从基准矩阵带过来（本组件不再自己拉矩阵）。 */
  stageLabels: Record<string, string>
}>()
defineEmits<{ 'update:modelValue': [boolean] }>()

const loading = ref(false)
const error = ref('')
const data = ref<ProductBenchmarkSamples | null>(null)

watch(() => [props.modelValue, props.taskConfigId], async value => {
  if (!value[0] || props.taskConfigId == null) return
  loading.value = true
  error.value = ''
  data.value = null
  try { data.value = await fetchProductBenchmarkSamples(props.taskConfigId) }
  catch (e: any) { error.value = e?.message || '样本明细加载失败' }
  finally { loading.value = false }
}, { immediate: true })

function cell(row: ProductBenchmarkSampleOrder, code: string): ProductBenchmarkSampleStage | null {
  return row.stages.find(s => s.stageCode === code) ?? null
}
function cellClass(s: ProductBenchmarkSampleStage) {
  return s.used ? (s.zeroDay ? 'zero' : 'used') : 'excluded'
}
function anchorText(kind: string) {
  return kind === 'PLANNED' ? '排产日' : kind === 'APPLICATION' ? '申请日兜底' : '无锚点'
}
function anchorType(kind: string): 'success' | 'warning' | 'info' {
  return kind === 'PLANNED' ? 'success' : kind === 'APPLICATION' ? 'warning' : 'info'
}
</script>

<style scoped>
.summary { display: flex; gap: 20px; flex-wrap: wrap; margin-bottom: 10px; }
.stat { font-size: 13px; color: var(--color-text-secondary, #5f6f82); }
.stat b { font-size: 17px; color: var(--color-text-heading, #0f172a); margin-right: 2px; }
.stat.warn b { color: var(--color-warning, #d97706); }
.hint { margin: 0 0 12px; font-size: 12px; line-height: 1.6; color: var(--color-text-secondary, #5f6f82); }
.order-no { font-weight: 600; color: var(--color-text-heading, #0f172a); }
.order-sub { color: var(--color-text-secondary, #94a3b8); font-size: 11px; }
.cell { display: inline-block; min-width: 46px; padding: 1px 6px; border-radius: 6px; font-variant-numeric: tabular-nums; }
.cell.used { background: var(--color-success-bg, #ecfdf5); color: var(--color-success, #059669); }
.cell.zero { background: var(--color-warning-bg, #fffbeb); color: var(--color-warning, #d97706); }
.cell.excluded { background: var(--color-surface-alt, #f1f5f9); color: var(--color-text-secondary, #94a3b8); cursor: help; }
.cell.none { color: var(--color-text-secondary, #cbd5e1); }
</style>
