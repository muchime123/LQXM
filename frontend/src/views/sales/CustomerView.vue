<template>
  <MasterLayout
    title="客户档案"
    description="维护客户档案，聚合关联的销售出库（出库单自带联系人电话 / 收货地址 / 业务员）与合并历史、案例素材。"
    :is-workbench="true"
    page-class="cust-view"
  >
    <template #toolbar>
      <div class="cust-toolbar">
        <SearchToolbar
          v-model:keyword="filters.keyword"
          search-placeholder="搜索名称、简称、编号、联系人、手机号..."
          search-mode="debounce"
          :fields="toolbarFields"
          :field-values="filters"
          :batch-enabled="canManageCustomer"
          :batch-mode="batchMode"
          :selected-count="selectedIds.size"
          :models-count="list.length"
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
            <ReturnToSourceButton label="返回订单并复查" />
            <el-button :icon="MapLocation" @click="openDistribution">区域分布</el-button>
            <el-button v-if="canManageCustomer" type="primary" @click="openCreate">新增客户</el-button>
          </template>
        </SearchToolbar>
        <ActiveFilterChips :filters="activeFilterChips" @remove="removeFilterChip" @clear="handleResetFilters" />
      </div>
    </template>

    <template #list>
      <MasterListPanel
        :models="listModels"
        :pagination="pagination"
        :loading="loading"
        :selected-id="selectedCustomerId"
        :batch-mode="batchMode"
        :selected-model-ids="selectedIds"
        layout="workbench"
        :list-sort="`${sortState.field}-${sortState.direction}`"
        :sort-options="masterSortOptions"
        :row="masterRowConfig"
        :empty-title="list.length ? '当前筛选没有客户' : '暂无客户档案'"
        :empty-hint="list.length ? '调整筛选条件或新建客户' : '点击「新增客户」建立客户档案库。'"
        loading-text="正在加载客户..."
        @select="onSelectCustomer"
        @toggle-select="toggleSelect"
        @sort-change="handleMasterSortChange"
        @pagination-change="doSearch"
      />
    </template>

    <template #detail>
      <CustomerDetailPanel
        :key="`${selectedCustomerId ?? 'none'}-${detailRefreshKey}`"
        :customer-id="selectedCustomerId"
        :can-manage="canManageCustomer"
        @edit="openEditFromDrawer"
        @changed="onDrawerChanged"
      />
    </template>

    <template #modals>
      <CustomerFormModal
        :visible="formModal.visible"
        :data="formModal.data"
        :can-manage="canManageCustomer"
        :is-create-mode="formModal.isCreateMode"
        :is-edit-mode="formModal.isEditMode"
        :filter-options="filterOptions"
        @close="closeFormModal"
        @save="onFormSaved"
      />

      <FieldBatchEditModal
        :visible="batchEditModal.visible"
        :selected-count="selectedIds.size"
        :field-groups="batchEditFieldGroups"
        :loading="batchEditModal.loading"
        entity-name="客户"
        @close="batchEditModal.visible = false"
        @apply="applyBatchEdit"
      />

      <CustomerDistributionDialog
        v-if="distributionDialog.visible"
        :visible="true"
        :active-region="activeRegionFilter"
        :can-manage="canManageCustomer"
        @close="distributionDialog.visible = false"
        @apply="handleDistributionApply"
      />
    </template>
  </MasterLayout>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { MapLocation } from '@element-plus/icons-vue'
import {
  batchUpdateCustomers,
  deleteCustomer,
  fetchCustomerFilters,
  fetchCustomers
} from '@/api/customer'
import ActiveFilterChips, { type ActiveFilter } from '@/components/common/ActiveFilterChips.vue'
import FieldBatchEditModal, { type BatchEditFieldGroup } from '@/components/common/FieldBatchEditModal.vue'
import MasterLayout from '@/components/master/MasterLayout.vue'
import MasterListPanel, { type MasterRowConfig, type SortOption } from '@/components/master/MasterListPanel.vue'
import SearchToolbar, { type ToolbarField, type ToolbarFieldOptionInput } from '@/components/common/SearchToolbar.vue'
import ReturnToSourceButton from '@/components/common/ReturnToSourceButton.vue'
import CustomerDetailPanel from '@/components/customer/CustomerDetailPanel.vue'
import CustomerDistributionDialog from '@/components/customer/CustomerDistributionDialog.vue'
import CustomerFormModal from '@/components/customer/CustomerFormModal.vue'
import { useBatchSelection } from '@/composables/list/useBatchSelection'
import { useListFilters } from '@/composables/list/useListFilters'
import { useListPagination } from '@/composables/list/useListPagination'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { notify } from '@/composables/ui/useToast'
import type {
  CustomerDetail,
  CustomerFilterOptions,
  CustomerRegionFilter,
  CustomerSummary
} from '@/types/customer'

