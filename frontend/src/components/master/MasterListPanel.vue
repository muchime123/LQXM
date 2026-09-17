<template>
  <section
    class="master-list-panel catalog-card-grid"
    :class="{ 'is-workbench': isWorkbenchLayout, 'workbench-list-panel': isWorkbenchLayout }"
  >
    <header class="master-list-panel__header">
      <select
        v-model="localListSort"
        class="master-list-panel__sort list-sort-select"
        aria-label="列表排序"
        @change="onSortChange"
      >
        <option v-for="option in sortOptions" :key="option.value" :value="option.value">
          {{ option.label }}
        </option>
      </select>

      <div v-if="listFilters.length" class="master-list-panel__filters" aria-label="快捷筛选">
        <button
          v-for="filter in listFilters"
          :key="filter.key"
          type="button"
          class="master-list-panel__filter"
          :class="{ 'is-empty': !filter.count, 'is-active': currentFilter === filter.key }"
          @click="emit('filter-change', filter.key)"
        >
          <span>{{ filter.label }}</span>
          <em>{{ filter.count }}</em>
        </button>
      </div>
    </header>

    <MasterCardGrid
      v-if="!isWorkbenchLayout"
      :models="models"
      :loading="loading"
      :selected-id="selectedId"
      :batch-mode="batchMode"
      :selected-model-ids="selectedModelIds"
      :broken-images="brokenImages"
      :can-drag-sort="canDragSort"
      :empty-title="emptyTitle"
      :empty-hint="emptyHint"
      :loading-text="loadingText"
      @select="emit('select', $event)"
      @toggle-select="emit('toggleSelect', $event)"
      @preview-image="emit('previewImage', $event)"
      @image-error="emit('image-error', $event)"
      @reorder-models="emit('reorder-models', $event)"
    />

    <DataState
      v-else
      class="master-list-panel__state"
      :state="panelState"
      :loading-text="loadingText"
      :empty-text="emptyTitle"
      :empty-hint="emptyHint"
      skeleton
      skeleton-variant="list"
    >
      <div :class="['master-list-panel__rows', { 'is-batch-selectable': batchMode }]">
        <button
          v-for="model in models"
          :key="model.id"
          type="button"
          class="master-list-row"
          :class="[
            {
              'is-active': selectedId === model.id,
              'is-selected': batchMode && selectedModelIds.has(model.id),
              'is-dragging': dragSort.isDragging(model),
              'is-drag-over': dragSort.isDragOver(model)
            },
            rowClassOf(model)
          ]"
          v-bind="dragSort.dragHandlers(model)"
          @click="handleModelClick(model.id)"
        >
          <div
            class="master-list-row__thumb catalog-list-thumb"
            :class="{
              'is-drop-target': thumbDragOverId === model.id,
              'drag-active': thumbDragOverId === model.id
            }"
            :title="thumbDrop ? '拖拽图片到这里上传主图' : undefined"
            @click="handleThumbClick(model, $event)"
            @dragenter="onThumbDragEnter(model, $event)"
            @dragover="onThumbDragOver(model, $event)"
            @dragleave="onThumbDragLeave(model)"
            @drop="onThumbDrop(model, $event)"
          >
            <img
              v-if="thumbUrl(model) && !brokenImages.has(model.id)"
              :src="thumbUrl(model) || undefined"
              :alt="titleOf(model)"
              loading="lazy"
              decoding="async"
              @error="emit('image-error', model.id)"
            />
            <i v-else>{{ thumbFallback(model) }}</i>
            <span
              v-if="batchMode"
              class="master-list-row__check"
              :class="{ 'is-checked': selectedModelIds.has(model.id) }"
            >
              <span v-if="selectedModelIds.has(model.id)" aria-hidden="true">✓</span>
            </span>
            <em v-if="thumbDrop" class="master-list-row__drop-label thumb-drop-label">{{ thumbDropLabel(model) }}</em>
          </div>

          <div class="master-list-row__info">
            <strong class="master-list-row__title">
              <span v-if="titleTagOf(model)" class="master-list-row__tag">{{ titleTagOf(model) }}</span>
              <span>{{ titleOf(model) }}</span>
            </strong>
            <div v-if="metaParts(model).length" class="master-list-row__meta" :title="metaTitle(model)">
              <span
                v-for="(part, index) in metaParts(model)"
                v-show="part"
                :key="index"
                :class="{ 'is-emphasis': effectiveRow.emphasisFirstMeta && index === 0 }"
              >{{ part }}</span>
            </div>
            <em v-if="countsOf(model)" class="master-list-row__counts">{{ countsOf(model) }}</em>
            <span
              v-if="badgesOf(model).length || model.websiteEnabled === true"
              class="master-list-row__badges catalog-list-badges"
            >
              <i v-for="(badge, index) in badgesOf(model)" :key="index" v-show="badge">{{ badge }}</i>
              <i
                v-if="row.websiteToggle"
                class="badge-website is-actionable"
                :class="{ 'is-disabled': model.websiteEnabled !== true, 'is-blocked': !websiteEligibility(model).eligible }"
                role="switch"
                tabindex="0"
                :aria-checked="model.websiteEnabled === true"
                :title="websiteEligibility(model).reason || (model.websiteEnabled === true ? '点击取消官网发布' : '点击发布到官网')"
                @click.stop="row.websiteToggle(model)"
                @keydown.enter.stop.prevent="row.websiteToggle(model)"
                @keydown.space.stop.prevent="row.websiteToggle(model)"
              >{{ !websiteEligibility(model).eligible ? '不可发布' : model.websiteEnabled === true ? '已发布' : '待发布' }}</i>
              <i v-else-if="model.websiteEnabled === true" class="badge-website" title="已发布到官网">官网</i>
            </span>
          </div>
        </button>
      </div>
    </DataState>

    <footer v-if="hasMultiplePages" class="master-list-panel__pagination">
      <LqPagination :pagination="pagination" :pager-count="5" @change="emit('paginationChange')" />
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import DataState, { type DataStateStatus } from '@/components/common/DataState.vue'
import LqPagination from '@/components/common/LqPagination.vue'
import MasterCardGrid from '@/components/master/MasterCardGrid.vue'
import { useDragSort } from '@/composables/list/useDragSort'
import type { ListPagination } from '@/composables/list/useListPagination'
import { ASSET_CATEGORY_KEYS } from '@/constants/fields'
import { FILE_OWNER_TYPE_KEYS } from '@/types/fileAsset'
import { displayModelTag, hasDisplayModelTag } from '@/utils/catalog/productDisplay'
import { fileAssetThumbnailUrlByOptions } from '@/utils/ui/fileAssetUrls'

