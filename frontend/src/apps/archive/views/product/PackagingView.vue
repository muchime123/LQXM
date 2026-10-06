<template>
  <MasterLayout
    title="包材管理"
    description="维护包材系列、规格卡片、质量信息与素材资产。"
    :is-workbench="true"
    page-class="packaging-view"
  >
    <template #toolbar>
      <SearchToolbar
        v-model:keyword="filters.keyword"
        search-placeholder="搜索包材名称、材质、适用产品、标签..."
        search-mode="debounce"
        :fields="toolbarFields"
        :field-values="toolbarFieldValues"
        batch-enabled
        :batch-mode="batchMode"
        :selected-count="selectedIds.size"
        :models-count="visibleModels.length"
        :is-all-selected="isAllPageSelected"
        @search="handleSearch"
        @filter-change="handleToolbarFilter"
        @clear="clearFilters"
        @toggle-batch-mode="toggleBatchMode"
        @toggle-select-all="batchSelection.toggleSelectAll(visibleModels)"
        @batch-edit="openBatchEdit"
        @batch-remove="handleBatchDelete"
      >
        <template #actions>
          <el-button v-if="canManagePackaging" @click="exportVisibleModels">导出</el-button>
          <el-button v-if="canManagePackaging" type="primary" @click="openEditor()">新建包材</el-button>
        </template>
      </SearchToolbar>
    </template>

    <template #list>
      <MasterListPanel
        :models="visibleModels"
        :pagination="pagination"
        :loading="loading"
        :selected-id="selectedModelId"
        :batch-mode="batchMode"
        :selected-model-ids="selectedIds"
        layout="workbench"
        :list-sort="listSort"
        :list-filters="listFilters"
        :current-filter="listFilter"
        :sort-options="SORT_OPTIONS"
        :row="rowConfig"
        :empty-title="models.length ? '当前筛选没有包材' : '暂无包材数据'"
        :empty-hint="models.length ? '调整筛选条件或新建包材' : '点击「新建包材」建立包材资料库。'"
        loading-text="正在加载包材..."
        @select="selectModel"
        @toggle-select="id => batchSelection.toggleSelection(Number(id))"
        @sort-change="handleSortChange"
        @filter-change="handleListFilterChange"
        @pagination-change="loadModels"
      />
    </template>

    <template #detail>
      <PackagingDetailDrawer
        v-if="selectedModelId"
        :key="`${selectedModelId}-${detailRefreshKey}`"
        :visible="true"
        :model-id="selectedModelId"
        :focus-variant-id="focusedVariantId"
        :pinned="true"
        @edit="openEditor"
        @deleted="handleDeleted"
        @asset-changed="refreshCurrent"
      />
    </template>

    <template #empty>
      <EmptyState
        v-if="!selectedModelId"
        :text="models.length ? '选择包材查看详情' : '暂无包材数据'"
        :hint="
          models.length ? '从左侧列表选择一个包材，维护规格、素材和主档信息。' : '点击「新建包材」开始建立包材资料库。'
        "
      />
    </template>

    <template #modals>
      <PackagingEditorModal
        v-if="editorVisible"
        :visible="editorVisible"
        :model-id="editingModelId"
        @close="editorVisible = false"
        @saved="handleSaved"
      />
      <FieldBatchEditModal
        :visible="batchEditModal.visible"
        :selected-count="selectedIds.size"
        :field-groups="batchEditFieldGroups"
        :loading="batchEditModal.loading"
        entity-name="包材主档"
        @close="batchEditModal.visible = false"
        @apply="applyBatchEdit"
      />
    </template>
  </MasterLayout>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import EmptyState from '@/components/common/EmptyState.vue'
