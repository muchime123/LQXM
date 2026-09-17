<template>
  <div class="fee-records-panel">
    <div class="panel-header">
      <h4 class="panel-title">缴费记录</h4>
      <button class="btn-sm primary" @click="openAddFeeForm">+ 新增缴费记录</button>
    </div>

    <div v-if="feeRecords.length" class="fee-summary">
      <span>共 <strong>{{ feeRecords.length }}</strong> 条</span>
      <span>合计 <strong class="fee-summary__total">¥{{ totalFeeAmount }}</strong></span>
      <span v-if="overdueCount" class="fee-summary__overdue">逾期 <strong>{{ overdueCount }}</strong> 条</span>
    </div>

    <div v-if="feeRecordsLoading" class="loading-line">加载中...</div>
    <div v-else-if="feeRecords.length === 0" class="empty-line">暂无缴费记录</div>
    <div v-else class="fee-records-list">
      <div
        v-for="record in feeRecords"
        :key="record.id"
        class="fee-record-item"
        :class="{ 'fee-record-item--overdue': isOverdue(record) }"
      >
        <div class="fee-record-main">
          <div class="fee-record-header">
            <span class="fee-type">{{ record.feeType || '年费' }}</span>
            <span v-if="record.feeYear" class="fee-year">第{{ record.feeYear }}年</span>
            <span v-if="isOverdue(record)" class="fee-overdue-badge">已逾期</span>
            <span class="fee-amount">¥{{ record.amount || 0 }}</span>
          </div>
          <div class="fee-record-info">
            <span class="info-item">缴费日期：{{ fmtDate(record.paidDate) }}</span>
            <span v-if="record.dueDate" class="info-item">截止日期：{{ fmtDate(record.dueDate) }}</span>
            <span v-if="record.receiptNo" class="info-item">收据号：{{ record.receiptNo }}</span>
            <span v-if="record.payer" class="info-item">缴费人：{{ record.payer }}</span>
            <span v-if="record.invoiceStatus" class="info-item">发票：{{ record.invoiceStatus }}</span>
          </div>
          <div v-if="record.note" class="fee-record-note">{{ record.note }}</div>
        </div>
        <div class="fee-record-actions">
          <button class="btn-link" @click="openEditFeeRecord(record)">编辑</button>
          <button class="btn-link danger" @click="confirmDeleteFeeRecord(record)">删除</button>
        </div>
      </div>
    </div>

    <LqDialog v-model="feeModal.visible" title="缴费记录" size="sm" mode="form" :close-on-click-modal="false"
      destroy-on-close :loading="feeModal.saving" :confirm-disabled="feeModal.saving" @cancel="cancelFeeForm"
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
            <el-input v-model.number="feeForm.feeYear" type="number" min="1" placeholder="第几年" />
          </div>
          <div class="form-group">
            <label>缴费金额（元）</label>
            <el-input v-model.number="feeForm.amount" type="number" placeholder="0.00" step="0.01" />
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
  background: var(--color-surface);
  border-radius: var(--radius-lg);
  padding: var(--space-section-gap);
  margin-top: var(--space-section-gap);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-section-gap);
}

.panel-title {
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
  margin: 0;
}

.fee-summary {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap-wide);
  margin-bottom: var(--space-control-gap-wide);
  padding: var(--space-control-gap) var(--space-control-gap-wide);
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-md);
  font-size: var(--fs-sm);
  color: var(--color-text-secondary);
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

.fee-record-item--overdue {
  border-color: var(--color-danger);
  background: var(--color-danger-bg-soft);
}

.fee-overdue-badge {
  padding: 1px 8px;
  font-size: var(--fs-xs);
  color: var(--color-danger);
  background: var(--color-danger-bg-soft);
  border: 1px solid var(--color-danger);
  border-radius: var(--radius-sm);
  font-weight: var(--fw-medium);
}

.btn-sm {
  padding: 6px 12px;
  font-size: var(--fs-sm);
  border-radius: var(--radius-sm);
  border: 1px solid var(--color-border);
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
  height: 80px;
  color: var(--color-text-muted);
}

.fee-records-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.fee-record-item {
  display: flex;
  align-items: stretch;
  justify-content: space-between;
  padding: var(--space-control-gap-wide);
  background: var(--color-surface-raised);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
}

.fee-record-main {
  flex: 1;
  min-width: 0;
}

.fee-record-header {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-sm);
  flex-wrap: wrap;
}

.fee-type {
  padding: 2px 8px;
  background: var(--color-primary-bg);
  color: var(--color-primary);
  border-radius: var(--radius-sm);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
}

.fee-year {
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
}

.fee-amount {
  font-size: var(--fs-md);
  font-weight: var(--fw-bold);
  color: var(--color-text-heading);
}

.fee-record-info {
  display: flex;
  gap: var(--space-form-item-gap);
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
  flex-wrap: wrap;
}

.info-item {
  display: flex;
  align-items: center;
}

.fee-record-note {
  margin-top: var(--space-sm);
  font-size: var(--fs-xs);
  color: var(--color-text-secondary);
  font-style: italic;
}

.fee-record-actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap);
  margin-left: var(--space-form-item-gap);
}

.btn-link {
  background: none;
  border: none;
  color: var(--color-primary);
  cursor: pointer;
  font-size: var(--fs-xs);
  padding: 0;
}

.btn-link:hover {
  text-decoration: underline;
}

.btn-link.danger {
  color: var(--color-danger);
}

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