type ModelId = number

interface MasterListModel {
  id: ModelId
  name?: string
  defaultImage?: string | null
  defaultImageAssetId?: number | null
  defaultImageOwnerType?: 'product_model' | 'product_variant' | null
  category?: string | null
  assetCount?: number | null
  variantCount?: number | null
  websiteEnabled?: boolean
}

interface ListFilter {
  key: string
  label: string
  count: number
}

export interface MasterRowConfig {
  thumbnailUrl?: (model: any) => string | null
  thumbnailFallback?: (model: any) => string
  thumbnailDrop?: {
    label: (model: any) => string
    onDrop: (model: any, event: DragEvent) => void
  }
  title?: (model: any) => string
  titleTag?: (model: any) => string | null
  metaLine?: (model: any) => (string | number | null)[]
  /** 首条 meta 行加粗强调显示（如列表主张的联系方式）。 */
  emphasisFirstMeta?: boolean
  countsLine?: (model: any) => string | null
  badges?: (model: any) => (string | null)[]
  rowClass?: (model: any) => string | null
  preview?: boolean
  websiteToggle?: (model: any) => void
  websiteEligibility?: (model: any) => { eligible: boolean; reason?: string }
}

export interface SortOption {
  value: string
  label: string
}

const props = withDefaults(
  defineProps<{
    models?: MasterListModel[]
    pagination: ListPagination
    loading?: boolean
    selectedId?: ModelId | null
    batchMode?: boolean
    selectedModelIds?: Set<ModelId>
    listSort?: string
    listFilters?: ListFilter[]
    currentFilter?: string
    brokenImages?: Set<ModelId>
    layout?: 'browse' | 'workbench' | string
    row?: MasterRowConfig
    sortOptions?: SortOption[]
    emptyTitle?: string
    emptyHint?: string
    loadingText?: string
  }>(),
  {
    models: () => [],
    loading: false,
    selectedId: null,
    batchMode: false,
    selectedModelIds: () => new Set<ModelId>(),
    listSort: 'name-asc',
    listFilters: () => [],
    currentFilter: '',
    brokenImages: () => new Set<ModelId>(),
    layout: 'browse',
    row: () => ({}),
    sortOptions: () => [
      { value: 'manual', label: '手动排序' },
      { value: 'name-asc', label: '名称 A→Z' },
      { value: 'name-desc', label: '名称 Z→A' },
      { value: 'assets-desc', label: '素材最多' }
    ],
    emptyTitle: '暂无数据',
    emptyHint: '点击“新建”添加',
    loadingText: '正在加载...'
  }
)

