<template>
  <MasterLayout title="模具资产管理" description="包材模具编号、出模数、刻字版本与配件台账。" page-class="mold-view">
    <template #toolbar>
      <SearchToolbar
        v-model:keyword="filters.keyword"
        search-placeholder="搜索模号、刻字、产品、配件..."
        search-mode="debounce"
        :debounce="300"
        :fields="toolbarFields"
        :field-values="filters"
        batch-enabled
        :batch-mode="batchMode"
        :selected-count="selectedCount"
        :models-count="visibleMoldCount"
        :is-all-selected="isAllPageSelected"
        @search="applyFilters"
        @filter-change="handleToolbarFilterChange"
        @clear="resetFilters"
        @toggle-batch-mode="toggleBatchMode"
        @toggle-select-all="masterDetailRef?.toggleSelectAllCurrentPage()"
        @batch-edit="openBatchEdit"
        @batch-remove="masterDetailRef?.doBatchDelete()"
      >
        <template #actions>
          <el-button v-if="canManageMold" size="small" @click="triggerImport">导入 Excel</el-button>
          <input v-if="canManageMold" ref="importInputRef" type="file" accept=".xlsx,.xls" hidden @change="onImportFileChange" />
          <el-button v-if="canManageMold" size="small" type="primary" @click="masterDetailRef?.openCreate()">新建模具</el-button>
          <LqDropdown v-if="canManageMold" :items="MENU_ACTIONS" @command="(c: string | number) => handleMenuCommand(String(c))">
            <template #trigger><el-button>更多</el-button></template>
          </LqDropdown>
        </template>
      </SearchToolbar>
    </template>

    <template #list>
      <section class="mold-workspace">
        <ActiveFilterChips :filters="activeFilterChips" @remove="removeFilterChip" @clear="resetFilters" />

        <PackagingMoldsMasterDetail
          ref="masterDetailRef"
          mode="global"
          :extra-filters="filters"
          v-model:batch-mode="batchMode"
          @data-changed="loadStats"
        />
      </section>
    </template>

    <template #modals>
      <FieldBatchEditModal
        :visible="batchEditModal.visible"
        :selected-count="selectedCount"
        :field-groups="batchEditFieldGroups"
        :loading="batchEditModal.loading"
        entity-name="模具"
        @close="batchEditModal.visible = false"
        @apply="applyBatchEdit"
      />
    </template>
  </MasterLayout>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue'
import { useRoute } from 'vue-router'
import {
  fetchPackagingMoldFilterOptions,
  fetchPackagingMoldStats,
  type MoldStats,
  type FilterOptions,
  type PackagingMoldSummary
} from '@/api'
import { ElMessage, type TagProps } from 'element-plus'
import MasterLayout from '@/components/master/MasterLayout.vue'
import SearchToolbar, { type ToolbarField } from '@/components/common/SearchToolbar.vue'
import ActiveFilterChips from '@/components/common/ActiveFilterChips.vue'
import LqDropdown from '@/components/common/LqDropdown.vue'
import PackagingMoldsMasterDetail from '@/components/packaging/PackagingMoldsMasterDetail.vue'
import FieldBatchEditModal, { type BatchEditFieldGroup } from '@/components/common/FieldBatchEditModal.vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { batchUpdatePackagingMolds } from '@/api/packagingMold'
import type { ActiveFilter } from '@/components/common/ActiveFilterChips.vue'

const STATUS_OPTIONS: Record<string, { label: string; tag: TagProps['type'] }> = {
  in_use: { label: '在用', tag: 'success' },
  stopped: { label: '停用', tag: 'info' },
  repair: { label: '维修中', tag: 'warning' },
  unverified: { label: '待核实', tag: 'danger' }
}

const ROLE_OPTIONS: Record<string, string> = {
  main: '主模',
  accessory: '配件模',
  unknown: '未知'
}

const ENGRAVING_OPTIONS = [
  { value: 'blank', label: '空白' },
  { value: 'generic', label: '通用' },
  { value: 'brand_custom', label: '品牌专版' },
  { value: 'functional', label: '功能标识' }
]

const MENU_ACTIONS = [
  { command: 'stats', label: '刷新统计' },
  { command: 'export', label: '导出可见模具' }
]

// ====== 数据 ======
const masterDetailRef = ref<InstanceType<typeof PackagingMoldsMasterDetail> | null>(null)
const route = useRoute()

