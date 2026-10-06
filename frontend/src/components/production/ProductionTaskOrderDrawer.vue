<template>
  <!--
    独立生产任务单（展会、展厅、打样等不依赖销售订单的需求）—— 抽屉形态。
    形态纪律：下单是「车间执行页右上角按钮」调出的抽屉，不是独立侧边栏页面。
    联动纪律：先选产线与产品名称，制程跟随「任务单产品与配置」中产品绑定的制程，
              未单独绑定时取产线默认制程；该产线的「产品名称 / 可选配置」才可选
              （数据源 = 制程与工序管理 → 任务单产品与配置）。
    建单纪律：用户选择的是制程品类；后端根据配置统一生成规范产品名称和 skuKey。
              建单不要求先选库存产品，正式产品身份在完工入库前确认；单位默认「台」（后端兜底）。
  -->
  <el-drawer v-model="visible" :title="configureOnly ? '生产任务单 · 配置销售需求' : '生产任务单'" :size="drawerSize" :close-on-click-modal="!submitting"
    :close-on-press-escape="!submitting" :show-close="!submitting" :before-close="beforeClose" @open="onOpen">
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="to-form">
      <el-alert v-if="!configureOnly" type="info" :closable="false" show-icon class="to-tip"
        title="销售需求从「销售订单」发起；展会、展厅、打样等无销售订单的任务可在这里直接新建。" />
      <el-alert v-else type="info" :closable="false" show-icon class="to-tip"
        title="沿用生产任务单的产品配置字段。这里仅保存销售订单需求；销售订单确认后，车间再生成生产任务单。" />
      <el-alert v-if="fromSalesOrder" type="success" :closable="false" show-icon class="to-tip"
        title="本单由销售订单明细行发起；客户、数量与已确认配置应以销售订单为准。" />
      <section v-if="!configureOnly" class="to-block">
        <h3>订单信息</h3>
        <div class="to-grid">
          <el-form-item label="工单申请日期">
            <el-input :model-value="today" disabled />
          </el-form-item>
          <el-form-item label="生产工单流水号">
            <el-input model-value="提交后自动生成" disabled />
          </el-form-item>
          <el-form-item label="要求生产完成日期" prop="dueDate">
            <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" class="full" />
          </el-form-item>
          <el-form-item label="计划类型" prop="taskType">
            <el-select v-model="form.taskType" class="full" @change="onTaskTypeChange">
              <el-option v-for="t in TASK_TYPES" :key="t.value" :value="t.value" :label="t.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="产线选择（生产车间）" prop="workshopId">
            <el-select v-model="form.workshopId" class="full" placeholder="请选择产线（生产车间）" @change="onWorkshopChange">
              <el-option v-for="w in workshops" :key="w.id" :value="w.id" :label="w.workshopName" />
            </el-select>
          </el-form-item>
          <el-form-item :label="internalPurpose ? '需求方' : '客户选择'" prop="customerName">
            <el-input v-if="internalPurpose" model-value="内部用途" disabled class="full" />
            <el-select v-model="form.customerName" filterable remote clearable :remote-method="searchCustomers"
              v-else :loading="customerLoading" placeholder="输入关键字搜索客户" class="full" @change="onCustomerChange">
              <el-option v-for="c in customerOptions" :key="c.id" :value="c.name" :label="c.name" />
            </el-select>
          </el-form-item>
          <el-form-item label="业务员">
            <el-input :model-value="currentUser?.name ?? ''" disabled />
          </el-form-item>
          <el-form-item label="需求数量" prop="plannedQuantity">
            <el-input-number v-model="form.plannedQuantity" :min="0.01" :precision="2" :controls="false"
              placeholder="大于 0" class="full" />
          </el-form-item>
        </div>
      </section>

      <section class="to-block">
        <h3>产品配置</h3>
        <el-form-item v-if="configureOnly" label="产线选择（生产车间）" prop="workshopId">
          <el-select v-model="form.workshopId" class="full" placeholder="先选择产线" @change="onWorkshopChange">
            <el-option v-for="w in workshops" :key="w.id" :value="w.id" :label="w.workshopName" />
          </el-select>
        </el-form-item>
        <el-alert v-if="!configureOnly && internalPurpose" type="warning" :closable="false" show-icon class="to-tip"
          :title="internalPurposeTip" />
        <el-alert v-if="noOpsTemplate" type="warning" :closable="false" show-icon class="to-tip"
          :title="`产品绑定制程「${noOpsTemplate}」不展开工序，建单后直接进入待收货。`" />
        <el-alert type="info" :closable="false" show-icon class="to-tip"
          title="先选产线与产品名称。制程自动取「任务单产品与配置」中该产品绑定的制程；未单独绑定时取产线默认制程。" />
        <div class="to-grid">
          <el-form-item label="产品名称" prop="productName" class="span-2">
            <el-select v-model="form.productName" :disabled="!form.workshopId" class="full" @change="onProductChange"
              :placeholder="form.workshopId ? '请选择' : '先选择产线'">
              <el-option v-for="n in productNames" :key="n" :value="n" :label="n" />
            </el-select>
          </el-form-item>
          <el-form-item label="对应制程" class="span-2">
            <el-input :model-value="resolvedProcessName" disabled class="full" />
          </el-form-item>
          <!--
            形状先行（09-24 LQ 定）：先选盒子形状，形状决定下面录哪几个尺寸值。
            尺寸的形态规范（方=长x宽 / 圆=裸直径 / 其他=不进比对）统一在 @/utils/boxSize，
            前端不在这里自己拼尺寸文本。
          -->
          <template v-if="productKind !== 'PART'">
          <el-form-item label="盒子形状" prop="boxShape" class="span-2">
            <ProductionConfigurationField v-model="form.boxShape" field="boxShape" @change="onShapeChange" />
          </el-form-item>
          <template v-if="form.boxShape === 'RECT'">
            <el-form-item label="盒子长（mm）" prop="boxLengthMm">
            <ProductionConfigurationField v-model="form.boxLengthMm" field="boxLengthMm" />
          </el-form-item>
            <el-form-item label="盒子宽（mm）" prop="boxWidthMm">
            <ProductionConfigurationField v-model="form.boxWidthMm" field="boxWidthMm" />
          </el-form-item>
          </template>
          <template v-else-if="form.boxShape === 'ROUND'">
            <el-form-item label="盒子直径（mm）" prop="boxDiameterMm">
            <ProductionConfigurationField v-model="form.boxDiameterMm" field="boxDiameterMm" />
          </el-form-item>
          </template>
          <template v-else-if="form.boxShape === 'OTHER'">
            <el-form-item label="盒子形状说明" prop="boxShapeNote">
            <ProductionConfigurationField v-model="form.boxShapeNote" field="boxShapeNote" />
          </el-form-item>
            <el-form-item label="尺寸描述" prop="boxSizeNote">
            <ProductionConfigurationField v-model="form.boxSizeNote" field="boxSizeNote" />
          </el-form-item>
            <el-alert class="span-2 to-tip" type="warning" :closable="false" show-icon
              title="异形盒子车间没有常规尺寸模具，需要按订做安排。" />
          </template>
          <el-form-item label="盒子高度" prop="boxHeight">
            <ProductionConfigurationField v-model="form.boxHeight" field="boxHeight" />
          </el-form-item>
          <el-form-item label="模具排列" prop="moldArrangement">
            <ProductionConfigurationField v-model="form.moldArrangement" field="moldArrangement" />
          </el-form-item>
          </template>
          <template v-if="productKind === 'MACHINE'">
          <el-form-item label="盒子材质" prop="boxMaterial">
            <ProductionConfigurationField v-model="form.boxMaterial" field="boxMaterial" />
          </el-form-item>
          <el-form-item label="电压" prop="voltage">
            <ProductionConfigurationField v-model="form.voltage" field="voltage" />
          </el-form-item>
          <el-form-item label="插头" prop="plug">
            <ProductionConfigurationField v-model="form.plug" field="plug" />
          </el-form-item>
          <el-form-item label="品牌" prop="brand">
            <ProductionConfigurationField v-model="form.brand" field="brand" />
          </el-form-item>
          </template>
          <template v-for="(label,key) in extraFields" :key="key">
            <el-form-item v-if="productKind === 'PART' ? ['specificName','compatibleModel','partSpecification'].includes(String(key)) : productKind === 'MOLD' && ['compatibleModel','moldType','drawingVersion'].includes(String(key))" :label="label"><ProductionConfigurationField v-model="extraConfig[key]" :field="key as ProductionConfigFieldKey" /></el-form-item>
          </template>
          <el-form-item label="可选配置" class="span-2">
            <el-select v-model="form.optionalConfigs" multiple filterable :disabled="!form.workshopId" class="full"
              :placeholder="form.workshopId ? '可多选' : '先选择产线'">
              <el-option v-for="o in configOptions" :key="o" :value="o" :label="o" />
            </el-select>
          </el-form-item>
          <el-form-item label="是否需要额外模具" prop="extraMold">
            <ProductionConfigurationField v-model="form.extraMold" field="extraMold" />
          </el-form-item>
          <el-form-item label="填写配置说明" class="span-2">
            <ProductionConfigurationField v-model="form.configNote" field="configNote" />
          </el-form-item>
        </div>
      </section>

      <el-alert v-if="namePreview" :title="namePreview.namePreview || '名称待完善'" :description="[namePreview.specificationPreview, ...Object.values(namePreview.fieldErrors), ...namePreview.confirmationItems].join('；')" :type="namePreview.completionState === 'COMPLETE' ? 'success' : 'warning'" :closable="false"/>
      <section v-if="!configureOnly" class="to-block">
        <h3>额外信息</h3>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </section>
    </el-form>

    <template #footer>
      <div class="to-footer">
        <el-button :disabled="submitting" @click="close">取消</el-button>
        <el-button v-if="!configureOnly" :loading="submitting" @click="submit(true)">提交并继续下单</el-button>
        <el-button type="primary" :loading="submitting" @click="submit(false)">{{ configureOnly ? '保存配置到销售订单' : '提交' }}</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import ProductionConfigurationField from './ProductionConfigurationField.vue'
