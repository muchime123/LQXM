<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  fetchSalesDeliveryOptions,
  createOrderOutboundDraft,
  type SalesOrderLine,
  type SalesDeliveryOption
} from '@/api/salesOrder'
import { newRequestKey, postSalesOutbound } from '@/api/salesOutbound'
import { notify } from '@/composables/ui/useToast'
const props = defineProps<{ modelValue: boolean; orderId: number; line: SalesOrderLine | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; created: [id: number] }>()
const options = ref<SalesDeliveryOption[]>([])
const selected = ref<number>()
const quantity = ref(1)
const loading = ref(false)
const saving = ref(false)
/** 生成并过账（一次完成选货 → 生成草稿 → 立即过账）：避免「生成草稿→跳页→再编辑→过账」两步割裂。 */
const posting = ref(false)
const error = ref('')
const chosen = computed(() => (selected.value === undefined ? undefined : options.value[selected.value]))
const valid = computed(
  () =>
    !!chosen.value &&
    Number.isFinite(quantity.value) &&
    quantity.value > 0 &&
    quantity.value <= chosen.value.availableQuantity
)
let version = 0
let submission: { payload: string; key: string } | undefined
async function load() {
  const current = ++version
  options.value = []
  selected.value = undefined
  error.value = ''
  loading.value = true
  try {
    if (!props.line?.id) throw new Error('订单明细无效')
    const rows = await fetchSalesDeliveryOptions(props.orderId, props.line.id)
    if (current !== version) return
    options.value = rows
    if (rows.length === 1) {
      selected.value = 0
      pick()
    }
  } catch (e) {
    if (current === version) error.value = e instanceof Error ? e.message : '可发来源加载失败'
  } finally {
    if (current === version) loading.value = false
  }
}
function pick() {
  quantity.value = Number(chosen.value?.availableQuantity || 0)
}
async function submit() {
  if (saving.value || loading.value || !valid.value || !chosen.value || !props.line?.id) return
  const payload = JSON.stringify([
    props.orderId,
    props.line.id,
    chosen.value.warehouseId,
    chosen.value.sourceLotId,
    quantity.value
  ])
  if (submission?.payload !== payload) submission = { payload, key: newRequestKey() }
  saving.value = true
  error.value = ''
  try {
    const id = await createOrderOutboundDraft(
      props.orderId,
      props.line.id,
      chosen.value.warehouseId,
      quantity.value,
      submission!.key,
      chosen.value.sourceLotId
    )
    emit('created', id)
    emit('update:modelValue', false)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '生成草稿失败，请重试'
  } finally {
    saving.value = false
  }
}
/**
 * 一次完成选货并过账：选好仓库+来源+数量后直接生成草稿并立即过账，
 * 省去「跳到 /sales-outbounds 打开抽屉再点过账」的中间步骤。
 * 失败时草稿仍保留（后端事务保证），用户可去出库页重试。
 */
async function submitAndPost() {
  if (saving.value || posting.value || loading.value || !valid.value || !chosen.value || !props.line?.id) return
  try {
    await ElMessageBox.confirm(
      `将立即按所选仓库/来源/数量生成出库草稿并过账，扣减「${chosen.value.warehouseName}」库存。确定继续？`,
      '生成并过账确认',
      { type: 'warning', confirmButtonText: '确定过账', cancelButtonText: '取消' }
    )
  } catch { return }
  const payload = JSON.stringify([
    props.orderId,
    props.line.id,
    chosen.value.warehouseId,
    chosen.value.sourceLotId,
    quantity.value
  ])
  if (submission?.payload !== payload) submission = { payload, key: newRequestKey() }
  posting.value = true
  error.value = ''
  try {
    const id = await createOrderOutboundDraft(
      props.orderId,
      props.line.id,
      chosen.value.warehouseId,
      quantity.value,
      submission!.key,
      chosen.value.sourceLotId
    )
    // 立即过账：用同一个 requestKey 防止重复点击产生重复过账。
    await postSalesOutbound(id, submission!.key)
    notify.success(`已生成并过账出库单 #${id}，库存已扣减`)
    emit('created', id)
    emit('update:modelValue', false)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '生成并过账失败，请重试（草稿若已生成可去出库页过账）'
  } finally {
    posting.value = false
  }
}
watch(
  () => [props.modelValue, props.orderId, props.line?.id],
  () => {
    if (props.modelValue) {
      submission = undefined
      void load()
    } else {
      version++
      loading.value = false
    }
  },
  { immediate: true }
)
</script>
<template>
  <el-dialog
    :model-value="modelValue"
    title="从原订单明细办理发货"
    width="min(720px, 94vw)"
    :close-on-click-modal="!saving"
    :close-on-press-escape="!saving"
    :show-close="!saving"
    @update:model-value="!saving && emit('update:modelValue', $event)"
  >
    <p>
      <strong>{{ line?.productName }}</strong> · 按原订单来源分批交付
    </p>
    <p>自产引用原任务的主产品入库批次，外采引用原采购到货；生成草稿后核对过账才扣库存。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <p v-if="loading">正在核对原订单的可发来源…</p>
    <el-alert
      v-else-if="!error && !options.length"
      title="暂无可发来源。自产请先检验入库，外采请先到货入库；供应商直发请到采购单办理代发。已有草稿也会占用本明细可发数量。"
      type="info"
      :closable="false"
    />
    <el-select
      v-if="options.length"
      v-model="selected"
      :disabled="saving"
      placeholder="选择发货仓库与来源"
      style="width: 100%; margin-top: 12px"
      @change="pick"
      ><el-option
        v-for="(option, index) in options"
        :key="index"
        :value="index"
        :label="`${option.warehouseName} · ${option.sourceNo || '现货'} · ${option.productName} · 可发 ${option.availableQuantity}${option.unit}`"
    /></el-select>
    <p v-if="chosen">
      {{ chosen.specification || '无规格' }} · 当前总库存 {{ chosen.stockQuantity }} {{ chosen.unit }}
    </p>
    <el-form v-if="chosen" label-position="top"
      ><el-form-item label="本次发货数量，可分批"
        ><el-input-number
          v-model="quantity"
          :disabled="saving"
          :min="0.000001"
          :max="Number(chosen.availableQuantity)"
          :precision="6"
          :controls="false" /></el-form-item
    ></el-form>
    <template #footer
      ><el-button :disabled="saving || posting" @click="emit('update:modelValue', false)">取消</el-button
      ><el-button v-if="error && !options.length" :loading="loading" @click="load">重新加载来源</el-button
      ><el-button type="primary" :disabled="loading || !valid" :loading="saving" @click="submit"
        >生成草稿</el-button
      ><el-button type="success" :disabled="loading || !valid" :loading="posting" @click="submitAndPost"
        >生成并过账</el-button
      ></template
    >
  </el-dialog>
</template>
