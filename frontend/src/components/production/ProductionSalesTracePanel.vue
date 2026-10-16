<template>
  <section class="sales-trace">
    <header class="sales-trace-head">
      <div><h3>销售去向</h3><small>按采购入库明细核对销售出库；同产品号仅是候选</small></div>
      <el-button size="small" :loading="loading" @click="load">刷新</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="trace">
      <div class="sales-trace-summary">
        <span>工单计划生产 <b>{{ trace.requiredQuantity == null ? '未填' : `${quantity(trace.requiredQuantity)} ${trace.requiredUnit || '台'}` }}</b></span>
        <span>有效采购入库明细 <b>{{ trace.receipts.filter(receipt => receipt.receiptStatus === '已生效' && Number(receipt.quantity) > 0).length }} 项</b></span>
      </div>
      <p class="sales-trace-note">下方按每条入库产品分别核对销售；不同产品的“台”“套”不能相加。“已核实归属销售”是人工确认的数量，0 不代表产品尚未销售。</p>
      <el-alert :title="trace.coverageNote" :type="trace.receipts.some(receipt => receiptAfterCoverage(receipt)) ? 'warning' : 'info'" :closable="false" show-icon />
      <el-empty v-if="!trace.receipts.length" description="当前未见关联采购入库；不能据此判断是否已销售" :image-size="56" />
      <div v-for="receipt in trace.receipts" :key="receipt.receiptItemId" class="sales-trace-receipt">
        <div class="sales-trace-receipt-head">
          <div><b>{{ receipt.productCode || '产品号未填' }}</b> · {{ receipt.productName || '产品名称未填' }}
            <small>{{ receipt.receiptNo }} · {{ receipt.receiptDate || '日期未填' }} · {{ receipt.receiptWarehouse || '仓库未填' }}</small>
          </div>
          <el-tag v-if="receipt.receiptStatus !== '已生效'" type="info" size="small">{{ receipt.receiptStatus || '状态未填' }}，不计入</el-tag>
          <el-tag v-else-if="activeReceiptId === receipt.receiptItemId && candidates.length" type="warning" size="small">找到销售出库候选，待核实归属</el-tag>
          <el-tag v-else-if="receiptAfterCoverage(receipt)" type="warning" size="small">出库数据尚未覆盖此入库日期</el-tag>
          <el-tag v-else-if="Number(receipt.confirmedQuantity) >= Number(receipt.quantity) && Number(receipt.quantity) > 0" type="success" size="small">入库部分已确认售出</el-tag>
          <el-tag v-else-if="Number(receipt.confirmedQuantity) > 0" type="warning" size="small">部分确认</el-tag>
          <el-tag v-else type="info" size="small">销售归属待核</el-tag>
        </div>
        <div class="sales-trace-actions">
          <span>入库 {{ quantity(receipt.quantity) }} {{ receipt.unit || '' }} · 已核实归属销售 {{ quantity(receipt.confirmedQuantity) }} {{ receipt.unit || '' }}</span>
          <el-button link type="primary" :loading="candidateLoading && activeReceiptId === receipt.receiptItemId"
            @click="showCandidates(receipt)">查看销售出库候选</el-button>
        </div>
        <div v-if="activeReceiptId === receipt.receiptItemId" class="sales-trace-candidates">
          <div class="sales-trace-search"><el-input v-model="candidateKeyword" clearable placeholder="按销售出库单号搜索" @keyup.enter="searchCandidates(receipt)" /><el-button :loading="candidateLoading" @click="searchCandidates(receipt)">搜索</el-button></div>
          <p v-if="!candidateLoading && !candidates.length" class="sales-trace-note">{{ receiptAfterCoverage(receipt) ? '该入库日期晚于当前销售出库台账最新日期；请先导入更新的销售出库数据。' : '当前数据未找到同产品号的有效销售出库；不能判定尚未销售。' }}</p>
          <p v-if="candidates.length === 100" class="sales-trace-note">仅显示前 100 条候选，请进一步核对产品号和日期。</p>
          <div v-for="sale in candidates" :key="sale.saleItemId" class="sales-trace-candidate">
            <div><b>{{ sale.outboundNo }}</b> · {{ sale.outboundDate || '日期未填' }}
              <small>出库 {{ quantity(sale.quantity) }} {{ sale.unit || '' }} · {{ sale.outboundWarehouse || '仓库未填' }}</small>
              <small v-if="!sale.sameUnit || !sale.sameWarehouse || !sale.dateAfterReceipt" class="sales-trace-warning">
                {{ [!sale.sameUnit && '单位不同', !sale.sameWarehouse && '仓库不同', !sale.dateAfterReceipt && '出库早于入库'].filter(Boolean).join('、') }}，需核对
              </small>
            </div>
            <div class="sales-trace-candidate-actions">
              <el-button link type="primary" @click="openOutbound(sale.outboundId)">出库详情</el-button>
              <el-button v-if="canConfirm && receipt.receiptStatus === '已生效' && sale.sameUnit && sale.dateAfterReceipt"
                link type="primary" @click="prepareConfirmation(receipt, sale)">确认数量归属</el-button>
            </div>
          </div>
        </div>
      </div>
      <div v-if="trace.allocations.length" class="sales-trace-history">
        <b>数量确认记录</b>
        <div v-for="record in trace.allocations" :key="record.id" class="sales-trace-history-row">
          <span>{{ record.receiptNo }} → {{ record.outboundNo }} · {{ quantity(record.quantity) }} {{ record.unit }}</span>
          <el-tag :type="record.status !== 'ACTIVE' ? 'info' : record.currentSource ? 'success' : 'danger'" size="small">
            {{ record.status !== 'ACTIVE' ? '已撤销' : record.currentSource ? '当前有效' : '源单已变更，需复核' }}
          </el-tag>
          <small>{{ record.confirmedByName }} · {{ record.reason }}</small>
          <el-button v-if="canConfirm && record.status === 'ACTIVE'" link type="danger" @click="revoke(record)">撤销</el-button>
        </div>
      </div>
    </template>
    <el-dialog v-model="confirmVisible" title="确认销售数量归属" width="min(510px, 94vw)" append-to-body>
      <p>{{ selectedReceipt?.receiptNo }} · {{ selectedReceipt?.productCode }} → {{ selectedSale?.outboundNo }}</p>
      <p class="sales-trace-note">入库 {{ quantity(selectedReceipt?.quantity) }} {{ selectedReceipt?.unit }}；出库 {{ quantity(selectedSale?.quantity) }} {{ selectedSale?.unit }}。系统保存时再次检查两端剩余数量。</p>
      <el-form label-position="top">
        <el-form-item label="确认归属数量"><el-input-number v-model="confirmQuantity" :min="0.0001" :precision="4" :step="1" style="width:100%" /></el-form-item>
        <el-form-item label="核实依据"><el-input v-model="confirmReason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="写明核实人、批次或单据等依据" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="confirmVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="confirm">确认归属</el-button></template>
    </el-dialog>
    <TrackingSourceDetailDrawer v-model="outboundVisible" kind="outbound" :loading="outboundLoading" :data="outboundData" :error="outboundError" />
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmTrackingSalesAllocation, fetchTrackingSalesCandidates, fetchTrackingSalesOutboundDetail, fetchTrackingSalesTrace, revokeTrackingSalesAllocation } from '@/api/productionTracking'
import type { TrackingSalesTrace, TrackingSalesTraceReceipt, TrackingSalesTraceCandidate, TrackingSalesTraceAllocation } from '@/api/productionTracking'
import type { LedgerDetailResult } from '@/types/h3Import'
import TrackingSourceDetailDrawer from '@/components/production/TrackingSourceDetailDrawer.vue'

