<template>
  <MasterLayout
    title="产品管理"
    description="维护产品档案、技术资料、定价与营销资产"
    :show-header="false"
    :is-workbench="effectiveCatalogDisplayMode === 'workbench'"
    page-class="product-catalog-view"
    :global-loading="globalLoading"
    :loading-text="globalLoadingText"
  >
    <template #toolbar>
      <SearchToolbar
        v-model:keyword="query.keyword"
        search-placeholder="搜索产品名称、分类..."
        search-mode="debounce"
        :fields="toolbarFields"
        :field-values="{
          category: query.category || '',
          status: query.status || ''
        }"
        :view-mode="catalogDisplayMode"
        :view-mode-options="catalogViewModeOptions"
        :view-toggle-visible="!isCompactViewport"
        batch-enabled
        :batch-mode="batchMode"
        :selected-count="selectedModelIds.size"
        :models-count="models.length"
        :is-all-selected="isAllModelsSelected"
        @search="handleSearch"
        @filter-change="handleToolbarFilter"
        @clear="handleClearFilters"
        @update:view-mode="setCatalogDisplayMode"
        @toggle-batch-mode="toggleBatchMode"
        @toggle-select-all="toggleSelectAllModels"
        @batch-edit="openBatchEditModal"
        @batch-remove="batchRemoveModels"
      >
        <template #actions>
          <el-button link @click="downloadTemplate">下载模板</el-button>
          <el-button v-if="canManageProduct" link :disabled="exporting" @click="handleExportExcel">
            {{ exporting ? '导出中' : '导出' }}
          </el-button>
          <el-button v-if="canManageProduct" link @click="importFileInput?.click()">
            导入 Excel
            <input ref="importFileInput" type="file" accept=".xlsx,.xls" @change="onCatalogImportChange" />
          </el-button>
          <el-button v-if="canManageProduct" type="primary" @click="openNewModelEditor">新建产品</el-button>
        </template>
      </SearchToolbar>
    </template>

    <template #list>
      <MasterListPanel
        :models="models"
        :selected-id="selectedId"
        :batch-mode="batchMode"
        :selected-model-ids="selectedModelIds"
        :loading="loading"
        :exporting="exporting"
        :layout="effectiveCatalogDisplayMode"
        :pagination="pagination"
        :list-sort="listSort"
        :list-filters="listFilters"
        :current-filter="listFilter"
        :broken-images="brokenImages"
        @select="openProductDrawer"
        @toggle-select="toggleModelSelect"
        @preview-image="previewModelImage"
        @pagination-change="loadModels(pageInfo.page, true)"
        @sort-change="handleSortChange"
        @filter-change="handleListFilterChange"
        @reorder-models="handleReorderModels"
        @image-error="markImageBroken"
      />
    </template>

    <template #detail>
      <!-- ===== 产品详情：卡片浏览时为抽屉，维护工作台时为右侧面板 ===== -->
      <ProductDetailPanel
        v-model:active-tab="activeTab"
        :detail="detail ?? undefined"
        :display-mode="effectiveCatalogDisplayMode"
        :drawer-visible="detailDrawerVisible"
        :detail-suspended="detailSuspended"
        :selecting-model="selectingModel"
        :subtitle="detailDrawerSubtitle"
        :subtitle-parts="detailDrawerSubtitleParts"
        :show-model-tag="showDrawerModelTag"
        @close-drawer="closeDetailDrawer"
        @open-model-editor="handleOpenModelEditor"
        @toggle-title-menu="toggleTitleMenu"
      >
        <CatalogOverviewTab
          v-if="activeTab === 'overview'"
          key="overview"
          class="drawer-tab-panel overview-tab-host"
          :model="detail?.model"
          :assets="groupedAssets"
          :health="archiveHealth"
          :manual-state="overviewManualState"
          :show-guide="showNewProductGuide"
          :model-name="detail?.model?.name || ''"
          :model-tag="overviewModelTag"
          :subtitle-parts="detailDrawerSubtitleParts"
          @preview-asset="previewCatalogAsset"
          @reorder-images="handleReorderMainImages"
          @drop-images="handleDropMainImages"
          @run-action="runHealthAction"
          @guide-action="handleGuideAction"
        />
        <section v-else-if="activeTab === 'variants'" key="variants" class="drawer-tab-panel drawer-tab-panel--scroll">
          <ProductMachineConfigurationTab
            v-if="detail?.model?.id"
            :model-id="detail.model.id"
            :model-name="detail.model.name"
            @changed="refreshCurrentModel('variants')"
          />
        </section>
        <section v-else-if="activeTab === 'assets'" key="assets" class="drawer-tab-panel drawer-tab-panel--scroll">
          <CatalogAssetGallery
            :assets="groupedAssets"
            :categories="ASSET_CATEGORIES"
            :active-filter="assetFilter"
            :upload-category="assetUploadCategory"
            :uploading="assetUploadUploading"
            :batch-mode="assetBatchMode"
            :selected-ids="selectedAssetIds"
            :highlighted-id="highlightedAssetId"
            :model-id="selectedId"
            :exporting-zip="exportingZip"
            :modal-suspended="detailSuspended"
            @filter-change="selectAssetFilter"
            @category-change="assetUploadCategory = $event"
            @upload="uploadAsset"
            @preview="previewCatalogAsset"
            @set-default="setAssetDefault"
            @move-asset="openMoveAssetModal"
            @save-tags="saveAssetTags"
            @batch-save-tags="batchSaveAssetTags"
            @delete-asset="removeAsset"
            @toggle-asset-select="toggleAssetSelect"
            @toggle-select-all="toggleSelectAllAssets"
            @clear-selection="selectedAssetIds.clear()"
            @batch-move-assets="openBatchMoveAssetModal"
            @batch-delete-assets="batchRemoveAssets"
            @toggle-batch-mode="toggleAssetBatchMode"
            @drag-upload="handleAssetDragUpload"
            @paste-upload="handleAssetPasteUpload"
            @reorder-assets="handleReorderAssets"
            @export-zip="onExportAssetsZip"
            @cover-changed="onAssetCoverChanged"
            @website-visibility-changed="onWebsiteVisibilityChanged"
          />
        </section>
      </ProductDetailPanel>
    </template>

    <template #empty>
      <section
        v-if="effectiveCatalogDisplayMode === 'workbench' && !detail && !loading"
        class="catalog-workspace-empty"
      >
        <strong>{{ models.length ? '请选择一个主产品' : '当前筛选没有产品' }}</strong>
        <span>{{
          models.length ? '从左侧列表选择产品后，在这里维护主产品档案、素材和说明书' : '调整上方搜索或筛选条件后继续'
        }}</span>
      </section>
    </template>

    <template #modals>
      <Teleport to="body">
        <div
          v-if="titleMenuOpen"
          class="action-menu-popover action-menu-popover--floating"
          :style="titleMenuStyle"
          @click.stop
        >
          <button type="button" class="danger" @click="handleTitleMenuRemove">删除产品</button>
        </div>
      </Teleport>

      <!-- 主产品编辑器 -->
      <ProductEditorModal
        v-if="modelEditorVisible"
        :visible="modelEditorVisible"
        :mode="modelEditorMode"
        :form="modelEditorForm"
        :dirty="modelEditorDirty"
        :image-uploading="modelEditorImageUploading"
        :manual-uploading="modelEditorManualUploading"
        :validation-errors="modelEditorValidationErrors"
        :main-image-assets="mainImageAssets"
        :model-id="selectedId"
        @close="closeModelEditor"
        @preview-asset="previewCatalogAsset"
        @upload-image="uploadModelImage"
        @upload-manual="uploadModelManual"
        @update:dirty="modelEditorDirty = $event"
        @field-change="handleModelFieldChange"
        @choose-image="handleChooseModelImage"
        @submit="saveModel"
      />

      <!-- 批量编辑 -->
      <BatchEditModal
        :visible="batchEditVisible"
        :selected-count="selectedModelIds.size"
        :field-groups="batchEditFieldGroups"
        @close="batchEditVisible = false"
        @apply="doBatchEditModels"
      />

      <!-- 移动素材 -->
      <MoveAssetModal
        :visible="moveAssetVisible"
        title="移动素材或调整分类"
        subtitle="整理素材归属"
        :asset-name="moveAssetAssetFileName"
        :categories="ASSET_CATEGORIES"
        :models="moveAssetModelOptions"
        @close="moveAssetVisible = false"
        @confirm="doMoveAssetSubmit"
      />

      <!-- 批量移动素材 -->
      <MoveAssetModal
        :visible="batchMoveAssetVisible"
        :title="`移动或重新归类${selectedAssetIds.size} 个素材`"
        subtitle="批量整理素材"
        size="sm"
        :categories="ASSET_CATEGORIES"
        :models="batchMoveAssetModelOptions"
        show-keep-current-option
        @close="batchMoveAssetVisible = false"
        @confirm="doBatchMoveAssetSubmit"
      />

    </template>

    <template #loading-extra>
      <template v-if="batchProgress.total > 0">
        <!-- C21 LqProgress 推广：替代手写 .batch-progress-bar + .batch-progress-fill -->
        <LqProgress
          :percentage="batchProgress.percent"
          :show-text="false"
          :stroke-width="8"
          striped
          striped-flow
          aria-label="批量操作进度"
        />
        <small>{{ batchProgress.current }} / {{ batchProgress.total }}</small>
      </template>
    </template>
  </MasterLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

