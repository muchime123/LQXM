<template>
  <section class="tracking-benchmarks">
    <header>
      <div>
        <h2>节点参考间隔</h2>
        <p>按自然日设置该产品各跟单节点的参考间隔；空值沿用车间基准。只影响参考日期，不改变氚云工序或跟单节点。</p>
      </div>
      <div class="actions">
        <el-button :loading="calculating" @click="refreshCandidates">计算历史样本</el-button>
        <el-button :loading="applying" :disabled="!fillableCount" @click="applyP50">按 P50 填充</el-button>
        <el-button @click="openSamples">样本明细</el-button>
        <el-button :disabled="!peers.length" @click="openCopy">复制其它产品</el-button>
      </div>
    </header>
    <p class="hint">
      候选来自本产品自身的跟单记录（P50 / P75 仅供审核）；点击单行保存、或用「按 P50 填充」批量发布后才参与跟单参考日期。
      <template v-if="!fillableCount">当前没有可批量填充的节点（需候选样本 ≥ 8 段、零天占比不过半、且尚未发布；零天多的节点请人工看样本后单行发布）。</template>
    </p>
    <el-table v-if="stageRows.length" :data="stageRows" stripe size="small">
      <el-table-column label="跟单节点" min-width="110">
        <template #default="{ row }">
          <div class="stage-name">{{ row.stageLabel }}</div>
          <small class="stage-interval">{{ row.intervalLabel }}</small>
        </template>
      </el-table-column>
      <el-table-column label="车间基准" width="86" align="center">
        <template #default="{ row }">{{ row.workshopDays ?? '未设' }} 天</template>
      </el-table-column>
      <el-table-column label="本产品间隔（天）" min-width="150">
        <template #default="{ row }">
          <el-input-number v-model="row.typicalDays" :min="0" :max="60" :precision="1" :step="0.5" controls-position="right" size="small" placeholder="沿用车间" />
          <small v-if="row.publishedSource" class="publish-note">{{ publishText(row) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="历史候选" min-width="170">
        <template #default="{ row }">
          <div>{{ row.sampleCount ? `P50 ${row.p50Days} / P75 ${row.p75Days} · ${row.sampleCount} 段` : '暂无可信候选' }}</div>
          <small v-if="row.applicationAnchorCount">申请日兜底 {{ row.applicationAnchorCount }} 段</small>
          <small v-if="row.zeroDayCount"> · 零天 {{ row.zeroDayCount }}</small>
          <small v-if="row.excludedCount"> · 排除 {{ row.excludedCount }}</small>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="116">
        <template #default="{ row }">
          <el-button link type="primary" :loading="saving === row.stageCode" @click="save(row)">保存</el-button>
          <el-button v-if="row.publishedSource" link type="danger" :loading="revoking === row.stageCode" @click="revoke(row)">撤销</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-else :image-size="52" description="该产品所属车间类别没有可配置的跟单节点" />

    <!-- 复制：同系列产品常共用一套节奏，逐个维护太慢；只补未发布节点，不覆盖人工审核值。 -->
    <el-dialog v-model="copyVisible" title="从同车间其它产品复制" width="min(460px, calc(100vw - 32px))">
      <p class="copy-hint">把所选产品已发布的节点间隔复制到「{{ productName }}」，只补尚未发布的节点；已发布的人工审核值不会被覆盖。</p>
      <el-select v-model="copySourceId" placeholder="选择源产品" class="dialog-full">
        <el-option v-for="p in peers" :key="p.id" :label="p.configName" :value="p.id" />
      </el-select>
      <template #footer>
        <el-button @click="copyVisible = false">取消</el-button>
        <el-button type="primary" :loading="copying" :disabled="copySourceId == null" @click="copyFrom">复制</el-button>
      </template>
    </el-dialog>

    <ProductBenchmarkSamplesDrawer v-model="samplesVisible" :task-config-id="taskConfigId" :product-name="productName" :stage-labels="stageLabels" />
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  applyTrackingProductBenchmarkP50, copyTrackingProductBenchmarks, refreshTrackingProductBenchmarkCandidates,
  saveTrackingProductBenchmark, type TrackingBenchmarkWorkshop, type TrackingProductBenchmarkRow
} from '@/api/productionTracking'
import ProductBenchmarkSamplesDrawer from './ProductBenchmarkSamplesDrawer.vue'

interface StageRow {
  stageCode: string
  stageLabel: string
  intervalLabel: string
  workshopDays: number | null
  typicalDays: number | null
  /** 发布来源：MANUAL 人工审核 / P50 批量填充 / COPY 从同车间产品复制 / null 未发布（含已撤销）。 */
  publishedSource: 'MANUAL' | 'P50' | 'COPY' | null
  publishedAt: string | null
  sampleCount: number
  p50Days: number | null
  p75Days: number | null
  excludedCount: number
  applicationAnchorCount: number
  zeroDayCount: number
}

/** 产品与车间矩阵由父组件（产品工期面板）提供，本组件只负责按当前产品渲染与保存。 */
const props = defineProps<{
  taskConfigId: number
  productName: string
  workshopName: string
  matrix: TrackingBenchmarkWorkshop[]
  rows: TrackingProductBenchmarkRow[]
  /** 同车间其它产品：作为「复制间隔」的来源候选。 */
  peers: { id: number; configName: string }[]
}>()
const emit = defineEmits<{ changed: [] }>()

const saving = ref<string | null>(null)
const calculating = ref(false)
const applying = ref(false)
/** 样本明细：把候选的每一段摊开，发布前先抽查「零天是不是批量补录」。 */
const samplesVisible = ref(false)
const revoking = ref<string | null>(null)
const copyVisible = ref(false)
const copySourceId = ref<number | null>(null)
const copying = ref(false)

/** 车间类别与后端一致：车间名含「封口」即封口机节点序列。 */
const workshopKind = computed(() => (props.workshopName ?? '').includes('封口') ? 'SEALER' : 'MACHINE')

const stageRows = computed<StageRow[]>(() => {
  const workshop = props.matrix.find(item => item.workshopKind === workshopKind.value)
  return (workshop?.stages ?? []).map(stage => {
    const value = props.rows.find(item => item.stageCode === stage.stageCode)
    return {
      stageCode: stage.stageCode,
      stageLabel: stage.stageLabel,
      intervalLabel: stage.intervalLabel,
      workshopDays: stage.typicalDays,
      typicalDays: value?.typicalDays ?? null,
      publishedSource: value?.publishedSource ?? null,
      publishedAt: value?.publishedAt ?? null,
      sampleCount: value?.sampleCount ?? 0,
      p50Days: value?.p50Days ?? null,
      p75Days: value?.p75Days ?? null,
      excludedCount: value?.excludedCount ?? 0,
      applicationAnchorCount: value?.applicationAnchorCount ?? 0,
      zeroDayCount: value?.zeroDayCount ?? 0
    }
  })
})

/** 节点 code → 中文名，交给样本明细抽屉复用，不让它再拉一次矩阵。 */
const stageLabels = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const s of stageRows.value) map[s.stageCode] = s.stageLabel
  return map
})