import MasterLayout from '@/components/master/MasterLayout.vue'
import SearchToolbar from '@/components/common/SearchToolbar.vue'
import type { ToolbarField } from '@/components/common/SearchToolbar.vue'
import MasterListPanel, { type MasterRowConfig, type SortOption } from '@/components/master/MasterListPanel.vue'
import { useListPagination } from '@/composables/list/useListPagination'
import { useBatchSelection } from '@/composables/list/useBatchSelection'
import {
  addPackagingAsset,
  batchDeletePackagingModels,
  batchUpdatePackagingModels,
  fetchPackagingFilters,
  fetchPackagingModels,
  type PackagingModelSummary
} from '@/api/packaging'
import PackagingDetailDrawer from '@/components/packaging/PackagingDetailDrawer.vue'
import PackagingEditorModal from '@/components/packaging/PackagingEditorModal.vue'
import FieldBatchEditModal, { type BatchEditFieldGroup } from '@/components/common/FieldBatchEditModal.vue'
import {
  kindLabel,
  FILM_FORMS,
  PACKAGING_ASSET_CATEGORY_KEYS,
  PACKAGING_KINDS,
  PACKAGING_STATUSES,
  SHAPE_TYPES
} from '@/constants/packaging'
import { useRegisteredFileUpload } from '@/composables/upload/useRegisteredFileUpload'
import { useFileAssetActions } from '@/composables/upload/useFileAssetActions'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { notify } from '@/composables/ui/useToast'
import { downloadBlob } from '@/utils/ui/downloadBlob'
import { fileAssetThumbnailUrlByOptions } from '@/utils/ui/fileAssetUrls'
import { FILE_OWNER_TYPE_KEYS } from '@/types/fileAsset'
import { todayIso } from '@/utils/common/dateUtils'
import { askConfirm } from '@/composables/ui/useConfirmDialog'

const { can } = useCurrentUser()
/** 是否可维护包材档案（新建/编辑/导入/导出/批量），由 packaging.manage 权限控制 */
const canManagePackaging = computed(() => can('packaging.manage'))

// ===================== 状态：列表分页与数据 =====================
const loading = ref(false)
const route = useRoute()
const models = ref<PackagingModelSummary[]>([])
const pagination = useListPagination()

// ===================== 状态：选中与编辑器 =====================
const selectedModelId = ref<number | null>(null)
const detailRefreshKey = ref(0)
const focusedVariantId = ref<number | null>(null)
const editorVisible = ref(false)
const editingModelId = ref<number | null>(null)
const batchSelection = useBatchSelection<number>()
const { batchMode, selectedIds } = batchSelection
const isAllPageSelected = computed(() => batchSelection.isAllSelected.value(visibleModels.value))

function toggleBatchMode() {
  batchSelection.toggleBatchMode()
}

const batchEditModal = reactive({ visible: false, loading: false })
const batchEditFieldGroups = computed<BatchEditFieldGroup[]>(() => [
  {
    title: '分类规格',
    fields: [
      { key: 'nameEn', label: '英文名称', type: 'text' },
      { key: 'modelCode', label: '型号编码', type: 'text' },
      {
        key: 'packagingKind',
        label: '包材类型',
        type: 'select',
        options: PACKAGING_KINDS.map(item => ({ value: item.key, label: item.label }))
      },
      {
        key: 'filmForm',
        label: '膜形态',
        type: 'select',
        options: FILM_FORMS.map(item => ({ value: item.key, label: item.label }))
      },
      {
        key: 'shapeType',
        label: '形状',
        type: 'select',
        options: SHAPE_TYPES.map(item => ({ value: item.key, label: item.label }))
      },
      {
        key: 'material',
        label: '材质',
        type: 'select',
        options: filterOptions.material.map(value => ({ value, label: value }))
      },
      { key: 'colorStyle', label: '颜色/风格', type: 'text' },
      { key: 'capacityMl', label: '容量', type: 'text' }
    ]
  },
  {
    title: '采购销售',
    fields: [
      { key: 'moq', label: '起订量', type: 'text' },
      { key: 'unitPrice', label: '参考单价', type: 'text' },
      { key: 'leadTime', label: '交期', type: 'text' },
      { key: 'customizable', label: '可定制', type: 'switch' },
      {
        key: 'status',
        label: '状态',
        type: 'select',
        options: PACKAGING_STATUSES.map(value => ({ value, label: value }))
      },
      { key: 'sortOrder', label: '排序', type: 'number' }
    ]
  },
  {
    title: '应用描述',
    fields: [
      { key: 'applicable', label: '适用产品', type: 'text', full: true },
      { key: 'tags', label: '标签', type: 'text', full: true },
      { key: 'advantages', label: '产品亮点/工艺说明', type: 'textarea', full: true },
      { key: 'remark', label: '备注', type: 'textarea', full: true }
    ]
  }
  ,
  {
    title: '官网发布',
    fields: [
      { key: 'websiteEnabled', label: '发布到官网', type: 'switch' },
      { key: 'websiteSortOrder', label: '官网排序', type: 'number' }
    ]
  }
])