// 组件导入
import LqProgress from '@/components/common/LqProgress.vue'
import ProductEditorModal from '@/components/product/ProductEditorModal.vue'
import MasterLayout from '@/components/master/MasterLayout.vue'
import SearchToolbar from '@/components/common/SearchToolbar.vue'
import MasterListPanel from '@/components/master/MasterListPanel.vue'

// API 和工具
import { fetchCatalogFilterOptions, reorderCatalogModels } from '@/api/catalog'
import { MODEL_FIELD_GROUPS, ASSET_CATEGORIES, ASSET_CATEGORY_KEYS } from '@/constants/fields'
import { downloadTemplate } from '@/utils/export/exportTemplate'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'
import { notify } from '@/composables/ui/useToast'
import { askConfirm, confirmDialog, resolveConfirm } from '@/composables/ui/useConfirmDialog'
import { useCatalogEditors } from '@/composables/catalog/useCatalogEditors'
import { useCatalogModels } from '@/composables/catalog/useCatalogModels'
import { useCatalogFilters } from '@/composables/catalog/useCatalogFilters'
import { useListPagination } from '@/composables/list/useListPagination'
import { useBatchSelection } from '@/composables/list/useBatchSelection'
import { useBatchActions } from '@/composables/list/useBatchActions'
import { useDialogStack } from '@/composables/ui/useDialogStack'
import { useCatalogMoveOperations } from '@/composables/catalog/useCatalogMoveOperations'
import { useTitleMenu } from '@/composables/catalog/useTitleMenu'
import { useProductFileAssets } from '@/composables/catalog/useProductFileAssets'
import { useCatalogImportExport } from '@/composables/catalog/useCatalogImportExport'
import { useCatalogDisplayMode } from '@/composables/catalog/useCatalogDisplayMode'
import { useCatalogAssetOperations } from '@/composables/catalog/useCatalogAssetOperations'
import { useCatalogEditorOps } from '@/composables/catalog/useCatalogEditorOps'
import { useCatalogBatchOps } from '@/composables/catalog/useCatalogBatchOps'
import { usePreviewAsset, useImported } from '@/composables/ui/useAppBridge'
import { useCatalogGuide } from '@/composables/catalog/useCatalogGuide'
import { useCatalogFileUpload } from '@/composables/upload/useCatalogFileUpload'
import { ARCHIVE_CHECKS, buildArchiveChecks, computeListHealthSummary } from '@/composables/catalog/useArchiveHealth'
import CatalogOverviewTab from '@/components/product/CatalogOverviewTab.vue'
import CatalogAssetGallery from '@/components/product/CatalogAssetGallery.vue'
import BatchEditModal from '@/components/product/BatchEditModal.vue'
import MoveAssetModal from '@/components/product/MoveAssetModal.vue'
import ProductDetailPanel from '@/components/product/ProductDetailPanel.vue'
import ProductMachineConfigurationTab from '@/components/product/ProductMachineConfigurationTab.vue'
import { displayModelTag } from '@/utils/catalog/productDisplay'

