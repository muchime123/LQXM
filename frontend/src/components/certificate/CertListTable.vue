<template>
  <LqTable
    :loading="loading"
    :data="list"
    stripe
    :default-sort="defaultSort"
    :row-class-name="elRowClassName"
    @row-click="emit('row-click', $event)"
    @sort-change="emit('sort-change', $event)"
  >
    <el-table-column width="46" align="center">
      <template #header>
        <el-checkbox :model-value="isAllSelected" aria-label="全选当前页" @change="emit('toggle-select-all')" />
      </template>
      <template #default="{ row }">
        <el-checkbox
          :model-value="selectedIds.has(row.id)"
          :aria-label="`选择${row.name}`"
          @click.stop
          @change="emit('toggle-select', row.id)"
        />
      </template>
    </el-table-column>
    <el-table-column
prop="name"
label="名称"
min-width="140"
sortable="custom">
      <template #default="{ row }">
        <span class="name-cell" title="点击查看详情" @click="$emit('detail', row)">{{ row.name }}</span>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="80">
      <template #default="{ row }">
        <StatusTag :type="certStatusTagClass(row.assetStatus)" :label="row.assetStatus" />
      </template>
    </el-table-column>
    <el-table-column
label="类型"
prop="patentType"
width="100"
sortable="custom">
      <template #default="{ row }">
        {{
          row.assetCategory === '商标'
            ? row.niceClass
              ? `第${row.niceClass}类`
              : '商标'
            : row.patentType || row.assetCategory
        }}
      </template>
    </el-table-column>
    <el-table-column label="编号" width="210">
      <template #default="{ row }">
        <div class="cell-with-copy">
          <span class="mono-cell">{{ row.patentNo || '-' }}</span>
          <button
            v-if="row.patentNo"
            class="btn-icon copy-btn"
            title="复制纯数字编号"
            @click.stop="copyPatentNo(row.patentNo)"
          >
            复制
          </button>
        </div>
      </template>
    </el-table-column>
    <el-table-column
prop="applyDate"
label="申请日"
width="140"
sortable="custom">
      <template #default="{ row }">
        <span class="mono-cell">{{ fmtDate(row.applyDate) }}</span>
      </template>
    </el-table-column>
    <el-table-column
prop="expireDate"
label="到期日"
width="140"
sortable="custom">
      <template #default="{ row }">
        <span class="mono-cell">{{ fmtDate(row.expireDate) }}</span>
      </template>
    </el-table-column>
    <el-table-column
prop="daysRemaining"
label="剩余"
width="120"
sortable="custom">
      <template #default="{ row }">
        <span
          :class="{
            'days-warn': row.daysRemaining != null && row.daysRemaining < 180,
            'days-danger': row.daysRemaining != null && row.daysRemaining < 0
          }"
        >
          {{ row.daysRemaining != null ? row.daysRemaining + '天' : '-' }}
        </span>
      </template>
    </el-table-column>
    <el-table-column label="缴费状态" width="180">
      <template #default="{ row }">
        <StatusTag :type="certFeeTagClass(row.feeStatus)" :label="row.feeStatus" />
      </template>
    </el-table-column>
    <el-table-column