const { filters, resetFilters, getQueryParams } = useListFilters({
  keyword: '',
  customerType: '',
  industry: '',
  province: '',
  city: '',
  district: '',
  relationStatus: '',
  status: '',
  salesperson: '',
  contact: '',
  address: ''
})
const route = useRoute()
filters.keyword = String(route.query.keyword || '')
const requestedCustomerId = Number(route.query.customerId)

/**
 * 地区筛选的两个哨兵值（都不是真实省份，后端按布尔标记处理）：
 * - 未标注区域：省份为空或不是官方省名，上不了地图；
 * - 待补市县：省份已合法，但市/区县缺失或地址解析状态异常。
 */
const NO_REGION_PROVINCE = '__NO_REGION__'
const PARTIAL_REGION_PROVINCE = '__PARTIAL_REGION__'

/** 地区下拉 = 库内已有省份 + 两个哨兵入口，便于逐条人工补全省/市/区县 */
const provinceFilterOptions = computed<ToolbarFieldOptionInput[]>(() => {
  const options: ToolbarFieldOptionInput[] = [...(filterOptions.value?.provinces ?? [])]
  options.push({ value: PARTIAL_REGION_PROVINCE, label: '待补市县' })
  options.push({ value: NO_REGION_PROVINCE, label: '未标注区域' })
  return options
})

const toolbarFields = computed<ToolbarField[]>(() => [
  { key: 'customerType', label: '类型', options: filterOptions.value?.customerTypes || [], width: 110 },
  { key: 'relationStatus', label: '关系状态', options: filterOptions.value?.relationStatuses || [], width: 120 },
  { key: 'status', label: '状态', options: filterOptions.value?.statuses || [], width: 90 },
  { key: 'industry', label: '行业', options: filterOptions.value?.industries || [], width: 130 },
  { key: 'province', label: '地区', options: provinceFilterOptions.value, width: 110 },
  { key: 'salesperson', label: '业务员', options: filterOptions.value?.salespersons || [], width: 120 },
  { key: 'contact', label: '联系人', type: 'text', placeholder: '联系人姓名 / 手机 / 微信', width: 190 },
  { key: 'address', label: '收货地址', type: 'text', placeholder: '地区 / 地址 / 收货电话', width: 220 }
])

const pagination = useListPagination()
const { resetPage, getPageParams, updateFromResponse } = pagination

const loading = ref(false)
const list = ref<CustomerSummary[]>([])

const { can } = useCurrentUser()
/** 是否可维护客户档案（新增/编辑/删除/联系人/业务员关系），由 customer.manage 权限控制 */
const canManageCustomer = computed(() => can('customer.manage'))

const filterOptions = ref<CustomerFilterOptions | null>(null)

const sortState = reactive<{
  field: string
  direction: 'asc' | 'desc'
  order: 'ascending' | 'descending'
}>({ field: 'customerCode', direction: 'asc', order: 'ascending' })

// ========== 主从布局：选中与列表行 ==========

const selectedCustomerId = ref<number | null>(null)
/** 编辑档案保存后自增，强制右侧面板重新加载 */
const detailRefreshKey = ref(0)

const listModels = computed<CustomerSummary[]>(() => list.value)

const masterSortOptions: SortOption[] = [
  { value: 'customerCode-asc', label: '编号 A→Z' },
  { value: 'customerCode-desc', label: '编号 Z→A' },
  { value: 'name-asc', label: '名称 A→Z' },
  { value: 'name-desc', label: '名称 Z→A' },
  { value: 'createdAt-desc', label: '最近建档' }
]

const masterRowConfig: MasterRowConfig = {
  thumbnailUrl: () => null,
  thumbnailFallback: c => (c.shortName || c.customerName || '客').slice(0, 2),
  title: c => c.customerName || c.shortName || '未命名客户',
  titleTag: () => null,
  metaLine: c => {
    const location = [c.province, c.city, c.district].filter(Boolean).join(' ')
    return [location || null].filter(Boolean) as string[]
  },
  countsLine: () => null,
  badges: () => []
}

function onSelectCustomer(id: number) {
  selectedCustomerId.value = id
}

function handleMasterSortChange(sort: string) {
  const [field, direction] = sort.split('-')
  if (!field || !direction) return
  sortState.field = field
  sortState.direction = direction as 'asc' | 'desc'
  sortState.order = direction === 'asc' ? 'ascending' : 'descending'
  resetPage()
  doSearch()
}

