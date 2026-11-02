<template>
  <el-dialog :model-value="modelValue" title="调整本单制程" width="860px" append-to-body
    @update:model-value="emit('update:modelValue', $event)">
    <div v-loading="loading" class="order-process">
      <el-alert type="info" :closable="false"
        title="本单制程与产品模板独立。调整仅影响本单；已有节点执行记录不会被自动删除。" />
      <template v-if="snapshot?.sourceKind === 'NOT_CAPTURED'">
        <p>这张历史任务尚无制程快照。先按当前节点与既有执行记录建立旧制程兼容快照，再逐项调整。</p>
        <el-button v-if="canManage" type="primary" :loading="saving" @click="captureLegacy">建立旧制程兼容快照</el-button>
      </template>
      <template v-else-if="snapshot">
        <p class="meta">{{ snapshot.sourceKind === 'PUBLISHED' ? `产品模板 V${snapshot.templateVersion}` : '旧制程或人工调整' }} · 本单修订 {{ snapshot.revisionNo }}</p>
        <div class="grid-head"><span>采用</span><span>节点</span><span>顺序</span><span>适用性</span><span>前置节点</span></div>
        <div v-for="row in rows" :key="row.code" class="grid-row">
          <el-checkbox v-model="row.selected" />
          <span>{{ row.label }} <small>{{ row.code }}</small></span>
          <el-input-number v-model="row.sortNo" :min="1" :max="40" :controls="false" :disabled="!row.selected" />
          <el-select v-model="row.applicability" :disabled="!row.selected">
            <el-option label="适用" value="APPLICABLE" />
            <el-option label="不适用" value="NOT_APPLICABLE" />
            <el-option label="待确认" value="PENDING_REVIEW" />
          </el-select>
          <el-select v-model="row.predecessors" multiple collapse-tags clearable placeholder="无前置节点" :disabled="!row.selected">
            <el-option v-for="candidate in rows.filter(item => item.selected && item.code !== row.code)"
              :key="candidate.code" :label="candidate.label" :value="candidate.code" />
          </el-select>
        </div>
        <el-input v-model="reason" type="textarea" :rows="2" maxlength="500" show-word-limit
          placeholder="调整原因与现场依据（必填）" />
      </template>
    </div>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">关闭</el-button>
      <el-button v-if="snapshot && snapshot.sourceKind !== 'NOT_CAPTURED' && canManage" type="primary"
        :loading="saving" @click="save">保存本单制程</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { fetchTrackingStageDictionary } from '@/api/productionTracking'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'

interface SnapshotNode { code: string; label: string; sortNo: number; applicability: string }
interface Edge { predecessor: string; successor: string }
interface Snapshot { sourceKind: string; templateVersion?: number | null; revisionNo?: number; nodes: SnapshotNode[]; edges: Edge[] }
interface Row extends SnapshotNode { selected: boolean; predecessors: string[] }
const props = defineProps<{ modelValue: boolean; orderId: number | null; workshop: string | null }>()
const emit = defineEmits<{ 'update:modelValue': [boolean]; saved: [] }>()
const { can } = useCurrentUser()
const canManage = computed(() => can('manufacturing.template.manage'))
const snapshot = ref<Snapshot | null>(null)
const rows = ref<Row[]>([])
const reason = ref('')
const loading = ref(false)
const saving = ref(false)

async function load() {
  if (!props.orderId) return
  loading.value = true
  try {
    const [snapshotResponse, defs] = await Promise.all([
      http.get<Snapshot>(`/manufacturing/tracking-processes/orders/${props.orderId}/snapshot`),
      fetchTrackingStageDictionary(),
    ])
    snapshot.value = snapshotResponse.data
    const found = new Map(snapshot.value.nodes.map(row => [row.code, row]))
    const dictionary = defs.map((row, index) => ({
      code: row.stageCode, label: row.stageLabel, sortNo: index + 1,
      applicability: row.optional ? 'PENDING_REVIEW' : 'APPLICABLE',
    }))
    const combined = [...snapshot.value.nodes, ...dictionary.filter(row => !found.has(row.code))]
    rows.value = combined.map(row => ({ ...row, selected: found.has(row.code),
      predecessors: snapshot.value?.edges.filter(edge => edge.successor === row.code).map(edge => edge.predecessor) ?? [],
    }))
    reason.value = ''
  } finally { loading.value = false }
}
watch(() => [props.modelValue, props.orderId] as const, ([visible]) => { if (visible) void load() }, { immediate: true })

async function captureLegacy() {
  if (!props.orderId) return
  saving.value = true
  try {
    await http.post(`/manufacturing/tracking-processes/orders/${props.orderId}/legacy-snapshot`)
    await load()
    ElMessage.success('已建立旧制程兼容快照')
  } finally { saving.value = false }
}

async function save() {
  if (!props.orderId || !snapshot.value?.revisionNo) return
  if (!reason.value.trim()) { ElMessage.warning('请填写调整原因'); return }
  const selected = rows.value.filter(row => row.selected)
  if (!selected.length) { ElMessage.warning('至少保留一个节点'); return }
  if (new Set(selected.map(row => row.sortNo)).size !== selected.length) {
    ElMessage.warning('节点顺序不能重复'); return
  }
  const codes = new Set(selected.map(row => row.code))
  saving.value = true
  try {
    await http.post(`/manufacturing/tracking-processes/orders/${props.orderId}/revision`, {
      expectedRevision: snapshot.value.revisionNo,
      nodes: selected.map(row => ({ code: row.code, sortNo: row.sortNo, conditional: row.applicability !== 'APPLICABLE' })),
      edges: selected.flatMap(row => row.predecessors.filter(code => codes.has(code))
        .map(code => ({ from: code, to: row.code }))),
      applicability: Object.fromEntries(selected.map(row => [row.code, row.applicability])),
      reason: reason.value.trim(),
    })
    await load()
    emit('saved')
    ElMessage.success('本单制程已保存')
  } finally { saving.value = false }
}
</script>

<style scoped>
.order-process { display: grid; gap: 12px; }
.order-process p { margin: 0; }
.meta, .grid-row small { color: var(--color-text-secondary); font-size: 12px; }
.grid-head, .grid-row { display: grid; grid-template-columns: 42px minmax(160px, 1fr) 90px 130px minmax(180px, 1.2fr); align-items: center; gap: 8px; padding: 7px 8px; }
.grid-head { background: #f5f7fa; color: var(--color-text-secondary); font-size: 12px; }
.grid-row { border-bottom: 1px solid #ebeef5; }
.grid-row small { margin-left: 5px; }
</style>