const { can } = useCurrentUser()
/** 是否可维护产品档案（新建/编辑/导入/导出/批量），由 product.manage 权限控制 */
const canManageProduct = computed(() => can('product.manage'))

const emit = defineEmits([])

const openAssetPreview = usePreviewAsset()
const onImported = useImported()

// 文件上传逻辑
const catalogFileUpload = useCatalogFileUpload({
  notify,
  getDetail: () => detail.value,
  getSelectedId: () => selectedId.value,
  refreshCurrentModel: async tab => await refreshCurrentModel(tab),
  emit: () => onImported()
})
const { assetUploadCategory, assetUploadUploading, processUploadFiles, uploadAsset } = catalogFileUpload

// ========== 状态：使用 Composables 管理 ==========
// 筛选与搜索
const catalogFilters = useCatalogFilters()
const route = useRoute()
const { query, filterOptions, listSort, listFilter, setListFilter } = catalogFilters

const toolbarFields = computed(() => [
  { key: 'category', label: '分类', options: filterOptions.categories },
  { key: 'status', label: '状态', options: filterOptions.statuses }
])

// 分页
const pagination = useListPagination({ size: 50 })
const { pageInfo } = pagination

// 批量选择：主产品 / 素材各自独立
const batchSelection = useBatchSelection<number>()
const { batchMode, selectedIds: selectedModelIds } = batchSelection
const assetBatchSelection = useBatchSelection<number>()
const { batchMode: assetBatchMode, selectedIds: selectedAssetIds } = assetBatchSelection

