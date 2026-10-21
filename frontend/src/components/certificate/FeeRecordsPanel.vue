<template>
  <div class="fee-records-panel">
    <div class="panel-head">
      <h4 class="panel-title">缴费记录</h4>
      <div v-if="feeRecords.length" class="fee-summary">
        <span>共 <strong>{{ feeRecords.length }}</strong> 条</span>
        <span>合计 <strong class="fee-summary__total">¥{{ totalFeeAmount }}</strong></span>
        <span v-if="overdueCount" class="fee-summary__overdue">逾期 <strong>{{ overdueCount }}</strong> 条</span>
      </div>
      <button class="btn-sm primary" @click="openAddFeeForm">＋ 新增缴费记录</button>
    </div>

    <div v-if="feeRecordsLoading" class="loading-line">加载中...</div>
    <div v-else-if="feeRecords.length === 0" class="empty-line">暂无缴费记录，点击右上角按钮登记第一笔年费。</div>
    <table v-else class="fee-table">
      <thead>
        <tr>
          <th class="col-date">缴费日期</th>
          <th class="col-type">类型</th>
          <th class="col-year">年度</th>
          <th class="col-amount">金额</th>
          <th class="col-date">截止日期</th>
          <th class="col-rest">收据号 / 缴费人</th>
          <th class="col-actions"></th>
        </tr>
      </thead>
      <tbody>
        <template v-for="record in feeRecords" :key="record.id">
          <tr :class="{ 'fee-record-overdue': isOverdue(record) }">
            <td class="mono">{{ fmtDate(record.paidDate) || '—' }}</td>
            <td>
              <span class="fee-type">{{ record.feeType || '年费' }}</span>
              <span v-if="record.invoiceStatus" class="fee-invoice" :title="`发票：${record.invoiceStatus}`">{{
                record.invoiceStatus
              }}</span>
            </td>
            <td>{{ record.feeYear ? `第 ${record.feeYear} 年` : '—' }}</td>
            <td class="num">¥{{ record.amount || 0 }}</td>
            <td class="mono" :class="{ 'date-danger': isOverdue(record) }">{{ fmtDate(record.dueDate) || '—' }}</td>
            <td class="rest-cell">
              <span class="rest-inner">
                <span v-if="record.receiptNo" class="mono receipt">{{ record.receiptNo }}</span>
                <span v-if="record.payer" class="payer">{{ record.payer }}</span>
                <span v-if="!record.receiptNo && !record.payer" class="text-faint">—</span>
                <span v-if="isOverdue(record)" class="overdue-badge">已逾期</span>
              </span>
            </td>
            <td class="actions">
              <button @click="openEditFeeRecord(record)">编辑</button>
              <button class="danger" @click="confirmDeleteFeeRecord(record)">删除</button>
            </td>
          </tr>
          <tr v-if="record.note" class="note-row">
            <td :colspan="7">{{ record.note }}</td>
          </tr>
        </template>
      </tbody>
    </table>

    <LqDialog
v-model="feeModal.visible"
title="缴费记录"
size="sm"
mode="form"
:close-on-click-modal="false"
      destroy-on-close
:loading="feeModal.saving"
:confirm-disabled="feeModal.saving"
@cancel="cancelFeeForm"
      @confirm="submitFeeRecord">
      <div class="fee-form">
        <div class="form-group">
          <label>缴费日期 <span class="required">*</span></label>
          <LqDatePicker v-model="feeForm.paidDate" />
        </div>
        <div class="form-group">
          <label>费用类型</label>
          <el-select v-model="feeForm.feeType" placeholder="年费" style="width: 100%">
            <el-option label="年费" value="年费" />
            <el-option label="申请费" value="申请费" />
            <el-option label="授权费" value="授权费" />
            <el-option label="维持费" value="维持费" />
            <el-option label="其他" value="其他" />
          </el-select>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label>缴费年度</label>
            <el-input
v-model.number="feeForm.feeYear"
type="number"
min="1"
placeholder="第几年" />
          </div>
          <div class="form-group">
            <label>缴费金额（元）</label>
            <el-input
