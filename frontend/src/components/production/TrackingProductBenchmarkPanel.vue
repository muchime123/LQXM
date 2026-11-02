<template>
  <section class="tracking-benchmarks">
    <header>
      <div>
        <h2>{{ productName }} · 产品节点与参考工期
          <el-tooltip placement="top" :show-after="200">
            <template #content>
              按自然日设置该产品各跟单节点的参考天数；空值沿用通用参考。<br>
              同一行可配置本产品采用的节点、顺序、前置节点与参考天数；制程版本发布后用于新任务，氚云工序不受影响。<br>
              候选来自本产品的跟单记录，P50 / P75 仅供审核；保存或按 P50 填充后才参与参考日期。<br>
              批量填充要求样本至少 8 段、零天占比低于一半，且节点尚未发布。
            </template>
            <el-icon class="help-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </h2>
      </div>
      <div class="actions">
        <el-button :loading="calculating" @click="refreshCandidates">计算历史样本</el-button>
        <el-button :loading="applying" :disabled="!fillableCount" @click="applyP50">按 P50 填充</el-button>
        <el-button @click="openSamples">样本明细</el-button>
        <el-button :disabled="!peers.length" @click="openCopy">复制其它产品</el-button>
      </div>
    </header>
    <TrackingProductProcessPanel :key="taskConfigId" :task-config-id="taskConfigId" :workshop-name="workshopName" :same-name-count="sameNameCount" @changed="emit('changed')" v-slot="{ nodes, canManage, toggleNode, moveNode, canMove }">
      <div class="node-toolbar">
        <span>本产品使用 {{ nodes.filter(node => node.selected).length }} 个节点</span>
        <el-button v-if="canManage" size="small" @click="manageNodesVisible = true">管理节点</el-button>
      </div>
      <div class="benchmark-scroll">
      <el-table v-if="nodes.some(node => node.selected)" :data="orderedStageRows(nodes)" stripe size="small" class="benchmark-table" table-layout="fixed">
      <el-table-column label="跟单节点" min-width="210">
        <template #default="{ row }">
          <div class="stage-name">{{ row.stageLabel }}<el-tag v-if="row.optionalStage" size="small" type="info" class="optional-tag">按需</el-tag></div>
          <small class="stage-interval">{{ row.intervalLabel }}</small>
        </template>
      </el-table-column>
      <el-table-column label="本产品制程" width="126" align="center">
        <template #default="{ row }">
          <el-button v-for="node in nodes.filter(node => node.code === row.stageCode)" :key="node.code"
            link type="danger" :disabled="!canManage" @click="toggleNode(node.code)">
            移除节点
          </el-button>
        </template>
      </el-table-column>
      <el-table-column label="顺序" width="110" align="center">
        <template #default="{ row }">
          <div v-for="node in nodes.filter(node => node.code === row.stageCode && node.selected)" :key="node.code" class="order-actions">
            <el-button size="small" text :disabled="!canManage || !canMove(node.code, -1)" aria-label="上移节点" @click="moveNode(node.code, -1)">↑</el-button>
            <span>{{ node.sortNo }}</span>
            <el-button size="small" text :disabled="!canManage || !canMove(node.code, 1)" aria-label="下移节点" @click="moveNode(node.code, 1)">↓</el-button>
          </div>
          <span v-if="!nodes.some(node => node.code === row.stageCode && node.selected)" class="days-unset">—</span>
        </template>
      </el-table-column>
      <el-table-column label="前置节点" min-width="220">
        <template #default="{ row }">
          <el-select v-for="node in nodes.filter(node => node.code === row.stageCode && node.selected)" :key="node.code" v-model="node.predecessors" multiple collapse-tags clearable placeholder="无前置节点" size="small" :disabled="!canManage" class="predecessor-select">
            <el-option v-for="candidate in nodes.filter(item => item.selected && item.sortNo < node.sortNo)" :key="candidate.code" :label="candidate.label" :value="candidate.code" />
          </el-select>
          <span v-if="!nodes.some(node => node.code === row.stageCode && node.selected)" class="days-unset">加入后可设置</span>
        </template>
      </el-table-column>
      <el-table-column label="通用参考" width="100" align="center">
        <template #default="{ row }">
          <span v-if="row.workshopDays != null" class="days-value">{{ row.workshopDays }} 天</span>
          <span v-else class="days-unset">未设</span>
        </template>
      </el-table-column>
      <el-table-column label="产品参考天数" width="180">
        <template #default="{ row }">
          <el-input-number v-model="row.typicalDays" :min="0" :max="60" :precision="1" :step="0.5" controls-position="right" size="small" placeholder="沿用通用" class="days-input" />
          <small v-if="row.publishedSource" class="publish-note">{{ publishText(row) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="历史候选" min-width="230">
        <template #default="{ row }">
          <div class="candidate-main">{{ row.sampleCount ? `P50 ${row.p50Days} / P75 ${row.p75Days} · ${row.sampleCount} 段` : '暂无可信候选' }}</div>
          <small v-if="row.zeroDayCount || row.excludedCount" class="candidate-sub">
            {{ [row.zeroDayCount && `零天 ${row.zeroDayCount}`, row.excludedCount && `排除 ${row.excludedCount}`].filter(Boolean).join(' · ') }}
          </small>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="156" align="center" class-name="col-op">
        <template #default="{ row }">
          <el-button size="small" type="primary" plain :loading="saving === row.stageCode" @click="save(row)">保存</el-button>
          <el-button v-if="row.publishedSource" size="small" text type="danger" :loading="revoking === row.stageCode" @click="revoke(row)">撤销</el-button>
        </template>
      </el-table-column>
      </el-table>
      <el-empty v-else :image-size="52" description="本产品尚未选择跟单节点，请点击“管理节点”添加" />
      </div>
      <el-dialog v-model="manageNodesVisible" title="管理本产品跟单节点" width="min(520px, calc(100vw - 32px))" append-to-body>
        <p class="manage-hint">从全局节点字典选择；未采用的节点不会出现在本产品的参考工期表中。</p>
        <div v-for="node in nodes" :key="node.code" class="manage-node">
          <span>{{ node.label }} <small>{{ node.code }}</small></span>
          <el-button size="small" :type="node.selected ? 'danger' : 'primary'" plain @click="toggleNode(node.code)">{{ node.selected ? '移除' : '加入' }}</el-button>
        </div>
        <template #footer><el-button @click="manageNodesVisible = false">完成</el-button></template>
      </el-dialog>

    <!-- 合计：把这一列数字还原成「整段」。与画像卡的参考工期同源（都是必经节点生效值相加），
         区别是这里就地按表格当前值算，保存后立刻能看到合计变化，不必等接口回来。 -->
      <p v-if="nodes.length" class="total-row">
      已选节点参考工期 <b>{{ totalSummary(nodes).text }}</b> 天
      <span class="total-note">按已选必经节点试算（产品参考优先，通用参考兜底）{{ totalSummary(nodes).note }}</span>
      <span v-if="totalSummary(nodes).missing" class="total-warn">缺 {{ totalSummary(nodes).missing }}，未计入合计。</span>
      </p>

    </TrackingProductProcessPanel>

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
import { QuestionFilled } from '@element-plus/icons-vue'
import {
  applyTrackingProductBenchmarkP50, copyTrackingProductBenchmarks, refreshTrackingProductBenchmarkCandidates,
  saveTrackingProductBenchmark, type TrackingBenchmarkWorkshop, type TrackingProductBenchmarkRow
} from '@/api/productionTracking'
import ProductBenchmarkSamplesDrawer from './ProductBenchmarkSamplesDrawer.vue'
import TrackingProductProcessPanel from './TrackingProductProcessPanel.vue'

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
  zeroDayCount: number
}

/** 产品与车间矩阵由父组件（产品工期面板）提供，本组件只负责按当前产品渲染与保存。 */
const props = defineProps<{
  taskConfigId: number
  productName: string
  workshopName: string
  sameNameCount: number
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
const manageNodesVisible = ref(false)

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
      zeroDayCount: value?.zeroDayCount ?? 0
    }
  })
})

