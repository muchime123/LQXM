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
        <!-- ---------- 产品名称：氚云任务单产品字典的镜像，只读；每行可点选，把节点参考隔间接出去 ---------- -->
        <section class="block">
          <div class="block-head">
            <div>
              <h3>任务单产品</h3>
              <p>来源＝氚云生产任务单的产品下拉字典，产品名不可在本地修改。点选行可在右侧维护该产品的节点参考间隔。</p>
            </div>
            <div class="block-actions">
              <el-button size="small" @click="openCreate('PRODUCT_NAME')">补录产品</el-button>
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
                  <span class="product-name">{{ row.configName }}</span>
                  <el-tooltip v-if="row.sameNameCount > 1" content="该车间存在多个同名产品配置：参考日期匹配不猜，会沿用车间基准。请删除多余的那行以消除歧义（产品名不可改）。" placement="top">
                    <el-tag type="warning" size="small" effect="plain">重名 ×{{ row.sameNameCount }}</el-tag>
                  </el-tooltip>
                  <el-tag v-if="row.externalSource === 'MANUAL'" type="info" size="small" effect="plain">本地补录</el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="historicalTaskCount" label="历史任务单" width="118" align="center">
              <template #header>历史任务单 <el-tooltip content="氚云历史任务中「该车间 + 该产品名」的全部张数（含从未跟单、含草稿与已取消）。这不是样本量；点击数字看具体是哪些单。" placement="top"><el-icon><QuestionFilled /></el-icon></el-tooltip></template>
              <template #default="{ row }">
                <el-button link type="primary" class="count-link" @click.stop="emit('show-orders', row)">
                  {{ row.historicalTaskCount ?? '—' }}
                </el-button>
              </template>
            </el-table-column>
            <el-table-column prop="trackedTaskCount" label="跟单记录" width="100" align="center">
              <template #header>跟单记录 <el-tooltip content="历史任务单中已建跟单记录的张数——节点参考间隔的样本只取这些记录。点击查看每一单每段间隔。" placement="top"><el-icon><QuestionFilled /></el-icon></el-tooltip></template>
              <template #default="{ row }">
                <el-button v-if="row.trackedTaskCount" link type="primary" class="count-link" @click.stop="emit('show-samples', row)">
                  {{ row.trackedTaskCount }}
                </el-button>
                <span v-else class="none">—</span>
              </template>
            </el-table-column>
            <el-table-column label="发布节点" width="92" align="center">
              <template #default="{ row }">
                <span :class="{ none: publishedCount(row) === 0 }">{{ stageTotal ? `${publishedCount(row)} / ${stageTotal}` : '—' }}</span>
              </template>
            </el-table-column>
            <!-- 产品没有启用/禁用：能不能下单由氚云决定。删除只对本地补录行开放——
                 氚云字典镜像行（H3YUN）没被用过也不许删（可能是氚云新增还没下单的项，2026-10-21 复查 P0）。 -->
            <el-table-column label="操作" width="72" fixed="right">
              <template #default="{ row }">
                <el-tooltip v-if="row.externalSource === 'MANUAL'" content="删除本地补录错的产品行。已匹配历史任务、或已有参考间隔的产品会被后端拒绝。" placement="top">
                  <el-button link type="danger" @click.stop="removeConfig(row)">删除</el-button>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <!-- ---------- 可选配置：氚云任务单「可选配置」字典的镜像，同样只读；产线级，不随产品切换 ---------- -->
        <section class="block">
          <div class="block-head">
            <div>
              <h3>可选配置</h3>
              <p>来源＝氚云生产任务单的「可选配置」下拉字典（灌装方式、材质、电压、包装等），配置名不可在本地修改。仅属于本条产线。</p>
            </div>
            <div class="block-actions">
              <el-input v-model="configKeyword" size="small" clearable placeholder="搜索配置名称" class="kw" />
              <el-button size="small" @click="openCreate('OPTIONAL_CONFIG')">补录可选配置</el-button>
            </div>
          </div>
          <el-empty v-if="!optionalConfigs.length" :image-size="70" description="本条产线还没有可选配置" />
          <template v-else>
            <el-table :data="filteredConfigs" stripe size="small" class="grid" max-height="320">
              <el-table-column label="配置名称" min-width="120">
                <template #default="{ row }">
                  <div class="name-cell">
                    <span class="product-name">{{ row.configName }}</span>
                    <el-tooltip v-if="row.sameNameCount > 1" content="该车间存在多个同名可选配置：建单选择时会出现两个一样的选项。请删除多余的那行以消除歧义（名称不可改）。" placement="top">
                      <el-tag type="warning" size="small" effect="plain">重名 ×{{ row.sameNameCount }}</el-tag>
                    </el-tooltip>
                    <el-tag v-if="row.externalSource === 'MANUAL'" type="info" size="small" effect="plain">本地补录</el-tag>
                  </div>
                </template>
              </el-table-column>
              <!-- 同上：删除只对本地补录行开放，氚云字典镜像行不可删。 -->
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="{ row }">
                  <el-tooltip v-if="row.externalSource === 'MANUAL'" content="删除本地补录错的配置行。已被历史任务用过的会被后端拒绝。" placement="top">
                    <el-button link type="danger" @click.stop="removeConfig(row)">删除</el-button>
                  </el-tooltip>
                </template>
              </el-table-column>
            </el-table>
            <p v-if="configKeyword && !filteredConfigs.length" class="none">没有匹配「{{ configKeyword }}」的配置</p>
          </template>
        </section>
      </div>
    </template>

    <!-- 新增配置：父级 View 通过 :dialogOpen 接收，由子组件触发打开 -->
    <el-dialog v-model="dialogOpen" :title="createForm.configType === 'PRODUCT_NAME' ? '补录产品' : '补录可选配置'" width="min(520px, calc(100vw - 32px))">
      <el-form label-width="92px">
        <el-form-item label="所属车间">
          <el-input :model-value="workshop?.workshopName || ''" disabled />
        </el-form-item>
        <el-form-item :label="createForm.configType === 'PRODUCT_NAME' ? '产品名称' : '配置名称'" required>
          <el-input v-model="createForm.configName" maxlength="200" :placeholder="createForm.configType === 'PRODUCT_NAME' ? '须与氚云任务单产品下拉项完全一致（含型号、括号）' : '须与氚云任务单可选配置下拉项完全一致（如 灌装(量杯式)）'" />
        </el-form-item>
        <el-form-item label-width="0">
          <p class="hint">
            {{ createForm.configType === 'PRODUCT_NAME' ? '产品名' : '配置名' }}照抄氚云任务单
            「{{ createForm.configType === 'PRODUCT_NAME' ? '产品名称' : '可选配置' }}」下拉项，文本要逐字一致（空格、括号、大小写都算）。
            保存后<b>不可修改</b>，填错只能删除重录；已被氚云历史任务用过的删不掉。
          </p>
        </el-form-item>
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