/** 可批量填充＝尚未发布且候选样本达标（后端同口径：样本 ≥ 8 段、零天占比 < 50%，2026-10-21 收紧）。 */
const fillableCount = computed(() => stageRows.value.filter(row =>
  row.typicalDays == null && row.p50Days != null && row.sampleCount >= 8
  && row.zeroDayCount * 2 < row.sampleCount).length)

async function save(row: StageRow) {
  saving.value = row.stageCode
  try {
    await saveTrackingProductBenchmark({ taskConfigId: props.taskConfigId, stageCode: row.stageCode, typicalDays: row.typicalDays ?? null })
    emit('changed')
    ElMessage.success('本产品节点参考间隔已更新')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败')
  } finally { saving.value = null }
}

/**
 * 打开抽屉/弹窗前先让主页面失焦：否则产品行里那个输入框的 focus 高亮会一直亮在遮罩底下，
 * 看起来像「莫名高亮」。
 */
function releaseFocus() { (document.activeElement as HTMLElement | null)?.blur() }
function openSamples() { releaseFocus(); samplesVisible.value = true }
function openCopy() { releaseFocus(); copyVisible.value = true }
/** 供外层从「产品表 · 跟单记录」那个数字直接打开同一个样本抽屉，避免两处各写一套明细。 */
defineExpose({ openSamples })

