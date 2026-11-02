<template>
  <div class="process-page">
    <header class="page-head">
      <div>
        <h1>制程管理</h1>
        <p class="lead">统一维护全局跟单节点字典；车间默认流程和各产品制程分别选择需要的节点。</p>
      </div>
      <div class="head-actions">
        <el-popover placement="bottom-end" :width="540" trigger="click">
          <template #reference><el-button text size="small">口径说明</el-button></template>
          <p class="caliber-note">新增节点只进入全局字典，不归属于任何车间，也不会改变任务进度。车间默认流程和「生产参考工期」中的产品制程分别引用字典节点。</p>
          <p class="caliber-note">未匹配产品模板任务的「已完成 x/y」只计算默认流程中的必经节点；按需节点按源单配置或实际登记判断。已绑定产品制程的任务按本单快照计算。</p>
          <p class="caliber-note">否定关键词优先：「需要氧化，但不含特氟龙」不会被误判为需要。可选配置与生产要求分别判断后取并集。</p>
          <p class="caliber-note">节点编码关联历史里程碑、产品模板和参考工期，不可修改；被任务或模板引用的字典节点不可删除。移出默认流程不会删除历史记录。</p>
        </el-popover>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <h2 class="dictionary-heading">全局跟单节点字典 <small>同一节点只定义一次，新增后不自动进入任何流程</small></h2>
    <section class="dictionary-panel">
      <div class="chain-head">
        <div><h2>全部可用节点</h2><span class="muted">定义节点编码、名称与按需条件；车间和产品在各自流程中引用。</span></div>
        <el-button v-if="canManage" type="primary" plain @click="startCreate">新增字典节点</el-button>
      </div>
      <div class="dictionary-list">
        <div v-for="row in dictionary" :key="row.stageCode" class="dictionary-item">
          <div class="dictionary-name"><b>{{ row.stageLabel }}</b><small>{{ row.stageCode }}</small>
            <em v-if="row.optional" class="b-opt">按需</em></div>
          <div class="dictionary-actions">
            <el-button v-if="canManage" link type="primary" @click="selectDictionary(row)">编辑定义</el-button>
            <span v-if="row.usageCount" class="muted">已有 {{ row.usageCount }} 条跟单记录</span>
          </div>
        </div>
      </div>
    </section>

    <h2 class="dictionary-heading">车间默认流程 <small>仅供未匹配产品模板的任务使用</small></h2>
    <!-- 类别切换：卡片上带「几个节点 / 几个必经」，切换前就能对比两套制程的规模。 -->
    <section class="kind-bar" aria-label="车间类别">
      <button v-for="item in KINDS" :key="item.kind" type="button" class="kind-card"
        :class="{ active: kind === item.kind }" @click="switchKind(item.kind)">
        <span class="kind-name">{{ item.label }}</span>
        <span class="kind-hint">{{ item.hint }}</span>
        <span class="kind-count">
          {{ (defs[item.kind] ?? []).filter(row => row.enabled && row.defaultIncluded).length }} 个流程节点
        </span>
      </button>
    </section>

    <div v-if="canManage" class="flow-picker">
      <el-select v-model="defaultAddCode" placeholder="从全局字典选择节点" filterable clearable>
        <el-option v-for="item in dictionary.filter(item => !defaultRows.some(row => row.stageCode === item.stageCode))"
          :key="item.stageCode" :label="item.stageLabel" :value="item.stageCode" />
      </el-select>
      <el-button :disabled="!defaultAddCode" @click="addDefault">加入该车间默认流程</el-button>
    </div>

    <el-alert v-if="impact" type="info" :closable="false" class="impact">
      默认流程影响未匹配产品模板的任务：当前 <b>{{ impact.requiredStages }}</b> 个必经节点，
      <b>{{ impact.openOrders }}</b> 张在制单有跟单记录，其中 <b>{{ impact.completedOrders }}</b> 张已判跟单完成。
    </el-alert>

    <div class="layout">
      <section class="chain-panel">
        <div class="chain-head">
          <h2>车间默认流程</h2>
          <span class="muted">只有这里的节点才用于未匹配产品模板的任务；顺序可上移或下移</span>
        </div>

        <div v-loading="loading" class="chain">
          <template v-for="(row, index) in defaultRows" :key="row.id">
            <div class="node" :class="{ active: selectedCode === row.stageCode, optional: row.optional }">
              <span class="node-index">{{ index + 1 }}</span>
              <b class="node-label">{{ row.stageLabel }}</b>
              <code class="node-code">{{ row.stageCode }}</code>
              <span class="node-badges">
                <em :class="row.optional ? 'b-opt' : 'b-req'">{{ row.optional ? '按需' : '必经' }}</em>
              </span>
              <span v-if="row.usageCount" class="node-used">{{ row.usageCount }} 单</span>
              <span v-if="canManage" class="node-ops">
                <button type="button" :disabled="index === 0" title="上移" @click.stop="move(row, true)">↑</button>
                <button type="button" :disabled="index === defaultRows.length - 1" title="下移" @click.stop="move(row, false)">↓</button>
                <button type="button" title="移出默认流程" @click.stop="changeDefault(row, false)">×</button>
              </span>
            </div>
            <span v-if="index < defaultRows.length - 1" class="arrow" aria-hidden="true">→</span>
          </template>
          <el-empty v-if="!defaultRows.length" :image-size="48" description="尚未配置车间默认流程" />
        </div>

        <p class="chain-foot">
          新增字典节点不会改变这条流程。调整默认流程会影响未匹配产品模板的任务；已有产品制程版本与任务快照保持各自记录。
        </p>
      </section>

    </div>

    <el-dialog v-model="editorVisible" :title="mode === 'create' ? '新增字典节点' : '编辑字典定义'"
      width="min(520px, calc(100vw - 32px))">
      <div class="editor">
          <el-form label-position="top" size="small" class="editor-form">
            <el-form-item label="节点名称" required>
              <el-input v-model="form.label" maxlength="50" placeholder="如：物料配齐" />
            </el-form-item>
            <el-form-item label="节点编码" required>
              <el-input v-model="form.code" maxlength="30" :disabled="mode === 'edit'" placeholder="如：MATERIAL_READY" />
              <p class="field-hint">
                {{ mode === 'edit' ? '编码不可修改：历史里程碑、产品模板与参考工期都按编码关联。' : '大写字母、数字与下划线，以字母开头。保存后不可修改。' }}
              </p>
            </el-form-item>
            <el-form-item label="按需工序">
              <el-switch v-model="form.optional" active-text="按需" inactive-text="必经" />
              <p class="field-hint">按需工序不计入完成分母，只有命中的工单才需要走它。</p>
            </el-form-item>

            <template v-if="form.optional">
              <el-form-item label="命中关键词" required>
                <el-input v-model="form.matchKeywords" placeholder="氧化|特氟龙|特富龙" />
                <p class="field-hint">正则，匹配源单「可选配置」或「生产要求」即认为本单要走这道工序。</p>
              </el-form-item>
              <el-form-item label="否定关键词">
                <el-input v-model="form.excludeKeywords" placeholder="(不含|无需|不用|不需要|无)(氧化|特氟龙|特富龙)" />
                <p class="field-hint">命中则不算需要，避免「需要氧化，但不含特氟龙」被误判。</p>
              </el-form-item>
            </template>

            <el-form-item label="进度表表头别名">
              <el-input v-model="form.headerAliases" placeholder="物料齐套,齐套" />
              <p class="field-hint">除节点名称外，进度表里还要认的写法，逗号分隔。</p>
            </el-form-item>
          </el-form>

          <div class="editor-actions">
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
            <el-button v-if="mode === 'edit'" plain type="danger"
              :disabled="(selected?.usageCount ?? 0) > 0" @click="remove">删除</el-button>
          </div>
          <p v-if="mode === 'edit' && selected?.usageCount" class="field-hint">
            已有 {{ selected.usageCount }} 条跟单记录，不能删除该字典节点。
          </p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import {
  createTrackingStageDictionary,
  deleteTrackingStageDictionary,
  fetchTrackingStageDictionary,
  fetchTrackingStageDefs,
  fetchTrackingStageDefsImpact,
  moveTrackingStageDef,
  setTrackingStageDefaultFlow,
  addTrackingDictionaryStageToDefaultFlow,
  updateTrackingStageDictionary,
  type TrackingStageDef,
  type TrackingStageDictionaryItem,
  type TrackingStageDefImpact,
  type TrackingStageDefInput,
} from '@/api/productionTracking'

