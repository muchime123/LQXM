<template>
  <div class="task-order-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">PRODUCTION TASK ORDER</p>
        <h1>生产任务单</h1>
        <p>业务员下单入口：先选产线，再选该产线可选的产品名称与可选配置。提交后系统按「任务单产品与配置」自动展开工序，单据进入车间执行。</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="resetForm">重置表单</el-button>
      </div>
    </header>

    <el-form ref="formRef" :model="form" :rules="rules" label-width="150px" class="form-panel">
      <!-- ==================== 订单信息 ==================== -->
      <section class="block">
        <h3>订单信息</h3>
        <el-row :gutter="24">
          <el-col :span="8">
            <el-form-item label="工单申请日期">
              <el-input :model-value="today" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="生产工单流水号">
              <el-input model-value="提交后自动生成" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="要求生产完成日期" prop="dueDate">
              <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD"
                placeholder="选择日期" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="计划类型" prop="taskType">
              <el-select v-model="form.taskType" style="width:100%">
                <el-option v-for="t in TASK_TYPES" :key="t.value" :value="t.value" :label="t.label" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="客户选择" prop="customerName">
              <el-select v-model="form.customerName" filterable remote clearable :remote-method="searchCustomers"
                :loading="customerLoading" placeholder="输入关键字搜索客户" style="width:100%" @change="onCustomerChange">
                <el-option v-for="c in customerOptions" :key="c.id" :value="c.name" :label="c.name" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="业务员">
              <el-input :model-value="currentUser?.name ?? ''" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="产线选择" prop="workshopId">
              <el-select v-model="form.workshopId" placeholder="请选择产线（生产车间）" style="width:100%"
                @change="onWorkshopChange">
                <el-option v-for="w in workshops" :key="w.id" :value="w.id" :label="w.workshopName" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
      </section>

      <!-- ==================== 数量 ==================== -->
      <section class="block">
        <h3>数量</h3>
        <el-row :gutter="24">
          <el-col :span="8">
            <el-form-item label="需求数量" prop="plannedQuantity">
              <el-input-number v-model="form.plannedQuantity" :min="0.01" :precision="2" :controls="false"
                placeholder="大于 0" style="width:100%" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="是否需要额外模具" prop="extraMold">
              <el-radio-group v-model="form.extraMold">
                <el-radio value="yes">是</el-radio>
                <el-radio value="no">否</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
        </el-row>
      </section>

      <!-- ==================== 产品配置 ==================== -->
      <section class="block">
        <h3>产品配置</h3>
        <el-alert type="info" :closable="false" show-icon class="block-tip"
          title="产品名称与可选配置来自「制程与工序管理 → 任务单产品与配置」，按所选产线联动；可售产品为 ERP 产品档案中的 SKU，完工入库按它入账。" />
        <el-row :gutter="24">
          <el-col :span="16">
            <el-form-item label="可售产品（SKU）" prop="salesProductId">
              <el-select v-model="form.salesProductId" filterable remote clearable :remote-method="searchProducts"
                :loading="productLoading" placeholder="输入编码 / 名称 / 规格搜索产品档案" style="width:100%"
                @change="onProductChange">
                <el-option v-for="p in productOptions" :key="p.id" :value="p.id"
                  :label="`${p.productCode} ${p.productName}${p.specification ? '（' + p.specification + '）' : ''}`" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="单位">
              <el-input :model-value="selectedProduct?.unit ?? ''" disabled />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="产品名称" prop="productName">
              <el-select v-model="form.productName" :disabled="!form.workshopId"
                :placeholder="form.workshopId ? '请选择' : '先选择产线'" style="width:100%">
                <el-option v-for="n in productNames" :key="n" :value="n" :label="n" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="盒子尺寸" prop="boxSize">
              <el-input v-model="form.boxSize" placeholder="mm（只允许正数，保留 2 位）" maxlength="50" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="盒子高度" prop="boxHeight">
              <el-input v-model="form.boxHeight" placeholder="请输入" maxlength="50" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="模具排列" prop="moldArrangement">
              <el-select v-model="form.moldArrangement" filterable allow-create default-first-option
                placeholder="请选择或输入" style="width:100%">
                <el-option v-for="m in MOLD_ARRANGEMENTS" :key="m" :value="m" :label="m" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="盒子形状" prop="boxShape">
              <el-radio-group v-model="form.boxShape">
                <el-radio v-for="s in BOX_SHAPES" :key="s" :value="s">{{ s }}</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="盒子材质" prop="boxMaterial">
              <el-input v-model="form.boxMaterial" placeholder="请输入" maxlength="100" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="电压" prop="voltage">
              <el-radio-group v-model="form.voltage">
                <el-radio v-for="v in VOLTAGES" :key="v" :value="v">{{ v }}</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="插头" prop="plug">
              <el-radio-group v-model="form.plug">
                <el-radio v-for="p in PLUGS" :key="p" :value="p">{{ p }}</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="品牌" prop="brand">
              <el-radio-group v-model="form.brand">
                <el-radio v-for="b in BRANDS" :key="b" :value="b">{{ b }}</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="可选配置">
              <el-select v-model="form.optionalConfigs" multiple filterable :disabled="!form.workshopId"
                :placeholder="form.workshopId ? '可多选' : '先选择产线'" style="width:100%">
                <el-option v-for="o in optionalConfigs" :key="o" :value="o" :label="o" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="填写配置说明">
              <el-input v-model="form.configNote" type="textarea" :rows="2" maxlength="500" show-word-limit />
            </el-form-item>
          </el-col>
        </el-row>
      </section>

      <!-- ==================== 额外信息 ==================== -->
      <section class="block">
        <h3>额外信息</h3>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="500" show-word-limit />
        </el-form-item>
      </section>

      <div class="actions">
        <el-button type="primary" size="large" :loading="submitting" @click="submit(false)">提交</el-button>
        <el-button size="large" :loading="submitting" @click="submit(true)">提交并新增下一单</el-button>
      </div>
    </el-form>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { fetchCustomers } from '@/api/customer/customer'