prop="feeDueDate"
label="年费截止"
width="140"
sortable="custom">
      <template #default="{ row }">
        <span class="mono-cell">{{ fmtDate(row.feeDueDate) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="附件" width="180">
      <template #default="{ row }">
        <template v-if="row.specFile || row.certFile">
          <button
            v-if="row.specFile"
            class="btn-icon btn-file"
            :title="(row.assetCategory === '商标' ? '商标图样：' : '说明书文件：') + row.specFile"
            @click.stop="$emit('preview', row, 'specFile')"
          >
            <span class="file-btn-label">{{ row.assetCategory === '商标' ? '图样' : '说明书' }}</span>
          </button>
          <button
            v-if="row.certFile"
            class="btn-icon btn-file"
            :title="(row.assetCategory === '商标' ? '注册证：' : '证书图片：') + row.certFile"
            @click.stop="$emit('preview', row, 'certFile')"
          >
            <span class="file-btn-label">{{ row.assetCategory === '商标' ? '注册证' : '证书' }}</span>
          </button>
        </template>
        <span v-else class="text-muted">-</span>
      </template>
    </el-table-column>
    <el-table-column label="官网" width="105" align="center">
      <template #default="{ row }">
        <div class="website-state">
          <StatusTag :type="row.websiteEnabled ? 'success' : 'neutral'" :label="row.websiteEnabled ? '已公开' : '未公开'" />
          <span v-if="row.websiteFeatured" class="featured-mark">重点</span>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="操作" fixed="right" width="180">
      <template #default="{ row }">
        <el-button link @click.stop="$emit('edit', row)">编辑</el-button>
        <el-button class="lq-row-danger-action" size="small" @click.stop="$emit('delete', row)">删除</el-button>
      </template>
    </el-table-column>
    <template #empty>暂无证书数据</template>
  </LqTable>
</template>

<script setup lang="ts">
import { certFeeTagClass, certStatusTagClass } from '../../composables/common/useStatus'
import type { CertificateSummary } from '../../types/certificate'
import type { CertificateFileField } from '../../utils/certificate/certificateFileAsset'
import { fmtDate } from '../../utils/common/dateUtils'
import { digitsOnly } from '../../utils/common/textUtils'
import { copyText } from '../../utils/ui/clipboard'
import LqTable from '../common/LqTable.vue'
import StatusTag from '../common/StatusTag.vue'

defineProps<{
  loading: boolean
  list: CertificateSummary[]
  indexMethod: (index: number) => number
  defaultSort: { prop: string; order: 'ascending' | 'descending' }
  selectedIds: Set<number>
  isAllSelected: boolean
}>()

const emit = defineEmits<{
  detail: [row: CertificateSummary]
  edit: [row: CertificateSummary]
  delete: [row: CertificateSummary]
  preview: [row: CertificateSummary, field: CertificateFileField]
  'toggle-select': [id: number]
  'toggle-select-all': []
  'row-click': [row: CertificateSummary]
  'sort-change': [value: { column: any; prop: string | null; order: 'ascending' | 'descending' | null }]
}>()

function elRowClassName({ row }: { row: CertificateSummary }) {
  if (row.assetStatus === '已失效') return 'row-expired'
  return ''
}

function copyPatentNo(patentNo: string) {
  copyText(digitsOnly(patentNo), '纯数字专利号')
}
</script>

<style scoped>
.name-cell {
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading);
  cursor: pointer;
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.name-cell:hover {
  color: var(--color-primary);
}

.mono-cell {
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
}

.cell-with-copy {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.cell-with-copy .copy-btn {
  opacity: 0;
  transition: opacity var(--transition-fast);
}

.cell-with-copy:hover .copy-btn {
  opacity: 1;
}

.copy-btn {
  background: none;
  border: none;
  cursor: pointer;
  font-size: var(--fs-base);
  padding: 2px;
  opacity: 0.5;
  transition: opacity var(--transition-fast);
}

.copy-btn:hover {
  opacity: 1;
  color: var(--color-primary);
}

.row-expired {
  opacity: 0.6;
}

.days-warn {
  color: var(--color-warning);
  font-weight: var(--fw-semibold);
}

.days-danger {
  color: var(--color-danger);
  font-weight: var(--fw-semibold);
}

.btn-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-sm);
  transition: background var(--transition-fast);
}

.btn-icon:hover {
  background: var(--color-primary-bg);
}

.btn-file {
  color: var(--color-primary);
  font-size: var(--fs-xs);
}

.file-btn-label {
  font-weight: var(--fw-medium);
}

.text-muted {
  color: var(--color-text-muted);
}

.btn-link {
  background: none;
  border: none;
  color: var(--color-primary);
  cursor: pointer;
  font-size: var(--fs-sm);
  padding: 0 4px;
}

.btn-link:hover {
  text-decoration: underline;
}
</style>