const props = defineProps<{ orderId: number; canConfirm: boolean }>()
const trace = ref<TrackingSalesTrace | null>(null)
const loading = ref(false)
const error = ref('')
const activeReceiptId = ref<number | null>(null)
const candidateLoading = ref(false)
const candidates = ref<TrackingSalesTraceCandidate[]>([])
const candidateKeyword = ref('')
const confirmVisible = ref(false)
const selectedReceipt = ref<TrackingSalesTraceReceipt | null>(null)
const selectedSale = ref<TrackingSalesTraceCandidate | null>(null)
const confirmQuantity = ref(1)
const confirmReason = ref('')
const saving = ref(false)
const outboundVisible = ref(false)
const outboundLoading = ref(false)
const outboundData = ref<LedgerDetailResult | null>(null)
const outboundError = ref('')

function quantity(value?: number | null) { return value == null ? '—' : Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 4 }) }
function receiptAfterCoverage(receipt: TrackingSalesTraceReceipt) {
  if (!receipt.receiptDate || !trace.value) return false
  if (activeReceiptId.value === receipt.receiptItemId && candidates.value.length) return false
  // Older backend responses omit this field. Missing metadata cannot prove missing sales data.
  if (trace.value.outboundCoverageThrough === undefined) return false
  return trace.value.outboundCoverageThrough === null
    || receipt.receiptDate.slice(0, 10) > trace.value.outboundCoverageThrough.slice(0, 10)
}
async function load() {
  const orderId = props.orderId
  loading.value = true
  error.value = ''
  try { const data = await fetchTrackingSalesTrace(orderId); if (props.orderId === orderId) trace.value = data }
  catch (e) { if (props.orderId === orderId) error.value = e instanceof Error ? e.message : '加载销售去向失败' }
  finally { loading.value = false }
}
watch(() => props.orderId, () => { trace.value = null; candidates.value = []; candidateKeyword.value = ''; activeReceiptId.value = null; void load() }, { immediate: true })
async function showCandidates(receipt: TrackingSalesTraceReceipt) {
  if (activeReceiptId.value === receipt.receiptItemId) { activeReceiptId.value = null; return }
  activeReceiptId.value = receipt.receiptItemId
  candidateKeyword.value = ''
  await searchCandidates(receipt)
}
async function searchCandidates(receipt: TrackingSalesTraceReceipt) {
  candidates.value = []
  candidateLoading.value = true
  const orderId = props.orderId
  try {
    const rows = await fetchTrackingSalesCandidates(orderId, receipt.receiptItemId, candidateKeyword.value.trim())
    if (props.orderId === orderId && activeReceiptId.value === receipt.receiptItemId) candidates.value = rows
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '查询销售出库失败') }
  finally { candidateLoading.value = false }
}
function prepareConfirmation(receipt: TrackingSalesTraceReceipt, sale: TrackingSalesTraceCandidate) {
  selectedReceipt.value = receipt
  selectedSale.value = sale
  confirmQuantity.value = Math.max(0.0001, Math.min(Number(receipt.quantity) - Number(receipt.confirmedQuantity), Number(sale.quantity)))
  confirmReason.value = ''
  confirmVisible.value = true
}
async function confirm() {
  if (!selectedReceipt.value || !selectedSale.value) return
  if (!confirmReason.value.trim()) { ElMessage.warning('请填写核实依据'); return }
  saving.value = true
  try {
    await confirmTrackingSalesAllocation(props.orderId, {
      receiptItemId: selectedReceipt.value.receiptItemId, saleItemId: selectedSale.value.saleItemId,
      quantity: confirmQuantity.value, reason: confirmReason.value.trim()
    })
    confirmVisible.value = false
    ElMessage.success('已确认销售数量归属')
    await load()
  } catch (e) { ElMessage.error(e instanceof Error ? e.message : '保存销售归属失败') }
  finally { saving.value = false }
}
async function revoke(record: TrackingSalesTraceAllocation) {
  try {
    const result = await ElMessageBox.prompt('请填写撤销原因，历史记录会保留。', '撤销销售数量归属', { inputValidator: value => Boolean(value?.trim()) || '请填写撤销原因' })
    await revokeTrackingSalesAllocation(props.orderId, record.id, result.value.trim())
    ElMessage.success('已撤销归属记录')
    await load()
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e instanceof Error ? e.message : '撤销失败') }
}
async function openOutbound(outboundId: number) {
  outboundVisible.value = true
  outboundLoading.value = true
  outboundError.value = ''
  outboundData.value = null
  try { outboundData.value = await fetchTrackingSalesOutboundDetail(props.orderId, outboundId) }
  catch (e) { outboundError.value = e instanceof Error ? e.message : '加载销售出库详情失败' }
  finally { outboundLoading.value = false }
}
</script>

