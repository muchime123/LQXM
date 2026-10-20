<template>
  <ListPageLayout class="cert-view" title="知识产权" description="维护专利、商标、软著档案，管理缴费状态与证书文件。">
    <template #summary>
      <div class="cert-summary">
        <CategorySummaryTabs
          :model-value="filters.assetCategory"
          :tabs="categoryTabs"
          aria-label="知识产权分类"
          @update:model-value="switchCategoryTab"
        />

        <DueSoonAlert :count="stats.dueSoonItems?.length || 0">
          <span
            v-for="item in stats.dueSoonItems"
            :key="item.sourceType + '-' + item.id"
            class="due-tag"
            :class="'due-tag--' + item.sourceType.toLowerCase()"
          >
            <span class="due-source">{{ sourceLabel(item.sourceType) }}</span>
            {{ item.name
            }}<span v-if="item.number" class="due-patent-no">
              {{ item.number }}
              <button
                v-if="item.sourceType !== 'CERT_EXPIRY'"
                class="due-copy-btn"
                title="复制纯数字编号"
                aria-label="复制纯数字编号"
                @click="copyDuePatentNo(item)"
              >
                复制
              </button>
            </span>
            <em :class="{ 'due-overdue': item.daysRemaining < 0 }">{{
              item.daysRemaining < 0 ? `已逾期${-item.daysRemaining}天` : `${item.daysRemaining}天后`
            }}</em>
          </span>
        </DueSoonAlert>

        <div class="patent-portals">
          <span class="portal-label">官方平台：</span>
          <a
            href="https://interactive.cponline.cnipa.gov.cn/public-app-zljffw/onlinePay/fillInList"
            target="_blank"
            rel="noopener"
            class="portal-link portal-pay"
            title="专利年费在线缴费"
            >年费缴费</a
          >
          <a
            href="https://pss-system.cponline.cnipa.gov.cn/conventionalSearch"
            target="_blank"
            rel="noopener"
            class="portal-link portal-search"
            title="专利常规检索"
            >常规检索</a
          >
          <a
            href="https://cpquery.cponline.cnipa.gov.cn/chinesepatent/index"
            target="_blank"
            rel="noopener"
            class="portal-link portal-query"
            title="中国及多国专利审查信息查询"
            >审查信息查询</a
          >
        </div>
      </div>
    </template>

    <template #toolbar>
      <SearchToolbar
        v-model:keyword="filters.keyword"
        search-placeholder="搜索名称、编号、申请人..."
        search-mode="debounce"
        :fields="toolbarFields"
        :field-values="filters"
        @search="applyFilters"
        @filter-change="handleToolbarFilterChange"
        @clear="handleResetFilters"
        batch-enabled
        :batch-entry-visible="false"
        :batch-mode="batchMode"
        :selected-count="selectedIds.size"
        :models-count="list.length"
        :is-all-selected="isAllPageSelected"
        @toggle-batch-mode="toggleBatchMode"
        @toggle-select-all="toggleSelectAll"
        @batch-edit="openBatchEditModal"
        @batch-remove="handleBatchDelete"
      >
        <template #actions>
          <el-button v-if="canManageCertificate" @click="doExport">导出 Excel</el-button>
          <el-button v-if="canManageCertificate" type="primary" @click="openCreate">新增知识产权</el-button>
        </template>
      </SearchToolbar>
    </template>

    <ActiveFilterChips :filters="activeFilterChips" @remove="removeFilterChip" @clear="handleResetFilters" />

    <DataState
      class="list-data-panel"
      :state="certificateDataState"
      :refreshing="loading && list.length > 0"
      :skeleton="certificateDataState === 'loading'"
      skeleton-variant="card"
      :skeleton-rows="6"
      error-title="知识产权数据加载失败"
      :error-message="listError"
      stale-message="刷新失败，当前仍显示上一次成功加载的知识产权数据。"
      empty-text="暂无知识产权记录"
      empty-hint="点击右上角“新增知识产权”添加第一份专利、商标或软著档案。"
      filtered-empty-text="没有符合条件的知识产权记录"
      filtered-empty-hint="请调整筛选条件或清除筛选。"
      @retry="doSearch"
    >
      <template #emptyActions="{ state }">
        <el-button v-if="state === 'filtered-empty'" @click="handleResetFilters">清除筛选</el-button>
        <el-button v-else-if="canManageCertificate" type="primary" @click="openCreate">新增知识产权</el-button>
      </template>

      <CertListTable
        :loading="false"
        :list="list"
        :index-method="indexMethod"
        :default-sort="{ prop: sortState.field, order: sortState.order }"
        :selected-ids="selectedIds"
        :is-all-selected="isAllPageSelected"
        @detail="openDetail"
        @edit="openEdit"
        @delete="confirmDelete"
        @preview="openFilePreview"
        @row-click="onRowClick"
        @toggle-select="toggleSelect"
        @toggle-select-all="toggleSelectAll"
        @sort-change="handleSortChange"
      />
    </DataState>

    <template #pagination>
      <LqPagination :pagination="pagination" @change="loadList" />
    </template>

    <CertDetailModal
      :visible="certModal.visible"
      :data="certModal.data"
      :is-edit-mode="certModal.isEditMode"
      :is-create-mode="certModal.isCreateMode"
      @close="closeCertModal"
      @save="onCertModalSave"
      @edit="onCertModalEdit"
      @delete="onCertModalDelete"
      @fee-updated="loadStats"
      @preview-asset="openAssetPreview($event as any)"
    />

    <FieldBatchEditModal
      :visible="batchEditModal.visible"
      :selected-count="selectedIds.size"
      :field-groups="batchEditFieldGroups"
      :loading="batchEditModal.loading"
      entity-name="证书"
      @close="batchEditModal.visible = false"
      @apply="applyBatchEdit"
    />
  </ListPageLayout>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import {
  batchUpdateCertificates,
  deleteCertificate,
  exportCertificates,
  fetchCertificate,
  fetchCertificateFilters,
  fetchCertificateStats,
  fetchCertificates
} from '@/api/certificate'
import CertDetailModal from '@/components/certificate/CertDetailModal.vue'
import CertListTable from '@/components/certificate/CertListTable.vue'
import LqPagination from '@/components/common/LqPagination.vue'
import ListPageLayout from '@/components/common/ListPageLayout.vue'
import SearchToolbar, { type ToolbarField } from '@/components/common/SearchToolbar.vue'
import DataState, { type DataStateStatus } from '@/components/common/DataState.vue'
import FieldBatchEditModal, { type BatchEditFieldGroup } from '@/components/common/FieldBatchEditModal.vue'
import ActiveFilterChips, { type ActiveFilter } from '@/components/common/ActiveFilterChips.vue'
import CategorySummaryTabs from '@/components/common/CategorySummaryTabs.vue'
import DueSoonAlert from '@/components/common/DueSoonAlert.vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { useListFilters } from '@/composables/list/useListFilters'
import { useListPagination } from '@/composables/list/useListPagination'
import { useBatchSelection } from '@/composables/list/useBatchSelection'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { notify } from '@/composables/ui/useToast'
import { usePreviewAsset } from '@/composables/ui/useAppBridge'
import type { CertificateDetail, CertificateStats, CertificateSummary } from '@/types/certificate'
import { todayIso } from '@/utils/common/dateUtils'
import { digitsOnly } from '@/utils/common/textUtils'
import { downloadBlob } from '@/utils/ui/downloadBlob'
import { copyText } from '@/utils/ui/clipboard'
import { FILE_OWNER_TYPE_KEYS } from '@/types/fileAsset'
import {
  certificateFileAssetId,
  certificateFileFieldLabel,
  type CertificateFileField
} from '@/utils/certificate/certificateFileAsset'