/**
 * sameNameCount＝同车间同类型同名配置行数（>1 时产品基准不生效）；
 * externalSource＝'MANUAL' 表示本地补录（其余来自氚云字典）。
 * 没有 active：产品与可选配置都是氚云任务单下拉字典的镜像，有没有启用状态不由 LQXM 决定（2026-10-21）。
 */
interface ConfigRow { id: number; workshopId: number; configName: string; workshopName: string; configType: string; externalSource: string; historicalTaskCount: number; trackedTaskCount: number; sameNameCount: number }

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
  /** 点「历史任务单」数字：由外层打开该产品的历史生产任务明细抽屉。 */
  'show-orders': [row: ConfigRow]
  /** 点「跟单记录」数字：由外层打开该产品的样本明细抽屉（每单每段间隔）。 */
  'show-samples': [row: ConfigRow]
  changed: []
}>()

const configKeyword = ref('')
const dialogOpen = ref(false)
const creating = ref(false)
const newForm = () => ({ configType: 'PRODUCT_NAME' as 'PRODUCT_NAME' | 'OPTIONAL_CONFIG', configName: '' })
const createForm = ref(newForm())

/** 当前产线下的「产品名称」配置：作为右栏「节点参考间隔」的入口。
 *  **不按启用过滤**：产品没有启用/停用状态，50 个实测全在用，多一个开关只会让参考工期静默失效。 */
const products = computed<ConfigRow[]>(() => props.configs.filter(c => c.configType === 'PRODUCT_NAME'))
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

const rowClassName = ({ row }: { row: ConfigRow }) => row.id === props.selectedTaskConfigId ? 'is-selected' : ''

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
    // 只有身份本身：名称 + 车间 + 类型。没有 active（两类配置都没有启用概念，V400 已删列）。
    await http.post('/manufacturing/master/task-configs', {
      workshopId: props.workshop.id,
      configType: createForm.value.configType,
      configName: createForm.value.configName.trim()
    })
    dialogOpen.value = false
    emit('changed')
    ElMessage.success(createForm.value.configType === 'PRODUCT_NAME' ? '已补录' : '已新增')
  } finally { creating.value = false }
}

/** 删除本地补录错的配置行。名称只读，所以这是唯一的纠正路径；硬保护在后端
 *  （来源＝H3YUN 字典镜像行一律拒绝；产品：有历史任务 / 有参考间隔；可选配置：历史任务用过 → 409）。 */
async function removeConfig(row: ConfigRow) {
  const what = row.configType === 'PRODUCT_NAME' ? '任务单产品' : '可选配置'
  try {
    await ElMessageBox.confirm(
      `删除${what}「${row.configName}」？已被氚云历史任务用过的会被拒绝删除——那是真实在用的${what}，不是本地产物。`,
      `删除${what}`,
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    await http.delete(`/manufacturing/master/task-configs/${row.id}`)
    if (props.selectedTaskConfigId === row.id) emit('update:selectedTaskConfigId', 0)
    emit('changed')
    ElMessage.success(`已删除「${row.configName}」`)
  } catch (e: any) {
    // 后端拒绝原因（有历史任务 / 有参考间隔）必须原样显示，否则用户只会看到「删除失败」
    ElMessage.error(e?.message || '删除失败')
  }
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
.name-cell { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
/* 产品名只读展示：不可编辑，所以给正文色而不是输入框观感，避免被误当成可改字段。 */
.product-name { color: var(--color-text-heading, #0f172a); font-weight: 500; }
.hint { margin: 0; font-size: 12px; line-height: 1.6; color: var(--color-warning, #d97706); }
.none { margin: 0; font-size: 12px; color: var(--color-text-secondary, #94a3b8); }
/* 可点的计数：数字本身就是明细入口，用等宽数字避免行与行之间跳动。 */
.count-link { font-variant-numeric: tabular-nums; font-weight: 600; }

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

@media (max-width: 760px) {
  .kw { width: 100%; }
  .block-actions { width: 100%; }
}
</style>