function openBatchEdit() {
  if (selectedIds.size === 0) return
  batchEditModal.visible = true
}

async function applyBatchEdit(fields: Record<string, unknown>) {
  const ids = batchSelection.getSelectedIds().map(Number)
  batchEditModal.loading = true
  try {
    await batchUpdatePackagingModels(ids, fields)
    notify(`已更新 ${ids.length} 个包材主档`, 'success')
    batchEditModal.visible = false
    batchSelection.clearSelection()
    batchMode.value = false
    await loadModels()
  } catch (error) {
    notify.errorWithDetail('批量修改包材失败', error)
  } finally {
    batchEditModal.loading = false
  }
}

async function handleBatchDelete() {
  const ids = batchSelection.getSelectedIds().map(Number)
  if (ids.length === 0) return
  const ok = await askConfirm({
    title: '批量删除包材主档',
    message: `确认将已选的 ${ids.length} 个包材主档移入回收站？`,
    confirmText: '移入回收站',
    cancelText: '取消',
    danger: true
  })
  if (!ok) return
  try {
    await batchDeletePackagingModels(ids)
    notify(`已将 ${ids.length} 个包材主档移入回收站`, 'success')
    batchSelection.clearSelection()
    batchMode.value = false
    await loadModels()
  } catch (error) {
    notify.errorWithDetail('批量删除包材失败', error)
  }
}

// ===================== 状态：列表筛选与排序 =====================
const listFilter = ref('all')
const listSort = ref('sortOrder-asc')
const filters = reactive({
  keyword: '',
  packagingKind: '',
  material: '',
  status: ''
})
const filterOptions = reactive({
  material: [] as string[],
  packagingKind: [] as string[],
  status: [] as string[]
})

// ===================== 状态：拖拽上传 =====================
const uploadingModelId = ref<number | null>(null)

// ===================== 组合式函数 =====================
const { uploadAndRegister } = useRegisteredFileUpload({ notify })
const fileAssetActions = useFileAssetActions({ notify, notifySuccess: false })

// ===================== 工具栏与列表配置 =====================
const SORT_OPTIONS: SortOption[] = [
  { value: 'sortOrder-asc', label: '默认排序' },
  { value: 'name-asc', label: '名称 A-Z' },
  { value: 'variants-desc', label: '规格最多' },
  { value: 'assets-desc', label: '素材最多' },
  { value: 'issues-desc', label: '问题最多' }
]

const toolbarFields = computed<ToolbarField[]>(() => [
  { key: 'packagingKind', label: '类型', options: filterOptions.packagingKind },
  { key: 'material', label: '材质', options: filterOptions.material },
  { key: 'status', label: '状态', options: filterOptions.status }
])

const toolbarFieldValues = computed(() => ({
  packagingKind: filters.packagingKind,
  material: filters.material,
  status: filters.status
}))

const rowConfig: MasterRowConfig = {
  thumbnailUrl: packagingSummaryImageUrl,
  thumbnailFallback: m => (uploadingModelId.value === m.id ? '...' : rowInitial(m)),
  thumbnailDrop: {
    label: m => (m.defaultImage ? '换图' : '上传'),
    onDrop: handleSeriesThumbDrop
  },
  title: m => m.name || '',
  titleTag: () => null,
  metaLine: m => [[kindLabel(m.packagingKind), m.material, m.capacityMl].filter(Boolean).join(' / ') || '未分类'],
  countsLine: m => `${m.variantCount ?? 0} 规格 · ${m.assetCount ?? 0} 素材`,
  badges: m => [
    m.status || null,
    m.customizable ? '可定制' : null
  ].filter(Boolean),
  rowClass: m => (m.assetCount === 0 || m.variantCount === 0 ? 'incomplete' : ''),
  preview: false,
  websiteEligibility: m => ({
    eligible: m.status === '在售',
    reason: m.status === '在售' ? '发布条件已满足' : '状态需调整为“在售”'
  }),
  websiteToggle: togglePackagingWebsite
}