// 批量操作：全局 Loading
const batchActions = useBatchActions()
const { globalLoading, globalLoadingText } = batchActions

// 弹窗统一管理
const modals = useDialogStack()
const { anyOpenExcept: anyModalOpenExcept, closeTopmost: closeTopmostModal } = modals

// 批量操作进度
const batchProgress = reactive({ current: 0, total: 0, percent: 0 })
async function runBatchWithProgress<T>(items: T[], text: string, asyncFn: (item: T) => Promise<void>) {
  batchProgress.total = items.length
  batchProgress.current = 0
  batchProgress.percent = 0
  globalLoading.value = true
  try {
    for (let i = 0; i < items.length; i++) {
      await asyncFn(items[i])
      batchProgress.current = i + 1
      batchProgress.percent = Math.round(((i + 1) / items.length) * 100)
      globalLoadingText.value = `${text} (${batchProgress.current}/${batchProgress.total})`
    }
  } finally {
    globalLoading.value = false
    batchProgress.total = 0
  }
}

// 模型列表和详情
const catalogModels = useCatalogModels()
const { models, detail, selectedId, loading, selectingModel } = catalogModels
const { getCachedHealth } = catalogModels

// 标题菜单状态
const titleMenu = useTitleMenu()
const { titleMenuOpen, titleMenuStyle, closeTitleMenu, toggleTitleMenu } = titleMenu

watch(selectedId, () => {
  resetScopedBatchModes()
})

// 导入导出
const importExport = useCatalogImportExport({
  query,
  notify,
  loadModels,
  globalLoading,
  globalLoadingText
})
const { exporting, exportingZip, handleExportExcel, handleExportAssetsZip, handleCatalogImport } = importExport