function orderedStageRows(nodes: { code: string; label: string; conditional: boolean; selected: boolean; sortNo: number }[]) {
  const byCode = new Map(nodes.map(node => [node.code, node]))
  const rows = [...stageRows.value]
  const existing = new Set(rows.map(row => row.stageCode))
  for (const node of nodes) {
    if (existing.has(node.code)) continue
    const candidate = props.rows.find(row => row.stageCode === node.code)
    rows.push({ stageCode: node.code, stageLabel: node.label, intervalLabel: '该车间暂无通用参考',
      optionalStage: node.conditional, workshopDays: null, typicalDays: candidate?.typicalDays ?? null,
      publishedSource: candidate?.publishedSource ?? null, publishedAt: candidate?.publishedAt ?? null,
      sampleCount: candidate?.sampleCount ?? 0, p50Days: candidate?.p50Days ?? null,
      p75Days: candidate?.p75Days ?? null, excludedCount: candidate?.excludedCount ?? 0,
      zeroDayCount: candidate?.zeroDayCount ?? 0 })
  }
  return rows.filter(row => byCode.get(row.stageCode)?.selected).sort((a, b) => {
    const aNode = byCode.get(a.stageCode)
    const bNode = byCode.get(b.stageCode)
    if (aNode?.selected !== bNode?.selected) return aNode?.selected ? -1 : 1
    if (aNode?.selected && bNode?.selected) return aNode.sortNo - bNode.sortNo
    return 0
  })
}

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

