<template>
  <section class="product-process" :class="{ embedded }">
    <div v-if="!embedded" class="panel-head">
      <div><h2>本产品制程配置</h2><p>选择本产品使用的节点及前置依赖；发布后只用于新导入任务，已有任务保留原快照。</p></div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <div v-if="!embedded" class="selectors">
      <el-select v-model="workshopId" placeholder="选择车间" filterable @change="productId = null">
        <el-option v-for="row in workshops" :key="row.id" :label="row.workshopName" :value="row.id" />
      </el-select>
      <el-select v-model="productId" placeholder="选择产品" filterable clearable @change="loadVersions">
        <el-option v-for="row in products" :key="row.id" :label="row.configName" :value="row.id" />
      </el-select>
      <el-select v-model="selectedVersionId" placeholder="查看版本" clearable @change="loadVersion">
        <el-option v-for="row in versions" :key="row.id" :label="`V${row.versionNo} · ${row.state === 'PUBLISHED' ? '已发布' : '草稿'}`" :value="row.id" />
      </el-select>
    </div>
    <div v-else class="embedded-version">
      <span>本产品制程版本</span>
      <el-select v-model="selectedVersionId" placeholder="尚无版本" clearable @change="loadVersion">
        <el-option v-for="row in versions" :key="row.id" :label="`V${row.versionNo} · ${row.state === 'PUBLISHED' ? '已发布' : '草稿'}`" :value="row.id" />
      </el-select>
      <span class="muted">已选 {{ editableNodes.filter(row => row.selected).length }} / {{ editableNodes.length }} 个节点</span>
      <el-button v-if="canManage && !versions.length" @click="selectDefaultNodes">恢复车间默认节点</el-button>
      <el-button :loading="loading" @click="load">刷新制程</el-button>
    </div>
    <el-alert v-if="productId && duplicateProduct" type="warning" :closable="false"
      title="同车间存在同名产品配置；新任务无法唯一匹配此模板，请先清理重复产品。" />
    <el-empty v-if="!productId" :image-size="64" description="选择产品后配置本产品的跟单节点" />
    <template v-else>
      <div v-if="!embedded || selectedVersionId" class="template-actions">
        <span v-if="selectedVersionId" class="muted">{{ selectedVersion?.state === 'PUBLISHED' ? '已发布版本不可修改；可基于当前节点另建版本。' : '草稿版本不可修改；可重新建草稿。' }}</span>
        <el-button v-if="selectedVersionId && canBaseline" :loading="samplesLoading" @click="loadDurationSamples">查看版本样本</el-button>
        <el-button v-if="canManage && selectedVersionId && selectedVersion?.state === 'DRAFT'"
          type="success" :loading="saving" @click="publish">发布此版本</el-button>
      </div>
      <div v-if="durationSamples" class="sample-panel">
        <div class="sample-title">V{{ durationSamples.templateVersion }} 历史节点完成间隔 <small>同版本任务 {{ durationSamples.matchedOrders }} 单</small></div>
        <el-alert v-if="!durationSamples.linear" type="warning" :closable="false" :title="durationSamples.reason || '并行制程暂不计算线性间隔'" />
        <el-table v-else :data="[...durationSamples.stages, durationSamples.whole]" size="small" border>
          <el-table-column label="范围" min-width="130">
            <template #default="{ row }">{{ row.code === 'WHOLE' ? '整单周期' : editableNodes.find(node => node.code === row.code)?.label || row.code }}</template>
          </el-table-column>
          <el-table-column prop="sampleCount" label="有效样本" width="95" />
          <el-table-column label="P50" width="90"><template #default="{ row }">{{ row.p50Days ?? '—' }} 天</template></el-table-column>
          <el-table-column label="P75" width="90"><template #default="{ row }">{{ row.p75Days ?? '—' }} 天</template></el-table-column>
          <el-table-column label="排除原因"><template #default="{ row }">{{ excludedText(row.excluded) }}</template></el-table-column>
        </el-table>
        <p class="muted">仅供核查历史样本，不会自动发布为产品参考工期。</p>
      </div>
      <slot v-if="embedded" :nodes="editableNodes" :can-manage="canManage"
        :toggle-node="toggleNode" :move-node="moveNode" :can-move="canMove" />
      <div v-else class="node-table">
        <div class="node-head"><span>采用</span><span>车间可用节点</span><span>顺序</span><span>前置节点（可多选；空值表示可直接开始）</span></div>
        <div v-for="row in editableNodes" :key="row.code" class="node-row">
          <el-checkbox v-model="row.selected" :disabled="!canManage" />
          <span>{{ row.label }} <small>{{ row.code }}</small><em v-if="row.conditional">条件节点</em></span>
          <el-input-number v-model="row.sortNo" :min="1" :max="40" :controls="false" :disabled="!row.selected || !canManage" />
          <el-select v-model="row.predecessors" multiple collapse-tags clearable placeholder="无前置节点"
            :disabled="!row.selected || !canManage">
            <el-option v-for="candidate in editableNodes.filter(item => item.selected && item.code !== row.code)"
              :key="candidate.code" :label="candidate.label" :value="candidate.code" />
          </el-select>
        </div>
      </div>
      <div v-if="canManage" class="save-row">
        <el-input v-model="note" maxlength="500" placeholder="版本说明（可选）" />
        <el-button type="primary" :loading="saving" @click="createDraft">保存制程草稿</el-button>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { fetchTrackingStageDictionary, fetchTrackingStageDefs } from '@/api/productionTracking'