const openAssetPreview = usePreviewAsset()

const { filters, resetFilters, getQueryParams } = useListFilters({
  keyword: '',
  assetStatus: '',
  assetCategory: '',
  patentType: '',
  feeStatus: ''
})

const toolbarFields = computed<ToolbarField[]>(() => [
  { key: 'assetStatus', label: '状态', options: filterOptions.value?.assetStatuses || [], width: 130 },
  { key: 'assetCategory', label: '类别', options: filterOptions.value?.assetCategories || [], width: 120 },
  { key: 'patentType', label: '类型', options: filterOptions.value?.patentTypes || [], width: 130 },
  { key: 'feeStatus', label: '缴费状态', options: filterOptions.value?.feeStatuses || [], width: 140 }
])

const pagination = useListPagination()
const { resetPage, getPageParams, updateFromResponse, indexMethod } = pagination

const { can } = useCurrentUser()
/** 是否可维护知识产权档案（新增/编辑/删除/导入导出），由 certificate.manage 权限控制 */
const canManageCertificate = computed(() => can('certificate.manage'))

const loading = ref(false)
const list = ref<CertificateSummary[]>([])
const listError = ref('')
const hasLoadedList = ref(false)
const hasActiveFilters = computed(() => Object.values(filters).some(value => String(value || '').trim()))
const certificateDataState = computed<DataStateStatus>(() => {
  if (loading.value && !hasLoadedList.value) return 'loading'
  if (listError.value && list.value.length > 0) return 'stale'
  if (listError.value) return 'error'
  if (list.value.length === 0) return hasActiveFilters.value ? 'filtered-empty' : 'empty'
  return 'ready'
})