v-model.number="feeForm.amount"
type="number"
placeholder="0.00"
step="0.01" />
          </div>
        </div>
        <div class="form-group">
          <label>截止日期</label>
          <LqDatePicker v-model="feeForm.dueDate" />
        </div>
        <div class="form-group">
          <label>缴费人</label>
          <el-input v-model="feeForm.payer" placeholder="缴费人姓名" />
        </div>
        <div class="form-group">
          <label>收据号/流水号</label>
          <el-input v-model="feeForm.receiptNo" placeholder="收据编号" />
        </div>
        <div class="form-group">
          <label>备注</label>
          <el-input v-model="feeForm.note" type="textarea" :rows="2" />
        </div>
      </div>
    </LqDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import LqDatePicker from '../common/LqDatePicker.vue'
import type { FeeRecordSummary } from '../../types/certificate'
import { addFeeRecord, deleteFeeRecord, fetchFeeRecords, updateFeeRecord } from '../../api/certificate'
import { askConfirm } from '../../composables/ui/useConfirmDialog'
import { useModalForm } from '../../composables/ui/useModalForm'
import { notify } from '../../composables/ui/useToast'
import { fmtDate, todayIso } from '../../utils/common/dateUtils'

const props = defineProps<{
  certificateId: number
}>()

const emit = defineEmits<{
  updated: []
}>()

const feeRecords = ref<FeeRecordSummary[]>([])
const feeRecordsLoading = ref(false)

const emptyFeeForm = () => ({
  paidDate: todayIso(),
  feeType: '年费',
  feeYear: null as number | null,
  amount: null as number | null,
  dueDate: '',
  payer: '',
  receiptNo: '',
  note: ''
})

const {
  modal: feeModal,
  form: feeForm,
  isNew: feeIsNew,
  openAdd: _openFeeAdd,
  openEdit: _openFeeEdit,
  closeModal: _closeFeeModal,
  handleSave: _handleFeeSave
} = useModalForm({ defaultForm: emptyFeeForm })

async function loadFeeRecords() {
  if (!props.certificateId) return
  feeRecordsLoading.value = true
  try {
    const records = await fetchFeeRecords(props.certificateId)
    feeRecords.value = [...records].sort((a, b) => (b.paidDate || '').localeCompare(a.paidDate || ''))
  } catch (err) {
    console.warn('加载缴费记录失败', err)
  } finally {
    feeRecordsLoading.value = false
  }
}

const totalFeeAmount = computed(() =>
  feeRecords.value.reduce((sum, r) => sum + (Number(r.amount) || 0), 0)
)

const overdueCount = computed(() => feeRecords.value.filter(isOverdue).length)

function isOverdue(record: FeeRecordSummary): boolean {
  return !!record.dueDate && record.dueDate < todayIso()
}

function openAddFeeForm() {
  if (feeModal.visible) {
    _closeFeeModal()
  } else {
    _openFeeAdd()
  }
}

function cancelFeeForm() {
  _closeFeeModal()
}

function openEditFeeRecord(record: FeeRecordSummary) {
  _openFeeEdit(record, r => ({
    paidDate: r.paidDate || '',
    feeType: r.feeType || '年费',
    feeYear: r.feeYear || null,
    amount: r.amount || null,
    dueDate: r.dueDate || '',
    payer: r.payer || '',
    receiptNo: r.receiptNo || '',
    note: r.note || ''
  }))
}

async function submitFeeRecord() {
  if (!feeForm.paidDate) {
    notify('请填写缴费日期', 'warning')
    return
  }
  await _handleFeeSave(
    async () => {
      if (feeIsNew.value) {
        await addFeeRecord(props.certificateId, { ...feeForm })
      } else {
        if (feeModal.editId == null) throw new Error('Missing fee record id')
        await updateFeeRecord(props.certificateId, feeModal.editId, { ...feeForm })
      }
      await loadFeeRecords()
    },
    '',
    '保存缴费记录失败'
  )
  emit('updated')
}

