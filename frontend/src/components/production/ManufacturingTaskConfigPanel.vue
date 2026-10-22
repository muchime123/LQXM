<template>
  <section class="detail">
    <el-empty v-if="!workshop" description="选择左侧生产车间以维护该产线下的产品与配置" />
    <template v-else>
      <div class="blocks">
        <!-- ---------- 产品名称：氚云任务单产品字典的镜像，只读；每行可点选，把节点参考隔间接出去 ---------- -->
        <section class="block">
          <div class="block-head">
            <div>
              <h3>{{ workshop.workshopName }} · 任务单产品
                <el-tooltip content="一行对照产品参考工期、实测工期与各节点生效参考值。点选产品后在下方维护节点；任务和样本数字可查看完整明细。" placement="top">
                  <el-icon class="help-icon"><QuestionFilled /></el-icon>
                </el-tooltip>
              </h3>
            </div>
            <div class="block-actions">
              <el-button v-if="canManageBaseline" size="small" type="primary" plain :loading="batchWorking" :disabled="!products.length" @click="emit('batch-calculate')">{{ batchWorking ? `批量处理中 ${batchProgress}` : '批量计算并填充' }}</el-button>
              <el-button size="small" @click="optionalVisible = true">可选配置（{{ optionalConfigs.length }}）</el-button>
              <el-button size="small" @click="openCreate('PRODUCT_NAME')">补录产品</el-button>
            </div>
          </div>
          <el-empty v-if="!products.length" :image-size="70" description="本条产线还没有产品名称" />
          <!-- 选中态只用受控的 is-selected 表达：不再叠 Element 自己的 highlight-current-row，
               两套高亮同时存在会在抽屉/弹窗打开后的焦点变化里互相打架，出现「莫名高亮」的行。 -->
          <el-table v-else :data="products" stripe size="small" class="grid" max-height="380"
            :default-sort="defaultSort" :row-class-name="rowClassName" @row-click="selectRow">
            <el-table-column prop="configName" label="产品名称" min-width="180" sortable :sort-method="byName">
              <template #default="{ row }">
                <div class="name-cell">
                  <span class="product-name">{{ row.configName }}</span>
                  <el-tooltip v-if="row.sameNameCount > 1" content="该车间存在多个同名产品配置：参考日期匹配不猜，会沿用通用参考。请删除多余的那行以消除歧义（产品名不可改）。" placement="top">
                    <el-tag type="warning" size="small" effect="plain">重名 ×{{ row.sameNameCount }}</el-tag>
                  </el-tooltip>
                  <el-tag v-if="row.externalSource === 'MANUAL'" type="info" size="small" effect="plain">本地补录</el-tag>
                  <el-tooltip v-if="row.externalSource === 'MANUAL'" content="删除本地补录错的产品；已匹配历史任务或已有参考间隔时后端会拒绝" placement="top">
                    <el-button link type="danger" size="small" @click.stop="removeConfig(row)">删除</el-button>
                  </el-tooltip>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="参考工期" width="105" align="center">
              <template #default="{ row }">
                <b v-if="durations[row.id] && durations[row.id].missingStages.length < durations[row.id].stageCount">{{ durations[row.id].chainDays }} 天</b>
                <span v-else class="none">—</span>
              </template>
            </el-table-column>
            <el-table-column label="实测 P50" width="105" align="center">
              <template #default="{ row }">
                <span v-if="durations[row.id]?.actualP50Days != null" :title="`${durations[row.id].actualSampleCount} 单完成`">{{ durations[row.id].actualP50Days!.toFixed(1) }} 天</span>
                <span v-else class="none">—</span>
              </template>
            </el-table-column>
            <el-table-column label="节点参考工期（产品优先，通用兜底）" min-width="330">
              <template #default="{ row }">
                <div v-if="durations[row.id]" class="stage-chips">
                  <span v-for="stage in durations[row.id].stages" :key="stage.stageCode" class="stage-chip" :class="{ product: stage.source === 'PRODUCT', missing: stage.days == null }"
                    :title="`${stage.stageLabel}：${stage.source === 'PRODUCT' ? '产品已发布' : stage.source === 'GENERAL' ? '沿用通用' : '未配置'}`">
                    {{ stage.stageLabel }} {{ stage.days == null ? '—' : `${stage.days}天` }}
                  </span>
                </div>
                <span v-else class="none">加载中…</span>
              </template>
            </el-table-column>
            <el-table-column prop="historicalTaskCount" label="任务单" width="120" align="center" sortable>
              <template #header>任务单 <el-tooltip content="氚云历史任务中「该车间 + 该产品名」的全部张数（含从未跟单、含草稿与已取消）。这不是样本量；点击数字看具体是哪些单。" placement="top"><el-icon><QuestionFilled /></el-icon></el-tooltip></template>
              <template #default="{ row }">
                <el-button link type="primary" class="count-link" @click.stop="emit('show-orders', row)">
                  {{ row.historicalTaskCount ?? '—' }}
                </el-button>
              </template>
            </el-table-column>
            <el-table-column prop="trackedTaskCount" label="跟单" width="120" align="center" sortable>
              <template #header>跟单 <el-tooltip content="历史任务单中已建跟单记录的张数——产品参考工期的样本只取这些记录。点击查看每一单每段间隔。" placement="top"><el-icon><QuestionFilled /></el-icon></el-tooltip></template>
              <template #default="{ row }">
                <el-button v-if="row.trackedTaskCount" link type="primary" class="count-link" @click.stop="emit('show-samples', row)">
                  {{ row.trackedTaskCount }}
                </el-button>
                <span v-else class="none">—</span>
              </template>
            </el-table-column>
            <!-- 发布节点：分子＝该产品已发布的必经节点数，分母＝该车间类别的必经节点总数。
                 点开看每个节点「发布的参考天数 / 来源 / 样本量」，未发布的节点在这里也列出来（沿用通用参考）。 -->
            <el-table-column prop="publishedCount" label="发布节点" width="135" align="center" sortable :sort-method="byPublished">
              <template #header>发布节点 <el-tooltip content="已发布的必经节点数 / 该车间类别的必经节点总数（按需工序不计入分母）。点数字看每个节点发布的间隔与来源。" placement="top"><el-icon><QuestionFilled /></el-icon></el-tooltip></template>
              <template #default="{ row }">
                <el-button link :type="publishedCount(row) ? 'primary' : 'info'" class="count-link" @click.stop="openStages(row)">
                  {{ stageTotal ? `${publishedCount(row)} / ${stageTotal}` : '—' }}
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </div>
    </template>

    <!-- 可选配置是车间级字典，按需打开，不占产品工期工作区。 -->
    <el-drawer v-model="optionalVisible" :title="`${workshop?.workshopName ?? ''} · 可选配置`" size="min(620px, 100vw)" append-to-body>
        <section class="optional-content">
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
                    <el-tooltip v-if="row.externalSource === 'MANUAL'" content="删除本地补录错的配置；历史任务用过时后端会拒绝" placement="top">
                      <el-button link type="danger" size="small" @click.stop="removeConfig(row)">删除</el-button>
                    </el-tooltip>
                  </div>
                </template>
              </el-table-column>
            </el-table>
            <p v-if="configKeyword && !filteredConfigs.length" class="none">没有匹配「{{ configKeyword }}」的配置</p>
          </template>
        </section>
    </el-drawer>

    <!-- 节点发布明细：中栏「发布节点 x/y」数字的下钻。只读——真正的编辑在右栏面板，
         这里回答的是「这 x 个已发布节点分别是哪些、值多少、谁定的」。 -->
    <el-dialog v-model="stagesVisible" :title="`节点发布明细 · ${stageProduct?.configName ?? ''}`"
      width="min(720px, calc(100vw - 32px))">
      <!-- 节点矩阵需要 manufacturing.baseline.manage：无权限时 matrix 为空，这里给明确空态，
           不能留骨架屏转圈（那会让用户以为还在加载）。 -->
      <el-empty v-if="!stageDetailRows.length" :image-size="70" description="没有加载到节点矩阵：查看节点明细需要产品参考工期权限" />
      <template v-else>
        <p class="stage-hint">
          分母只数<b>必经节点</b>（按需工序如氧化、木箱不计）；未发布的节点沿用通用参考。
          编辑产品参考天数请关闭本窗后在右侧面板操作。
        </p>
        <el-table :data="stageDetailRows" size="small" stripe border>
          <el-table-column label="节点" min-width="110">
            <template #default="{ row }">
              <span class="product-name">{{ row.stageLabel }}</span>
              <el-tag v-if="row.optionalStage" size="small" effect="plain" type="info">按需</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="区间" min-width="140">
            <template #default="{ row }"><span class="muted">{{ row.intervalLabel }}</span></template>
          </el-table-column>
          <el-table-column label="参考天数" width="110" align="right">
            <template #default="{ row }">
              <b v-if="row.typicalDays != null">{{ row.typicalDays }} 天</b>
              <span v-else class="muted">{{ row.workshopDays != null ? `${row.workshopDays} 天` : '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.typicalDays != null" size="small" effect="plain"
                :type="row.publishedSource === 'MANUAL' ? 'success' : 'warning'">{{ publishText(row) }}</el-tag>
              <span v-else class="muted">未发布（沿用通用）</span>
            </template>
          </el-table-column>
          <el-table-column label="样本 / 零天" width="100" align="center">
            <template #default="{ row }">
              <span :class="{ muted: !row.sampleCount }">{{ row.sampleCount || '—' }}</span>
              <template v-if="row.sampleCount"><span class="muted"> / {{ row.zeroDayCount }}</span></template>
            </template>
          </el-table-column>
          <el-table-column label="P50 / P75" width="108" align="right">
            <template #default="{ row }">
              <span :class="{ muted: row.p50Days == null }">{{ row.p50Days ?? '—' }}</span>
              <span class="muted"> / {{ row.p75Days ?? '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer><el-button @click="stagesVisible = false">关闭</el-button></template>
    </el-dialog>

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
import type { ProductDuration, TrackingBenchmarkWorkshop, TrackingProductBenchmarkRow } from '@/api/productionTracking'

/**
 * sameNameCount＝同车间同类型同名配置行数（>1 时产品基准不生效）；
 * externalSource＝'MANUAL' 表示本地补录（其余来自氚云字典）。
 * 没有 active：产品与可选配置都是氚云任务单下拉字典的镜像，有没有启用状态不由 LQXM 决定（2026-10-21）。
 */
interface ConfigRow { id: number; workshopId: number; configName: string; workshopName: string; configType: string; externalSource: string; historicalTaskCount: number; trackedTaskCount: number; sameNameCount: number }

const props = defineProps<{
  /** 当前选中的产线（外层提供产品与配置计数）。 */
  workshop: WorkshopCard | null
  /** 当前产线下所有配置（产品 + 可选配置），由父级过滤后传入。 */
  configs: ConfigRow[]
  /** 当前选中的产品行 id（任务单产品名称配置行），用于高亮并外推到右栏。 */
  selectedTaskConfigId: number | null
  /** 产品级基准矩阵 + 该产品已发布的间隔行，用于「已发布节点」统计与已发布数提示。 */
  matrix: TrackingBenchmarkWorkshop[]
  benchmarks: TrackingProductBenchmarkRow[]
  durations: Record<number, ProductDuration>
  canManageBaseline: boolean
  batchWorking: boolean
  batchProgress: string
}>()
const emit = defineEmits<{
  'update:selectedTaskConfigId': [value: number]
  /** 点「历史任务单」数字：由外层打开该产品的历史生产任务明细抽屉。 */
  'show-orders': [row: ConfigRow]
  /** 点「跟单记录」数字：由外层打开该产品的样本明细抽屉（每单每段间隔）。 */
  'show-samples': [row: ConfigRow]
  'batch-calculate': []
  changed: []
}>()

const configKeyword = ref('')
const optionalVisible = ref(false)
const dialogOpen = ref(false)
const creating = ref(false)
const newForm = () => ({ configType: 'PRODUCT_NAME' as 'PRODUCT_NAME' | 'OPTIONAL_CONFIG', configName: '' })
const createForm = ref(newForm())

/** 当前产线下的「产品名称」配置：作为右栏「产品节点参考工期」的入口。
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

/** 节点数取该产线类别（封口机 / 其他机器）对应的通用参考矩阵，且**只数必经节点**：
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

/* ---------------- 排序：默认按「跟单记录」降序 ---------------- */

/**
 * 默认排序＝跟单记录降序（2026-10-22 LQ 定）：这是唯一能说明「这个产品有多少可算样本」的列，
 * 历史任务单多但没人跟单的产品对参考工期没有价值，排在后面才不会被当成主力。
 * 排序在本地做（一台车间只有二十来个产品，全部已加载），不额外加后端参数。
 */
const defaultSort = { prop: 'trackedTaskCount', order: 'descending' } as const
/** 产品名用中文 Collator 排：默认的码点排序会把「气动…」和「手动…」按 Unicode 打散，看着像乱序。 */
const nameCollator = new Intl.Collator('zh-Hans-CN', { numeric: true, sensitivity: 'base' })
const byName = (a: ConfigRow, b: ConfigRow) => nameCollator.compare(a.configName ?? '', b.configName ?? '')
const byPublished = (a: ConfigRow, b: ConfigRow) => publishedCount(a) - publishedCount(b)

/* ---------------- 「发布节点 x/y」下钻 ---------------- */

interface StageDetail { stageCode: string; stageLabel: string; intervalLabel: string; optionalStage: boolean; workshopDays: number | null; typicalDays: number | null; publishedSource: 'MANUAL' | 'P50' | 'COPY' | null; publishedAt: string | null; sampleCount: number; p50Days: number | null; p75Days: number | null; zeroDayCount: number }

const stagesVisible = ref(false)
const stageProduct = ref<ConfigRow | null>(null)

/** 该产品每个节点的发布值 + 候选统计：未发布的也列出来，才能看出「差哪几个节点」。 */
const stageDetailRows = computed<StageDetail[]>(() => {
  const product = stageProduct.value
  if (!product) return []
  const stages = props.matrix.find(m => m.workshopKind === workshopKind.value)?.stages ?? []
  return stages.map(stage => {
    const value = props.benchmarks.find(b => b.taskConfigId === product.id && b.stageCode === stage.stageCode)
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
      zeroDayCount: value?.zeroDayCount ?? 0
    }
  })
})

function openStages(row: ConfigRow) { stageProduct.value = row; stagesVisible.value = true }
/** 与右栏面板同一套来源文案：人工发布 / P50 填充 / 复制，附带发布日。 */
function publishText(row: StageDetail) {
  const source = row.publishedSource === 'P50' ? 'P50 填充' : row.publishedSource === 'COPY' ? '复制' : '人工发布'
  return row.publishedAt ? `${source} · ${String(row.publishedAt).slice(5, 10)}` : source
}

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
watch(() => props.workshop?.id, () => { configKeyword.value = ''; optionalVisible.value = false })
</script>

<style scoped>
.detail { display: grid; gap: var(--space-lg-xl, 16px); min-width: 0; }


.blocks { display: grid; gap: var(--space-lg-xl, 16px); align-items: start; min-width: 0; }
.block { min-width: 0; background: var(--color-surface, #fff); border: 1px solid var(--color-border, #e2e8f0); border-radius: var(--radius-2xl, 12px); padding: var(--space-lg-xl, 16px); box-shadow: var(--shadow-sm, 0 1px 3px rgba(0,0,0,.04)); }
.optional-content { display: grid; gap: 12px; }
.block-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.block-head h3 { margin: 0 0 4px; font-size: 15px; color: var(--color-text-heading, #0f172a); }
.help-icon { margin-left: 4px; color: var(--color-text-secondary, #94a3b8); font-size: 13px; vertical-align: -2px; cursor: help; }
.block-head p { margin: 0; font-size: 12px; color: var(--color-text-secondary, #5f6f82); }
.block-actions { display: flex; align-items: center; gap: 8px; }
.kw { width: 200px; }
.name-cell { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
/* 产品名只读展示：不可编辑，所以给正文色而不是输入框观感，避免被误当成可改字段。 */
.product-name { color: var(--color-text-heading, #0f172a); font-weight: 500; }
.hint { margin: 0; font-size: 12px; line-height: 1.6; color: var(--color-warning, #d97706); }
.none { margin: 0; font-size: 12px; color: var(--color-text-secondary, #94a3b8); }
.muted { color: var(--color-text-secondary, #94a3b8); }
.stage-hint { margin: 0 0 10px; font-size: 12px; line-height: 1.6; color: var(--color-text-secondary, #5f6f82); }
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
.stage-chips { display: flex; flex-wrap: wrap; gap: 4px; padding: 3px 0; }
.stage-chip { padding: 2px 6px; border-radius: 5px; background: var(--color-surface-alt, #f1f5f9); color: var(--color-text-secondary, #64748b); font-size: 11px; white-space: nowrap; }
.stage-chip.product { background: var(--color-primary-bg-light, #ecfdf5); color: var(--color-primary, #0f766e); font-weight: 600; }
.stage-chip.missing { color: var(--color-danger, #dc2626); }

@media (max-width: 760px) {
  .kw { width: 100%; }
  .block-actions { width: 100%; }
}
</style>