const emit = defineEmits<{
  select: [id: ModelId]
  toggleSelect: [id: ModelId]
  paginationChange: []
  previewImage: [model: MasterListModel]
  'sort-change': [sort: string]
  'filter-change': [filterKey: string]
  'image-error': [id: ModelId]
  'reorder-models': [ids: ModelId[]]
}>()

const localListSort = ref(props.listSort)
const thumbDragOverId = ref<ModelId | null>(null)
const isWorkbenchLayout = computed(() => props.layout === 'workbench')
const canDragSort = computed(() => localListSort.value === 'manual' && !props.batchMode && !props.loading)
const panelState = computed<DataStateStatus>(() => {
  if (props.loading && props.models.length === 0) return 'loading'
  if (props.models.length === 0) return 'empty'
  return 'ready'
})

/** 列表多于 1 页才显示分页栏（单页/空数据时不占位，避免出现空灰条） */
const hasMultiplePages = computed(() => props.pagination.totalPages.value > 1)

watch(
  () => props.listSort,
  value => {
    localListSort.value = value
  }
)

function defaultThumbnailUrl(model: MasterListModel) {
  return fileAssetThumbnailUrlByOptions({
    ownerType: model.defaultImageOwnerType || FILE_OWNER_TYPE_KEYS.productModel,
    assetId: model.defaultImageAssetId,
    category: ASSET_CATEGORY_KEYS.mainImage,
    fileName: model.defaultImage || '',
    modelId: model.id,
    size: 480
  })
}

const effectiveRow = computed<MasterRowConfig>(() => ({
  thumbnailUrl: defaultThumbnailUrl,
  thumbnailFallback: () => '暂无',
  title: model => String(model?.name ?? ''),
  titleTag: model => (hasDisplayModelTag(model) ? displayModelTag(model) : null),
  metaLine: model => [
    model.category || '其他',
    `${model.assetCount || 0} 素材`
  ],
  countsLine: () => null,
  badges: () => [],
  rowClass: () => null,
  preview: true,
  ...props.row
}))

const thumbDrop = computed(() => effectiveRow.value.thumbnailDrop || null)
const dragSort = useDragSort<MasterListModel>({
  items: () => props.models,
  canDrag: () => canDragSort.value,
  onReorder: ({ orderedIds }) => emit('reorder-models', orderedIds)
})

function onSortChange() {
  emit('sort-change', localListSort.value)
}

function handleModelClick(id: ModelId) {
  if (dragSort.suppressClick.value) return
  if (props.batchMode) emit('toggleSelect', id)
  else emit('select', id)
}

function handleThumbClick(model: MasterListModel, event: MouseEvent) {
  if (props.batchMode) {
    emit('toggleSelect', model.id)
    event.stopPropagation()
  } else if (effectiveRow.value.preview !== false) {
    emit('previewImage', model)
    event.stopPropagation()
  }
}