import type { ProductionConfigFieldKey } from '@/constants/productionConfiguration'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { fetchCustomers } from '@/api/customer/customer'
import {
  createManufacturingOrder, fetchProcessTemplates, fetchTaskConfigOptions, fetchWorkshopOptions,
  newRequestKey, type ProcessTemplate, type WorkshopOption
} from '@/api/manufacturing'
import {
  boxRectSides, boxSizeFieldProblem, boxSizeProblem, boxSizeText, specificationText, tidyMm,
  type BoxShape, type BoxSizeFields
} from '@/utils/boxSize'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { configurationDirectory, configurationRule, previewConfiguration, type ConfigRule, type DirectoryProduct, type NamePreview } from '@/api/productConfiguration'
import type { ProductionDemandConfig } from '@/api/salesOrder'
import { fetchOrderProductionWorkshops, fetchOrderProductionOptions } from '@/api/salesOrder'

/**
 * 由上层带进来的预填值。
 *
 * 独立建单时可由上层预填客户、数量与销售订单明细行。
 * 销售订单的配置模式只使用同一套产品配置字段，保存到订单明细；车间接单时再建任务。
 */
interface TaskOrderPreset {
  customerId?: number | null
  customerName?: string
  plannedQuantity?: number | null
  salesOrderItemId?: number
  taskType?: string
}