function onCatalogImport(event: Event) {
  const input = event.target as HTMLInputElement | null
  handleCatalogImport(
    { target: { files: input?.files ?? undefined, value: input?.value ?? '' } },
    askConfirm,
    _result => {
      onImported()
      selectedId.value = null
      detail.value = null
      detailDrawerVisible.value = false
    }
  )
}

function onCatalogImportChange(event: Event) {
  onCatalogImport(event)
  const target = event.target as HTMLInputElement
  if (target) target.value = ''
}

function onExportAssetsZip() {
  handleExportAssetsZip(selectedId.value, detail.value?.model?.name)
}

function onAssetCoverChanged(_assetId: number) {
  // 封面变更后刷新整个模型详情（含素材列表），以便拿到最新的 coverFileName
  refreshCurrentModel('assets')
}

/**
 * 素材官网可见性变更后，CatalogAssetGallery 内部已乐观更新本地状态，
 * 此处仅触发一次轻量刷新以同步详情面板的统计信息（如完整度分可能变化）。
 */
function onWebsiteVisibilityChanged() {
  refreshCurrentModel('assets')
}

// 列表筛选标签：按待办类型分组统计
const listFilters = computed(() => {
  const todoDefinitions = ARCHIVE_CHECKS.filter(item => item.listFilter).map(item => ({
    key: item.key,
    issueLabel: item.issueLabel
  }))
  const summary = computeListHealthSummary(models.value, getCachedHealth, todoDefinitions)
  return summary.filters
})

// 当前详情模型的档案检查项
const archiveHealth = computed(() => {
  if (!detail.value?.model) return { items: [] }
  const items = buildArchiveChecks(detail.value.model, {
    assets: detail.value.assets || [],
    assetCount: detail.value.assetCount || 0
  })
  return { items }
})

// 编辑器状态
const editors = useCatalogEditors()
const {
  modelEditorVisible,
  modelEditorMode,
  modelEditorForm,
  modelEditorImageUploading,
  modelEditorManualUploading,
  modelEditorDirty,
  modelEditorValidationErrors,
  openNewModelEditor,
  openModelEditor: openModelEditorForm,
  handleModelFieldChange,
  handleChooseModelImage
} = editors

// ========== 视图特有状态=========
const activeTab = ref('assets')
const brokenImages = reactive(new Set<number>())
const showNewProductGuide = ref(false)

// 视图模式与抽屉
const displayMode = useCatalogDisplayMode({
  selectedId,
  detail,
  models,
  activeTab,
  selectModel
})
const {
  catalogDisplayMode,
  isCompactViewport,
  effectiveCatalogDisplayMode,
  detailDrawerVisible,
  resumeDetailAfterModelEdit,
  syncViewportMode,
  setCatalogDisplayMode,
  closeDetailDrawer,
  openProductDrawer,
  detailDrawerSubtitleParts,
  detailDrawerSubtitle,
  showDrawerModelTag
} = displayMode

// 编辑器操作
const editorOps = useCatalogEditorOps({
  editors,
  modelEditorVisible,
  modelEditorDirty,
  modelEditorMode,
  modelEditorForm,
  modelEditorImageUploading,
  modelEditorManualUploading,
  modelEditorValidationErrors,
  selectedId,
  detail,
  models,
  activeTab,
  effectiveCatalogDisplayMode,
  detailDrawerVisible,
  resumeDetailAfterModelEdit,
  showNewProductGuide,
  loadModels,
  selectModel,
  processUploadFiles,
  closeTitleMenu,
  openModelEditorForm
})
const {
  closeModelEditor,
  openModelEditor,
  handleOpenModelEditor,
  saveModel,
  removeModel,
  uploadModelImage,
  uploadModelManual
} = editorOps

// 产品详情与主操作弹窗互斥，避免弹窗层级互相遮挡
const detailSuspended = computed(() => anyModalOpenExcept('detailDrawer') || confirmDialog.visible)

// ========== 计算属性=========

const overviewManualState = computed(() => {
  return { label: '缺少附件', level: 'missing' }
})