function thumbUrl(model: MasterListModel) {
  return effectiveRow.value.thumbnailUrl?.(model) || null
}
function thumbFallback(model: MasterListModel) {
  return effectiveRow.value.thumbnailFallback?.(model) || '暂无'
}
function thumbDropLabel(model: MasterListModel) {
  return thumbDrop.value?.label(model) || '上传'
}
function titleOf(model: MasterListModel) {
  return effectiveRow.value.title?.(model) || ''
}
function titleTagOf(model: MasterListModel) {
  return effectiveRow.value.titleTag?.(model) || null
}
function metaParts(model: MasterListModel) {
  return effectiveRow.value.metaLine?.(model) || []
}
function metaTitle(model: MasterListModel) {
  return metaParts(model).filter(Boolean).join(' · ')
}
function countsOf(model: MasterListModel) {
  return effectiveRow.value.countsLine?.(model) || null
}
function badgesOf(model: MasterListModel) {
  return effectiveRow.value.badges?.(model) || []
}
function websiteEligibility(model: MasterListModel) {
  return effectiveRow.value.websiteEligibility?.(model) ?? { eligible: true, reason: '' }
}
function rowClassOf(model: MasterListModel) {
  return effectiveRow.value.rowClass?.(model) || ''
}

function onThumbDragEnter(model: MasterListModel, event: DragEvent) {
  if (!thumbDrop.value) return
  event.preventDefault()
  event.stopPropagation()
  thumbDragOverId.value = model.id
}
function onThumbDragOver(model: MasterListModel, event: DragEvent) {
  onThumbDragEnter(model, event)
}
function onThumbDragLeave(model: MasterListModel) {
  if (thumbDragOverId.value === model.id) thumbDragOverId.value = null
}
async function onThumbDrop(model: MasterListModel, event: DragEvent) {
  if (!thumbDrop.value) return
  event.preventDefault()
  event.stopPropagation()
  thumbDragOverId.value = null
  await thumbDrop.value.onDrop(model, event)
}
</script>

<style scoped>
.master-list-panel {
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
}

.master-list-panel__header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--space-sm);
  min-width: 0;
  padding: 10px 12px;
  border-bottom: 1px solid var(--color-border-lighter);
}

.master-list-panel__sort {
  min-width: 112px;
  height: var(--control-height-sm);
  padding: 0 28px 0 10px;
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: var(--fs-xs);
}

.master-list-panel__filters {
  display: flex;
  min-width: 0;
  gap: var(--space-xs);
  overflow-x: auto;
  scrollbar-width: none;
}
.master-list-panel__filters::-webkit-scrollbar {
  display: none;
}

.master-list-panel__filter {
  display: inline-flex;
  height: var(--control-height-sm);
  flex: 0 0 auto;
  align-items: center;
  gap: 5px;
  padding: 0 9px;
  border: 1px solid transparent;
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text-secondary);
  cursor: pointer;
  font-size: var(--fs-xs);
}
.master-list-panel__filter em {
  color: var(--color-text-muted);
  font-size: var(--fs-xxs);
  font-style: normal;
}
.master-list-panel__filter:hover {
  border-color: var(--color-primary-border);
  color: var(--color-primary);
}
.master-list-panel__filter.is-active {
  border-color: var(--color-primary);
  background: var(--color-primary-bg);
  color: var(--color-primary);
}
.master-list-panel__filter.is-empty {
  opacity: 0.65;
}

.master-list-panel__state,
.master-list-panel__state :deep(.data-state-content),
.master-list-panel__rows {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
}
.master-list-panel__rows {
  overflow-y: auto;
}

.master-list-row {
  position: relative;
  display: flex;
  width: 100%;
  align-items: center;
  gap: 12px;
  padding: 11px 12px;
  border: 0;
  border-bottom: 1px solid var(--color-border-lighter);
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background var(--transition-fast);
}
.master-list-row:hover {
  background: var(--color-surface-hover);
}
.master-list-row.is-active {
  background: var(--color-primary-bg);
  box-shadow: inset 3px 0 0 var(--color-primary);
}
.master-list-row.is-selected {
  background: var(--color-surface-selected);
}
.master-list-row.is-dragging {
  opacity: 0.5;
}
.master-list-row.is-drag-over {
  box-shadow: inset 0 2px 0 var(--color-primary);
}