const props = defineProps<{ modelValue: boolean; preset?: TaskOrderPreset | null; mode?: 'create' | 'configure'; initial?: ProductionDemandConfig | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; created: [id: number]; configured: [config: ProductionDemandConfig, specification: string, displayName: string] }>()
const configureOnly = computed(() => props.mode === 'configure')

interface CustomerOption { id: number; name: string }

const TASK_TYPES = [
  // 备货（09-24 LQ 定）：直接建单先生产后入库，之后在销售订单里作为现货可选；无客户
  { value: 'STOCK', label: '备货（先生产后入库）' },
  // 销售订单来源的单（后端 task_type 白名单里的 SALES_ORDER）
  { value: 'EXHIBITION', label: '展会展示机' },
  { value: 'SHOWROOM', label: '展厅展示机' },
  { value: 'SAMPLE', label: '打样' },
  { value: 'SPECIAL', label: '特殊定制' },
  { value: 'INTERNAL', label: '内部领用' },
  { value: 'REWORK', label: '返工' },
  { value: 'REPLACEMENT', label: '返工替换' }
]

/** 窄屏按视口收窄，宽屏固定 920px —— 避免小屏上抽屉比屏幕还宽。 */
const drawerSize = computed(() => (typeof window !== 'undefined' && window.innerWidth < 960 ? '96vw' : '920px'))