const overviewModelTag = computed(() => {
  const tag = String(displayModelTag(detail?.value?.model) || '').trim()
  const name = String(detail?.value?.model?.name || '').trim()
  if (tag && !name.toLocaleLowerCase().startsWith(tag.toLocaleLowerCase())) {
    return tag
  }
  return ''
})

const groupedAssets = computed(() => {
  if (!detail.value) return []
  return (detail.value.assets || []).filter(asset => asset.fileName)
})

const mainImageAssets = computed(() =>
  groupedAssets.value.filter(asset => asset.category === ASSET_CATEGORY_KEYS.mainImage)
)

// 文件资产管理
const productFileAssets = useProductFileAssets({
  selectedId,
  groupedAssets,
  selectedAssetIds,
  notify,
  refreshCurrentModel
})
const {
  productModelFileAssets,
  fileAssetActions,
  findProductModelFileHandle,
  fileOwnerTypeForProductAsset,
  selectedProductAssetGroups,
  filePreviewUrlForProductAsset
} = productFileAssets

// 素材操作
const assetOps = useCatalogAssetOperations({
  detail,
  selectedId,
  activeTab,
  assetBatchSelection,
  fileOwnerTypeForProductAsset,
  findProductModelFileHandle,
  productModelFileAssets,
  fileAssetActions,
  filePreviewUrlForProductAsset,
  selectedProductAssetGroups,
  processUploadFiles,
  refreshCurrentModel,
  emitPreviewAsset: asset => openAssetPreview(asset),
  assetUploadCategory
})
const {
  assetFilter,
  highlightedAssetId,
  highlightedAssetTimer,
  assetMenuId,
  setAssetDefault,
  saveAssetTags,
  batchSaveAssetTags,
  removeAsset,
  toggleAssetBatchMode,
  toggleAssetSelect,
  toggleSelectAllAssets,
  batchRemoveAssets,
  openAssetTask,
  handleReorderMainImages,
  handleReorderAssets,
  handleDropMainImages,
  handleAssetDragUpload,
  handleAssetPasteUpload,
  previewCatalogAsset,
  selectAssetFilter
} = assetOps

// 请求去重已由 composable 管理

async function refreshCurrentModel(tab = activeTab.value) {
  if (!selectedId.value) return
  await selectModel(selectedId.value, { preserveTab: true, nextTab: tab })
  await loadModels(pageInfo.page)
}

const batchEditFieldGroups = computed(() => {
  return MODEL_FIELD_GROUPS.map(group => ({
    title: group.title,
    fields: group.fields.filter(field => !['name', 'defaultImage'].includes(field.key))
  })).filter(group => group.fields.length > 0)
})

const isAllModelsSelected = computed(() => batchSelection.isAllSelected.value(models.value))

function handleTitleMenuRemove() {
  closeTitleMenu()
  removeModel()
}

// ========== 方法 ==========
// 工具栏筛选逻辑（SearchToolbar 事件处理）
const catalogViewModeOptions = [
  { value: 'workbench', label: '列表' },
  { value: 'browse', label: '卡片' }
]

const importFileInput = ref<HTMLInputElement | null>(null)

function handleToolbarFilter(field: string, value: unknown) {
  const q = query
  handleFilterChange({
    category: field === 'category' ? String(value ?? '') : q.category || '',
    status: field === 'status' ? String(value ?? '') : q.status || ''
  })
}

function handleSearch(keyword: string) {
  query.keyword = keyword
  loadModels(0)
}

function handleFilterChange(newFilters: Record<string, string>) {
  catalogFilters.updateFilters(newFilters)
  loadModels(0)
}

function handleClearFilters() {
  catalogFilters.clearFilters()
  loadModels(0)
}

function handleSortChange(sort: string) {
  catalogFilters.setListSort(sort)
  catalogFilters.saveListSort()
  loadModels(0)
}