// ========== 批量操作 ==========
const batchSelection = useBatchSelection<number>()
const { batchMode, selectedIds } = batchSelection
const isAllPageSelected = computed(() => batchSelection.isAllSelected.value(list.value))
/** 根据当前选中数量同步批量模式：有选中自动进入，全部取消自动退出 */
function syncBatchModeWithSelection() {
  if (selectedIds.size > 0) {
    if (!batchMode.value) batchMode.value = true
  } else if (batchMode.value) {
    batchMode.value = false
  }
}
function toggleBatchMode() {
  batchSelection.toggleBatchMode()
}
function toggleSelect(id: number) {
  batchSelection.toggleSelection(id)
  syncBatchModeWithSelection()
}
function toggleSelectAll() {
  batchSelection.toggleSelectAll(list.value)
  syncBatchModeWithSelection()
}
function onRowClick(row: CertificateSummary) {
  if (batchMode.value) toggleSelect(row.id)
}
// ========== 批量编辑（通用字段 Schema） ==========
const batchEditModal = reactive({ visible: false, loading: false })
const assetStatusOptions = ['有效', '已失效', '未生效', '已转让']
const assetCategoryOptions = ['专利', '软著', '商标']
const patentTypeOptions = ['发明专利', '实用新型', '外观设计', '软著']
const feeStatusOptions = ['待查询/待核实', '已支付', '未支付', '已失效待缴费', '已登记/已公告']
const batchEditFieldGroups = computed<BatchEditFieldGroup[]>(() => [
  {
    title: '状态与类型',
    fields: [
      {
        key: 'assetStatus',
        label: '资产状态',
        type: 'select',
        options: assetStatusOptions.map(v => ({ value: v, label: v }))
      },
      {
        key: 'assetCategory',
        label: '资产类别',
        type: 'select',
        options: assetCategoryOptions.map(v => ({ value: v, label: v }))
      },
      {
        key: 'patentType',
        label: '专利类型',
        type: 'select',
        options: patentTypeOptions.map(v => ({ value: v, label: v }))
      },
      {
        key: 'feeStatus',
        label: '缴费状态',
        type: 'select',
        options: feeStatusOptions.map(v => ({ value: v, label: v }))
      },
      { key: 'legalStatus', label: '法律状态', type: 'text' }
    ]
  },
  {
    title: '主体信息',
    fields: [
      { key: 'applicant', label: '申请人', type: 'text' },
      { key: 'inventors', label: '发明人', type: 'text' },
      { key: 'examiner', label: '审查员', type: 'text' },
      { key: 'agency', label: '代理机构', type: 'text' },
      { key: 'agencyContact', label: '代理联系人', type: 'text' }
    ]
  },
  {
    title: '官网发布',
    fields: [
      { key: 'websiteEnabled', label: '官网公开', type: 'switch' },
      { key: 'websiteFeatured', label: '重点展示', type: 'switch' },
      { key: 'websiteSortOrder', label: '官网排序', type: 'number' }
    ]
  },
  {
    title: '办理信息',
    fields: [
      { key: 'invoiceStatus', label: '开票状态', type: 'text' },
      { key: 'reviewProgress', label: '审查进度', type: 'text' },
      { key: 'remark', label: '备注', type: 'textarea', full: true }
    ]
  }
])