// ========== 批量操作 ==========

const batchSelection = useBatchSelection<number>()
const { batchMode, selectedIds } = batchSelection
const isAllPageSelected = computed(() => batchSelection.isAllSelected.value(list.value))

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

const batchEditModal = reactive({ visible: false, loading: false })
const batchEditFieldGroups = computed<BatchEditFieldGroup[]>(() => [
  {
    title: '类型与关系',
    fields: [
      {
        key: 'customerType',
        label: '客户类型',
        type: 'select',
        options: (filterOptions.value?.customerTypes || []).map(v => ({ value: v, label: v }))
      },
      {
        key: 'relationStatus',
        label: '关系状态',
        type: 'select',
        options: (filterOptions.value?.relationStatuses || []).map(v => ({ value: v, label: v }))
      },
      {
        key: 'status',
        label: '启用状态',
        type: 'select',
        options: (filterOptions.value?.statuses || []).map(v => ({ value: v, label: v }))
      },
      {
        key: 'level',
        label: '客户等级',
        type: 'select',
        options: [
          { value: 'COMMON', label: '普通' },
          { value: 'KEY', label: '重点' }
        ]
      }
    ]
  },
  {
    title: '主体信息',
    fields: [
      { key: 'industry', label: '所属行业', type: 'text' },
      { key: 'source', label: '客户来源', type: 'text' },
      { key: 'province', label: '省', type: 'text' },
      { key: 'city', label: '市', type: 'text' },
      { key: 'district', label: '区县', type: 'text' }
    ]
  },
  {
    title: '备注',
    fields: [{ key: 'remark', label: '备注', type: 'textarea', full: true }]
  }
])

function openBatchEditModal() {
  if (selectedIds.size === 0) {
    notify('请先勾选要批量编辑的客户', 'warning')
    return
  }
  batchEditModal.visible = true
}

async function applyBatchEdit(payload: Record<string, unknown>) {
  const ids = batchSelection.getSelectedIds().map(Number)
  if (ids.length === 0) return
  batchEditModal.loading = true
  try {
    await batchUpdateCustomers(ids, payload)
    notify(`已更新 ${ids.length} 个客户`)
    batchEditModal.visible = false
    batchSelection.clearSelection()
    batchMode.value = false
    await loadList()
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
    message: `确定要删除选中的 ${count} 个客户档案吗？此操作不可恢复。`,
    confirmText: '批量删除',
    danger: true
  })
  if (!confirmed) return
  try {
    await Promise.all(ids.map(id => deleteCustomer(id)))
    batchSelection.clearSelection()
    batchMode.value = false
    notify(`已删除 ${count} 个客户`, 'success')
    await loadList()
  } catch (err) {
    notify.errorWithDetail('批量删除失败', err)
  }
}

// ========== 筛选 ==========

const activeFilterChips = computed<ActiveFilter[]>(() => {
  const chips: ActiveFilter[] = []
  if (filters.customerType) chips.push({ key: 'customerType', label: '客户类型', valueLabel: filters.customerType })
  if (filters.relationStatus) chips.push({ key: 'relationStatus', label: '关系状态', valueLabel: filters.relationStatus })
  if (filters.status) chips.push({ key: 'status', label: '状态', valueLabel: filters.status })
  if (filters.industry) chips.push({ key: 'industry', label: '行业', valueLabel: filters.industry })
  if (filters.province)
    chips.push({
      key: 'province',
      label: '地区',
      valueLabel: filters.province === NO_REGION_PROVINCE ? '未标注区域' : filters.province
    })
  if (filters.city) chips.push({ key: 'city', label: '城市', valueLabel: filters.city })
  if (filters.district) chips.push({ key: 'district', label: '区县', valueLabel: filters.district })
  if (filters.salesperson) chips.push({ key: 'salesperson', label: '业务员', valueLabel: filters.salesperson })
  if (filters.contact) chips.push({ key: 'contact', label: '联系人', valueLabel: filters.contact })
  if (filters.address) chips.push({ key: 'address', label: '收货地址', valueLabel: filters.address })
  return chips
})

function applyFilters() {
  resetPage()
  doSearch()
}

function handleToolbarFilterChange(key: string, value: unknown) {
  filters[key as keyof typeof filters] = value as never
  // 手动切省/清省时，市级/区县条件跟随失效，避免跨省误筛
  if (key === 'province') {
    filters.city = ''
    filters.district = ''
  }
  applyFilters()
}