/**
 * 全局节点字典与未匹配产品制程任务的车间默认流程。
 * 产品采用哪些节点及参考天数，在「生产参考工期」按产品配置。
 */
const { can } = useCurrentUser()
const canManage = computed(() => can('manufacturing.template.manage'))

const KINDS = [
  { kind: 'SEALER', label: '封口机车间', hint: '含氧化/喷特氟龙、物料配齐' },
  { kind: 'MACHINE', label: '其他机器车间', hint: '画图、钣金、调试' },
] as const
type Kind = typeof KINDS[number]['kind']

/** 字典全局一份；车间类别仅决定未匹配产品模板任务的默认流程。 */
const dictionary = ref<TrackingStageDictionaryItem[]>([])
const defs = reactive<Record<Kind, TrackingStageDef[]>>({ SEALER: [], MACHINE: [] })
const kind = ref<Kind>('SEALER')
const defaultAddCode = ref<string | null>(null)
const impact = ref<TrackingStageDefImpact | null>(null)
const loading = ref(false)

const rows = computed(() => defs[kind.value])
const defaultRows = computed(() => rows.value.filter(row => row.enabled && row.defaultIncluded))
const selectedCode = ref<string | null>(null)
const selected = computed(() => dictionary.value.find(row => row.stageCode === selectedCode.value) ?? null)
const mode = ref<'idle' | 'edit' | 'create'>('idle')
const editorVisible = computed({ get: () => mode.value !== 'idle', set: value => { if (!value) mode.value = 'idle' } })
const saving = ref(false)