async function togglePackagingWebsite(item: PackagingModelSummary) {
  const previous = item.websiteEnabled === true
  if (!previous && item.status !== '在售') {
    notify('请先将包材状态调整为“在售”，再发布到官网', 'warning')
    return
  }
  const nextEnabled = !previous
  const confirmed = await askConfirm({
    title: nextEnabled ? '确认发布包材' : '确认取消官网展示',
    message: nextEnabled
      ? `「${item.name}」将出现在官网包材内容中。后台规格、素材和关联模具仍以当前档案为准。`
      : `「${item.name}」将从官网隐藏，但后台档案、规格和关联关系不会删除。`,
    confirmText: nextEnabled ? '确认发布' : '取消展示',
    cancelText: '返回检查',
    danger: !nextEnabled
  })
  if (!confirmed) return
  item.websiteEnabled = !previous
  try {
    await batchUpdatePackagingModels([item.id], { websiteEnabled: item.websiteEnabled })
    notify(`${item.name} 已${item.websiteEnabled ? '发布到官网' : '取消官网发布'}`, 'success')
    detailRefreshKey.value += 1
  } catch (error) {
    item.websiteEnabled = previous
    notify.errorWithDetail('更新包材官网发布状态失败', error)
  }
}

// ===================== 工具函数 =====================
function rowInitial(item: PackagingModelSummary) {
  return (kindLabel(item.packagingKind) || item.name || '包').slice(0, 1)
}

function packagingSummaryImageUrl(item: PackagingModelSummary) {
  return fileAssetThumbnailUrlByOptions({
    ownerType: FILE_OWNER_TYPE_KEYS.packagingModel,
    assetId: item.defaultImageAssetId,
    category: PACKAGING_ASSET_CATEGORY_KEYS.mainImage,
    fileName: item.defaultImage || '',
    modelId: item.id
  })
}

function escapeCsv(value: unknown) {
  const text = String(value ?? '')
  return `"${text.replace(/"/g, '""')}"`
}

// ===================== 计算属性：列表筛选与排序 =====================
const listFilters = computed(() => [
  { key: 'all', label: '全部', count: models.value.length },
  { key: 'missing-assets', label: '缺素材', count: models.value.filter(item => item.assetCount === 0).length },
  { key: 'missing-variants', label: '缺规格', count: models.value.filter(item => item.variantCount === 0).length },
  { key: 'customizable', label: '可定制', count: models.value.filter(item => item.customizable).length }
])

function filterModelsByListFilter(rows: PackagingModelSummary[]) {
  switch (listFilter.value) {
    case 'missing-assets':
      return rows.filter(item => item.assetCount === 0)
    case 'missing-variants':
      return rows.filter(item => item.variantCount === 0)
    case 'customizable':
      return rows.filter(item => item.customizable)
    default:
      return rows
  }
}

function sortModels(rows: PackagingModelSummary[]) {
  const sorted = [...rows]
  sorted.sort((a, b) => {
    if (listSort.value === 'name-asc') return a.name.localeCompare(b.name, 'zh-Hans-CN')
    if (listSort.value === 'variants-desc') return b.variantCount - a.variantCount
    if (listSort.value === 'assets-desc') return b.assetCount - a.assetCount
    return (a.sortOrder || 0) - (b.sortOrder || 0)
  })
  return sorted
}

const visibleModels = computed(() => sortModels(filterModelsByListFilter(models.value)))

// ===================== 操作：数据加载与刷新 =====================
async function loadModels() {
  loading.value = true
  try {
    const res = await fetchPackagingModels({
      ...filters,
      ...pagination.getPageParams(),
      sort: 'sortOrder,asc'
    })
    models.value = res.content || []
    pagination.updateFromResponse(res)
    if (!models.value.some(item => item.id === selectedModelId.value)) {
      selectedModelId.value = models.value[0]?.id ?? null
    }
  } finally {
    loading.value = false
  }
}