function removeFilterChip(key: string) {
  filters[key as keyof typeof filters] = '' as never
  applyFilters()
}

function handleResetFilters() {
  resetFilters(doSearch)
}

// ========== 数据加载 ==========

async function doSearch() {
  loading.value = true
  try {
    const params: Record<string, unknown> = {
      ...getQueryParams(),
      ...getPageParams(),
      sort: sortState.field,
      direction: sortState.direction
    }
    // 地区哨兵值 → 后端布尔标记，避免被当作省份原值精确匹配
    if (filters.province === NO_REGION_PROVINCE) {
      params.noRegion = true
      delete params.province
      delete params.city
      delete params.district
    } else if (filters.province === PARTIAL_REGION_PROVINCE) {
      params.regionPartial = true
      delete params.province
      delete params.city
      delete params.district
    }
    const result = await fetchCustomers(params)
    updateFromResponse(result)
    list.value = result.content || []
    if (Number.isSafeInteger(requestedCustomerId) && requestedCustomerId > 0 && list.value.some(c => c.id === requestedCustomerId)) {
      selectedCustomerId.value = requestedCustomerId
    }
    // 当前选中客户不在本页时，自动取消选中（右侧面板回到空态）
    if (selectedCustomerId.value != null && !list.value.some(c => c.id === selectedCustomerId.value)) {
      selectedCustomerId.value = null
    }
  } catch (err) {
    notify.errorWithDetail('加载失败', err)
  } finally {
    loading.value = false
  }
}

async function loadFilters() {
  try {
    filterOptions.value = await fetchCustomerFilters()
  } catch (err) {
    console.warn('加载客户筛选选项失败', err)
  }
}

/**
 * 数据变更/筛选后刷新：回到第 1 页重新查询。
 * 注意：不要直接用于 @pagination-change（翻页事件自身已携带目标页码，
 * 再 resetPage 会导致页码被弹回第 1 页），翻页应绑定 doSearch。
 */
function loadList() {
  resetPage()
  doSearch()
}

// ========== 区域分布 ==========

const distributionDialog = reactive({ visible: false })

/** 弹窗打开时用于预选中的当前列表地区筛选 */
const activeRegionFilter = computed(() => {
  if (filters.province === NO_REGION_PROVINCE) return { noRegion: true }
  if (filters.province === PARTIAL_REGION_PROVINCE) return { regionPartial: true }
  return {
    province: filters.province || undefined,
    city: filters.city || undefined,
    district: filters.district || undefined
  }
})

function openDistribution() {
  if (batchMode.value) {
    batchSelection.clearSelection()
    batchMode.value = false
  }
  distributionDialog.visible = true
}

function handleDistributionApply(region: CustomerRegionFilter) {
  if (region.noRegion) {
    filters.province = NO_REGION_PROVINCE
    filters.city = ''
    filters.district = ''
  } else if (region.regionPartial) {
    filters.province = PARTIAL_REGION_PROVINCE
    filters.city = ''
    filters.district = ''
  } else {
    filters.province = region.province ?? ''
    filters.city = region.city || ''
    // 未落到市级的筛选（仅省 / 待整省）不应残留上一轮的区县条件
    filters.district = region.city ? region.district || '' : ''
  }
  distributionDialog.visible = false
  // 地区筛选后焦点客户可能已不在结果内，先回到空态，让筛选结果更直观
  selectedCustomerId.value = null
  applyFilters()
}

// ========== 客户管理 ==========

const formModal = reactive({
  visible: false,
  data: {} as CustomerDetail,
  isCreateMode: false,
  isEditMode: false
})

function openCreate() {
  formModal.data = {} as CustomerDetail
  formModal.isCreateMode = true
  formModal.isEditMode = false
  formModal.visible = true
}

function openEditFromDrawer(detail: CustomerDetail) {
  formModal.data = detail
  formModal.isCreateMode = false
  formModal.isEditMode = true
  formModal.visible = true
}

function closeFormModal() {
  formModal.visible = false
}

function onFormSaved() {
  formModal.visible = false
  loadList()
  detailRefreshKey.value++
}

function onDrawerChanged() {
  loadList()
}

// ========== 生命周期 ==========

Promise.allSettled([loadList(), loadFilters()])
</script>

<style scoped>
.cust-view {
  width: 100%;
}

.cust-toolbar {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.cust-toolbar :deep(.search-toolbar) {
  padding: var(--space-sm) var(--space-control-gap-wide);
}

.cust-toolbar :deep(.active-filter-chips) {
  padding: 0;
}
</style>