const { user: currentUser } = useCurrentUser()
const formRef = ref<FormInstance>()

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const today = new Date().toISOString().slice(0, 10)

const workshops = ref<WorkshopOption[]>([])
const processTemplates = ref<ProcessTemplate[]>([])
const productNames = ref<string[]>([])
const productTemplates = ref<Record<string, number | null>>({})
const productTemplateNames = ref<Record<string, string>>({})
const configOptions = ref<string[]>([])

/** 产品对应制程仅展示；建单时后端再次按产品配置解析，避免依赖前端选项快照。 */
const selectedTemplate = computed(() => processTemplates.value.find(t => t.id === form.processTemplateId))
const resolvedProcessName = computed(() => {
  if (!form.workshopId || !form.productName) return '选择产品后自动确定'
  if (configureOnly.value) return productTemplateNames.value[form.productName] || '无需报工'
  return selectedTemplate.value?.templateName || '无需报工'
})
/** 选中的制程是 0 道工序（免报工）→ 当场讲清楚这张单不会有工序。 */
const noOpsTemplate = computed(() =>
  form.productName && !form.processTemplateId ? '无需报工'
    : selectedTemplate.value?.operationCount === 0 ? selectedTemplate.value.templateName : '')
const customerOptions = ref<CustomerOption[]>([])
const customerLoading = ref(false)
const directory = ref<DirectoryProduct[]>([])
const namingRule = ref<ConfigRule>()
const namePreview = ref<NamePreview>()
const ruleLoading = ref(false)
const directoryFailed = ref(false)
let ruleSequence = 0, previewSequence = 0
const extraFields: Record<string,string> = {specificName:'配件名称',compatibleModel:'适配型号',moldType:'模具类型',drawingVersion:'图纸版本',partSpecification:'配件规格'}
const extraConfig = reactive<Record<string,string>>({specificName:'',compatibleModel:'',moldType:'',drawingVersion:'',partSpecification:''})
const productKind = computed(() => namingRule.value?.definition.productType || 'MACHINE')
function namingInput() { return {...form,...extraConfig, extraMold:form.extraMold==='yes', optionIds:namingRule.value?.definition.options.filter(o => form.optionalConfigs.includes(namingRule.value!.labels.optionNames[o.optionId])).map(o=>o.optionId) || []} }
async function loadNamingRule(pinned?:number) {
  const seq=++ruleSequence; ruleLoading.value=false; previewSequence++; namingRule.value=undefined; namePreview.value=undefined
  const p=directory.value.find(p=>p.name===form.productName)
  if(!p || !(pinned || p.ruleId))return
  ruleLoading.value=true
  try {const r=await configurationRule(p.id,pinned || p.ruleId!); if(seq===ruleSequence){namingRule.value=r; configOptions.value=Object.values(r.labels.optionNames)}}
  catch {if(seq===ruleSequence){directoryFailed.value=true; ElMessage.error('产品规则加载失败，请重新选择产线')}}
  finally {if(seq===ruleSequence)ruleLoading.value=false}
}
const submitting = ref(false)
/** 请求键：打开抽屉时生成，提交失败保留（重试复用同一次动作），提交成功作废。 */
let requestKey = ''
/** 销售订单明细行 id（由 preset 带入）；非空表示本单由销售订单发起。 */
const salesOrderItemId = ref<number | null>(null)
const fromSalesOrder = computed(() => salesOrderItemId.value != null)

const blankForm = () => ({
  processTemplateId: null as number | null,
  dueDate: '',
  taskType: 'STOCK',
  customerId: null as number | null,
  customerName: '',
  workshopId: null as number | null,
  plannedQuantity: undefined as number | undefined,
  extraMold: 'no',
  productName: '',
  boxShape: '' as BoxShape | '',
  boxLengthMm: '',
  boxWidthMm: '',
  boxDiameterMm: '',
  boxShapeNote: '',
  boxSizeNote: '',
  boxHeight: '',
  moldArrangement: '',
  boxMaterial: '',
  voltage: '',
  plug: '',
  brand: '',
  optionalConfigs: [] as string[],
  configNote: '',
  remark: ''
})
const form = reactive(blankForm())

