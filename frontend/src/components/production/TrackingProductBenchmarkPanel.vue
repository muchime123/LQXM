<template>
  <section class="tracking-benchmarks">
    <header>
      <div>
        <h2>产品节点参考工期</h2>
        <p>按自然日设置该产品各跟单节点的参考天数；空值沿用通用参考（右上角「通用参考设置」）。只影响参考日期，不改变氚云工序或跟单节点。</p>
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
      <el-table-column label="通用参考" width="86" align="center">
        <template #default="{ row }">{{ row.workshopDays ?? '未设' }} 天</template>
      </el-table-column>
      <el-table-column label="产品参考天数" min-width="150">
        <template #default="{ row }">
          <el-input-number v-model="row.typicalDays" :min="0" :max="60" :precision="1" :step="0.5" controls-position="right" size="small" placeholder="沿用通用" />
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

    <!-- 合计：把这一列数字还原成「整段」。与画像卡的参考工期同源（都是必经节点生效值相加），
         区别是这里就地按表格当前值算，保存后立刻能看到合计变化，不必等接口回来。 -->
    <p v-if="stageRows.length" class="total-row">
      合计参考工期 <b>{{ totalText }}</b> 天
      <span class="total-note">＝各必经节点生效参考值相加（产品优先、通用兜底）{{ totalNote }}</span>
      <span v-if="missingLabel" class="total-warn">缺 {{ missingLabel }}，未计入合计。</span>
    </p>

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

    <ProductBenchmarkSamplesDrawer v-model="samplesVisible" :task-config-id="taskConfigId" :product-name="productName" :stage-labels="stageLabels"
      @open-order="emit('open-order', $event)" />
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
  /** 按需工序（氧化 / 木箱）：不占链，不计入合计。 */
  optionalStage: boolean
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
const emit = defineEmits<{ changed: []; 'open-order': [number] }>()

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
      optionalStage: stage.optionalStage,
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

/**
 * 合计只算必经节点（按需工序不占链，与分母同源）。
 * 缺值节点按 0 参与求和会把合计说小，所以缺一个就点名一个——宁可显示「3.0 天（缺备料）」，
 * 也不让一个偏小的总数盖住配置缺口。
 */
const requiredRows = computed(() => stageRows.value.filter(row => !row.optionalStage))
const effectiveDays = (row: StageRow) => row.typicalDays ?? row.workshopDays
/** 逐段取整后相加：跟单侧的链式推算每走一个节点就取整到整天，合计必须跟它同源，否则两个页面各说各话。 */
const totalDays = computed(() => requiredRows.value.reduce((sum, row) => {
  const days = effectiveDays(row)
  return days == null ? sum : sum + Math.round(days)
}, 0))
/** 精确和（含小数）：只在有半天值、取整后对不上时用来解释差额。 */
const exactDays = computed(() => requiredRows.value.reduce((sum, row) => sum + (effectiveDays(row) ?? 0), 0))
const missingLabel = computed(() => requiredRows.value
  .filter(row => effectiveDays(row) == null).map(row => row.stageLabel).join('、'))
/** 一个节点都没值时显示「—」：0 会被读成「当天做完」，含义完全相反。 */
const totalText = computed(() => requiredRows.value.some(row => effectiveDays(row) != null)
  ? String(totalDays.value) : '—')
const totalNote = computed(() => Math.abs(exactDays.value - totalDays.value) >= 0.05
  ? `（各段相加 ${exactDays.value.toFixed(1)} 天，每段按整天取整后为 ${totalDays.value} 天，与跟单推算的入库参考日期同源）`
  : '')

async function save(row: StageRow) {
  saving.value = row.stageCode
  try {
    await saveTrackingProductBenchmark({ taskConfigId: props.taskConfigId, stageCode: row.stageCode, typicalDays: row.typicalDays ?? null })
    emit('changed')
    ElMessage.success('本产品参考天数已更新')
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

/** 撤销发布：清掉发布值与来源，参考日期回到通用参考；候选统计保留，下次仍可参考。 */
async function revoke(row: StageRow) {
  try {
    await ElMessageBox.confirm(
      `撤销后「${row.stageLabel}」不再使用本产品参考天数，参考日期回到通用参考；历史候选统计保留。`,
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
      `将把 ${fillableCount.value} 个未发布节点的候选 P50 写成产品参考天数（已发布的节点不变）。发布后即时影响跟单参考日期，请确认样本可信。`,
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
.total-row { margin: 0; font-size: 13px; color: var(--color-text-secondary, #5f6f82); display: flex; flex-wrap: wrap; gap: 6px; align-items: baseline; }
.total-row b { font-size: 18px; color: var(--color-primary, #0f766e); font-variant-numeric: tabular-nums; }
.total-note { font-size: 12px; color: var(--color-text-secondary, #94a3b8); }
.total-warn { font-size: 12px; color: var(--color-warning, #d97706); }
.stage-name { font-weight: 600; color: var(--color-text-heading, #0f172a); }
.stage-interval { display: block; color: var(--color-text-secondary, #94a3b8); font-size: 11px; }
.publish-note { display: block; color: var(--color-text-secondary, #94a3b8); font-size: 11px; }
.copy-hint { margin: 0 0 12px; font-size: 12px; line-height: 1.6; color: var(--color-text-secondary, #5f6f82); }
.dialog-full { width: 100%; }
</style>
