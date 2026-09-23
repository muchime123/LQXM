<template>
  <el-dialog v-model="visible" title="完工检验并入库" width="min(900px, 96vw)" :close-on-click-modal="false" @opened="load">
    <div v-loading="loading">
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <template v-if="detail">
        <p><b>{{ detail.order.orderNo }}</b> · {{ detail.order.productNameSnapshot }} · 计划 {{ detail.order.plannedQuantity }} {{ detail.order.unit }}</p>
        <p>全部必做工序已完成，由质检或跟单人员核验。选择合格时，本次提交同时生成检验记录与入库记录。</p>
        <el-form label-width="110px">
          <el-form-item label="检验结论" required>
            <el-radio-group v-model="result"><el-radio value="PASSED">合格并入库</el-radio><el-radio value="FAILED">不合格，退回返工</el-radio></el-radio-group>
          </el-form-item>
          <template v-if="result === 'FAILED'">
            <el-form-item label="返工工序" required>
              <el-select v-model="returnOperationId" placeholder="选择返工起始工序" style="width: 100%">
                <el-option v-for="op in detail.operations" :key="op.id" :label="`${op.sequenceNo}. ${op.operationNameSnapshot}`" :value="op.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="返工要求" required><el-input v-model="reworkRequirement" type="textarea" :rows="3" /></el-form-item>
          </template>
          <template v-else>
            <el-form-item label="入库仓库" required>
              <el-select v-model="warehouseId" placeholder="选择仓库" style="width: 100%">
                <el-option v-for="w in warehouses" :key="w.id" :label="w.warehouseName" :value="w.id" />
              </el-select>
            </el-form-item>
            <el-alert v-if="unmatchedOutputs.length" type="warning" :closable="false"
              title="以下产出缺少库存产品身份。请选择对应产品档案；若没有准确产品，请先建档，不能把仅登记数量当成已入库。" />
            <el-table :data="detail.outputs" border>
              <el-table-column prop="productNameSnapshot" label="入库产品" min-width="200" />
              <el-table-column prop="productCodeSnapshot" label="产品编号" width="130" />
              <el-table-column prop="unit" label="单位" width="70" />
              <el-table-column label="本次入库" width="130"><template #default="{ row }">{{ Number(row.plannedQuantity) - Number(row.receivedQuantity) }}</template></el-table-column>
              <el-table-column v-if="unmatchedOutputs.length" label="库存产品匹配" min-width="260"><template #default="{ row }">
                <span v-if="row.salesProductId && row.canonicalProductId">已匹配</span>
                <el-select v-else v-model="productMatches[row.id]" filterable remote clearable :remote-method="searchProducts"
                  placeholder="搜索产品编号或名称" style="width: 100%">
                  <el-option v-for="option in productOptions.filter(p => p.unit === row.unit)" :key="option.id"
                    :label="`${option.productCode} · ${option.productName} ${option.specification || ''}`" :value="option.id" />
                </el-select>
              </template></el-table-column>
            </el-table>
          </template>
          <el-form-item label="检验说明"><el-input v-model="remark" type="textarea" :rows="2" /></el-form-item>
        </el-form>
      </template>
    </div>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="!detail || (result === 'PASSED' && unmatchedOutputs.some(o => !productMatches[o.id]))" @click="submit">
        {{ result === 'PASSED' ? '确认合格并入库' : '提交返工' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchManufacturingOrder, fetchManufacturingWarehouses, fetchManufacturingProductOptions, newRequestKey,
  submitManufacturingInspection, submitQualityCompletion,
  type ManufacturingDetail, type Warehouse, type InventoryProductOption } from '@/api/manufacturing'
const visible = defineModel<boolean>({ default: false })
const props = defineProps<{ orderId: number | null }>()
const emit = defineEmits<{ completed: [] }>()
const detail = ref<ManufacturingDetail | null>(null), warehouses = ref<Warehouse[]>([])
const loading = ref(false), submitting = ref(false), error = ref('')
const result = ref<'PASSED' | 'FAILED'>('PASSED'), warehouseId = ref<number>(), returnOperationId = ref<number>()
const reworkRequirement = ref(''), remark = ref(''), requestKey = ref('')
const productOptions = ref<InventoryProductOption[]>([]), productMatches = ref<Record<number,number>>({})
const unmatchedOutputs = computed(() => (detail.value?.outputs || []).filter(o => !o.salesProductId || !o.canonicalProductId))
async function searchProducts(keyword:string) {
  try { productOptions.value = await fetchManufacturingProductOptions(keyword) }
  catch { productOptions.value = [] }
}
async function load() {
  if (!props.orderId) return
  loading.value = true; error.value = ''; detail.value = null; requestKey.value = newRequestKey()
  result.value = 'PASSED'; returnOperationId.value = undefined; reworkRequirement.value = ''; remark.value = ''; productMatches.value = {}
  try {
    const [order, options] = await Promise.all([fetchManufacturingOrder(props.orderId), fetchManufacturingWarehouses()])
    detail.value = order; warehouses.value = options; warehouseId.value = options[0]?.id
    if (order.outputs.some(o => !o.salesProductId || !o.canonicalProductId)) await searchProducts(order.order.productNameSnapshot)
  } catch (e) { error.value = (e as Error).message || '加载待检验任务失败' }
  finally { loading.value = false }
}
async function submit() {
  if (!props.orderId || !detail.value) return
  const planned = Number(detail.value.order.plannedQuantity)
  if (result.value === 'PASSED' && !warehouseId.value) { ElMessage.warning('请选择入库仓库'); return }
  if (result.value === 'FAILED' && (!returnOperationId.value || !reworkRequirement.value.trim())) { ElMessage.warning('请选择返工工序并填写要求'); return }
  const inspection = { result: result.value, qualifiedQuantity: result.value === 'PASSED' ? planned : 0,
    unqualifiedQuantity: result.value === 'FAILED' ? planned : 0,
    returnOperationId: result.value === 'FAILED' ? returnOperationId.value : null,
    reworkRequirement: result.value === 'FAILED' ? reworkRequirement.value.trim() : null,
    issueDescription: result.value === 'FAILED' ? reworkRequirement.value.trim() : null, remark: remark.value.trim() || null }
  submitting.value = true
  try {
    if (result.value === 'FAILED') await submitManufacturingInspection(props.orderId, inspection, requestKey.value)
    else await submitQualityCompletion(props.orderId, { inspection, inventory: {
      warehouseId: warehouseId.value, completedAt: null,
      items: detail.value.outputs.map(o => ({ outputId: o.id, quantity: Number(o.plannedQuantity) - Number(o.receivedQuantity), unitCost: 0 })),
      remark: remark.value.trim() || null },
      productMatches: unmatchedOutputs.value.map(o => ({ outputId: o.id, salesProductId: productMatches.value[o.id] })) }, requestKey.value)
    ElMessage.success(result.value === 'PASSED' ? '检验合格，已办理完工入库' : '检验不合格，已退回返工')
    visible.value = false; emit('completed')
  } catch (e) { ElMessage.error((e as Error).message || '提交失败') }
  finally { submitting.value = false }
}
</script>