/**
 * 交给 @/utils/boxSize 的尺寸字段视图。
 * 形状与尺寸的**形态规范只在那一个文件里实现**，这里只负责把表单值喂进去、
 * 把算出来的错误文案挂回对应字段（避免抽屉与后端各有一套口径）。
 */
const sizeFields = (): BoxSizeFields => ({
  boxShape: form.boxShape,
  boxLengthMm: form.boxLengthMm,
  boxWidthMm: form.boxWidthMm,
  boxDiameterMm: form.boxDiameterMm,
  boxShapeNote: form.boxShapeNote,
  boxSizeNote: form.boxSizeNote
})

const internalPurpose = computed(() => ['EXHIBITION', 'SHOWROOM', 'INTERNAL', 'STOCK'].includes(form.taskType))
/** 内部用途提示按类型区分：备货的去向就是可售库存（这正是它存在的意义），其余内部任务完工后不自动转库存。 */
const internalPurposeTip = computed(() => form.taskType === 'STOCK'
  ? '备货任务无需销售订单：完工检验入库后进可售库存，之后在销售订单里作为「现货」可选。'
  : '内部任务无需销售订单。展会结束后转可售库存、或交接到展厅，是后续独立业务动作；当前完工记录不会自动完成这两种去向。')

function onTaskTypeChange() {
  form.customerId = null
  form.customerName = ''
  formRef.value?.clearValidate('customerName')
}

/** 换形状：上一形状的字段已经卸载，清掉它们的校验痕迹，别让红字留到提交时。 */
function onShapeChange() {
  formRef.value?.clearValidate(['boxShape', 'boxLengthMm', 'boxWidthMm', 'boxDiameterMm', 'boxShapeNote', 'boxSizeNote'])
}

const required = { required: true, message: '必填', trigger: ['blur', 'change'] as const }

/**
 * 尺寸字段的校验：判定与文案全部来自 @/utils/boxSize（唯一实现），这里只做接线。
 * 不属于当前形状的字段恒通过 —— 所以即使表单项被 v-if 卸掉也不会误拦。
 */
const sizeField = (field: keyof BoxSizeFields) => ({
  validator: (_rule: unknown, _value: unknown, callback: (error?: Error) => void) => {
    const problem = boxSizeFieldProblem(field, sizeFields())
    if (problem) callback(new Error(problem))
    else callback()
  },
  trigger: ['blur', 'change'] as const
})

const legacyRules: FormRules = {
  dueDate: required,
  taskType: required,
  customerName: { validator: (_rule, value: string, callback: (error?: Error) => void) => {
    if (!internalPurpose.value && !value?.trim()) callback(new Error('请选择客户'))
    else callback()
  }, trigger: ['blur', 'change'] },
  workshopId: required,
  plannedQuantity: { required: true, message: '必填且大于 0', trigger: ['blur', 'change'] },
  extraMold: required,
  productName: required,
  boxShape: sizeField('boxShape'),
  boxLengthMm: sizeField('boxLengthMm'),
  boxWidthMm: sizeField('boxWidthMm'),
  boxDiameterMm: sizeField('boxDiameterMm'),
  boxShapeNote: sizeField('boxShapeNote'),
  boxSizeNote: sizeField('boxSizeNote'),
  boxHeight: required,
  moldArrangement: required,
  boxMaterial: required,
  voltage: required,
  plug: required,
  brand: required
}

const rules = computed<FormRules>(() => {
 if(!namingRule.value)return legacyRules
 const r={...legacyRules}; for(const key of ['boxShape','boxLengthMm','boxWidthMm','boxDiameterMm','boxShapeNote','boxSizeNote','boxHeight','moldArrangement','boxMaterial','voltage','plug','brand']) delete r[key]
 for(const key of namingRule.value.definition.requiredFields) r[key]=required
 if(productKind.value !== 'PART') for(const key of ['boxShape','boxLengthMm','boxWidthMm','boxDiameterMm','boxShapeNote','boxSizeNote'] as const) r[key]=sizeField(key)
 return r
})