interface FormState extends TrackingStageDefInput { label: string; code?: string }
const blankForm = (): FormState => ({
  kind: '', label: '', code: '', optional: false,
  matchKeywords: null, excludeKeywords: null, headerAliases: null,
})
const form = ref<FormState>(blankForm())

async function load() {
  loading.value = true
  try {
    const [global, sealer, machine] = await Promise.all([
      fetchTrackingStageDictionary(),
      fetchTrackingStageDefs('SEALER'),
      fetchTrackingStageDefs('MACHINE'),
    ])
    dictionary.value = global.map(row => ({ ...row, optional: Boolean(row.optional) }))
    // MySQL 的 TINYINT(1) 回来是 0/1，统一成布尔，否则 switch 绑上去会出现「1 也当假」。
    defs.SEALER = sealer.map(row => ({ ...row, optional: Boolean(row.optional), enabled: Boolean(row.enabled), defaultIncluded: Boolean(row.defaultIncluded) }))
    defs.MACHINE = machine.map(row => ({ ...row, optional: Boolean(row.optional), enabled: Boolean(row.enabled), defaultIncluded: Boolean(row.defaultIncluded) }))
    impact.value = canManage.value ? await fetchTrackingStageDefsImpact(kind.value) : null
  } finally {
    loading.value = false
  }
}
await load()

function switchKind(next: Kind) {
  if (kind.value === next) return
  kind.value = next
  mode.value = 'idle'
  defaultAddCode.value = null
  void loadImpact()
}

async function loadImpact() {
  impact.value = canManage.value ? await fetchTrackingStageDefsImpact(kind.value) : null
}

function selectDictionary(row: TrackingStageDictionaryItem) {
  if (!canManage.value) return
  selectedCode.value = row.stageCode
  mode.value = 'edit'
  form.value = {
    kind: '', code: row.stageCode, label: row.stageLabel, optional: row.optional,
    matchKeywords: row.matchKeywords, excludeKeywords: row.excludeKeywords,
    headerAliases: row.headerAliases,
  }
}

function startCreate() {
  selectedCode.value = null
  mode.value = 'create'
  form.value = blankForm()
}