const effectiveDays = (row: StageRow) => row.typicalDays ?? row.workshopDays
function totalSummary(nodes: { code: string; label: string; conditional: boolean; selected: boolean; sortNo: number }[]) {
  const selectedCodes = new Set(nodes.filter(node => node.selected).map(node => node.code))
  const required = orderedStageRows(nodes).filter(row => selectedCodes.has(row.stageCode) && !row.optionalStage)
  const missing = required.filter(row => effectiveDays(row) == null).map(row => row.stageLabel).join('、')
  const days = required.reduce((sum, row) => sum + Math.round(effectiveDays(row) ?? 0), 0)
  const exact = required.reduce((sum, row) => sum + (effectiveDays(row) ?? 0), 0)
  return {
    text: required.some(row => effectiveDays(row) != null) ? String(days) : '—',
    missing,
    note: Math.abs(exact - days) >= 0.05 ? `；各段相加 ${exact.toFixed(1)} 天，逐段取整后为 ${days} 天` : '',
  }
}

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
.node-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 10px 0; color: var(--color-text-secondary); font-size: 13px; }
.manage-hint { margin: 0 0 12px; color: var(--color-text-secondary); font-size: 13px; }
.manage-node { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 4px; border-bottom: 1px solid var(--color-border, #ebeef5); }
.manage-node small { margin-left: 8px; color: var(--color-text-secondary); }
.tracking-benchmarks { padding: 16px; border: 1px solid var(--color-border, #e2e8f0); border-radius: 12px; background: var(--color-surface, #fff); display: grid; gap: 14px; }
.tracking-benchmarks header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.tracking-benchmarks header h2 { margin: 0 0 4px; font-size: 17px; }
.tracking-benchmarks header p { margin: 0; color: var(--color-text-secondary, #64748b); font-size: 12px; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; }
.help-icon { margin-left: 4px; color: var(--color-text-secondary, #94a3b8); font-size: 14px; vertical-align: -1px; cursor: help; }
.total-row { margin: 0; font-size: 13px; color: var(--color-text-secondary, #5f6f82); display: flex; flex-wrap: wrap; gap: 6px; align-items: baseline; }
.total-row b { font-size: 18px; color: var(--color-primary, #0f766e); font-variant-numeric: tabular-nums; }
.total-note { font-size: 12px; color: var(--color-text-secondary, #94a3b8); }
.total-warn { font-size: 12px; color: var(--color-warning, #d97706); }
.stage-name { font-weight: 600; color: var(--color-text-heading, #0f172a); display: flex; align-items: center; gap: 6px; }
.optional-tag { flex: none; transform: scale(0.9); }
.stage-interval { display: block; color: var(--color-text-secondary, #94a3b8); font-size: 11px; }
.publish-note { display: block; color: var(--color-text-secondary, #94a3b8); font-size: 11px; }
/* 列宽契约：表头一律不换行（「通用参考」曾被挤成两行）；窄屏交给横向滚动而不是挤压。 */
.benchmark-scroll { width: 100%; overflow-x: auto; }
.benchmark-table { width: 100%; min-width: 1450px; }
.benchmark-table :deep(th .cell) { white-space: nowrap; }
.order-actions { display: flex; align-items: center; justify-content: center; gap: 2px; white-space: nowrap; }
.order-actions span { min-width: 18px; text-align: center; font-variant-numeric: tabular-nums; }
.order-actions .el-button { margin: 0; padding: 4px 6px; font-size: 16px; }
.predecessor-select { width: 100%; }
.days-input { width: 138px; }
.days-input :deep(.el-input__inner) { font-variant-numeric: tabular-nums; }
.days-value { font-variant-numeric: tabular-nums; color: var(--color-text-secondary, #64748b); }
.days-unset { color: var(--color-text-placeholder, #cbd5e1); font-size: 12px; }
.candidate-main { font-variant-numeric: tabular-nums; }
.candidate-sub { display: block; color: var(--color-text-secondary, #94a3b8); font-size: 11px; }
/* 保存/撤销并排：单元格里默认可换行，列窄时会竖排成两行。 */
.benchmark-table :deep(.col-op .cell) { display: flex; justify-content: center; align-items: center; gap: 4px; white-space: nowrap; }
.benchmark-table :deep(.col-op .el-button) { margin: 0; flex: none; }
.copy-hint { margin: 0 0 12px; font-size: 12px; line-height: 1.6; color: var(--color-text-secondary, #5f6f82); }
.dialog-full { width: 100%; }
</style>
