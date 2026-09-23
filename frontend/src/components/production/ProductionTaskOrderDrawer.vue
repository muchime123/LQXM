<template>
  <!--
    生产任务单（业务员下单）—— 抽屉形态。
    形态纪律：下单是「车间执行页右上角按钮」调出的抽屉，不是独立侧边栏页面。
    联动纪律：先选产线 → 该产线的「产品名称 / 可选配置」才可选（数据源 = 制程与工序管理 → 任务单产品与配置）。
    建单纪律：产品名称即后端制程解析的 processCategory（同读 manufacturing_task_config_items.config_name）；
              制程跟随产线 —— 解析顺序 = 产品在配置里指定的制程 → 车间默认制程（见 ManufacturingTaskTemplateResolver.resolve）。
              09-22 起**不再要求选可售产品（SKU）** —— SKU 是完工入库的库存身份键，
              不是下单字段，且该主数据要重新设计；下单只带 产线 + 产品名称 + 配置 + 数量，
              单位默认「台」（后端兜底）。无 SKU 的工单完工收货时不落库存账，只登记收货事实。
  -->
  <el-drawer v-model="visible" title="生产任务单" :size="drawerSize" :close-on-click-modal="false"
    :close-on-press-escape="!submitting" :show-close="!submitting" :before-close="beforeClose" @open="onOpen">
    <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="to-form">
      <section class="to-block">
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
            <el-select v-model="form.taskType" class="full">
              <el-option v-for="t in TASK_TYPES" :key="t.value" :value="t.value" :label="t.label" />
            </el-select>
          </el-form-item>
          <el-form-item label="产线选择（生产车间）" prop="workshopId">
            <el-select v-model="form.workshopId" placeholder="请选择产线（生产车间）" class="full" @change="onWorkshopChange">
              <el-option v-for="w in workshops" :key="w.id" :value="w.id" :label="w.workshopName" />
            </el-select>
          </el-form-item>
          <el-form-item label="客户选择" prop="customerName">
            <el-select v-model="form.customerName" filterable remote clearable :remote-method="searchCustomers"
              :loading="customerLoading" placeholder="输入关键字搜索客户" class="full" @change="onCustomerChange">
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
          <el-form-item label="是否需要额外模具" prop="extraMold">
            <el-radio-group v-model="form.extraMold">
              <el-radio value="yes">是</el-radio>
              <el-radio value="no">否</el-radio>
            </el-radio-group>
          </el-form-item>
        </div>
      </section>

      <section class="to-block">
        <h3>产品配置</h3>
        <el-alert type="info" :closable="false" show-icon class="to-tip"
          title="产品名称与可选配置来自「制程与工序管理 → 任务单产品与配置」，按所选产线联动。本单展开的制程跟随产线：默认用车间的制程，仅当配置里给该产品名称单独指定了制程时才按产品覆盖。" />
        <div class="to-grid">
          <el-form-item label="产品名称" prop="productName" class="span-2">
            <el-select v-model="form.productName" :disabled="!form.workshopId" class="full"
              :placeholder="form.workshopId ? '请选择' : '先选择产线'">
              <el-option v-for="n in productNames" :key="n" :value="n" :label="n" />
            </el-select>
          </el-form-item>
          <el-form-item label="盒子尺寸" prop="boxSize">
            <el-input v-model="form.boxSize" placeholder="mm（只允许正数，保留 2 位）" maxlength="50" />
          </el-form-item>
          <el-form-item label="盒子高度" prop="boxHeight">
            <el-input v-model="form.boxHeight" placeholder="请输入" maxlength="50" />
          </el-form-item>
          <el-form-item label="模具排列" prop="moldArrangement">
            <el-select v-model="form.moldArrangement" filterable allow-create default-first-option
              placeholder="请选择或输入" class="full">
              <el-option v-for="m in MOLD_ARRANGEMENTS" :key="m" :value="m" :label="m" />
            </el-select>
          </el-form-item>
          <el-form-item label="盒子材质" prop="boxMaterial">
            <el-input v-model="form.boxMaterial" placeholder="请输入" maxlength="100" />
          </el-form-item>
          <el-form-item label="盒子形状" prop="boxShape">
            <el-radio-group v-model="form.boxShape">
              <el-radio v-for="s in BOX_SHAPES" :key="s" :value="s">{{ s }}</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="电压" prop="voltage">
            <el-radio-group v-model="form.voltage">
              <el-radio v-for="v in VOLTAGES" :key="v" :value="v">{{ v }}</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="插头" prop="plug">
            <el-radio-group v-model="form.plug">
              <el-radio v-for="p in PLUGS" :key="p" :value="p">{{ p }}</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="品牌" prop="brand">
            <el-radio-group v-model="form.brand">
              <el-radio v-for="b in BRANDS" :key="b" :value="b">{{ b }}</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="可选配置" class="span-2">
            <el-select v-model="form.optionalConfigs" multiple filterable :disabled="!form.workshopId" class="full"
              :placeholder="form.workshopId ? '可多选' : '先选择产线'">
              <el-option v-for="o in configOptions" :key="o" :value="o" :label="o" />
            </el-select>
          </el-form-item>
          <el-form-item label="填写配置说明" class="span-2">
            <el-input v-model="form.configNote" type="textarea" :rows="2" maxlength="500" show-word-limit />
          </el-form-item>
        </div>
      </section>

      <section class="to-block">
        <h3>额外信息</h3>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </section>
    </el-form>

    <template #footer>
      <div class="to-footer">
        <el-button :disabled="submitting" @click="close">取消</el-button>
        <el-button :loading="submitting" @click="submit(true)">提交并继续下单</el-button>
        <el-button type="primary" :loading="submitting" @click="submit(false)">提交</el-button>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { fetchCustomers } from '@/api/customer/customer'