function openBatchEditModal() {
  if (selectedIds.size === 0) {
    notify('请先勾选要批量编辑的证书', 'warning')
    return
  }
  batchEditModal.visible = true
}

async function applyBatchEdit(payload: Record<string, unknown>) {
  const ids = batchSelection.getSelectedIds().map(Number)
  if (ids.length === 0) return
  batchEditModal.loading = true
  try {
    await batchUpdateCertificates(ids, payload)
    notify(`已更新 ${ids.length} 个证书`)
    batchEditModal.visible = false
    batchSelection.clearSelection()
    batchMode.value = false
    await Promise.all([loadList(), loadStats()])
  } catch (err) {
    notify.errorWithDetail('批量修改失败', err)
  } finally {
    batchEditModal.loading = false
  }
}

async function handleBatchDelete() {
  const ids = batchSelection.getSelectedIds().map(Number)
  const count = ids.length
  const confirmed = await askConfirm({
    title: '批量删除',
    message: `确定要删除选中的 ${count} 个证书吗？此操作不可恢复。`,
    confirmText: '批量删除',
    danger: true
  })
  if (!confirmed) return
  try {
    await Promise.all(ids.map(id => deleteCertificate(id)))
    batchSelection.clearSelection()
    batchMode.value = false
    notify(`已删除 ${count} 个证书`, 'success')
    await Promise.all([loadList(), loadStats()])
  } catch (err) {
    notify.errorWithDetail('批量删除失败', err)
  }
}

const stats = reactive<CertificateStats>({
  totalCount: 0,
  activeCount: 0,
  expiredCount: 0,
  pendingCount: 0,
  transferredCount: 0,
  feeDueSoonCount: 0,
  inventionCount: 0,
  utilityCount: 0,
  designCount: 0,
  trademarkCount: 0,
  softwareCopyrightCount: 0,
  dueSoonItems: []
})

const categoryTabs = computed(() => [
  { label: '全部', value: '', count: stats.totalCount },
  { label: '专利', value: '专利', count: stats.inventionCount + stats.utilityCount + stats.designCount },
  { label: '商标', value: '商标', count: stats.trademarkCount },
  { label: '软著', value: '软著', count: stats.softwareCopyrightCount }
])

function switchCategoryTab(category: string) {
  filters.assetCategory = category
  applyFilters()
}
const filterOptions = ref<{
  assetStatuses: string[]
  assetCategories: string[]
  patentTypes: string[]
  feeStatuses: string[]
} | null>(null)