function handleListFilterChange(filterKey: string) {
  if (listFilter.value === filterKey) {
    setListFilter('')
  } else {
    setListFilter(filterKey)
  }
  loadModels(0)
}

async function handleReorderModels(orderedIds: (number | string)[]) {
  const ids = orderedIds.map(Number)
  if (!Array.isArray(ids) || ids.length === 0) return
  const orderMap = new Map<number, number>(ids.map((id, index) => [id, index]))
  models.value = [...models.value].sort((a, b) => {
    const ai = orderMap.get(a.id) ?? Number.MAX_SAFE_INTEGER
    const bi = orderMap.get(b.id) ?? Number.MAX_SAFE_INTEGER
    return ai - bi
  })
  try {
    const pageOffset = (pageInfo.page || 0) * (pageInfo.size || ids.length)
    const items = ids.map((id, index) => ({ id, sortOrder: pageOffset + index + 1 }))
    await reorderCatalogModels(items)
    notify('产品排序已保存')
  } catch (err) {
    notify.errorWithDetail('保存产品排序失败', err)
    await loadModels(pageInfo.page, true)
  }
}

async function loadModels(nextPage = pageInfo.page, isPageChange = false) {
  await catalogModels.loadModels(
    () => catalogFilters.getQueryParams(),
    () => catalogFilters.getSortParams(),
    pageInfo,
    nextPage,
    { isPageChange }
  )
  // 仅初始加载时自动预加载第一个产品数据
  if (!isPageChange && !selectedId.value && models.value.length) {
    await selectModel(models.value[0].id, { nextTab: 'assets' })
  }
}

async function selectModel(id: number, options: { preserveTab?: boolean; nextTab?: string } = {}) {
  const { preserveTab = false, nextTab = 'assets' } = options
  await catalogModels.selectModel(id, options)
  if (!preserveTab) activeTab.value = nextTab
  titleMenuOpen.value = false
  assetMenuId.value = null
  selectedAssetIds.clear()
}

// 批量管理 —— 委托给 composable
function toggleBatchMode() {
  batchSelection.toggleBatchMode()
}
function toggleModelSelect(id: number | string) {
  batchSelection.toggleSelection(Number(id))
}
function toggleSelectAllModels() {
  batchSelection.toggleSelectAll(models.value)
}

function resetScopedBatchModes() {
  assetBatchMode.value = false
  selectedAssetIds.clear()
}

// 批量操作
const { batchEditVisible, openBatchEditModal, batchRemoveModels, doBatchEditModels } = useCatalogBatchOps({
  selectedModelIds,
  batchMode,
  selectedId,
  detail,
  detailDrawerVisible,
  pageInfo,
  loadModels
})

function previewModelImage(model: {
  id: number | string
  defaultImage?: string | null
  defaultImageAssetId?: number | null
}) {
  if (!model.defaultImage) return
  previewCatalogAsset({
    category: ASSET_CATEGORY_KEYS.mainImage,
    fileName: model.defaultImage,
    modelId: Number(model.id),
    ownerType: model.defaultImageAssetId ? 'product_model' : undefined,
    assetId: model.defaultImageAssetId || undefined
  })
}

function markImageBroken(id: number | string) {
  brokenImages.add(Number(id))
}

watch(activeTab, tab => {
  if (tab !== 'assets') {
    assetBatchMode.value = false
    selectedAssetIds.clear()
  }
})

// 移动操作：当前主流程只暴露素材移动
const moveOps = useCatalogMoveOperations({
  getSelectedId: () => selectedId.value ?? undefined,
  getDetail: () => detail.value ?? undefined,
  refreshCurrentModel,
  selectModel,
  runBatchWithProgress,
  getSelectedAssetIds: () => selectedAssetIds,
  clearAssetSelection: () => selectedAssetIds.clear(),
  exitAssetBatchMode: () => {
    assetBatchMode.value = false
  },
  setActiveTab: tab => {
    activeTab.value = tab
  }
})
const {
  moveAssetVisible,
  moveAssetAssetFileName,
  moveAssetModelOptions,
  openMoveAssetModal,
  doMoveAsset,
  batchMoveAssetVisible,
  batchMoveAssetModelOptions,
  openBatchMoveAssetModal,
  doBatchMoveAsset
} = moveOps