function payload(): TrackingStageDefInput {
  return {
    kind: '',
    code: form.value.code?.trim().toUpperCase() || undefined,
    label: form.value.label?.trim() ?? '',
    optional: form.value.optional,
    matchKeywords: form.value.matchKeywords?.trim() || null,
    excludeKeywords: form.value.excludeKeywords?.trim() || null,
    headerAliases: form.value.headerAliases?.trim() || null,
  }
}

/** Only explicit changes to the fallback flow can change legacy task completion. */
async function confirmImpact(action: string) {
  if (!impact.value) return true
  try {
    await ElMessageBox.confirm(
      `该类别有 ${impact.value.openOrders} 张在制工单正在跟单，其中 ${impact.value.completedOrders} 张已判「跟单完成」。`
      + `「${action}」可能改变未匹配产品模板任务的节点与完成判定。`,
      '确认改制程', { type: 'warning', confirmButtonText: '确认修改', cancelButtonText: '再想想' })
    return true
  } catch { return false }
}

async function save() {
  if (!form.value.label?.trim()) { ElMessage.warning('请填写节点名称'); return }
  if (mode.value === 'create' && !form.value.code?.trim()) { ElMessage.warning('请填写节点编码'); return }
  if (form.value.optional && !form.value.matchKeywords?.trim()) {
    ElMessage.warning('按需工序必须填写命中关键词，否则没有任何工单会被判为要走这道工序')
    return
  }
  saving.value = true
  try {
    const creating = mode.value === 'create'
    if (creating) await createTrackingStageDictionary(payload())
    else if (selectedCode.value) await updateTrackingStageDictionary(selectedCode.value, payload())
    mode.value = 'idle'
    selectedCode.value = null
    await load()
    ElMessage.success(creating ? '字典节点已新增，尚未加入任何流程' : '节点定义已保存')
  } finally {
    saving.value = false
  }
}

async function changeDefault(row: TrackingStageDef, included: boolean) {
  if (!(await confirmImpact(included ? '加入默认流程' : '移出默认流程'))) return
  await setTrackingStageDefaultFlow(row.id, included)
  await load()
  ElMessage.success(included ? '已加入车间默认流程' : '已移出车间默认流程')
}

async function addDefault() {
  if (!defaultAddCode.value || !(await confirmImpact('加入默认流程'))) return
  await addTrackingDictionaryStageToDefaultFlow(kind.value, defaultAddCode.value)
  defaultAddCode.value = null
  await load()
  ElMessage.success('节点已加入该车间默认流程')
}

/** 只换顺序：不影响完成判定的分子，故不弹确认。 */
async function move(row: TrackingStageDef, up: boolean) {
  await moveTrackingStageDef(row.id, up)
  await load()
}

async function remove() {
  if (!selected.value) return
  if (selected.value.usageCount > 0) { ElMessage.warning('该节点已有跟单记录，不能删除'); return }
  await deleteTrackingStageDictionary(selected.value.stageCode)
  mode.value = 'idle'
  selectedCode.value = null
  await load()
  ElMessage.success('节点已删除')
}
</script>

