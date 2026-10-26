<template>
  <div class="ccert-page">
    <!-- 概要：分类Tab + 到期提醒 -->
    <div class="ccert-summary">
      <CategorySummaryTabs
        :model-value="filters.category"
        :tabs="categoryTabs"
        aria-label="资质分类"
        @update:model-value="switchCategoryTab"
      />

      <DueSoonAlert :count="dueSoonItems.length">
        <span v-for="item in dueSoonItems" :key="item.id" class="due-tag due-tag--cert_expiry">
          <span class="due-source">资质</span>
          {{ item.name }}
          <span v-if="item.certNumber" class="due-patent-no">{{ item.certNumber }}</span>
          <em :class="{ 'due-overdue': (item.daysRemaining ?? 0) < 0 }">{{
            (item.daysRemaining ?? 0) < 0 ? `已逾期${-(item.daysRemaining ?? 0)}天` : `${item.daysRemaining}天后`
          }}</em>
        </span>
      </DueSoonAlert>
    </div>

    <!-- 工具栏 -->
    <SearchToolbar
      v-model:keyword="filters.keyword"
      search-placeholder="搜索名称、编号、签发机构..."
      search-mode="debounce"
      :fields="toolbarFields"
      :field-values="toolbarFieldValues"
      batch-enabled
      :batch-entry-visible="false"
      :batch-mode="batchMode"
      :selected-count="selectedIds.size"
      :models-count="pagedList.length"
      :is-all-selected="isAllPageSelected"
      @search="applyFilters"
      @filter-change="handleToolbarFilterChange"
      @clear="handleResetFilters"
      @toggle-batch-mode="toggleBatchMode"
      @toggle-select-all="toggleSelectAll"
      @batch-edit="openBatchEditModal"
      @batch-remove="handleBatchDelete"
    >
      <template #actions>
        <el-button type="primary" @click="openDialog()">+ 新增资质</el-button>
      </template>
    </SearchToolbar>

    <ActiveFilterChips :filters="activeFilterChips" @remove="removeFilterChip" @clear="handleResetFilters" />

    <DataState
      class="list-data-panel"
      :state="dataState"
      :refreshing="loading && filteredList.length > 0"
      :skeleton="dataState === 'loading'"
      skeleton-variant="card"
      :skeleton-rows="6"
      error-title="资质数据加载失败"
      :error-message="listError"
      stale-message="刷新失败，当前仍显示上一次成功加载的资质数据。"
      empty-text="暂无资质/合规文件"
      empty-hint="点击右上角「+ 新增资质」开始添加第一份档案。"
      filtered-empty-text="没有符合条件的资质文件"
      filtered-empty-hint="请调整筛选条件或清除筛选。"
      @retry="loadList"
    >
      <template #emptyActions="{ state }">
        <el-button v-if="state === 'filtered-empty'" @click="handleResetFilters">清除筛选</el-button>
        <el-button v-else type="primary" @click="openDialog()">+ 新增资质</el-button>
      </template>

      <LqTable
        ref="tableRef"
        :loading="false"
        :data="pagedList"
        stripe
        :default-sort="{ prop: sortState.field, order: sortState.order }"
        @sort-change="handleSortChange"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="50" />
        <el-table-column prop="name" label="名称" min-width="220" sortable="custom">
          <template #default="{ row }">
            <div class="name-cell" title="点击查看/编辑" @click="editItem(row)">
              <span class="name-cell__cn">{{ row.name }}</span>
              <span v-if="row.nameEn" class="name-cell__en">{{ row.nameEn }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <StatusTag :type="statusTagType(row.status)" :label="row.status || '有效'" />
          </template>
        </el-table-column>
        <el-table-column label="分类" prop="category" width="112" sortable="custom">
          <template #default="{ row }">
            <span class="ccert-cat-tag">{{ row.category }}</span>
          </template>
        </el-table-column>
        <el-table-column label="编号" width="220">
          <template #default="{ row }">
            <span v-if="row.certNumber" class="mono-cell" :title="row.certNumber">{{ row.certNumber }}</span>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="issueDate" label="签发日期" width="128" sortable="custom">
          <template #default="{ row }">
            <span class="mono-cell">{{ fmtDate(row.issueDate) || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="expiryDate" label="到期日期" width="128" sortable="custom">
          <template #default="{ row }">
            <span v-if="row.isPermanent" class="cert-permanent-tag">长期有效</span>
            <span v-else class="mono-cell">{{ fmtDate(row.expiryDate) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="daysRemaining" label="剩余天数" width="108" sortable="custom">
          <template #default="{ row }">
            <span v-if="row.isPermanent" class="text-muted">—</span>
            <span
              v-else
              :class="{
                'days-warn': row.daysRemaining != null && row.daysRemaining < 180 && row.daysRemaining >= 0,
                'days-danger': row.daysRemaining != null && row.daysRemaining < 0
              }"
            >
              {{
                row.daysRemaining != null && row.daysRemaining < 0
                  ? `已过期${-row.daysRemaining}天`
                  : row.daysRemaining != null
                    ? row.daysRemaining + '天'
                    : '—'
              }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="签发机构" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.issuingAuthority || '-' }}
          </template>
        </el-table-column>
        <el-table-column label="附件" width="120">
          <template #default="{ row }">
            <button
              v-if="fileCount(row)"
              class="btn-icon btn-file"
              :title="fileCount(row) > 1 ? `${fileCount(row)} 个附件` : '附件文件'"
              @click.stop="openFilePreview(row)"
            >
              <span class="file-btn-label">{{ fileCount(row) > 1 ? `附件(${fileCount(row)})` : '附件' }}</span>
            </button>
            <span v-else class="text-muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="160">
          <template #default="{ row }">
            <el-button link @click="editItem(row)">编辑</el-button>
            <el-button class="lq-row-danger-action" size="small" @click="removeItem(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>暂无资质数据</template>
      </LqTable>
    </DataState>

    <LqPagination :pagination="pagination" @change="clearSelection" />

    <CertificationFormDialog
      v-model="showDialog"
      :is-edit="!!editing"
      :form-data="formData"
      :certification-id="editing?.id ?? null"
      :saving="saving"
      @save="handleSave"
      @close="showDialog = false"
      @preview-asset="asset => emit('preview-asset', asset)"
    />

    <FieldBatchEditModal
      :visible="batchEditModal.visible"
      :selected-count="selectedIds.size"
      :field-groups="batchEditFieldGroups"
      :loading="batchEditModal.loading"
      entity-name="公司资质"
      @close="batchEditModal.visible = false"
      @apply="applyBatchEdit"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import CertificationFormDialog from './CertificationFormDialog.vue'
import StatusTag from '../common/StatusTag.vue'
import SearchToolbar from '../common/SearchToolbar.vue'
import type { ToolbarField } from '../common/SearchToolbar.vue'
import LqTable from '../common/LqTable.vue'
import LqPagination from '../common/LqPagination.vue'
import FieldBatchEditModal, { type BatchEditFieldGroup } from '../common/FieldBatchEditModal.vue'
import DataState, { type DataStateStatus } from '../common/DataState.vue'
import ActiveFilterChips, { type ActiveFilter } from '../common/ActiveFilterChips.vue'
import CategorySummaryTabs from '../common/CategorySummaryTabs.vue'
import DueSoonAlert from '../common/DueSoonAlert.vue'
import {
  getCertifications,
  createCertification,
  updateCertification,
  deleteCertification,
  batchDeleteCertifications,
  batchUpdateCertifications
} from '../../api/company'
import { notify } from '../../composables/ui/useToast'
import { askConfirm } from '../../composables'
import { useBatchSelection } from '../../composables/list/useBatchSelection'
import { useListPagination } from '../../composables/list/useListPagination'
import {
  COMPANY_CERTIFICATION_CATEGORIES,
  COMPANY_CERTIFICATION_STATUSES,
  type CompanyCertification,
  type CompanyCertificationPayload
} from '../../types/company'
import { FILE_OWNER_TYPE_KEYS } from '../../types/fileAsset'
import { fmtDate } from '../../utils/common/dateUtils'
import { certificationFileAssetId, parseCertificationFileNames } from '../../utils/company/certificationFileAsset'

const emit = defineEmits<{ 'preview-asset': [asset: unknown] }>()

// ===== 批量选择 =====

const batchSelection = useBatchSelection<number>()
const { batchMode, selectedIds, getSelectedIds } = batchSelection
const batchLoading = ref(false)

function handleSelectionChange(selection: CompanyCertification[]) {
  selectedIds.clear()
  selection.forEach(row => row.id != null && selectedIds.add(row.id))
  batchMode.value = selection.length > 0
}

function clearSelection() {
  batchSelection.clearSelection()
  tableRef.value?.clearSelection()
}

function toggleBatchMode() {
  batchSelection.toggleBatchMode()
  if (!batchMode.value) tableRef.value?.clearSelection()
}

const isAllPageSelected = computed(() => batchSelection.isAllSelected.value(pagedList.value))

function toggleSelectAll() {
  tableRef.value?.toggleAllSelection?.()
}

async function handleBatchDelete() {
  const count = selectedIds.size
  if (count === 0) return
  const ok = await askConfirm({
    title: '批量删除资质',
    message: `确认删除已选的 ${count} 项资质/合规文件？此操作不可恢复。`,
    confirmText: '删除',
    cancelText: '取消',
    danger: true
  })
  if (!ok) return
  batchLoading.value = true
  try {
    const ids = getSelectedIds().map(Number)
    await batchDeleteCertifications(ids)
    notify(`已删除 ${ids.length} 项资质`)
    clearSelection()
    await loadList()
  } catch (err) {
    notify.errorWithDetail('批量删除失败', err)
  } finally {
    batchLoading.value = false
  }
}

const batchEditModal = reactive({ visible: false, loading: false })
const batchEditFieldGroups = computed<BatchEditFieldGroup[]>(() => [
  {
    title: '基本信息',
    fields: [
      { key: 'name', label: '名称', type: 'text' },
      { key: 'nameEn', label: '英文名称', type: 'text' },
      {
        key: 'category',
        label: '分类',
        type: 'select',
        options: COMPANY_CERTIFICATION_CATEGORIES.map(value => ({ value, label: value }))
      },
      {
        key: 'status',
        label: '状态',
        type: 'select',
        options: COMPANY_CERTIFICATION_STATUSES.map(value => ({ value, label: value }))
      },
      { key: 'certNumber', label: '证书编号', type: 'text' },
      { key: 'issuingAuthority', label: '签发机构', type: 'text' }
    ]
  },
  {
    title: '有效期与说明',
    fields: [
      { key: 'issueDate', label: '签发日期', type: 'date' },
      { key: 'expiryDate', label: '到期日期', type: 'date' },
      { key: 'isPermanent', label: '长期有效', type: 'switch' },
      { key: 'description', label: '说明', type: 'textarea', full: true }
    ]
  }
])

function openBatchEditModal() {
  if (selectedIds.size === 0) return
  batchEditModal.visible = true
}

async function applyBatchEdit(fields: Record<string, unknown>) {
  batchEditModal.loading = true
  try {
    const ids = getSelectedIds().map(Number)
    await batchUpdateCertifications(ids, fields)
    notify(`已更新 ${ids.length} 项公司资质`)
    batchEditModal.visible = false
    clearSelection()
    batchMode.value = false
    await loadList()
  } catch (err) {
    notify.errorWithDetail('批量修改失败', err)
  } finally {
    batchEditModal.loading = false
  }
}

// ===== 数据与筛选 =====

const list = ref<CompanyCertification[]>([])
const loading = ref(false)
const listError = ref('')
const hasLoadedList = ref(false)
const pagination = useListPagination()

const filters = reactive<{
  keyword: string
  category: string
  status: string
  issuingAuthority: string
  dueRange: '' | 'soon' | 'overdue'
}>({
  keyword: '',
  category: '',
  status: '',
  issuingAuthority: '',
  dueRange: ''
})

const activeFilterChips = computed<ActiveFilter[]>(() => {
  const chips: ActiveFilter[] = []
  if (filters.status) chips.push({ key: 'status', label: '文件状态', valueLabel: filters.status })
  if (filters.category) chips.push({ key: 'category', label: '文件分类', valueLabel: filters.category })
  if (filters.issuingAuthority)
    chips.push({ key: 'issuingAuthority', label: '签发机构', valueLabel: filters.issuingAuthority })
  if (filters.dueRange === 'soon') chips.push({ key: 'dueRange', label: '有效期', valueLabel: '半年内到期' })
  if (filters.dueRange === 'overdue') chips.push({ key: 'dueRange', label: '有效期', valueLabel: '已过期' })
  return chips
})
// ===== 搜索栏筛选字段配置 =====

const toolbarFields = computed<ToolbarField[]>(() => [
  { key: 'status', label: '状态', options: [...COMPANY_CERTIFICATION_STATUSES], width: 120 },
  { key: 'category', label: '分类', options: [...COMPANY_CERTIFICATION_CATEGORIES], width: 130 },
  { key: 'issuingAuthority', label: '签发机构', options: authorityOptions.value, filterable: true, width: 160 },
  {
    key: 'dueRange',
    label: '有效期',
    options: [
      { value: 'soon', label: '半年内到期' },
      { value: 'overdue', label: '已过期' }
    ],
    width: 140
  }
])

const toolbarFieldValues = computed(() => ({
  status: filters.status,
  category: filters.category,
  issuingAuthority: filters.issuingAuthority,
  dueRange: filters.dueRange || undefined
}))

function handleToolbarFilterChange(key: string, value: unknown) {
  const v = (value as string) || ''
  if (key === 'dueRange') filters.dueRange = (v as 'soon' | 'overdue') || ''
  else if (key === 'status' || key === 'category' || key === 'issuingAuthority') filters[key] = v
  pagination.resetPage()
}
function applyFilters() {
  pagination.resetPage()
}
function removeFilterChip(key: string) {
  if (key === 'status') filters.status = ''
  if (key === 'category') filters.category = ''
  if (key === 'issuingAuthority') filters.issuingAuthority = ''
  if (key === 'dueRange') filters.dueRange = ''
  pagination.resetPage()
}
function handleResetFilters() {
  filters.keyword = ''
  filters.category = ''
  filters.status = ''
  filters.issuingAuthority = ''
  filters.dueRange = ''
  pagination.resetPage()
}
// ===== 分类Tab + 统计 =====

const stats = computed(() => {
  const m = new Map<string, number>()
  let valid = 0,
    expiring = 0,
    expired = 0
  for (const item of list.value) {
    m.set(item.category, (m.get(item.category) || 0) + 1)
    if (item.status === '有效' || !item.status) valid++
    else if (item.status === '即将到期') expiring++
    else if (item.status === '已过期') expired++
  }
  return { total: list.value.length, byCategory: m, valid, expiring, expired }
})

const categoryTabs = computed(() => {
  const tabs: { label: string; value: string; count: number }[] = [
    { label: '全部', value: '', count: stats.value.total }
  ]
  for (const cat of COMPANY_CERTIFICATION_CATEGORIES) {
    const c = stats.value.byCategory.get(cat) || 0
    // 仅展示有数据或用户已选择的分类（避免一长串空分类Tab）
    if (c > 0 || filters.category === cat) tabs.push({ label: cat, value: cat, count: c })
  }
  return tabs
})

function switchCategoryTab(cat: string) {
  filters.category = cat
  pagination.resetPage()
}

// ===== 到期提醒 =====

const dueSoonItems = computed(() =>
  list.value
    .filter(c => !c.isPermanent && c.daysRemaining != null && c.daysRemaining < 180)
    .sort((a, b) => (a.daysRemaining ?? 0) - (b.daysRemaining ?? 0))
)

// ===== 排序 =====
const sortState = reactive<{
  field: string
  direction: 'asc' | 'desc'
  order: 'ascending' | 'descending'
}>({ field: 'daysRemaining', direction: 'asc', order: 'ascending' })
const tableRef = ref<{ clearSelection: () => void; toggleAllSelection?: () => void } | null>(null)

function handleSortChange({
  prop,
  order
}: {
  column: unknown
  prop: string | null
  order: 'ascending' | 'descending' | null
}) {
  sortState.field = prop || 'daysRemaining'
  sortState.order = order || 'ascending'
  sortState.direction = sortState.order === 'ascending' ? 'asc' : 'desc'
}
// ===== 筛选过滤 & 排序 =====

const filteredList = computed(() => {
  return list.value.filter(c => {
    if (filters.category && c.category !== filters.category) return false
    if (filters.status && c.status !== filters.status && !(filters.status === '有效' && !c.status)) return false
    if (filters.issuingAuthority && c.issuingAuthority !== filters.issuingAuthority) return false
    if (filters.dueRange === 'soon' && (c.daysRemaining == null || c.daysRemaining < 0 || c.daysRemaining >= 180))
      return false
    if (filters.dueRange === 'overdue' && (c.daysRemaining == null || c.daysRemaining >= 0)) return false
    if (filters.keyword) {
      const kw = filters.keyword.trim().toLowerCase()
      if (!kw) return true
      const hay = [c.name, c.nameEn, c.certNumber, c.issuingAuthority, c.description]
        .filter(Boolean)
        .join(' ')
        .toLowerCase()
      if (!hay.includes(kw)) return false
    }
    return true
  })
})

const authorityOptions = computed(() =>
  Array.from(new Set(list.value.map(c => c.issuingAuthority).filter(Boolean) as string[])).sort()
)

const sortedList = computed(() => {
  const arr = [...filteredList.value]
  const { field, direction } = sortState
  const dir = direction === 'asc' ? 1 : -1
  arr.sort((a: CompanyCertification, b: CompanyCertification) => {
    const va = (a as unknown as Record<string, unknown>)[field]
    const vb = (b as unknown as Record<string, unknown>)[field]
    // daysRemaining: null > 其他排后面
    if (field === 'daysRemaining') {
      const na = va == null ? Infinity : Number(va)
      const nb = vb == null ? Infinity : Number(vb)
      return (na - nb) * dir
    }
    if (va == null && vb == null) return 0
    if (va == null) return 1
    if (vb == null) return -1
    if (typeof va === 'number' && typeof vb === 'number') return (va - vb) * dir
    return String(va).localeCompare(String(vb), 'zh-Hans-CN') * dir
  })
  return arr
})

const pagedList = computed(() => {
  const start = pagination.pageInfo.page * pagination.pageInfo.size
  return sortedList.value.slice(start, start + pagination.pageInfo.size)
})

watch(
  [() => sortedList.value.length, () => pagination.pageInfo.size],
  ([total]) => {
    pagination.pageInfo.total = total
    pagination.pageInfo.pages = Math.ceil(total / pagination.pageInfo.size)
    if (pagination.pageInfo.page > Math.max(0, pagination.pageInfo.pages - 1)) pagination.resetPage()
  },
  { immediate: true }
)

const hasActiveFilters = computed(
  () => !!(filters.keyword.trim() || filters.category || filters.status || filters.issuingAuthority || filters.dueRange)
)
const dataState = computed<DataStateStatus>(() => {
  if (loading.value && !hasLoadedList.value) return 'loading'
  if (listError.value && list.value.length > 0) return 'stale'
  if (listError.value) return 'error'
  if (filteredList.value.length === 0) return hasActiveFilters.value ? 'filtered-empty' : 'empty'
  return 'ready'
})

// ===== 对话框 =====

const showDialog = ref(false)
const saving = ref(false)
const editing = ref<CompanyCertification | null>(null)
const formData = ref<Partial<CompanyCertificationPayload>>({})

function openDialog() {
  editing.value = null
  formData.value = { category: '检测报告', status: '有效' }
  showDialog.value = true
}

function editItem(c: CompanyCertification) {
  editing.value = c
  formData.value = {
    name: c.name,
    nameEn: c.nameEn ?? null,
    category: c.category,
    certNumber: c.certNumber ?? '',
    issueDate: c.issueDate ?? '',
    expiryDate: c.expiryDate ?? '',
    issuingAuthority: c.issuingAuthority ?? '',
    fileName: c.fileName ?? '',
    description: c.description ?? '',
    status: c.status
  }
  showDialog.value = true
}

async function handleSave(data: CompanyCertificationPayload) {
  if (!data.name?.trim()) {
    notify('请填写名称', 'warning')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await updateCertification(editing.value.id, data)
      notify('更新成功')
    } else {
      await createCertification(data)
      notify('创建成功')
    }
    showDialog.value = false
    await loadList()
  } catch {
    notify('操作失败', 'error')
  } finally {
    saving.value = false
  }
}

async function removeItem(c: CompanyCertification) {
  const ok = await askConfirm({
    title: '确认删除',
    message: `确认删除「${c.name}」？此操作不可恢复。`,
    confirmText: '删除',
    cancelText: '取消',
    danger: true
  })
  if (!ok) return
  try {
    await deleteCertification(c.id)
    notify('已删除')
    await loadList()
  } catch {
    notify('删除失败', 'error')
  }
}

function statusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' | 'default' {
  switch (status) {
    case '有效':
      return 'success'
    case '即将到期':
      return 'warning'
    case '已过期':
      return 'danger'
    case '已注销':
      return 'info'
    default:
      return 'default'
  }
}

// ===== 文件预览 =====

function openFilePreview(row: CompanyCertification) {
  const names = parseCertificationFileNames(row.fileName)
  if (!names.length) return
  emit('preview-asset', {
    fileName: names[0],
    title: row.name + '：附件',
    ownerType: FILE_OWNER_TYPE_KEYS.company,
    assetId: certificationFileAssetId(row.id, 'fileName', 0),
    id: row.id,
    category: 'company',
    ownerId: row.id
  })
}

function fileCount(row: CompanyCertification): number {
  return parseCertificationFileNames(row.fileName).length
}

// ===== 数据加载 =====

async function loadList() {
  loading.value = true
  listError.value = ''
  try {
    list.value = await getCertifications(undefined, undefined)
    hasLoadedList.value = true
  } catch (err) {
    listError.value = err instanceof Error ? err.message : '请检查网络连接后重试。'
    notify.errorWithDetail('加载资质/合规文件失败', err)
  } finally {
    loading.value = false
  }
}

onMounted(loadList)
</script>

<style scoped>
@import '../../styles/company-certifications-tab.css';
</style>