const stats = ref<MoldStats>({
  totalMolds: 0,
  inUseCount: 0,
  stoppedCount: 0,
  repairCount: 0,
  unverifiedCount: 0,
  mainMoldCount: 0,
  accessoryMoldCount: 0,
  variantLinkCount: 0,
  accessoryItemCount: 0
})
const filterOptions = ref<FilterOptions | null>(null)

const { can } = useCurrentUser()
/** 是否可维护模具档案（新建/编辑/导入/批量），由 mold.manage 权限控制 */
const canManageMold = computed(() => can('mold.manage'))

const filters = reactive({
  keyword: '',
  status: '',
  moldRole: '',
  moldTypeCode: '',
  engravingType: '',
  matchStatus: ''
})

const batchMode = ref(false)
const importInputRef = ref<HTMLInputElement | null>(null)
const selectedCount = computed(() => masterDetailRef.value?.selectedCount ?? 0)
const visibleMoldCount = computed(() => masterDetailRef.value?.getMolds()?.length ?? 0)
const isAllPageSelected = computed(() => masterDetailRef.value?.isAllPageSelected ?? false)

const toolbarFields = computed<ToolbarField[]>(() => [
  {
    key: 'status',
    label: '状态',
    options: (filterOptions.value?.statuses || []).map(s => ({ value: s, label: statusLabel(s) }))
  },
  {
    key: 'moldRole',
    label: '角色',
    options: (filterOptions.value?.moldRoles || []).map(r => ({ value: r, label: roleLabel(r) }))
  },
  { key: 'moldTypeCode', label: '盒型', options: filterOptions.value?.moldTypeCodes || [] },
  { key: 'engravingType', label: '刻字', options: ENGRAVING_OPTIONS },
  {
    key: 'matchStatus',
    label: '匹配状态',
    options: [
      { value: 'unmatched', label: '待匹配' },
      { value: 'matched', label: '已匹配' }
    ]
  }
])

function triggerImport() {
  importInputRef.value?.click()
}

function onImportFileChange(e: Event) {
  masterDetailRef.value?.handleImportFile(e)
}

function toggleBatchMode() {
  batchMode.value = !batchMode.value
  if (!batchMode.value) {
    masterDetailRef.value?.exitBatchMode()
  }
}

const batchEditModal = reactive({ visible: false, loading: false })
const batchEditFieldGroups = computed<BatchEditFieldGroup[]>(() => [
  {
    title: '模具信息',
    fields: [
      { key: 'baseMoldCode', label: '基础模号', type: 'text' },
      { key: 'moldTypeCode', label: '盒型代码', type: 'text' },
      { key: 'cavityCount', label: '一出数量', type: 'number' },
      {
        key: 'moldRole',
        label: '模具角色',
        type: 'select',
        options: Object.entries(ROLE_OPTIONS).map(([value, label]) => ({ value, label }))
      },
      { key: 'moldCategory', label: '模具分类', type: 'text' },
      { key: 'compartmentCount', label: '格数', type: 'text' },
      { key: 'surfaceStructure', label: '表面结构', type: 'text' },
      { key: 'bottomEngraving', label: '杯底刻字', type: 'text' },
      { key: 'engravingType', label: '刻字类型', type: 'select', options: ENGRAVING_OPTIONS },
      {
        key: 'status',
        label: '状态',
        type: 'select',
        options: Object.entries(STATUS_OPTIONS).map(([value, item]) => ({ value, label: item.label }))
      }
    ]
  },
  {
    title: '尺寸与备注',
    fields: [
      { key: 'rawCaliber', label: '原始口径', type: 'text' },
      { key: 'rawHeight', label: '原始高度', type: 'text' },
      { key: 'rawAccessoriesText', label: '配件原始信息', type: 'textarea', full: true },
      { key: 'remark', label: '备注', type: 'textarea', full: true }
    ]
  },
  {
    title: '官网发布',
    fields: [
      { key: 'websiteEnabled', label: '发布到官网', type: 'switch' },
      { key: 'websiteSortOrder', label: '官网排序', type: 'number' }
    ]
  }
])

function openBatchEdit() {
  if (selectedCount.value === 0) return
  batchEditModal.visible = true
}

async function applyBatchEdit(fields: Record<string, unknown>) {
  const ids = masterDetailRef.value?.getSelectedIds().map(Number) ?? []
  if (ids.length === 0) return
  batchEditModal.loading = true
  try {
    await batchUpdatePackagingMolds(ids, fields)
    ElMessage.success(`已更新 ${ids.length} 个模具`)
    batchEditModal.visible = false
    batchMode.value = false
    masterDetailRef.value?.clearSelection()
    await masterDetailRef.value?.loadMolds()
    await loadStats()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '批量修改失败')
  } finally {
    batchEditModal.loading = false
  }
}

