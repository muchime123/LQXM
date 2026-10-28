<template>
  <div class="process-page">
    <header class="page-head">
      <div>
        <h1>制程管理</h1>
        <p class="lead">车间跟单节点长什么样、按什么顺序走、哪些是可选的。保存即全站生效——跟单页的节点按钮、进度表表头、导出列与「已完成 x/y」的分母都按这里走。</p>
      </div>
      <div class="head-actions">
        <el-popover placement="bottom-end" :width="540" trigger="click">
          <template #reference><el-button text size="small">口径说明</el-button></template>
          <p class="caliber-note">制程按<b>车间类别</b>配置（封口机 / 其他机器），不是按单个车间——跟单统计的派生表按类别各出一列，按车间展开会让列数爆炸。将来制程真分叉，应扩「类别」而不是扩「车间」。</p>
          <p class="caliber-note">「已完成 x/y」的 y = <b>启用的必经节点数</b>；按需工序（氧化/喷特氟龙、木箱）不计入分母，按源单「可选配置 / 生产要求」是否命中关键词判断本单要不要走它。</p>
          <p class="caliber-note">否定关键词优先：「需要氧化，但不含特氟龙」不会被误判为需要。可选配置与生产要求分别判断后取并集。</p>
          <p class="caliber-note">节点编码是历史里程碑与参考工期的唯一关联键，<b>一经产生跟单记录不可修改</b>；有记录的节点只能停用、不能删除（历史记录原样保留，只是不再进分母、不再显示在节点条上）。</p>
        </el-popover>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
      </div>
    </header>

    <!-- 类别切换：卡片上带「几个节点 / 几个必经」，切换前就能对比两套制程的规模。 -->
    <section class="kind-bar" aria-label="车间类别">
      <button v-for="item in KINDS" :key="item.kind" type="button" class="kind-card"
        :class="{ active: kind === item.kind }" @click="switchKind(item.kind)">
        <span class="kind-name">{{ item.label }}</span>
        <span class="kind-hint">{{ item.hint }}</span>
        <span class="kind-count">
          {{ (defs[item.kind] ?? []).length }} 个节点 ·
          {{ (defs[item.kind] ?? []).filter(row => row.enabled && !row.optional).length }} 个必经
        </span>
      </button>
    </section>

    <!-- 改制程会当场改写在制工单的完成判定，先把影响面摆出来。 -->
    <el-alert v-if="impact" type="warning" :closable="false" class="impact">
      该类别现有 <b>{{ impact.requiredStages }}</b> 个必经节点；<b>{{ impact.openOrders }}</b> 张在制工单正在跟单，
      其中 <b>{{ impact.completedOrders }}</b> 张已判「跟单完成」。新增必经节点 / 停用节点会改写这些单的完成判定。
    </el-alert>

    <div class="layout">
      <section class="chain-panel">
        <div class="chain-head">
          <h2>制程链</h2>
          <span class="muted">点击节点编辑；箭头方向即进度表列顺序</span>
        </div>

        <div v-loading="loading" class="chain">
          <template v-for="(row, index) in rows" :key="row.id">
            <div class="node" :class="{ active: selectedId === row.id, off: !row.enabled, optional: row.optional }"
              role="button" tabindex="0" @click="select(row)" @keydown.enter.prevent="select(row)">
              <span class="node-index">{{ index + 1 }}</span>
              <b class="node-label">{{ row.stageLabel }}</b>
              <code class="node-code">{{ row.stageCode }}</code>
              <span class="node-badges">
                <em :class="row.optional ? 'b-opt' : 'b-req'">{{ row.optional ? '按需' : '必经' }}</em>
                <em v-if="!row.enabled" class="b-off">停用</em>
              </span>
              <span v-if="row.usageCount" class="node-used">{{ row.usageCount }} 单</span>
              <span v-if="canManage" class="node-ops">
                <button type="button" :disabled="index === 0" title="上移" @click.stop="move(row, true)">↑</button>
                <button type="button" :disabled="index === rows.length - 1" title="下移" @click.stop="move(row, false)">↓</button>
              </span>
            </div>
            <span v-if="index < rows.length - 1" class="arrow" aria-hidden="true">→</span>
          </template>

          <button v-if="canManage" type="button" class="node node-add" @click="startCreate">
            <span class="plus">+</span><span>新增节点</span>
          </button>
        </div>

        <p class="chain-foot">
          停用的节点会在链条上变灰并退出分母，历史记录不动。节点条顺序同时决定进度表模板的列顺序——
          <b>线下 Excel 模板要人工加列</b>，系统没有模板下载功能，缺列导入会直接报错（刻意的，避免静默读错列）。
        </p>
      </section>

      <aside class="editor">
        <template v-if="mode !== 'idle'">
          <div class="editor-head">
            <h2>{{ mode === 'create' ? '新增节点' : '节点属性' }}</h2>
            <el-button v-if="mode === 'edit'" link size="small" @click="mode = 'idle'">收起</el-button>
          </div>

          <el-form label-position="top" size="small" class="editor-form">
            <el-form-item label="节点名称" required>
              <el-input v-model="form.label" maxlength="50" placeholder="如：物料配齐" />
            </el-form-item>
            <el-form-item label="节点编码" required>
              <el-input v-model="form.code" maxlength="30" :disabled="mode === 'edit'" placeholder="如：MATERIAL_READY" />
              <p class="field-hint">
                {{ mode === 'edit' ? '编码不可修改：历史里程碑与参考工期都按编码关联，要换编码请停用本节点再新建。' : '大写字母、数字与下划线，以字母开头。保存后不可修改。' }}
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
            <el-form-item label="启用">
              <el-switch v-model="form.enabled" />
            </el-form-item>
          </el-form>

          <div class="editor-actions">
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
            <el-button v-if="mode === 'edit'" plain type="danger"
              :disabled="(selected?.usageCount ?? 0) > 0" @click="remove">删除</el-button>
          </div>
          <p v-if="mode === 'edit' && selected?.usageCount" class="field-hint">
            已有 {{ selected.usageCount }} 条跟单记录，只能停用、不能删除。
          </p>
        </template>

        <el-empty v-else :image-size="72"
          :description="canManage ? '点左侧节点编辑，或「+ 新增节点」' : '查看制程需要 manufacturing.template.manage 权限'" />
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import {
  createTrackingStageDef,
  deleteTrackingStageDef,
  fetchTrackingStageDefs,
  fetchTrackingStageDefsImpact,
  moveTrackingStageDef,
  updateTrackingStageDef,
  type TrackingStageDef,
  type TrackingStageDefImpact,
  type TrackingStageDefInput,
} from '@/api/productionTracking'