const sortState = reactive<{
  field: string
  direction: 'asc' | 'desc'
  order: 'ascending' | 'descending'
}>({ field: 'applyDate', direction: 'desc', order: 'descending' })
const activeFilterChips = computed<ActiveFilter[]>(() => {
  const chips: ActiveFilter[] = []
  if (filters.assetStatus) chips.push({ key: 'assetStatus', label: '资产状态', valueLabel: filters.assetStatus })
  if (filters.assetCategory) chips.push({ key: 'assetCategory', label: '资产类别', valueLabel: filters.assetCategory })
  if (filters.patentType) chips.push({ key: 'patentType', label: '专利类型', valueLabel: filters.patentType })
  if (filters.feeStatus) chips.push({ key: 'feeStatus', label: '缴费状态', valueLabel: filters.feeStatus })
  return chips
})
function applyFilters() {
  resetPage()
  doSearch()
}
function handleToolbarFilterChange(key: string, value: unknown) {
  filters[key as keyof typeof filters] = value as never
  applyFilters()
}
function removeFilterChip(key: string) {
  if (key === 'assetStatus') filters.assetStatus = ''
  if (key === 'assetCategory') filters.assetCategory = ''
  if (key === 'patentType') filters.patentType = ''
  if (key === 'feeStatus') filters.feeStatus = ''
  applyFilters()
}

const certModal = reactive({
  visible: false,
  data: {} as CertificateDetail,
  isEditMode: false,
  isCreateMode: false
})

// === 数据加载 ===

async function doSearch() {
  loading.value = true
  listError.value = ''
  try {
    const params = {
      ...getQueryParams(),
      ...getPageParams(),
      sort: sortState.field,
      direction: sortState.direction
    }
    const result = await fetchCertificates(params)
    updateFromResponse(result)
    list.value = result.content || []
    hasLoadedList.value = true
  } catch (err) {
    listError.value = err instanceof Error ? err.message : '请检查网络连接后重试。'
    notify.errorWithDetail('加载失败', err)
  } finally {
    loading.value = false
  }
}

async function loadStats() {
  try {
    const result = await fetchCertificateStats()
    Object.assign(stats, result)
  } catch (err) {
    console.warn('加载统计数据失败', err)
  }
}

async function loadFilters() {
  try {
    filterOptions.value = await fetchCertificateFilters()
  } catch (err) {
    console.warn('加载筛选选项失败', err)
  }
}

function loadList() {
  resetPage()
  doSearch()
}

function handleSortChange({
  prop,
  order
}: {
  column: any
  prop: string | null
  order: 'ascending' | 'descending' | null
}) {
  sortState.field = prop || 'applyDate'
  sortState.order = order || 'descending'
  sortState.direction = sortState.order === 'ascending' ? 'asc' : 'desc'
  resetPage()
  doSearch()
}

async function loadCertificate(id: number) {
  try {
    certModal.data = await fetchCertificate(id)
  } catch (err) {
    notify.errorWithDetail('加载证书失败', err)
    certModal.visible = false
  }
}

// === 筛选与分页 ===

function handleResetFilters() {
  resetFilters(doSearch)
}

// === 证书管理 ===

function copyDuePatentNo(item: { number?: string; queryNo?: string }) {
  // 优先使用后端写好的纯数字查询号（与官方检索框口径一致），缺失时按号码去非数字
  const value = item.queryNo || digitsOnly(item.number)
  if (!value) return
  copyText(value, '纯数字专利号')
}

function sourceLabel(sourceType: string): string {
  switch (sourceType) {
    case 'FEE_DUE':
      return '年费'
    case 'EXPIRY':
      return '到期'
    case 'CERT_EXPIRY':
      return '资质'
    default:
      return ''
  }
}

