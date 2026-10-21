<template>
  <section class="detail">
    <el-empty v-if="!workshop" description="选择左侧生产车间以维护该产线下的产品与配置" />
    <template v-else>
      <header class="detail-head">
        <div class="detail-title">
          <h2>{{ workshop.workshopName }}</h2>
          <p class="detail-sub">
            默认制程
            <el-tag v-if="workshop.defaultTemplateName" size="small" effect="plain">{{ workshop.defaultTemplateName }}</el-tag>
            <el-tag v-else size="small" type="danger" effect="plain">未设置</el-tag>
            <span v-if="!workshop.defaultTemplateName" class="note">请维护产线默认制程，建单时自动匹配</span>
          </p>
        </div>
      </header>

      <div class="blocks">
        <!-- ---------- 产品名称：每行可点选，把节点参考隔间接出去 ---------- -->
        <section class="block">
          <div class="block-head">
            <div>
              <h3>产品名称</h3>
              <p>本条产线可下单的产品。点击行可在右侧维护该产品的节点参考间隔。</p>
            </div>
            <div class="block-actions">
              <el-checkbox v-model="includeInactive" size="small">含停用</el-checkbox>
              <el-button type="primary" size="small" @click="openCreate('PRODUCT_NAME')">新增产品名称</el-button>
            </div>
          </div>
          <el-empty v-if="!products.length" :image-size="70" description="本条产线还没有产品名称" />
          <!-- 选中态只用受控的 is-selected 表达：不再叠 Element 自己的 highlight-current-row，
               两套高亮同时存在会在抽屉/弹窗打开后的焦点变化里互相打架，出现「莫名高亮」的行。 -->
          <el-table v-else :data="products" stripe size="small" class="grid" max-height="380"
            :row-class-name="rowClassName" @row-click="selectRow">
            <el-table-column label="产品名称" min-width="160">
              <template #default="{ row }">
                <div class="name-cell">
                  <el-input v-model="row.configName" maxlength="200" size="small" @click.stop />
                  <el-tooltip v-if="row.sameNameCount > 1" content="该车间存在多个同名产品配置：参考日期匹配不猜，会沿用车间基准。请合并或改名以消除歧义。" placement="top">
                    <el-tag type="warning" size="small" effect="plain">重名 ×{{ row.sameNameCount }}</el-tag>
                  </el-tooltip>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="historicalTaskCount" label="历史任务单" width="130" align="center">
              <template #header>历史任务单 <el-tooltip content="氚云历史任务中「该车间 + 该产品名」的全部张数（含从未跟单的）。这不是样本量。" placement="top"><el-icon><QuestionFilled /></el-icon></el-tooltip></template>
            </el-table-column>
            <el-table-column prop="trackedTaskCount" label="跟单记录" width="110" align="center">
              <template #header>跟单记录 <el-tooltip content="历史任务单中已建跟单记录的张数——节点参考间隔的样本只取这些记录" placement="top"><el-icon><QuestionFilled /></el-icon></el-tooltip></template>
              <template #default="{ row }">
                <span :class="{ none: !row.trackedTaskCount }">{{ row.trackedTaskCount ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="发布节点" width="100" align="center">
              <template #default="{ row }">
                <span :class="{ none: publishedCount(row) === 0 }">{{ stageTotal ? `${publishedCount(row)} / ${stageTotal}` : '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="70">
              <template #default="{ row }"><el-switch v-model="row.active" size="small" @click.stop /></template>
            </el-table-column>
            <el-table-column label="操作" width="80" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :loading="saving === row.id" @click.stop="save(row)">保存</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <!-- ---------- 可选配置：产线级，不随产品切换 ---------- -->
        <section class="block">
          <div class="block-head">
            <div>
              <h3>可选配置</h3>
              <p>建单时随产品一起选择的选项（灌装方式、材质、电压、包装等）。仅属于本条产线。</p>
            </div>
            <div class="block-actions">
              <el-input v-model="configKeyword" size="small" clearable placeholder="搜索配置名称" class="kw" />
              <el-button type="primary" size="small" @click="openCreate('OPTIONAL_CONFIG')">新增可选配置</el-button>
            </div>
          </div>
          <el-empty v-if="!optionalConfigs.length" :image-size="70" description="本条产线还没有可选配置" />
          <template v-else>
            <el-table :data="filteredConfigs" stripe size="small" class="grid" max-height="320">
              <el-table-column label="配置名称" min-width="120">
                <template #default="{ row }"><el-input v-model="row.configName" maxlength="200" size="small" /></template>
              </el-table-column>
              <el-table-column label="启用" width="64">
                <template #default="{ row }"><el-switch v-model="row.active" size="small" /></template>
              </el-table-column>
              <el-table-column label="操作" width="64" fixed="right">
                <template #default="{ row }">
                  <el-button link type="primary" :loading="saving === row.id" @click="save(row)">保存</el-button>
                </template>
              </el-table-column>
            </el-table>
            <p v-if="configKeyword && !filteredConfigs.length" class="none">没有匹配「{{ configKeyword }}」的配置</p>
          </template>
        </section>
      </div>
    </template>

    <!-- 新增配置：父级 View 通过 :dialogOpen 接收，由子组件触发打开 -->
    <el-dialog v-model="dialogOpen" :title="createForm.configType === 'PRODUCT_NAME' ? '新增产品名称' : '新增可选配置'" width="min(520px, calc(100vw - 32px))">
      <el-form label-width="92px">
        <el-form-item label="所属车间">
          <el-input :model-value="workshop?.workshopName || ''" disabled />
        </el-form-item>
        <el-form-item :label="createForm.configType === 'PRODUCT_NAME' ? '产品名称' : '配置名称'" required>
          <el-input v-model="createForm.configName" maxlength="200" :placeholder="createForm.configType === 'PRODUCT_NAME' ? '如 全自动气调机 500 型' : '如 灌装(量杯式)'" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="createForm.active" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="createConfig">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import { http } from '@/api/http'
import type { WorkshopCard } from './WorkshopSelector.vue'
import type { TrackingBenchmarkWorkshop, TrackingProductBenchmarkRow } from '@/api/productionTracking'

/** originalName＝本次加载时的名称，用于识别「这次保存是改名」；sameNameCount＝同车间同类型同名配置行数（>1 时产品基准不生效）。 */
interface ConfigRow { id: number; workshopId: number; configName: string; originalName: string; workshopName: string; configType: string; active: boolean; historicalTaskCount: number; trackedTaskCount: number; sameNameCount: number }

const props = defineProps<{
  /** 当前选中的产线（外层提供，含默认制程/产品数等聚合值），便于复用同一卡片样式。 */
  workshop: WorkshopCard | null
  /** 当前产线下所有配置（产品 + 可选配置），由父级过滤后传入。 */
  configs: ConfigRow[]
  /** 当前选中的产品行 id（任务单产品名称配置行），用于高亮并外推到右栏。 */
  selectedTaskConfigId: number | null
  /** 产品级基准矩阵 + 该产品已发布的间隔行，用于「已发布节点」统计与已发布数提示。 */
  matrix: TrackingBenchmarkWorkshop[]
  benchmarks: TrackingProductBenchmarkRow[]
}>()
const emit = defineEmits<{
  'update:selectedTaskConfigId': [value: number]
  changed: []
}>()

const configKeyword = ref('')
/** 停用产品默认隐藏：它不再可下单，但仍需能翻出来维护已发布的间隔、或重新启用。 */
const includeInactive = ref(false)
const saving = ref<number | null>(null)
const dialogOpen = ref(false)
const creating = ref(false)
const newForm = () => ({ configType: 'PRODUCT_NAME' as 'PRODUCT_NAME' | 'OPTIONAL_CONFIG', configName: '', active: true })
const createForm = ref(newForm())

/** 当前产线下的「产品名称」配置：作为右栏「节点参考间隔」的入口。 */
const products = computed<ConfigRow[]>(() => props.configs.filter(c => c.configType === 'PRODUCT_NAME' && (includeInactive.value || c.active)))
const optionalConfigs = computed<ConfigRow[]>(() => props.configs.filter(c => c.configType !== 'PRODUCT_NAME'))
const filteredConfigs = computed(() => {
  const k = configKeyword.value.trim().toLowerCase()
  return k ? optionalConfigs.value.filter(c => c.configName.toLowerCase().includes(k)) : optionalConfigs.value
})

/** 车间类别与后端一致：车间名含「封口」即封口机节点序列。 */
const workshopKind = computed(() => props.workshop?.workshopName.includes('封口') ? 'SEALER' : 'MACHINE')
/** 按需工序（氧化 / 木箱）节点集合：不进已发布分母。 */
const optionalStageCodes = computed(() => new Set(
  (props.matrix.find(m => m.workshopKind === workshopKind.value)?.stages ?? [])
    .filter(s => s.optionalStage).map(s => s.stageCode)))

/** 节点数取该产线类别（封口机 / 其他机器）对应的车间基准表，且**只数必经节点**：
 *  按需工序（氧化 / 木箱）没做不算缺，否则分母虚高、永远显示不满。 */
const stageTotal = computed(() => {
  if (!props.workshop) return 0
  const stages = props.matrix.find(m => m.workshopKind === workshopKind.value)?.stages ?? []
  return stages.filter(s => !s.optionalStage).length
})
/** 已发布数与分母同源：只数必经节点的发布值。 */
const publishedCount = (row: ConfigRow) => props.benchmarks.filter(b =>
  b.taskConfigId === row.id && b.typicalDays != null
  && b.stageCode != null && !optionalStageCodes.value.has(b.stageCode)).length

const rowClassName = ({ row }: { row: ConfigRow }) => [
  row.id === props.selectedTaskConfigId ? 'is-selected' : '',
  row.active ? '' : 'is-inactive'
].filter(Boolean).join(' ')

function selectRow(row: ConfigRow) { emit('update:selectedTaskConfigId', row.id) }
function openCreate(type: 'PRODUCT_NAME' | 'OPTIONAL_CONFIG') {
  if (!props.workshop) { ElMessage.warning('请先选择生产车间'); return }
  createForm.value = { ...newForm(), configType: type }
  dialogOpen.value = true
}

async function createConfig() {
  if (!props.workshop) return
  if (!createForm.value.configName.trim()) { ElMessage.warning('请填写配置名称'); return }
  creating.value = true
  try {
    await http.post('/manufacturing/master/task-configs', {
      workshopId: props.workshop.id,
      configType: createForm.value.configType,
      configName: createForm.value.configName.trim(),
      active: createForm.value.active
    })
    dialogOpen.value = false
    emit('changed')
    ElMessage.success('已新增')
  } finally { creating.value = false }
}

async function save(row: ConfigRow) {
  const name = row.configName.trim()
  if (!name) { ElMessage.warning('请填写产品名称'); return }
  // 产品身份是「车间 + 产品名」的文本匹配：改名会断开历史任务，先让用户看见代价再改。
  if (name !== row.originalName && row.historicalTaskCount > 0) {
    try {
      await ElMessageBox.confirm(
        `「${row.originalName}」当前匹配 ${row.historicalTaskCount} 张历史任务；改名后这些任务不再匹配到该产品（历史样本与覆盖率会下降）。已发布的节点参考间隔仍保留在该产品上。`,
        '改名会断开历史任务匹配',
        { type: 'warning', confirmButtonText: '确认改名', cancelButtonText: '取消' }
      )
    } catch { return }
  }
  saving.value = row.id
  try {
    await http.put(`/manufacturing/master/task-configs/${row.id}`, { configName: name, active: row.active })
    ElMessage.success(`已保存「${name}」`)
    emit('changed')
  } finally { saving.value = null }
}

/** 切换产线时清空产品搜索与选中，避免「上条产线的产品」被高亮到新产线。 */
watch(() => props.workshop?.id, () => { configKeyword.value = '' })
</script>

<style scoped>
.detail { display: grid; gap: var(--space-lg-xl, 16px); min-width: 0; }

.detail-head { padding: 16px 20px; background: linear-gradient(135deg, var(--color-primary-bg-light, #f0fdfa) 0%, var(--color-surface-raised, #fbfcfd) 100%); border: 1px solid var(--color-primary-border, #a7f3d0); border-radius: var(--radius-2xl, 12px); }
.detail-title h2 { margin: 0 0 6px; font-size: 20px; color: var(--color-text-heading, #0f172a); }
.detail-sub { margin: 0; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; color: var(--color-text-secondary, #5f6f82); }
.detail-sub .note { font-size: 12px; color: var(--color-danger, #dc2626); }

.blocks { display: grid; gap: var(--space-lg-xl, 16px); align-items: start; min-width: 0; }
.block { min-width: 0; background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-2xl, 12px); padding: var(--space-lg-xl, 16px); box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.04)); }
.block-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.block-head h3 { margin: 0 0 4px; font-size: 15px; color: var(--color-text-heading, #0f172a); }
.block-head p { margin: 0; font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.block-actions { display: flex; align-items: center; gap: 8px; }
.kw { width: 200px; }
.name-cell { display: flex; align-items: center; gap: 6px; }
.none { margin: 0; font-size: 12px; color: var(--color-text-secondary, #94a3b8); }

.grid :deep(th.el-table__cell) { background: var(--color-surface-alt, #f8fafc); color: var(--color-text-heading, #0f172a); font-weight: 600; }
.grid :deep(th.el-table__cell .cell) { white-space: nowrap; }
.grid :deep(.el-table) { --el-table-border-color: var(--color-border, #e2e8f0); }
/* 选中行加左侧色条，与产线选中态保持一致；点击整行即可选中。 */
.grid :deep(tr.is-selected td.el-table__cell) { background: var(--color-primary-bg-light, #f0fdfa) !important; box-shadow: inset 3px 0 0 var(--color-primary, #0f766e); }
/* 选中行的 hover 也保持选中色：否则鼠标划过会把选中态盖掉，看起来像选中行自己变了。 */
.grid :deep(tr.is-selected:hover td.el-table__cell) { background: var(--color-primary-bg-light, #f0fdfa) !important; }
.grid :deep(tbody tr) { cursor: pointer; }
/* 名称列是表里唯一的弹性列，宽屏下会被拉到三四百像素、整行稀疏：行内输入框限宽，多余空间留在单元格右侧。 */
.grid :deep(td .el-input) { max-width: 260px; }
/* 停用行灰显：不再可下单，但仍要能翻出来维护间隔或重新启用。 */
.grid :deep(tr.is-inactive td.el-table__cell) { color: var(--color-text-secondary, #94a3b8); }

@media (max-width: 760px) {
  .kw { width: 100%; }
  .block-actions { width: 100%; }
}
</style>