/**
 * 制程管理：车间类别下的跟单节点序列。
 *
 * 节点序列原先硬编码在 ProductionTrackingPolicy 的常量里，加一个节点要改常量 + 写迁移 + 改测试 + 发版，
 * 还被拼进 SQL 字面量。落表后在这里自助维护，保存即刷新进程内快照，全站口径跟随。
 *
 * 与「生产参考工期」是两件事：本页定义<b>有哪些节点</b>，那一页定义<b>每个节点给多少天</b>。
 * 所以不再挤在参考工期页脚，独立成页。
 */
const { can } = useCurrentUser()
const canManage = computed(() => can('manufacturing.template.manage'))

const KINDS = [
  { kind: 'SEALER', label: '封口机车间', hint: '含氧化/喷特氟龙、物料配齐' },
  { kind: 'MACHINE', label: '其他机器车间', hint: '画图、钣金、调试' },
] as const
type Kind = typeof KINDS[number]['kind']

/** 两套制程都拉下来，类别卡片上才能直接对比节点规模。 */
const defs = reactive<Record<Kind, TrackingStageDef[]>>({ SEALER: [], MACHINE: [] })
const kind = ref<Kind>('SEALER')
const impact = ref<TrackingStageDefImpact | null>(null)
const loading = ref(false)

const rows = computed(() => defs[kind.value])
const selectedId = ref<number | null>(null)
const selected = computed(() => rows.value.find(row => row.id === selectedId.value) ?? null)
const mode = ref<'idle' | 'edit' | 'create'>('idle')
const saving = ref(false)

interface FormState extends TrackingStageDefInput { label: string; code?: string }
const blankForm = (): FormState => ({
  kind: kind.value, label: '', code: '', optional: false,
  matchKeywords: null, excludeKeywords: null, headerAliases: null, enabled: true,
})
const form = ref<FormState>(blankForm())