watch(() => JSON.stringify(namingInput()), async (_v,_old,onCleanup) => {
  const seq=++previewSequence; namePreview.value=undefined
  if(!namingRule.value)return
  const r=namingRule.value
  const timer=setTimeout(async()=>{try {const result=await previewConfiguration(r.baseProductId,r.id,namingInput()); if(seq===previewSequence)namePreview.value=result}catch {if(seq===previewSequence)namePreview.value=undefined}},300)
  onCleanup(()=>clearTimeout(timer))
})

function resetForm() {
  Object.assign(form, blankForm())
  salesOrderItemId.value = null
  productNames.value = []
  productTemplates.value = {}
  productTemplateNames.value = {}
  configOptions.value = []
  customerOptions.value = []
  formRef.value?.clearValidate()
}

/**
 * 应用上层预填值（销售订单明细行发起时）。
 *
 * 销售订单模式只回写配置快照，不在此创建生产任务。
 */
function applyPreset() {
  const preset = props.preset
  if (!preset) return
  salesOrderItemId.value = preset.salesOrderItemId ?? null
  form.taskType = preset.taskType || 'SALES_ORDER'
  form.customerId = preset.customerId ?? null
  form.customerName = preset.customerName ?? ''
  // 客户下拉是远程搜索：不塞一条候选，带入的客户名就显示不出来
  if (preset.customerName) customerOptions.value = [{ id: preset.customerId ?? 0, name: preset.customerName }]
  if (preset.plannedQuantity) form.plannedQuantity = Number(preset.plannedQuantity)
}

/** 每次打开都从空白开始，并保证有一次新的请求键。 */
async function onOpen() {
  resetForm()
  applyPreset()
  requestKey = newRequestKey()
  try {
    if (configureOnly.value) {
      workshops.value = await fetchOrderProductionWorkshops()
    } else {
      const [ws, ts] = await Promise.all([fetchWorkshopOptions(), fetchProcessTemplates()])
      workshops.value = ws
      processTemplates.value = ts
    }
    if (configureOnly.value && props.initial) await applyInitial(props.initial)
  } catch {
    ElMessage.error('制程 / 产线加载失败，请关闭后重试')
  }
}

/** 产线联动：换产线必须清掉上一个产线的产品名称与可选配置，并重取本产线可选项。 */
async function onWorkshopChange(workshopId: number) {
  ruleSequence++; previewSequence++; namingRule.value=undefined; namePreview.value=undefined; directory.value=[]; directoryFailed.value=false; ruleLoading.value=false
  Object.keys(extraConfig).forEach(k=>extraConfig[k]='')
  form.productName = ''
  form.processTemplateId = null
  form.optionalConfigs = []
  productNames.value = []
  productTemplates.value = {}
  productTemplateNames.value = {}
  configOptions.value = []
  if (!workshopId) return
  try {
    const options = configureOnly.value
      ? await fetchOrderProductionOptions(workshopId)
      : await fetchTaskConfigOptions(workshopId)
    productNames.value = options.productNames
    productTemplates.value = options.productTemplates || {}
    productTemplateNames.value = configureOnly.value
      ? (options as Awaited<ReturnType<typeof fetchOrderProductionOptions>>).productTemplateNames
      : {}
    configOptions.value = options.optionalConfigs
    try {directory.value=(await configurationDirectory(workshopId)).products} catch {directoryFailed.value=true; ElMessage.error('产品规则目录加载失败，请重新选择产线')}
  } catch {
    ElMessage.error('该产线的产品名称与可选配置加载失败，请重新选择产线')
  }
}

async function onProductChange() {
  const productTemplate = productTemplates.value[form.productName]
  form.processTemplateId = productTemplate ?? workshops.value.find(w => w.id === form.workshopId)?.defaultProcessTemplateId ?? null
  await loadNamingRule()
}