async function confirmDeleteFeeRecord(record: FeeRecordSummary) {
  const ok = await askConfirm({
    title: '确认删除',
    message: `确认删除 ${fmtDate(record.paidDate)} ${record.feeType || ''}缴费记录（¥${record.amount || 0}）？`,
    danger: true
  })
  if (!ok) return
  try {
    await deleteFeeRecord(props.certificateId, record.id)
    await loadFeeRecords()
    emit('updated')
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

watch(() => props.certificateId, loadFeeRecords)
</script>

<style scoped>
.fee-records-panel {
  margin-top: var(--space-md-lg, 12px);
  padding: 13px 17px 15px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-xl, 10px);
}

.panel-head {
  display: flex;
  align-items: center;
  gap: var(--space-md-lg, 12px);
  margin-bottom: 10px;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: var(--space-sm-md, 8px);
  margin: 0;
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  letter-spacing: 0.02em;
  color: var(--color-text-heading);
}

.panel-title::before {
  content: '';
  flex-shrink: 0;
  width: 3px;
  height: 13px;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
  opacity: 0.85;
}

.fee-summary {
  display: flex;
  align-items: center;
  gap: var(--space-md-lg, 12px);
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
}

.fee-summary strong {
  color: var(--color-text-heading);
  font-weight: var(--fw-semibold);
}

.fee-summary__total {
  color: var(--color-primary) !important;
}

.fee-summary__overdue {
  color: var(--color-danger);
}

.fee-summary__overdue strong {
  color: var(--color-danger);
}

.btn-sm {
  padding: 5px 12px;
  font-size: var(--fs-xs);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all var(--transition-fast);
}

.btn-sm:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.btn-sm.primary {
  margin-left: auto;
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-surface);
}

.btn-sm.primary:hover {
  opacity: 0.9;
}

.loading-line,
.empty-line {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 72px;
  font-size: var(--fs-sm);
  color: var(--color-text-faint);
}

/* 记录表格 */
.fee-table {
  width: 100%;
  border-collapse: collapse;
}

.fee-table th {
  padding: 0 10px 7px 0;
  border-bottom: 1px solid var(--color-border-lighter);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-medium);
  letter-spacing: 0.02em;
  color: var(--color-text-muted);
  text-align: left;
}

.fee-table th.col-amount,
.fee-table td.num {
  text-align: right;
}

.fee-table th.col-actions,
.fee-table td.actions {
  text-align: right;
  white-space: nowrap;
}

.fee-table td {
  padding: 9px 10px 9px 0;
  border-bottom: 1px solid var(--color-border-lighter);
  font-size: var(--fs-sm);
  color: var(--color-text-primary);
  vertical-align: middle;
}

.fee-table tbody tr:last-child td {
  border-bottom: none;
}

.fee-table .mono {
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
}

.fee-table td.num {
  font-family: var(--font-mono);
  font-weight: var(--fw-semibold);
}

.date-danger {
  color: var(--color-danger);
}

.fee-type {
  display: inline-block;
  padding: 1px 7px;
  background: var(--color-primary-bg);
  color: var(--color-primary);
  border-radius: var(--radius-sm);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-medium);
}

.fee-invoice {
  margin-left: 6px;
  font-size: var(--fs-xxs);
  color: var(--color-text-faint);
}

.rest-cell .rest-inner {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.rest-cell .receipt {
  color: var(--color-text-secondary);
}

.rest-cell .payer {
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
}

.text-faint {
  color: var(--color-text-faint);
}

.overdue-badge {
  flex-shrink: 0;
  padding: 1px 7px;
  font-size: var(--fs-xxs);
  font-weight: var(--fw-medium);
  color: var(--color-danger);
  background: var(--color-danger-bg-soft);
  border: 1px solid var(--color-danger-border);
  border-radius: var(--radius-sm);
}

.fee-record-overdue {
  background: var(--color-danger-bg-soft);
}

.note-row td {
  padding: 0 10px 9px 0;
  font-size: var(--fs-xs);
  font-style: italic;
  color: var(--color-text-secondary);
}

.actions button {
  padding: 0 0 0 10px;
  border: none;
  background: none;
  cursor: pointer;
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
}

.actions button:hover {
  color: var(--color-primary);
}

.actions button.danger:hover {
  color: var(--color-danger);
}

/* 缴费记录弹窗表单 */
.fee-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-wide);
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
}

.form-group label {
  font-size: var(--fs-sm);
  color: var(--color-text-secondary);
  font-weight: var(--fw-medium);
}

.required {
  color: var(--color-danger);
}

.form-row {
  display: flex;
  gap: var(--space-control-gap-wide);
}

.form-row .form-group {
  flex: 1;
}
</style>