async function loadFilters() {
  const data = await fetchPackagingFilters()
  filterOptions.material = data.material || []
  filterOptions.packagingKind = data.packagingKind || []
  filterOptions.status = data.status || []
}

async function refreshAllData() {
  await Promise.all([loadModels(), loadFilters()])
}

// ===================== 操作：列表筛选与分页 =====================
function resetAndLoad() {
  pagination.resetPage()
  listFilter.value = 'all'
  loadModels()
}

function handleSearch(value: string) {
  filters.keyword = value
  resetAndLoad()
}

function handleToolbarFilter(field: string, value: unknown) {
  if (field in filters) {
    filters[field as keyof typeof filters] = value as string
  }
  resetAndLoad()
}

function handleSortChange(value: string) {
  listSort.value = value
}

function handleListFilterChange(key: string) {
  listFilter.value = key
}

function clearFilters() {
  filters.keyword = ''
  filters.packagingKind = ''
  filters.material = ''
  filters.status = ''
  resetAndLoad()
}

function selectModel(id: number | string) {
  selectedModelId.value = Number(id)
  focusedVariantId.value = null
}

// ===================== 操作：编辑器与详情 =====================
function openEditor(modelId?: number) {
  editingModelId.value = modelId ?? null
  editorVisible.value = true
}

async function handleSaved(id?: number) {
  editorVisible.value = false
  await refreshAllData()
  if (id) selectedModelId.value = id
}

async function handleDeleted() {
  selectedModelId.value = null
  await refreshAllData()
}

async function refreshCurrent() {
  await loadModels()
}

// ===================== 操作：拖拽上传主图 =====================
async function handleSeriesThumbDrop(item: PackagingModelSummary, event: DragEvent) {
  const file = Array.from(event.dataTransfer?.files || []).find(candidate => candidate.type.startsWith('image/'))
  if (!file || uploadingModelId.value) return

  uploadingModelId.value = item.id
  selectedModelId.value = item.id
  try {
    const registered = await uploadAndRegister(PACKAGING_ASSET_CATEGORY_KEYS.mainImage, file, {
      modelId: item.id,
      accept: 'image/*',
      register: uploaded =>
        addPackagingAsset(item.id, PACKAGING_ASSET_CATEGORY_KEYS.mainImage, uploaded.fileName, '', null)
    })
    const asset = registered?.asset
    if (!asset) return
    if (asset?.id) await fileAssetActions.setDefault(FILE_OWNER_TYPE_KEYS.packagingModel, asset.id)

    await loadModels()
    detailRefreshKey.value += 1
    notify('系列主图已上传', 'success')
  } finally {
    uploadingModelId.value = null
  }
}

function exportVisibleModels() {
  const headers = ['名称', '类型', '材质', '容量', '规格数', '素材数', '状态', '可定制']
  const rows = visibleModels.value.map(item => [
    item.name,
    kindLabel(item.packagingKind),
    item.material || '',
    item.capacityMl || '',
    item.variantCount,
    item.assetCount,
    item.status || '',
    item.customizable ? '是' : '否'
  ])
  const csv = [headers, ...rows].map(row => row.map(escapeCsv).join(',')).join('\n')
  const blob = new Blob([`\uFEFF${csv}`], { type: 'text/csv;charset=utf-8' })
  downloadBlob(blob, `包材列表-${todayIso()}.csv`)
}

// ===================== 生命周期 =====================
filters.keyword = typeof route.query.keyword === 'string' ? route.query.keyword : ''
await Promise.all([loadModels(), loadFilters()])
const requestedId = Number(route.query.id)
if (Number.isFinite(requestedId) && models.value.some(item => item.id === requestedId)) selectedModelId.value = requestedId
</script>

<style scoped>
/* 包材页：仅保留页面级定制，通用主从布局见 master-layout.css（index.css 全局导入） */
.packaging-view {
  --master-list-col: minmax(300px, 360px);
}
</style>