.master-list-row__thumb {
  position: relative;
  display: grid;
  overflow: hidden;
  width: 52px;
  height: 52px;
  flex: 0 0 52px;
  place-items: center;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface-alt);
}
.master-list-row__thumb.is-drop-target {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px var(--color-primary-ring);
}
.master-list-row__thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.master-list-row__thumb i {
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
  font-style: normal;
}
.master-list-row__check {
  position: absolute;
  top: 3px;
  left: 3px;
  display: grid;
  width: 16px;
  height: 16px;
  place-items: center;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  font-size: 10px;
}
.master-list-row__check.is-checked {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: white;
}
.master-list-row__drop-label {
  position: absolute;
  inset: auto 0 0;
  padding: 1px 2px;
  background: rgb(15 23 42 / 72%);
  color: white;
  font-size: 8px;
  font-style: normal;
  text-align: center;
}

.master-list-row__info {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 4px;
}
.master-list-row__title {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 5px;
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
}
.master-list-row__title > span:last-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.master-list-row__tag {
  flex: 0 0 auto;
  padding: 1px 5px;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-sm);
  color: var(--color-primary);
  font-size: var(--fs-xxs);
}
.master-list-row__meta {
  display: flex;
  min-width: 0;
  gap: 5px;
  color: var(--color-text-muted);
  font-size: var(--fs-xxs);
}
.master-list-row__meta span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.master-list-row__meta span.is-emphasis {
  color: var(--color-text-primary);
  font-weight: 600;
}
.master-list-row__counts {
  color: var(--color-text-faint);
  font-size: var(--fs-xxs);
  font-style: normal;
}
.master-list-row__badges {
  display: flex;
  flex-wrap: wrap;
  gap: 3px;
}
.master-list-row__badges i {
  padding: 1px 5px;
  border-radius: var(--radius-sm);
  background: var(--color-warning-bg-soft);
  color: var(--color-warning-text);
  font-size: var(--fs-xxs);
  font-style: normal;
}
.master-list-row__badges i.badge-website {
  border: 1px solid var(--color-success-border);
  background: var(--color-success-bg);
  color: var(--color-success-text);
}
.master-list-row__badges i.badge-website.is-actionable {
  cursor: pointer;
  transition: opacity var(--duration-fast), border-color var(--duration-fast);
}
.master-list-row__badges i.badge-website.is-actionable:hover,
.master-list-row__badges i.badge-website.is-actionable:focus-visible {
  opacity: 0.78;
  outline: 2px solid var(--color-primary-border);
  outline-offset: 1px;
}
.master-list-row__badges i.badge-website.is-disabled {
  border-color: var(--color-border-default);
  background: var(--color-surface-muted);
  color: var(--color-text-faint);
}

.master-list-panel__pagination {
  /* 以自身宽度为容器查询基准：窄列表列（主从布局左栏）自动折叠次要控件 */
  container-type: inline-size;
  display: flex;
  flex: 0 0 auto;
  min-width: 0;
  justify-content: flex-end;
  padding: 8px 12px;
  border-top: 1px solid var(--color-border-lighter);
  background: var(--color-surface-alt);
}

.master-list-panel__pagination .lq-pagination {
  min-width: 0;
  max-width: 100%;
}

.master-list-panel__pagination :deep(.el-pagination__sizes),
.master-list-panel__pagination :deep(.el-pagination__total),
.master-list-panel__pagination :deep(.el-pagination__jump) {
  flex: 0 0 auto;
}

/* 容器过窄时收起「每页条数」，再窄收起「共 N 条」，保证页码按钮完整可见 */
@container (max-width: 440px) {
  .master-list-panel__pagination :deep(.el-pagination__sizes) {
    display: none;
  }
}
@container (max-width: 320px) {
  .master-list-panel__pagination :deep(.el-pagination__total) {
    display: none;
  }
}

@media (max-width: 767px) {
  .master-list-panel__header {
    align-items: stretch;
    flex-direction: column;
  }
  .master-list-panel__sort {
    width: 100%;
  }
}
</style>