import {
  createManufacturingOrder, fetchProductOptions, fetchTaskConfigOptions, fetchWorkshopOptions,
  newRequestKey, type ProductOption, type WorkshopOption
} from '@/api/manufacturing'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'

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

const router = useRouter()
const { user: currentUser } = useCurrentUser()
const formRef = ref<FormInstance>()

const today = new Date().toISOString().slice(0, 10)

const workshops = ref<WorkshopOption[]>([])
const productNames = ref<string[]>([])
const optionalConfigs = ref<string[]>([])
const customerOptions = ref<CustomerOption[]>([])
const productOptions = ref<ProductOption[]>([])
const customerLoading = ref(false)
const productLoading = ref(false)
const submitting = ref(false)
const selectedProduct = ref<ProductOption | null>(null)

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

const required = { required: true, message: '必填', trigger: ['blur', 'change'] }
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

async function loadWorkshops() {
  workshops.value = await fetchWorkshopOptions()
}

async function onWorkshopChange(workshopId: number) {
  form.productName = ''
  form.optionalConfigs = []
  productNames.value = []
  optionalConfigs.value = []
  const opts = await fetchTaskConfigOptions(workshopId)
  productNames.value = opts.productNames
  optionalConfigs.value = opts.optionalConfigs
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
  } finally { productLoading.value = false }
}

function onProductChange(id: number | null) {
  selectedProduct.value = productOptions.value.find(p => p.id === id) ?? null
}

async function submit(createNext: boolean) {
  await formRef.value?.validate()
  submitting.value = true
  try {
    const specParts: string[] = []
    if (form.boxSize) specParts.push(`尺寸${form.boxSize}`)
    if (form.boxHeight) specParts.push(`高${form.boxHeight}`)
    const payload = {
      customerName: form.customerName,
      customerId: form.customerId,
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
    const id = await createManufacturingOrder(payload, newRequestKey())
    ElMessage.success(`生产任务单已创建：MO${String(id).padStart(8, '0')}`)
    if (createNext) {
      const keep = { workshopId: form.workshopId, dueDate: form.dueDate, taskType: form.taskType }
      Object.assign(form, blankForm(), keep)
      productNames.value = []
      optionalConfigs.value = []
      if (keep.workshopId) await onWorkshopChange(keep.workshopId)
      formRef.value?.clearValidate()
    } else {
      await router.push('/manufacturing-execution')
    }
  } finally { submitting.value = false }
}

function resetForm() {
  Object.assign(form, blankForm())
  productNames.value = []
  optionalConfigs.value = []
  selectedProduct.value = null
  formRef.value?.clearValidate()
}

onMounted(loadWorkshops)
</script>

<style scoped>
.task-order-page { padding: 20px 24px 40px; }
.page-head { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 16px; }
.page-head .eyebrow { margin: 0 0 4px; font-size: 12px; letter-spacing: 0.08em; color: var(--el-color-primary); }
.page-head h1 { margin: 0 0 6px; font-size: 20px; }
.page-head p { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; }
.form-panel { background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter); border-radius: 12px; padding: 20px 24px; }
.block { margin-bottom: 8px; }
.block h3 { margin: 8px 0 16px; font-size: 14px; font-weight: 600; padding-left: 8px; border-left: 3px solid var(--el-color-primary); }
.block-tip { margin-bottom: 16px; }
.actions { display: flex; justify-content: center; gap: 16px; padding-top: 8px; border-top: 1px solid var(--el-border-color-lighter); margin-top: 16px; padding-bottom: 4px; }
</style>