import {
  createManufacturingOrder, fetchProductOptions, fetchTaskConfigOptions, fetchWorkshopOptions,
  newRequestKey, type ProductOption, type WorkshopOption
} from '@/api/manufacturing'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; created: [id: number] }>()

interface CustomerOption { id: number; name: string }

const TASK_TYPES = [
  { value: 'SALES_ORDER', label: '销售排产（缺货下单）' },
  { value: 'SAMPLE', label: '打样' },
  { value: 'SPECIAL', label: '特殊定制' },
  { value: 'INTERNAL', label: '内部领用' },
  { value: 'REWORK', label: '返工' },
  { value: 'REPLACEMENT', label: '返工替换' }
]
const MOLD_ARRANGEMENTS = ['1出1', '1出2', '2出1', '2出2', '1出4', '4出1', '其他']
const BOX_SHAPES = ['圆形', '方形', '其他']
const VOLTAGES = ['单相110V', '单相220V', '三相220V', '三相380V']
const PLUGS = ['国标', '欧标', '英标', '美标', '其他']
const BRANDS = ['利强品牌', '中性', '其他']

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
const productNames = ref<string[]>([])
const configOptions = ref<string[]>([])
const customerOptions = ref<CustomerOption[]>([])
const productOptions = ref<ProductOption[]>([])
const customerLoading = ref(false)
const productLoading = ref(false)
const submitting = ref(false)
const selectedProduct = ref<ProductOption | null>(null)
/** 请求键：打开抽屉时生成，提交失败保留（重试复用同一次动作），提交成功作废。 */
let requestKey = ''

const blankForm = () => ({
  dueDate: '',
  taskType: 'SALES_ORDER',
  customerId: null as number | null,
  customerName: '',
  workshopId: null as number | null,
  plannedQuantity: undefined as number | undefined,
  extraMold: 'no',
  salesProductId: null as number | null,
  productName: '',
  boxSize: '',
  boxHeight: '',
  moldArrangement: '',
  boxShape: '',
  boxMaterial: '',
  voltage: '',
  plug: '',
  brand: '',
  optionalConfigs: [] as string[],
  configNote: '',
  remark: ''
})
const form = reactive(blankForm())