// ====== 计算属性 ======
const activeFilterChips = computed<ActiveFilter[]>(() => {
  const chips: ActiveFilter[] = []
  if (filters.keyword) chips.push({ key: 'keyword', label: '搜索', valueLabel: filters.keyword })
  if (filters.status) chips.push({ key: 'status', label: '状态', valueLabel: statusLabel(filters.status) })
  if (filters.moldRole) chips.push({ key: 'moldRole', label: '角色', valueLabel: roleLabel(filters.moldRole) })
  if (filters.moldTypeCode) chips.push({ key: 'moldTypeCode', label: '盒型', valueLabel: filters.moldTypeCode })
  if (filters.engravingType) {
    const v = ENGRAVING_OPTIONS.find(o => o.value === filters.engravingType)?.label || filters.engravingType
    chips.push({ key: 'engravingType', label: '刻字', valueLabel: v })
  }
  if (filters.matchStatus) {
    chips.push({
      key: 'matchStatus',
      label: '匹配状态',
      valueLabel: filters.matchStatus === 'unmatched' ? '待匹配' : '已匹配'
    })
  }
  return chips
})

// ====== 工具函数 ======
function statusLabel(s?: string) {
  return STATUS_OPTIONS[s || '']?.label || s || '—'
}
function roleLabel(r?: string) {
  return ROLE_OPTIONS[r || ''] || r || '—'
}

function removeFilterChip(key: string) {
  if (key === 'keyword') filters.keyword = ''
  if (key === 'status') filters.status = ''
  if (key === 'moldRole') filters.moldRole = ''
  if (key === 'moldTypeCode') filters.moldTypeCode = ''
  if (key === 'engravingType') filters.engravingType = ''
  if (key === 'matchStatus') filters.matchStatus = ''
  applyFilters()
}

function resetFilters() {
  filters.keyword = ''
  filters.status = ''
  filters.moldRole = ''
  filters.moldTypeCode = ''
  filters.engravingType = ''
  filters.matchStatus = ''
  applyFilters()
}

function handleToolbarFilterChange(key: string, value: unknown) {
  filters[key as keyof typeof filters] = value as never
}

function applyFilters() {
  // extraFilters 变化会触发通用组件的 watch，自动重新加载
}

async function loadStats() {
  try {
    stats.value = await fetchPackagingMoldStats()
  } catch {
    // ignore
  }
}

async function loadFilterOptions() {
  try {
    filterOptions.value = await fetchPackagingMoldFilterOptions()
  } catch {
    // ignore
  }
}

// ====== 更多菜单 ======
function handleMenuCommand(cmd: string) {
  if (cmd === 'stats') loadStats()
  if (cmd === 'export') exportVisibleMolds()
}

function exportVisibleMolds() {
  const molds = masterDetailRef.value?.getMolds() as PackagingMoldSummary[] | undefined
  if (!molds || molds.length === 0) {
    ElMessage.warning('当前列表为空，无可导出数据')
    return
  }
  const headers = [
    '模号',
    '基础模号',
    '产品系列',
    '规格族',
    '盒型',
    '一出几',
    '格数',
    '结构',
    '杯底刻字',
    '刻字类型',
    '角色',
    '状态',
    '配件数',
    '规格数'
  ]
  const keys: (keyof PackagingMoldSummary)[] = [
    'moldCode',
    'baseMoldCode',
    'modelName',
    'groupName',
    'moldTypeCode',
    'cavityCount',
    'compartmentCount',
    'surfaceStructure',
    'bottomEngraving',
    'engravingType',
    'moldRole',
    'status',
    'accessoryCount',
    'variantCount'
  ]
  const escapeCell = (v: unknown) => {
    const s = v == null ? '' : String(v)
    if (/[",\n\r]/.test(s)) return `"${s.replace(/"/g, '""')}"`
    return s
  }
  const rows = molds.map(m => keys.map(k => escapeCell(m[k])).join(','))
  const csv = '\uFEFF' + [headers.join(','), ...rows].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  const stamp = new Date().toISOString().slice(0, 10).replace(/-/g, '')
  link.download = `molds_${stamp}.csv`
  link.click()
  URL.revokeObjectURL(url)
  ElMessage.success(`已导出 ${molds.length} 条模具数据`)
}

filters.keyword = typeof route.query.keyword === 'string' ? route.query.keyword : ''
loadFilterOptions()
loadStats()
</script>

<style scoped></style>