async function applyInitial(config: ProductionDemandConfig) {
  form.workshopId = config.workshopId
  await onWorkshopChange(config.workshopId)
  form.productName = config.productName
  await onProductChange()
  if(config.configRuleId) await loadNamingRule(config.configRuleId)
  Object.keys(extraConfig).forEach(k=>extraConfig[k]=String(config[k as keyof ProductionDemandConfig] || ''))
  const legacyShape = config.boxShape === '方形' ? 'RECT' : config.boxShape === '圆形' ? 'ROUND' : config.boxShape === '异形' ? 'OTHER' : config.boxShape
  form.boxShape = ['RECT', 'ROUND', 'OTHER'].includes(legacyShape) ? legacyShape as BoxShape : 'OTHER'
  const rectSides = (config.boxSize || '').split(/[xX×*]/).map(side => side.trim())
  form.boxLengthMm = config.boxLengthMm || (form.boxShape === 'RECT' ? rectSides[0] || '' : '')
  form.boxWidthMm = config.boxWidthMm || (form.boxShape === 'RECT' ? rectSides[1] || '' : '')
  form.boxDiameterMm = config.boxDiameterMm || (form.boxShape === 'ROUND' ? config.boxSize || '' : '')
  form.boxShapeNote = config.boxShapeNote || (form.boxShape === 'OTHER' && legacyShape !== 'OTHER' ? config.boxShape : '')
  form.boxSizeNote = config.boxSizeNote || (form.boxShape === 'OTHER' ? config.boxSize || '' : '')
  form.boxHeight = config.boxHeight
  form.moldArrangement = config.moldArrangement
  form.boxMaterial = config.boxMaterial
  form.voltage = config.voltage
  form.plug = config.plug
  form.brand = config.brand
  form.optionalConfigs = [...(config.optionalConfigs || [])]
  form.configNote = config.configNote || ''
  form.extraMold = config.extraMold ? 'yes' : 'no'
}

async function searchCustomers(keyword: string) {
  if (!keyword || !keyword.trim()) { customerOptions.value = []; return }
  customerLoading.value = true
  try {
    const result = await fetchCustomers({ keyword: keyword.trim(), page: 1, size: 20 })
    const rows: Array<Record<string, unknown>> = result.content || []
    customerOptions.value = rows.map(r => ({
      id: Number(r.id),
      name: String(r.name ?? r.customerName ?? '')
    })).filter(c => c.name)
  } catch {
    ElMessage.error('客户搜索失败，请重试')
  } finally { customerLoading.value = false }
}

function onCustomerChange(name: string) {
  const hit = customerOptions.value.find(c => c.name === name)
  form.customerId = hit ? hit.id : null
}