const required = { required: true, message: '必填', trigger: ['blur', 'change'] as const }
const rules: FormRules = {
  dueDate: required,
  taskType: required,
  customerName: required,
  workshopId: required,
  plannedQuantity: { required: true, message: '必填且大于 0', trigger: ['blur', 'change'] },
  extraMold: required,
  salesProductId: required,
  productName: required,
  boxSize: required,
  boxHeight: required,
  moldArrangement: required,
  boxShape: required,
  boxMaterial: required,
  voltage: required,
  plug: required,
  brand: required
}

function resetForm() {
  Object.assign(form, blankForm())
  productNames.value = []
  configOptions.value = []
  selectedProduct.value = null
  customerOptions.value = []
  formRef.value?.clearValidate()
}

/** 每次打开都从空白开始，并保证有一次新的请求键。 */
async function onOpen() {
  resetForm()
  requestKey = newRequestKey()
  if (!workshops.value.length) {
    try {
      workshops.value = await fetchWorkshopOptions()
    } catch {
      ElMessage.error('产线（生产车间）加载失败，请关闭后重试')
    }
  }
  // 先铺一批产品候选：可售产品是远程搜索下拉，不铺的话点开是空的、看起来像「产品档案里没有货」。
  if (!productOptions.value.length) await searchProducts('')
}

/** 产线联动：换产线必须清掉上一个产线的产品名称与可选配置，并重取本产线可选项。 */
async function onWorkshopChange(workshopId: number) {
  form.productName = ''
  form.optionalConfigs = []
  productNames.value = []
  configOptions.value = []
  if (!workshopId) return
  try {
    const options = await fetchTaskConfigOptions(workshopId)
    productNames.value = options.productNames
    configOptions.value = options.optionalConfigs
  } catch {
    ElMessage.error('该产线的产品名称与可选配置加载失败，请重新选择产线')
  }
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

async function searchProducts(keyword: string) {
  productLoading.value = true
  try {
    productOptions.value = await fetchProductOptions(keyword || '', 30)
  } catch {
    ElMessage.error('产品档案搜索失败，请重试')
  } finally { productLoading.value = false }
}

function onProductChange(id: number | null) {
  selectedProduct.value = productOptions.value.find(p => p.id === id) ?? null
}

async function submit(createNext: boolean) {
  try {
    await formRef.value?.validate()
  } catch { return }
  if (submitting.value) return
  submitting.value = true
  try {
    const specParts: string[] = []
    if (form.boxSize) specParts.push(`尺寸${form.boxSize}`)
    if (form.boxHeight) specParts.push(`高${form.boxHeight}`)
    const payload = {
      customerName: form.customerName,
      customerId: form.customerId,
      // 产品名称 = processCategory（品类级身份）；制程解析以产线为主：车间默认制程兜底，
      // 该产线下此产品在配置里单独指定 process_template_id 时才按产品覆盖（见 ManufacturingTaskTemplateResolver.resolve）
      processCategory: form.productName,
      salesProductId: form.salesProductId,
      plannedQuantity: form.plannedQuantity,
      dueDate: form.dueDate || null,
      taskType: form.taskType,
      workshopId: form.workshopId,
      specification: specParts.length ? specParts.join('，') : '',
      remark: form.remark || '',
      productConfig: {
        productName: form.productName,
        boxSize: form.boxSize,
        boxHeight: form.boxHeight,
        moldArrangement: form.moldArrangement,
        boxShape: form.boxShape,
        boxMaterial: form.boxMaterial,
        voltage: form.voltage,
        plug: form.plug,
        brand: form.brand,
        optionalConfigs: form.optionalConfigs,
        configNote: form.configNote || '',
        extraMold: form.extraMold === 'yes'
      }
    }
    const id = await createManufacturingOrder(payload, requestKey)
    requestKey = ''
    ElMessage.success(`生产任务单已创建：MO${String(id).padStart(8, '0')}`)
    emit('created', id)
    if (createNext) {
      // 连续下单：保留产线与交期，产品/客户/数量清空重新填
      const keep = { workshopId: form.workshopId, dueDate: form.dueDate, taskType: form.taskType }
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