interface Workshop { id: number; workshopName: string }
interface Product { id: number; workshopId: number; configName: string; configType: string; sameNameCount?: number }
interface Version { id: number; versionNo: number; state: string }
interface TemplateNode { code: string; label: string; sortNo: number; conditional: number | boolean }
interface TemplateEdge { predecessor: string; successor: string }
interface NodeRow { code: string; label: string; sortNo: number; conditional: boolean; selected: boolean; predecessors: string[] }
interface DurationRow { code: string; sampleCount: number; p50Days: number | null; p75Days: number | null; excluded: Record<string, number> }
interface DurationSamples { templateVersion: number; matchedOrders: number; linear: boolean; reason?: string; stages: DurationRow[]; whole: DurationRow }
const { can } = useCurrentUser()
const props = defineProps<{ taskConfigId?: number; workshopName?: string; sameNameCount?: number }>()
const emit = defineEmits<{ changed: [] }>()
const embedded = computed(() => props.taskConfigId != null)
const canManage = computed(() => can('manufacturing.template.manage'))
const canBaseline = computed(() => can('manufacturing.baseline.manage'))
const workshops = ref<Workshop[]>([])
const allProducts = ref<Product[]>([])
const workshopId = ref<number | null>(null)
const productId = ref<number | null>(null)
const versions = ref<Version[]>([])
const selectedVersionId = ref<number | null>(null)
const editableNodes = ref<NodeRow[]>([])
const note = ref('')
const loading = ref(false)
const saving = ref(false)
const samplesLoading = ref(false)
const durationSamples = ref<DurationSamples | null>(null)
const products = computed(() => allProducts.value.filter(row => row.configType === 'PRODUCT_NAME' && row.workshopId === workshopId.value))
const duplicateProduct = computed(() => embedded.value
  ? props.sameNameCount !== 1
  : products.value.find(row => row.id === productId.value)?.sameNameCount !== 1)
const selectedVersion = computed(() => versions.value.find(row => row.id === selectedVersionId.value))

async function load() {
  if (embedded.value) {
    await loadEmbeddedProduct()
    return
  }
  loading.value = true
  try {
    const [workshopResponse, productResponse] = await Promise.all([
      http.get<Workshop[]>('/manufacturing/master/workshops'),
      http.get<Product[]>('/manufacturing/master/task-configs'),
    ])
    workshops.value = workshopResponse.data
    allProducts.value = productResponse.data
    if (workshopId.value && !workshops.value.some(row => row.id === workshopId.value)) workshopId.value = null
    if (productId.value) await loadVersions()
  } finally { loading.value = false }
}

async function loadEmbeddedProduct() {
  loading.value = true
  try {
    const id = props.taskConfigId
    productId.value = id ?? null
    versions.value = []
    selectedVersionId.value = null
    durationSamples.value = null
    const defs = await fetchTrackingStageDictionary()
    editableNodes.value = defs.map((row, index) => ({
      code: row.stageCode, label: row.stageLabel, sortNo: index + 1,
      conditional: Boolean(row.optional), selected: false, predecessors: [],
    }))
    if (id) await loadVersions()
  } finally { loading.value = false }
}