/** 发布来源标注：让「这条是人定的还是机器填的」一眼可见。 */
function publishText(row: StageRow) {
  const source = row.publishedSource === 'P50' ? 'P50 填充' : row.publishedSource === 'COPY' ? '复制' : '人工发布'
  const at = row.publishedAt ? String(row.publishedAt).slice(5, 10) : ''
  return at ? `${source} · ${at}` : source
}

/** 撤销发布：清掉发布值与来源，参考日期回到车间基准；候选统计保留，下次仍可参考。 */
async function revoke(row: StageRow) {
  try {
    await ElMessageBox.confirm(
      `撤销后「${row.stageLabel}」不再使用本产品间隔，参考日期回到车间基准；历史候选统计保留。`,
      '撤销发布',
      { type: 'warning', confirmButtonText: '撤销发布', cancelButtonText: '取消' }
    )
  } catch { return }
  revoking.value = row.stageCode
  try {
    await saveTrackingProductBenchmark({ taskConfigId: props.taskConfigId, stageCode: row.stageCode, typicalDays: null })
    emit('changed')
    ElMessage.success(`已撤销「${row.stageLabel}」的发布值`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '撤销失败')
  } finally { revoking.value = null }
}

async function copyFrom() {
  if (copySourceId.value == null) return
  copying.value = true
  try {
    const result = await copyTrackingProductBenchmarks(props.taskConfigId, copySourceId.value)
    copyVisible.value = false
    emit('changed')
    ElMessage.success(result.copied.length
      ? `已复制 ${result.copied.length} 个节点${result.skipped ? `，跳过 ${result.skipped} 个（已发布或无值）` : ''}`
      : '源产品没有可复制的已发布节点')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '复制失败')
  } finally { copying.value = false }
}

async function refreshCandidates() {
  calculating.value = true
  try {
    await refreshTrackingProductBenchmarkCandidates(props.taskConfigId)
    emit('changed')
    ElMessage.success('历史候选已更新，已发布的参考值未改变')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '计算历史样本失败')
  } finally { calculating.value = false }
}

async function applyP50() {
  try {
    await ElMessageBox.confirm(
      `将把 ${fillableCount.value} 个未发布节点的候选 P50 写成参考间隔（已发布的节点不变）。发布后即时影响跟单参考日期，请确认样本可信。`,
      '按 P50 批量发布', { type: 'warning' })
  } catch { return }
  applying.value = true
  try {
    const rows = await applyTrackingProductBenchmarkP50(props.taskConfigId)
    emit('changed')
    ElMessage.success(rows.length ? `已发布 ${rows.length} 个节点` : '没有可填充的节点')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '批量发布失败')
  } finally { applying.value = false }
}
</script>

<style scoped>
.tracking-benchmarks { padding: 16px; border: 1px solid var(--color-border, #e2e8f0); border-radius: 12px; background: var(--color-surface, #fff); display: grid; gap: 14px; }
.tracking-benchmarks header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.tracking-benchmarks header h2 { margin: 0 0 4px; font-size: 17px; }
.tracking-benchmarks header p { margin: 0; color: var(--color-text-secondary, #64748b); font-size: 12px; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
.hint { margin: 0; color: var(--color-text-secondary, #64748b); font-size: 12px; line-height: 1.6; }
.stage-name { font-weight: 600; color: var(--color-text-heading, #0f172a); }
.stage-interval { display: block; color: var(--color-text-secondary, #94a3b8); font-size: 11px; }
.publish-note { display: block; color: var(--color-text-secondary, #94a3b8); font-size: 11px; }
.copy-hint { margin: 0 0 12px; font-size: 12px; line-height: 1.6; color: var(--color-text-secondary, #5f6f82); }
.dialog-full { width: 100%; }
</style>
