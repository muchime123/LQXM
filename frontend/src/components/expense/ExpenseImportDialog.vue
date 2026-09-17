<template>
  <LqDialog
    v-model:model-value="visible"
    :title="!preview ? '导入钉钉报销 Excel' : '导入预览'"
    :subtitle="!preview ? '上传钉钉「日常报销」导出的 Excel，解析预览后再确认入库。' : buildSubtitle()"
    :width="preview ? 'min(1400px, 94vw)' : 'min(720px, 92vw)'"
    mode="workspace"
    class="expense-import-dialog"
    :loading="previewing || confirming"
    :before-close="handleBeforeClose"
    :confirm-text="preview ? '确认导入' : '解析预览'"
    :cancel-text="preview ? '取消' : '关闭'"
    :confirm-disabled="preview ? confirmDisabled : !currentFile"
    @confirm="handleConfirm"
    @cancel="visible = false"
  >
    <!-- 上传阶段 -->
    <div v-if="!preview" class="upload-stage">
      <el-alert type="info" :closable="false" show-icon>
        <template #title>
          数据源为钉钉 OA
          审批后台导出的「日常报销」Excel（.xlsx）。同一「审批编号」的多行明细会自动合并为一条报销单；重复导入会按内容自动跳过或更新，不会产生重复数据。
        </template>
      </el-alert>
      <el-upload
        class="expense-uploader"
        drag
        :auto-upload="false"
        :show-file-list="true"
        :limit="1"
        accept=".xlsx,.xls"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
      >
        <el-icon class="uploader-icon"><upload-filled /></el-icon>
        <div class="el-upload__text">拖拽或点击选择 <em>日常报销导出文件</em></div>
        <template #tip>
          <div class="el-upload__tip">
            支持 .xlsx / .xls 格式，自动识别「审批编号 / 报销金额（元）/ 费用发生日期 / 费用类型 / 费用说明 / 归属人 /
            审批状态」等列。
          </div>
        </template>
      </el-upload>
    </div>

    <!-- 预览阶段 -->
    <div v-else class="preview-stage">
      <div class="summary-cards">
        <div class="summary-card total">
          <span class="card-value">{{ preview.totalGroups }}</span>
          <span class="card-label">报销单</span>
        </div>
        <div class="summary-card create">
          <span class="card-value">{{ preview.createCount }}</span>
          <span class="card-label">新增</span>
        </div>
        <div class="summary-card update">
          <span class="card-value">{{ preview.updateCount }}</span>
          <span class="card-label">更新</span>
        </div>
        <div class="summary-card unchanged">
          <span class="card-value">{{ preview.unchangedCount }}</span>
          <span class="card-label">未变化</span>
        </div>
        <div class="summary-card error" :class="{ active: preview.errorCount > 0 }">
          <span class="card-value">{{ preview.errorCount }}</span>
          <span class="card-label">错误</span>
        </div>
        <div class="summary-card missed" :class="{ active: preview.mappingMissedCount > 0 }">
          <span class="card-value">{{ preview.mappingMissedCount }}</span>
          <span class="card-label">未映射员工</span>
        </div>
      </div>

      <el-table
        :data="visibleGroups"
        size="small"
        border
        height="420"
        class="preview-table"
        :row-class-name="rowClassName"
      >
        <el-table-column prop="approvalNo" label="审批编号" width="160" show-overflow-tooltip />
        <el-table-column prop="title" label="标题" min-width="200" show-overflow-tooltip />
        <el-table-column label="归属人" width="110">
          <template #default="{ row }">
            <span>{{ row.submitterName || '—' }}</span>
            <el-tooltip
              v-if="row.mappingMissed"
              content="未能识别系统员工，导入后归属人为空；请在员工名册完善姓名、手机号和部门后重新导入"
              placement="top"
            >
              <el-icon class="missed-icon" color="#d97706"><warning-filled /></el-icon>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="department" label="部门" width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.department || '—' }}</template>
        </el-table-column>
        <el-table-column label="明细" width="70" align="right">
          <template #default="{ row }">{{ row.itemCount }}</template>
        </el-table-column>
        <el-table-column label="总金额" width="120" align="right">
          <template #default="{ row }">
            <span class="amount-cell">{{ formatMoney(row.totalAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="提单日期" width="105">
          <template #default="{ row }">{{ row.claimDate || '—' }}</template>
        </el-table-column>
        <el-table-column label="审批状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.approvalStatus)" size="small" effect="light">
              {{ statusLabel(row.approvalStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="动作" width="80" align="center">
          <template #default="{ row }">
            <el-tag :type="actionType(row.action)" size="small" effect="plain">{{ actionLabel(row.action) }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <el-alert
        v-if="preview.errorCount > 0"
        class="error-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="`${preview.errorCount} 条记录解析失败（缺少审批编号等），将跳过不导入。`"
      />
      <el-alert
        v-if="preview.mappingMissedCount > 0"
        class="error-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="`${preview.mappingMissedCount} 条记录的归属人未能识别，导入后归属人为空；请在员工名册完善姓名、手机号和部门后重新导入。`"
      />
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled, WarningFilled } from '@element-plus/icons-vue'
import {
  APPROVAL_STATUS_OPTIONS,
  type ExpenseImportPreview,
  type ExpenseImportResult,
  previewExpenseImport,
  confirmExpenseImport
} from '@/api/expense/expenses'
import { askConfirm } from '@/composables'
import LqDialog from '@/components/common/LqDialog.vue'

const props = withDefaults(
  defineProps<{
    visible: boolean
  }>(),
  {}
)

const emit = defineEmits<{
  'update:visible': [v: boolean]
  confirmed: [result: ExpenseImportResult]
}>()

const visible = ref(props.visible)
watch(
  () => props.visible,
  v => (visible.value = v)
)
watch(visible, v => emit('update:visible', v))

const previewing = ref(false)
const confirming = ref(false)
const currentFile = ref<File | null>(null)
const preview = ref<ExpenseImportPreview | null>(null)

const visibleGroups = computed(() => preview.value?.groups ?? [])

const confirmDisabled = computed(() => {
  if (!preview.value) return false
  return preview.value.errorCount > 0 && preview.value.createCount + preview.value.updateCount === 0
})

function buildSubtitle() {
  if (!preview.value) return ''
  const p = preview.value
  return `共 ${p.totalRows} 行明细，合并为 ${p.totalGroups} 条报销单：新增 ${p.createCount} / 更新 ${p.updateCount} / 未变化 ${p.unchangedCount} / 错误 ${p.errorCount}。`
}

function formatMoney(v: number | null | undefined) {
  if (v == null) return '—'
  return '¥' + Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function statusLabel(status: string) {
  return APPROVAL_STATUS_OPTIONS.find(o => o.value === status)?.label ?? status
}

function statusType(status: string): any {
  return APPROVAL_STATUS_OPTIONS.find(o => o.value === status)?.type ?? 'info'
}

function actionLabel(action: string) {
  const map: Record<string, string> = { CREATE: '新增', UPDATE: '更新', UNCHANGED: '未变化', ERROR: '错误' }
  return map[action] ?? action
}

function actionType(action: string): any {
  const map: Record<string, any> = { CREATE: 'success', UPDATE: 'warning', UNCHANGED: 'info', ERROR: 'danger' }
  return map[action] ?? 'info'
}

function rowClassName({ row }: { row: any }) {
  return row.action === 'ERROR' ? 'row-error' : ''
}

function onFileChange(uploadFile: any) {
  if (!uploadFile.raw) return
  currentFile.value = uploadFile.raw
  doPreview()
}

function onFileRemove() {
  currentFile.value = null
  preview.value = null
}

async function doPreview() {
  if (!currentFile.value) return
  previewing.value = true
  try {
    preview.value = await previewExpenseImport(currentFile.value)
    const p = preview.value
    if (p.errorCount > 0) {
      ElMessage.warning(`解析完成：${p.totalGroups} 条报销单，其中 ${p.errorCount} 条有错误将被跳过`)
    } else {
      ElMessage.success(`解析完成：${p.totalGroups} 条报销单，${p.totalRows} 行明细`)
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '解析失败')
    preview.value = null
  } finally {
    previewing.value = false
  }
}

async function handleConfirm() {
  if (!preview.value) {
    await doPreview()
    return
  }
  const p = preview.value
  const valid = p.groups.filter(g => g.action !== 'ERROR')
  if (valid.length === 0) {
    ElMessage.warning('没有可导入的记录')
    return
  }
  const toImport = valid.filter(g => g.action !== 'UNCHANGED')
  if (toImport.length === 0) {
    ElMessage.info('文件内容与库中记录完全一致，无需导入')
    emit('confirmed', {
      totalGroups: valid.length,
      createdClaims: 0,
      updatedClaims: 0,
      unchangedClaims: valid.length,
      errorCount: p.errorCount,
      mappingMissedCount: p.mappingMissedCount,
      itemCount: 0,
      errors: []
    })
    visible.value = false
    return
  }
  const ok = await askConfirm({
    title: '确认导入报销记录？',
    message:
      `将导入 ${toImport.length} 条报销单（新增 ${p.createCount} / 更新 ${p.updateCount}），` +
      (p.errorCount > 0 ? `另有 ${p.errorCount} 条错误记录将被跳过。` : '文件内未变化记录将自动跳过。'),
    confirmText: '确认导入',
    cancelText: '再检查一下'
  })
  if (ok !== true) return
  try {
    confirming.value = true
    const result = await confirmExpenseImport(
      currentFile.value!,
      valid.map(g => g.approvalNo)
    )
    const parts = [`新增 ${result.createdClaims}`, `更新 ${result.updatedClaims}`, `未变化 ${result.unchangedClaims}`]
    if (result.errorCount > 0) parts.push(`跳过 ${result.errorCount}`)
    if (result.mappingMissedCount > 0) parts.push(`未映射 ${result.mappingMissedCount}`)
    ElMessage.success(`导入完成：${parts.join(' / ')}`)
    if (result.errors?.length) {
      result.errors.slice(0, 5).forEach(e => ElMessage.warning(e))
    }
    emit('confirmed', result)
    resetState()
  } catch (e: any) {
    ElMessage.error(e?.message || '导入失败')
  } finally {
    confirming.value = false
  }
}

async function handleBeforeClose(): Promise<boolean> {
  if (previewing.value || confirming.value) return false
  if (preview.value) {
    const ok = await askConfirm({
      title: '关闭导入预览？',
      message: '当前已解析的预览结果将丢失，确定关闭？',
      confirmText: '关闭',
      cancelText: '继续',
      danger: true
    })
    if (ok !== true) return false
  }
  resetState()
  return true
}

function resetState() {
  preview.value = null
  currentFile.value = null
}
</script>

<style scoped>
.upload-stage {
  padding: var(--space-section-gap, 24px) 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-m, 12px);
}

.expense-uploader :deep(.el-upload-dragger) {
  padding: 48px 24px;
  border-style: dashed;
}

.uploader-icon {
  font-size: 48px;
  color: var(--color-primary, #2563eb);
}

.preview-stage {
  display: flex;
  flex-direction: column;
  gap: var(--space-m, 12px);
}

.summary-cards {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: var(--space-control-gap, 8px);
}

.summary-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: var(--space-s, 8px) 4px;
  border-radius: var(--radius-card, 10px);
  background: var(--color-bg-elevated, #f9fafb);
  border: 1px solid var(--color-border-subtle, #e5e7eb);
}

.summary-card .card-value {
  font-size: 20px;
  font-weight: 700;
  line-height: 1.2;
  color: var(--color-text-primary, #111827);
}

.summary-card.total .card-value {
  color: var(--color-primary, #2563eb);
}
.summary-card.create .card-value {
  color: var(--color-success-700, #047857);
}
.summary-card.update .card-value {
  color: var(--color-warning-700, #b45309);
}
.summary-card.unchanged .card-value {
  color: var(--color-text-secondary, #6b7280);
}
.summary-card.error .card-value {
  color: var(--color-danger-700, #b91c1c);
}
.summary-card.missed .card-value {
  color: var(--color-warning-600, #d97706);
}
.summary-card.error.active,
.summary-card.missed.active {
  border-color: var(--color-danger-300, #fca5a5);
  background: var(--color-danger-50, #fef2f2);
}

.summary-card .card-label {
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.amount-cell {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.missed-icon {
  margin-left: 4px;
  vertical-align: -2px;
}

.error-alert {
  margin-top: var(--space-xs, 4px);
}

.preview-table :deep(.row-error) {
  --el-table-tr-bg-color: var(--color-danger-50, #fef2f2);
}

.preview-table :deep(.el-table__row) {
  cursor: default;
}
</style>