async function confirmDelete(row: CertificateSummary) {
  const ok = await askConfirm({ title: '确认删除', message: `确认删除「${row.name}」？此操作不可恢复。`, danger: true })
  if (!ok) return
  try {
    await deleteCertificate(row.id)
    loadList()
    loadStats()
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

async function doExport() {
  try {
    const blob = await exportCertificates(getQueryParams())
    downloadBlob(blob, `知识产权_${todayIso()}.xlsx`)
  } catch (err) {
    notify.errorWithDetail('导出失败', err)
  }
}

function openCreate() {
  certModal.data = {} as CertificateDetail
  certModal.isEditMode = false
  certModal.isCreateMode = true
  certModal.visible = true
}

function openDetail(row: CertificateSummary) {
  certModal.isEditMode = false
  certModal.isCreateMode = false
  certModal.visible = true
  loadCertificate(row.id)
}

function openEdit(row: CertificateSummary) {
  certModal.isEditMode = true
  certModal.isCreateMode = false
  certModal.visible = true
  loadCertificate(row.id)
}

// === 证书模态框 ===

function closeCertModal() {
  certModal.visible = false
}

function onCertModalSave() {
  certModal.visible = false
  loadList()
  loadStats()
}

function onCertModalEdit() {
  certModal.isEditMode = true
}

async function onCertModalDelete() {
  const ok = await askConfirm({
    title: '确认删除',
    message: `确认删除证书「${certModal.data.name}」？此操作不可恢复。`,
    danger: true
  })
  if (!ok) return
  try {
    await deleteCertificate(certModal.data.id)
    certModal.visible = false
    loadList()
    loadStats()
    notify('删除成功', 'success')
  } catch (err) {
    notify.errorWithDetail('删除失败', err)
  }
}

// === 文件预览 ===

function openFilePreview(row: CertificateSummary, field: CertificateFileField) {
  const fileName = row[field]
  if (!fileName) return
  openAssetPreview({
    fileName,
    title: certificateFileFieldLabel(field),
    ownerType: FILE_OWNER_TYPE_KEYS.certificate,
    assetId: certificateFileAssetId(row.id, field),
    id: row.id,
    category: 'certificate'
  })
}

// === 生命周期 ===

Promise.allSettled([loadList(), loadStats(), loadFilters()])
</script>

<style scoped>
.cert-view {
  width: 100%;
}

.filter-field {
  display: grid;
  gap: var(--space-control-gap-tight);
  min-width: 0;
}

.filter-field__label {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
}

.filter-field .el-select {
  width: 100%;
}

.cert-summary {
  display: grid;
  gap: var(--space-control-gap-wide);
}

.due-tag {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  background: var(--color-surface);
  border-radius: var(--radius-sm);
  font-size: var(--fs-sm);
  color: var(--color-text-secondary);
}

.due-source {
  display: inline-block;
  padding: 1px 6px;
  margin-right: 6px;
  border-radius: var(--radius-xs, 3px);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  line-height: 1.4;
}

.due-tag--fee_due .due-source {
  background: var(--color-warning-bg-soft);
  color: var(--color-warning);
}

.due-tag--expiry .due-source {
  background: var(--color-danger-bg-soft);
  color: var(--color-danger);
}

.due-tag--cert_expiry .due-source {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.due-patent-no {
  margin-left: var(--space-control-gap-tight);
  font-family: var(--font-mono);
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
}

.due-copy-btn {
  background: none;
  border: none;
  cursor: pointer;
  padding: 0 2px;
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
  opacity: 0.6;
  transition: opacity var(--transition-fast);
}

.due-copy-btn:hover {
  opacity: 1;
  color: var(--color-primary);
}

.due-tag em {
  margin-left: var(--space-sm);
  font-style: normal;
  color: var(--color-warning);
  font-weight: var(--fw-semibold);
}

.due-tag em.due-overdue {
  color: var(--color-danger);
}

.patent-portals {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: 10px 16px;
  background: var(--color-surface);
  border-radius: var(--radius-md);
}
.portal-label {
  font-size: var(--fs-sm);
  color: var(--color-text-muted);
}
.portal-link {
  display: inline-flex;
  align-items: center;
  padding: 6px 12px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-sm);
  text-decoration: none;
  transition: all var(--transition-fast);
}
.portal-pay {
  background: var(--color-danger-bg-soft);
  color: var(--color-danger);
}
.portal-pay:hover {
  background: var(--color-danger);
  color: var(--color-surface);
}
.portal-search {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}
.portal-search:hover {
  background: var(--color-primary);
  color: var(--color-surface);
}
.portal-query {
  background: var(--color-success-bg-soft);
  color: var(--color-success-text);
}
.portal-query:hover {
  background: var(--color-success);
  color: var(--color-surface);
}
</style>