async function doMoveAssetSubmit({ targetModelId, targetCategory }: { targetModelId: number; targetCategory: string }) {
  await doMoveAsset({ modelId: targetModelId, category: targetCategory })
}

async function doBatchMoveAssetSubmit({
  targetModelId,
  targetCategory
}: {
  targetModelId: number
  targetCategory: string
}) {
  await doBatchMoveAsset({ modelId: targetModelId, category: targetCategory })
}

// 引导 / 健康动作
const { runHealthAction, handleGuideAction } = useCatalogGuide({
  activeTab,
  showNewProductGuide,
  openAssetTask,
  openModelEditor
})

// ========== 以下主图拖拽已迁移 CatalogOverviewTab ==========

// 生命周期
const loadInitialFilters = async () => {
  try {
    const opts = await fetchCatalogFilterOptions()
    filterOptions.categories = opts.categories || []
    filterOptions.statuses = opts.statuses || []
  } catch (e) {
    console.warn('筛选选项加载失败', e)
  }
}
query.keyword = typeof route.query.keyword === 'string' ? route.query.keyword : ''
await Promise.all([loadModels(0), loadInitialFilters()])
const requestedId = Number(route.query.id)
if (Number.isFinite(requestedId) && models.value.some(model => model.id === requestedId)) await selectModel(requestedId)

onMounted(() => {
  syncViewportMode()
  window.addEventListener('resize', syncViewportMode)
  window.addEventListener('resize', closeTitleMenu)
  window.addEventListener('scroll', closeTitleMenu, true)

  // 注册所有弹窗到统一管理
  // 优先级越高，越先被 Esc 键关闭（上层弹窗优先）
  modals.register(
    'confirmDialog',
    () => confirmDialog.visible,
    () => resolveConfirm(false),
    100
  )
  modals.register('modelEditor', modelEditorVisible, closeModelEditor, 90)
  modals.register(
    'batchEdit',
    batchEditVisible,
    () => {
      batchEditVisible.value = false
    },
    70
  )
  modals.register(
    'moveAsset',
    moveAssetVisible,
    () => {
      moveAssetVisible.value = false
    },
    60
  )
  modals.register(
    'batchMoveAsset',
    batchMoveAssetVisible,
    () => {
      batchMoveAssetVisible.value = false
    },
    55
  )
  modals.register('detailDrawer', detailDrawerVisible, closeDetailDrawer, 10)

  document.addEventListener('keydown', handleEscKey)
})

onUnmounted(() => {
  window.removeEventListener('resize', syncViewportMode)
  window.removeEventListener('resize', closeTitleMenu)
  window.removeEventListener('scroll', closeTitleMenu, true)
  document.removeEventListener('keydown', handleEscKey)
  if (highlightedAssetTimer.value) clearTimeout(highlightedAssetTimer.value)
  editors.cleanupEditors()
})

function handleEscKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    if (titleMenuOpen.value) {
      closeTitleMenu()
      return
    }
    if (closeTopmostModal()) return
  }
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    if (modelEditorVisible.value && modelEditorDirty.value) {
      saveModel()
    }
  }
}
</script>

<style scoped>
/* ===== 产品管理页：主从工作台页面级定制 ===== */

/* 左列宽度：与 master-layout.css 的 --master-list-col 联动 */
.product-catalog-view {
  --master-list-col: minmax(360px, 440px);
}

/* Slot 内容不会被 ProductDetailPanel 的 scoped 样式匹配，
   需要在此处（ProductCatalogView 的 scope）定义 tab panel 滚动行为 */
.drawer-tab-panel {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
}

.drawer-tab-panel--scroll {
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior: contain;
  padding: 16px 20px;
}

.drawer-tab-panel.overview-tab-host {
  padding: 0;
}
</style>