<style scoped>
.sales-trace { margin: 22px 0; border-top: 1px solid #e4e7ed; padding-top: 18px; }
.sales-trace-head, .sales-trace-receipt-head, .sales-trace-actions, .sales-trace-candidate, .sales-trace-history-row { display: flex; justify-content: space-between; gap: 12px; align-items: center; }
.sales-trace-head h3 { margin: 0 0 3px; font-size: 15px; }
.sales-trace-head small, .sales-trace-receipt small, .sales-trace-candidate small, .sales-trace-history-row small { display: block; color: #667085; font-size: 12px; margin-top: 4px; }
.sales-trace-summary { display: flex; flex-wrap: wrap; gap: 10px; margin: 14px 0 8px; }
.sales-trace-summary span { padding: 8px 12px; border: 1px solid #e4e7ed; border-radius: 7px; background: #f8fafc; }
.sales-trace-summary b { margin-left: 5px; }
.sales-trace-note { color: #667085; font-size: 12px; line-height: 1.5; }
.sales-trace-receipt { border: 1px solid #e4e7ed; border-radius: 8px; padding: 12px; margin-top: 10px; }
.sales-trace-actions { font-size: 12px; color: #475467; margin-top: 9px; }
.sales-trace-candidates { border-top: 1px solid #e4e7ed; padding-top: 9px; margin-top: 8px; max-height: 300px; overflow: auto; }
.sales-trace-search { display: flex; gap: 8px; margin-bottom: 8px; }
.sales-trace-candidate { padding: 8px 0; border-bottom: 1px solid #eef1f4; }
.sales-trace-candidate-actions { white-space: nowrap; }
.sales-trace-candidate .sales-trace-warning { color: #b42318; }
.sales-trace-history { margin-top: 16px; }
.sales-trace-history-row { padding: 7px 0; border-bottom: 1px solid #eef1f4; font-size: 12px; flex-wrap: wrap; }
@media (max-width: 760px) { .sales-trace-head, .sales-trace-receipt-head, .sales-trace-actions, .sales-trace-candidate { align-items: flex-start; flex-direction: column; } }
</style>