async function load() {
  loading.value = true
  try {
    const [sealer, machine] = await Promise.all([
      fetchTrackingStageDefs('SEALER'),
      fetchTrackingStageDefs('MACHINE'),
    ])
    // MySQL 的 TINYINT(1) 回来是 0/1，统一成布尔，否则 switch 绑上去会出现「1 也当假」。
    defs.SEALER = sealer.map(row => ({ ...row, optional: Boolean(row.optional), enabled: Boolean(row.enabled) }))
    defs.MACHINE = machine.map(row => ({ ...row, optional: Boolean(row.optional), enabled: Boolean(row.enabled) }))
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
  selectedId.value = null
  void loadImpact()
}

async function loadImpact() {
  impact.value = canManage.value ? await fetchTrackingStageDefsImpact(kind.value) : null
}

function select(row: TrackingStageDef) {
  if (!canManage.value) return
  selectedId.value = row.id
  mode.value = 'edit'
  form.value = {
    kind: kind.value, code: row.stageCode, label: row.stageLabel, optional: row.optional,
    matchKeywords: row.matchKeywords, excludeKeywords: row.excludeKeywords,
    headerAliases: row.headerAliases, enabled: row.enabled,
  }
}

function startCreate() {
  selectedId.value = null
  mode.value = 'create'
  form.value = blankForm()
}

function payload(): TrackingStageDefInput {
  return {
    kind: kind.value,
    code: form.value.code?.trim().toUpperCase() || undefined,
    label: form.value.label?.trim() ?? '',
    optional: form.value.optional,
    matchKeywords: form.value.matchKeywords?.trim() || null,
    excludeKeywords: form.value.excludeKeywords?.trim() || null,
    headerAliases: form.value.headerAliases?.trim() || null,
    enabled: form.value.enabled,
  }
}

/** 会改写完成判定的操作（增删改性质 / 启停）都先摆出影响面，让操作者自己确认。 */
async function confirmImpact(action: string) {
  if (!impact.value) return true
  try {
    await ElMessageBox.confirm(
      `该类别有 ${impact.value.openOrders} 张在制工单正在跟单，其中 ${impact.value.completedOrders} 张已判「跟单完成」。`
      + `「${action}」会当场改写这些单的完成判定。`,
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
  if (!(await confirmImpact(mode.value === 'create' ? '新增节点' : '修改节点'))) return
  saving.value = true
  try {
    if (mode.value === 'create') await createTrackingStageDef(payload())
    else if (selectedId.value) await updateTrackingStageDef(selectedId.value, payload())
    mode.value = 'idle'
    selectedId.value = null
    await load()
    ElMessage.success('制程已保存')
  } finally {
    saving.value = false
  }
}

/** 只换顺序：不影响完成判定的分子，故不弹确认。 */
async function move(row: TrackingStageDef, up: boolean) {
  await moveTrackingStageDef(row.id, up)
  await load()
}

async function remove() {
  if (!selected.value) return
  if (selected.value.usageCount > 0) { ElMessage.warning('该节点已有跟单记录，只能停用'); return }
  if (!(await confirmImpact('删除节点'))) return
  await deleteTrackingStageDef(selected.value.id)
  mode.value = 'idle'
  selectedId.value = null
  await load()
  ElMessage.success('节点已删除')
}
</script>

<style scoped>
.process-page { display: flex; flex-direction: column; gap: 16px; }
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

.layout { display: grid; grid-template-columns: minmax(0, 1fr) 340px; gap: 16px; align-items: start; }
@media (max-width: 1100px) { .layout { grid-template-columns: minmax(0, 1fr); } }

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
/* 停用：灰底虚线，一眼看出「链条上还在、但不参与分母」。 */
.node.off { background: #f5f7fa; border-style: dashed; opacity: .72; }
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
.b-off { background: #f4f4f5; color: #909399; }
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

.editor { display: flex; flex-direction: column; gap: 10px; position: sticky; top: 12px; }
.editor-head { display: flex; align-items: center; justify-content: space-between; }
.editor-form { margin-top: 4px; }
.field-hint { margin: 4px 0 0; font-size: 12px; line-height: 1.6; color: var(--color-text-secondary); }
.editor-actions { display: flex; gap: 8px; }
</style>