<style scoped>
.process-page { display: flex; flex-direction: column; gap: 16px; }
.dictionary-heading { margin: 5px 0 0; font-size: 17px; }
.dictionary-heading small { margin-left: 10px; color: var(--color-text-secondary); font-size: 12px; font-weight: normal; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.page-head h1 { margin: 0; font-size: 20px; }
.lead { margin: 6px 0 0; color: var(--color-text-secondary); max-width: 720px; line-height: 1.6; }
.head-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.caliber-note { margin: 0 0 8px; line-height: 1.6; font-size: 13px; }

.kind-bar { display: flex; gap: 12px; flex-wrap: wrap; }
.kind-card {
  display: flex; flex-direction: column; gap: 2px; min-width: 220px;
  padding: 10px 14px; border: 1px solid var(--color-border, #dcdfe6); border-radius: 8px;
  background: #fff; cursor: pointer; text-align: left; transition: border-color .15s, box-shadow .15s;
}
.kind-card:hover { border-color: var(--color-primary, #409eff); }
.kind-card.active { border-color: var(--color-primary, #409eff); box-shadow: 0 0 0 1px var(--color-primary, #409eff) inset; }
.kind-name { font-weight: 600; }
.kind-hint, .kind-count { font-size: 12px; color: var(--color-text-secondary); }

.impact { margin: 0; }
.flow-picker { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.flow-picker .el-select { width: min(320px, 100%); }
.dictionary-panel { border: 1px solid var(--color-border, #ebeef5); border-radius: 10px; background: #fff; padding: 14px 16px; }
.dictionary-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(275px, 1fr)); gap: 8px; }
.dictionary-item { border: 1px solid var(--color-border, #ebeef5); border-radius: 8px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; }
.dictionary-name, .dictionary-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.dictionary-name b { font-size: 13px; }
.dictionary-name small { color: var(--color-text-secondary); }
.dictionary-name em { font-style: normal; font-size: 11px; padding: 1px 5px; border-radius: 3px; }
.dictionary-actions { min-height: 24px; }

.layout { display: grid; grid-template-columns: minmax(0, 1fr); gap: 16px; align-items: start; }

.chain-panel, .editor {
  border: 1px solid var(--color-border, #ebeef5); border-radius: 10px; background: #fff; padding: 14px 16px;
}
.chain-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.chain-head h2, .editor-head h2 { margin: 0; font-size: 15px; }
.muted { color: var(--color-text-secondary); font-size: 12px; }

.chain { display: flex; flex-wrap: wrap; align-items: stretch; gap: 8px; min-height: 120px; }

.node {
  position: relative; display: flex; flex-direction: column; gap: 3px;
  min-width: 118px; padding: 10px 12px 12px; border-radius: 8px;
  border: 1px solid var(--color-border, #dcdfe6); background: #fafcff; cursor: pointer;
  transition: border-color .15s, box-shadow .15s, background .15s;
}
.node:hover { border-color: var(--color-primary, #409eff); }
.node.active { border-color: var(--color-primary, #409eff); box-shadow: 0 0 0 1px var(--color-primary, #409eff) inset; background: #fff; }
.node-index {
  position: absolute; top: -8px; left: -8px; width: 20px; height: 20px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; color: #fff; background: var(--color-text-secondary, #909399);
}
.node.active .node-index { background: var(--color-primary, #409eff); }
.node-label { font-size: 13px; }
.node-code { font-size: 11px; color: var(--color-text-secondary); }
.node-badges { display: flex; gap: 4px; margin-top: 2px; }
.node-badges em { font-style: normal; font-size: 11px; padding: 1px 5px; border-radius: 3px; }
.b-req { background: #f0f2f5; color: #606266; }
.b-opt { background: #fdf6ec; color: #b88230; }
.node-used { font-size: 11px; color: var(--color-text-secondary); margin-top: 2px; }
.node-ops { position: absolute; right: 4px; bottom: 4px; display: none; gap: 2px; }
.node:hover .node-ops { display: flex; }
.node-ops button {
  width: 20px; height: 20px; border: 1px solid var(--color-border, #dcdfe6); border-radius: 4px;
  background: #fff; cursor: pointer; line-height: 1; color: #606266;
}
.node-ops button:disabled { cursor: not-allowed; opacity: .4; }

.arrow { align-self: center; color: var(--color-text-secondary, #c0c4cc); font-size: 14px; }
.node-add {
  align-items: center; justify-content: center; border-style: dashed; background: #fff;
  color: var(--color-text-secondary); min-width: 104px; gap: 4px;
}
.node-add .plus { font-size: 16px; line-height: 1; }

.chain-foot { margin: 14px 0 0; font-size: 12px; line-height: 1.7; color: var(--color-text-secondary); }

.editor { display: flex; flex-direction: column; gap: 10px; }
.editor-head { display: flex; align-items: center; justify-content: space-between; }
.editor-form { margin-top: 4px; }
.field-hint { margin: 4px 0 0; font-size: 12px; line-height: 1.6; color: var(--color-text-secondary); }
.editor-actions { display: flex; gap: 8px; }
</style>