async function submit(createNext: boolean) {
  if(ruleLoading.value || directoryFailed.value){ElMessage.error('请等待规则加载完成或重新选择产线'); return}
  try {
    await formRef.value?.validate()
  } catch { return }
  // 兜底：尺寸字段按形状渲染，这里再按形状整体校一次，避免漏网
  const sizeProblem = productKind.value === 'PART' ? '' : boxSizeProblem(sizeFields())
  if (sizeProblem) { ElMessage.error(sizeProblem); return }
  if (submitting.value) return
  submitting.value = true
  try {
    const fields = sizeFields()
    const shape = fields.boxShape as BoxShape
    /**
     * 结构化尺寸随单落 product_config_json：**只带当前形状相关的键**，
     * 避免残留上一个形状的值（先填方形的长宽又改成圆形，长宽不该跟着走）。
     * `boxSize` 是给人看与对齐台账的尺寸文本（方=`长x宽`、圆=裸直径）；
     * 数值键（长/宽/直径）才是**判「常规/订做」的入参**，两者分工不同但同出 @/utils/boxSize。
     * 「其他」形状不产出 `boxSize`，异形稳定落非标。
     */
    let sizeConfig: Record<string, string>
    if (shape === 'RECT') {
      // 长宽**原样保序**（长=较大的一边），与台账写法 `200x140` 一致；填反由 boxSize 校验当场拦下
      const sides = boxRectSides(fields)
      sizeConfig = { boxSize: boxSizeText(fields), boxLengthMm: sides?.lengthMm ?? '', boxWidthMm: sides?.widthMm ?? '' }
    } else if (shape === 'ROUND') {
      sizeConfig = { boxSize: boxSizeText(fields), boxDiameterMm: tidyMm(fields.boxDiameterMm) }
    } else {
      sizeConfig = { boxShapeNote: fields.boxShapeNote.trim(), boxSizeNote: fields.boxSizeNote.trim() }
    }
    const productConfig: ProductionDemandConfig = {
      workshopId: form.workshopId || 0,
      productName: form.productName,
      boxShape: shape,
      boxSize: sizeConfig.boxSize || '',
      boxHeight: form.boxHeight,
      moldArrangement: form.moldArrangement,
      boxMaterial: form.boxMaterial,
      voltage: form.voltage,
      plug: form.plug,
      brand: form.brand,
      optionalConfigs: [...form.optionalConfigs],
      configNote: form.configNote || '',
      extraMold: form.extraMold === 'yes',
      ...sizeConfig
    }
    let specification=specificationText(fields, form.boxHeight)
    if(namingRule.value){
      const r=namingRule.value; const input=namingInput(); const preview=await previewConfiguration(r.baseProductId,r.id,input)
      namePreview.value=preview
      if(Object.keys(preview.fieldErrors).length){ElMessage.error(Object.values(preview.fieldErrors).join('；')); return}
      Object.assign(productConfig,extraConfig,{baseProductId:r.baseProductId,configRuleId:r.id,optionIds:input.optionIds})
      specification=preview.specificationPreview
    }
    if (configureOnly.value) {
      const displayName = namePreview.value?.namePreview || form.productName
      productConfig.nameSnapshot = displayName
      emit('configured', productConfig, specification, displayName)
      visible.value = false
      return
    }
    const payload = {
      customerName: form.customerName,
      customerId: form.customerId,
      // 由销售订单明细行发起时带上行 id：后端落 manufacturing_orders.sales_order_item_id（唯一）
      salesOrderItemId: salesOrderItemId.value,
      // 用户选择的是制程品类；规范产品名称和 skuKey 由后端按配置统一生成。
      processCategory: form.productName,
      plannedQuantity: form.plannedQuantity,
      dueDate: form.dueDate || null,
      taskType: form.taskType,
      // 制程由后端按产线+产品配置解析，避免前端显示的制程与实际落单不一致。
      processTemplateId: null,
      workshopId: form.workshopId,
      // 规格摘要给人看（圆口径带 Φ）；与用于比对常规尺寸的裸数字分工不同，两处都出自 utils/boxSize
      specification,
      remark: form.remark || '',
      productConfig
    }
    const id = await createManufacturingOrder(payload, requestKey)
    requestKey = ''
    ElMessage.success(`生产任务单已创建：MO${String(id).padStart(8, '0')}`)
    emit('created', id)
    if (createNext) {
      // 连续下单：保留制程/产线/交期，产品/客户/数量清空重新填
      const keep = { processTemplateId: form.processTemplateId, workshopId: form.workshopId, dueDate: form.dueDate, taskType: form.taskType }
      resetForm()
      Object.assign(form, keep)
      if (keep.workshopId) await onWorkshopChange(keep.workshopId)
      requestKey = newRequestKey()
    } else {
      visible.value = false
    }
  } catch (e: unknown) {
    // 失败保留请求键：重试即复用同一次动作，不会重复建单
    ElMessage.error(e instanceof Error && e.message ? `创建失败：${e.message}` : '创建失败，请重试')
  } finally { submitting.value = false }
}

function close() {
  if (submitting.value) return
  visible.value = false
}

async function beforeClose(done: () => void) {
  if (submitting.value) return
  done()
}
</script>

<style scoped>
.to-form { padding-bottom: 8px; }
.to-block { margin-bottom: 8px; }
.to-block h3 {
  margin: 8px 0 14px; font-size: 14px; font-weight: 600; padding-left: 8px;
  border-left: 3px solid var(--color-primary, #0f766e); color: var(--color-text-heading, #0f172a);
}
.to-tip { margin-bottom: 16px; }
.to-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.to-grid .span-2 { grid-column: 1 / -1; }
.to-grid :deep(.el-form-item) { margin-bottom: 16px; }
.full, .to-grid :deep(.el-date-editor), .to-grid :deep(.el-input-number) { width: 100%; }
.to-footer { display: flex; justify-content: flex-end; gap: 12px; }
@media (max-width: 640px) { .to-grid { grid-template-columns: 1fr; } }
</style>