watch(() => [props.taskConfigId, props.workshopName], () => {
  if (embedded.value) void loadEmbeddedProduct()
})

watch(workshopId, async id => {
  productId.value = null
  versions.value = []
  selectedVersionId.value = null
  editableNodes.value = []
  if (!id) return
  const defs = await fetchTrackingStageDictionary()
  editableNodes.value = defs.map((row, index) => ({
    code: row.stageCode, label: row.stageLabel, sortNo: index + 1,
    conditional: Boolean(row.optional), selected: false, predecessors: [],
  }))
})

async function loadVersions() {
  durationSamples.value = null
  selectedVersionId.value = null
  versions.value = productId.value
    ? (await http.get<Version[]>(`/manufacturing/tracking-processes/products/${productId.value}/versions`)).data : []
  selectedVersionId.value = versions.value[0]?.id ?? null
  if (selectedVersionId.value) await loadVersion()
  else await selectDefaultNodes()
}

async function loadVersion() {
  durationSamples.value = null
  if (!selectedVersionId.value) { await selectDefaultNodes(); return }
  const data = (await http.get<{ nodes: TemplateNode[]; edges: TemplateEdge[] }>(
    `/manufacturing/tracking-processes/versions/${selectedVersionId.value}`)).data
  const nodes = new Map(data.nodes.map(row => [row.code, row]))
  editableNodes.value = editableNodes.value.map(row => ({ ...row,
    selected: nodes.has(row.code), sortNo: nodes.get(row.code)?.sortNo ?? row.sortNo,
    conditional: Boolean(nodes.get(row.code)?.conditional ?? row.conditional),
    predecessors: data.edges.filter(edge => edge.successor === row.code).map(edge => edge.predecessor),
  }))
}

function selectedInOrder() {
  return editableNodes.value.filter(row => row.selected).sort((a, b) => a.sortNo - b.sortNo)
}

function renumberSelected() {
  selectedInOrder().forEach((row, index) => { row.sortNo = index + 1 })
}

function toggleNode(code: string) {
  const row = editableNodes.value.find(item => item.code === code)
  if (!row || !canManage.value) return
  if (row.selected) {
    row.selected = false
    row.predecessors = []
    for (const other of editableNodes.value) other.predecessors = other.predecessors.filter(item => item !== code)
    renumberSelected()
  } else {
    renumberSelected()
    row.sortNo = selectedInOrder().length + 1
    row.selected = true
  }
}

async function selectDefaultNodes() {
  const kind = (props.workshopName ?? '').includes('封口') ? 'SEALER' : 'MACHINE'
  const defaults = (await fetchTrackingStageDefs(kind)).filter(row => row.enabled && row.defaultIncluded)
  const codes = new Set(defaults.map(row => row.stageCode))
  editableNodes.value.forEach(row => {
    row.selected = codes.has(row.code)
    row.predecessors = []
  })
  defaults.forEach((def, index) => {
    const row = editableNodes.value.find(item => item.code === def.stageCode)
    if (!row) return
    row.sortNo = index + 1
    row.predecessors = index ? [defaults[index - 1]!.stageCode] : []
  })
}

function canMove(code: string, direction: -1 | 1) {
  const rows = selectedInOrder()
  const index = rows.findIndex(row => row.code === code)
  if (index < 0 || index + direction < 0 || index + direction >= rows.length) return false
  const adjacent = rows[index + direction]!
  return direction < 0
    ? !rows[index]!.predecessors.includes(adjacent.code)
    : !adjacent.predecessors.includes(code)
}

function moveNode(code: string, direction: -1 | 1) {
  if (!canManage.value || !canMove(code, direction)) return
  const rows = selectedInOrder()
  const index = rows.findIndex(row => row.code === code)
  const currentSort = rows[index]!.sortNo
  rows[index]!.sortNo = rows[index + direction]!.sortNo
  rows[index + direction]!.sortNo = currentSort
}

async function loadDurationSamples() {
  if (!selectedVersionId.value) return
  samplesLoading.value = true
  try {
    durationSamples.value = (await http.get<DurationSamples>(
      `/manufacturing/tracking-processes/versions/${selectedVersionId.value}/duration-samples`)).data
  } finally { samplesLoading.value = false }
}

function excludedText(reasons: Record<string, number>) {
  const labels: Record<string, string> = {
    MISSING_DATE: '缺日期', NO_ANCHOR: '无排产日', PREVIOUS_DATE_MISSING: '前序日期缺失',
    REVERSED_DATE: '日期倒序', OVER_60_DAYS: '超过60天', OVER_365_DAYS: '超过365天',
    FUTURE_DATE: '未来日期', NOT_ALL_DONE: '未全部完成', MISSING_FINISH_DATE: '缺完工日期',
    APPLICABILITY_UNCONFIRMED: '适用性待确认',
  }
  return Object.entries(reasons).filter(([, count]) => count > 0)
    .map(([reason, count]) => `${labels[reason] || reason} ${count}`).join(' · ') || '—'
}

async function createDraft() {
  if (!productId.value) return
  const selected = editableNodes.value.filter(row => row.selected)
  if (!selected.length) { ElMessage.warning('至少选择一个节点'); return }
  const codes = new Set(selected.map(row => row.code))
  const sorts = new Set(selected.map(row => row.sortNo))
  if (sorts.size !== selected.length) { ElMessage.warning('节点顺序不能重复'); return }
  const edges = selected.flatMap(row => row.predecessors.filter(code => codes.has(code))
    .map(code => ({ from: code, to: row.code })))
  saving.value = true
  try {
    const result = (await http.post<{ id: number }>(
      `/manufacturing/tracking-processes/products/${productId.value}/versions`,
      { nodes: selected.map(row => ({ code: row.code, sortNo: row.sortNo, conditional: row.conditional })), edges, note: note.value })).data
    await loadVersions()
    selectedVersionId.value = result.id
    emit('changed')
    ElMessage.success('草稿版本已保存，请核对后发布')
  } finally { saving.value = false }
}

async function publish() {
  if (!selectedVersionId.value) return
  await ElMessageBox.confirm('发布后只用于新导入的匹配任务。已存在的任务快照保持不变。', '发布产品制程',
    { type: 'warning', confirmButtonText: '确认发布' })
  saving.value = true
  try {
    await http.post(`/manufacturing/tracking-processes/versions/${selectedVersionId.value}/publish`)
    await loadVersions()
    emit('changed')
    ElMessage.success('产品制程版本已发布')
  } finally { saving.value = false }
}
await load()
</script>

<style scoped>
.product-process { border: 1px solid var(--color-border, #dcdfe6); border-radius: 10px; background: #fff; padding: 16px; display: grid; gap: 14px; }
.product-process.embedded { border: 0; border-top: 1px solid var(--color-border, #dcdfe6); border-radius: 0; padding: 18px 0 0; }
.embedded-version { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; font-size: 13px; }
.embedded-version .el-select { width: 220px; }
.panel-head { display: flex; justify-content: space-between; align-items: start; gap: 12px; }
.panel-head h2 { margin: 0; font-size: 17px; }
.panel-head p { margin: 5px 0 0; color: var(--color-text-secondary); font-size: 13px; }
.selectors { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.template-actions { display: flex; justify-content: space-between; align-items: center; }
.muted, .node-row small { color: var(--color-text-secondary); font-size: 12px; }
.node-head, .node-row { display: grid; grid-template-columns: 42px minmax(180px, 1fr) 90px minmax(220px, 1.2fr); align-items: center; gap: 10px; padding: 7px 10px; }
.node-head { background: #f5f7fa; color: var(--color-text-secondary); font-size: 12px; }
.node-row { border-bottom: 1px solid #ebeef5; font-size: 13px; }
.node-row small { margin-left: 8px; }
.node-row em { margin-left: 8px; color: #b88230; font-style: normal; font-size: 11px; }
.save-row { display: flex; gap: 10px; }
.sample-panel { display: grid; gap: 10px; padding: 12px; border: 1px solid var(--color-border, #dcdfe6); border-radius: 8px; }
.sample-title { font-weight: 600; }
.sample-title small { margin-left: 8px; font-weight: 400; color: var(--color-text-secondary); }
@media(max-width: 900px) { .selectors { grid-template-columns: 1fr; } .node-head, .node-row { grid-template-columns: 32px 1fr 70px 1fr; } }
</style>
