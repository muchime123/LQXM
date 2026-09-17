<template>
  <DataState
    class="master-card-grid-state"
    :state="state"
    :loading-text="loadingText"
    :empty-text="emptyTitle"
    :empty-hint="emptyHint"
    skeleton
    skeleton-variant="card"
  >
    <div :class="['card-grid-list', { 'batch-selectable': batchMode }]">
      <button
        v-for="model in models"
        :key="model.id"
        type="button"
        class="card-grid-item"
        :class="{
          active: selectedId === model.id,
          selected: batchMode && selectedModelIds.has(model.id),
          dragging: dragSort.isDragging(model),
          'drag-over': dragSort.isDragOver(model)
        }"
        v-bind="dragSort.dragHandlers(model)"
        @click="handleModelClick(model.id)"
      >
        <div class="card-thumb" @click.stop="handleThumbnailClick(model)">
          <img
            v-if="model.defaultImage && !brokenImages.has(model.id)"
            :src="modelImageUrl(model)"
            :alt="model.name"
            loading="lazy"
            decoding="async"
            @error="emit('image-error', model.id)"
          />
          <div v-else class="card-thumb-placeholder">
            <svg viewBox="0 0 48 48" fill="none" width="32" height="32" aria-hidden="true">
              <rect
                x="8"
                y="12"
                width="32"
                height="26"
                rx="4"
                stroke="var(--color-border-input-hover)"
                stroke-width="2"
                fill="var(--color-surface-alt)"
              />
              <circle cx="20" cy="24" r="4" stroke="var(--color-text-faint)" stroke-width="1.5" />
              <path
                d="M36 38l-6-8-5 4-3-4-6 8"
                stroke="var(--color-text-faint)"
                stroke-width="1.5"
                stroke-linejoin="round"
              />
            </svg>
            <span>暂无主图</span>
          </div>
          <span v-if="model.websiteEnabled === true" class="card-website-pill" title="已发布到官网">官网</span>
          <span v-if="batchMode" class="batch-check-mark" :class="{ checked: selectedModelIds.has(model.id) }">
            <span v-if="selectedModelIds.has(model.id)" aria-hidden="true">✓</span>
          </span>
        </div>

        <div class="card-body">
          <div class="card-name-row">
            <strong>
              <span v-if="hasDisplayModelTag(model)" class="product-model-tag">{{ displayModelTag(model) }}</span>
              <span>{{ model.name }}</span>
            </strong>
          </div>
          <div class="card-meta-row">
            <b :class="['product-type-badge', typeClass(model)]">
              {{ model.category || '其他' }}
            </b>
          </div>
          <div class="card-stats">
            <span>{{ model.assetCount || 0 }} 素材</span>
          </div>
        </div>
      </button>
    </div>
  </DataState>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import DataState, { type DataStateStatus } from '@/components/common/DataState.vue'
import { useDragSort } from '@/composables/list/useDragSort'
import { ASSET_CATEGORY_KEYS } from '@/constants/fields'
import { FILE_OWNER_TYPE_KEYS } from '@/types/fileAsset'
import { displayModelTag, hasDisplayModelTag } from '@/utils/catalog/productDisplay'
import { fileAssetThumbnailUrlByOptions } from '@/utils/ui/fileAssetUrls'

type ModelId = number

interface MasterCardModel {
  id: ModelId
  name?: string
  defaultImage?: string | null
  defaultImageAssetId?: number | null
  defaultImageOwnerType?: 'product_model' | 'product_variant' | null
  category?: string | null
  assetCount?: number | null
  websiteEnabled?: boolean
}

const props = withDefaults(
  defineProps<{
    models?: MasterCardModel[]
    loading?: boolean
    selectedId?: ModelId | null
    batchMode?: boolean
    selectedModelIds?: Set<ModelId>
    brokenImages?: Set<ModelId>
    canDragSort?: boolean
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
    brokenImages: () => new Set<ModelId>(),
    canDragSort: false,
    emptyTitle: '暂无数据',
    emptyHint: '点击“新建”添加',
    loadingText: '正在加载...'
  }
)

const emit = defineEmits<{
  select: [id: ModelId]
  toggleSelect: [id: ModelId]
  previewImage: [model: MasterCardModel]
  'image-error': [id: ModelId]
  'reorder-models': [ids: ModelId[]]
}>()

const state = computed<DataStateStatus>(() => {
  if (props.loading && props.models.length === 0) return 'loading'
  if (props.models.length === 0) return 'empty'
  return 'ready'
})

const dragSort = useDragSort<MasterCardModel>({
  items: () => props.models,
  canDrag: () => props.canDragSort,
  onReorder: ({ orderedIds }) => emit('reorder-models', orderedIds)
})

function handleModelClick(id: ModelId) {
  if (dragSort.suppressClick.value) return
  if (props.batchMode) emit('toggleSelect', id)
  else emit('select', id)
}

function handleThumbnailClick(model: MasterCardModel) {
  if (props.batchMode) emit('toggleSelect', model.id)
  else emit('previewImage', model)
}

function modelImageUrl(model: MasterCardModel) {
  const numericModelId = Number(model.id)
  return fileAssetThumbnailUrlByOptions({
    ownerType: model.defaultImageOwnerType || FILE_OWNER_TYPE_KEYS.productModel,
    assetId: model.defaultImageAssetId,
    category: ASSET_CATEGORY_KEYS.mainImage,
    fileName: model.defaultImage || '',
    modelId: Number.isFinite(numericModelId) ? numericModelId : null,
    size: 480
  })
}

function typeClass(model: MasterCardModel) {
  const category = model.category || ''
  if (category.includes('封口') || category.includes('封盒')) return 'cat-seal'
  if (category.includes('气调') || category.includes('保鲜')) return 'cat-map'
  if (category.includes('贴体')) return 'cat-skin'
  if (category.includes('气动')) return 'cat-pneumatic'
  if (category.includes('连续')) return 'cat-continuous'
  if (category.includes('定制') || category.includes('特殊')) return 'cat-custom'
  return 'cat-default'
}
</script>

<style scoped>
.master-card-grid-state,
.master-card-grid-state :deep(.data-state-content) {
  display: flex;
  min-height: 0;
  flex: 1;
}

.card-grid-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  align-content: start;
  gap: var(--space-section-gap);
  min-height: 0;
  flex: 1;
  padding: var(--space-xl-2xl);
  overflow-y: auto;
}

.card-grid-item {
  position: relative;
  display: flex;
  overflow: hidden;
  flex-direction: column;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-2xl);
  background: var(--color-surface);
  cursor: pointer;
  text-align: left;
  transition: all var(--transition-base);
}

.card-grid-item:hover {
  border-color: var(--color-border-input-hover);
  box-shadow: 0 4px 16px rgb(15 23 42 / 8%);
  transform: translateY(-2px);
}

.card-grid-item.active,
.card-grid-item.selected {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-ring-strong);
}

.card-grid-item.selected {
  background: var(--color-primary-bg);
}

.card-grid-item.dragging {
  opacity: 0.55;
}
.card-grid-item.drag-over {
  border-color: var(--color-primary);
}

.card-thumb {
  position: relative;
  width: 100%;
  overflow: hidden;
  border-bottom: 1px solid var(--color-border);
  aspect-ratio: 1;
  background: var(--color-surface-alt);
}

.card-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform var(--transition-base);
}
.card-grid-item:hover .card-thumb img {
  transform: scale(1.03);
}

.card-thumb-placeholder {
  display: flex;
  width: 100%;
  height: 100%;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  gap: var(--space-sm);
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
}

.batch-check-mark {
  position: absolute;
  top: 8px;
  left: 8px;
  display: grid;
  width: 22px;
  height: 22px;
  place-items: center;
  border: 2px solid var(--color-border);
  border-radius: var(--radius-md);
  background: rgb(255 255 255 / 85%);
}

.batch-check-mark.checked {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: white;
}
.card-website-pill {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
  padding: 2px 8px;
  border: 1px solid var(--color-success-border);
  border-radius: var(--radius-pill);
  background: var(--color-success-bg);
  color: var(--color-success-text);
  font-size: var(--fs-xxs);
  pointer-events: none;
}
.card-body {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 12px 14px 14px;
}
.card-name-row strong {
  display: flex;
  overflow: hidden;
  align-items: center;
  color: var(--color-text-heading);
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
}
.card-name-row strong > span:last-child {
  min-width: 0;
  overflow: hidden;
  flex: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.product-model-tag {
  flex: 0 0 auto;
  margin-right: 6px;
  padding: 3px 7px;
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-sm);
  background: var(--color-primary-bg-light);
  color: var(--color-primary);
  font-size: var(--fs-xs);
}
.card-meta-row,
.card-stats {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}
.product-type-badge {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-size: var(--fs-xxs);
}
.cat-seal {
  background: var(--color-type-seal-bg);
  color: var(--color-type-seal-text);
}
.cat-map {
  background: var(--color-info-border);
  color: var(--color-info-text);
}
.cat-skin {
  background: var(--color-type-skin-bg);
  color: var(--color-type-skin-text);
}
.cat-pneumatic {
  background: var(--color-type-pneumatic-bg);
  color: var(--color-type-pneumatic-text);
}
.cat-continuous {
  background: var(--color-warning-bg);
  color: var(--color-warning-text);
}
.cat-custom {
  background: var(--color-type-custom-bg);
  color: var(--color-type-custom-text);
}
.cat-default {
  background: var(--color-surface-hover);
  color: var(--color-text-muted);
}
.product-workshop,
.card-stats,
.card-spec {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
}
.product-workshop {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-price {
  margin-left: auto;
  color: var(--color-completion-text);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
}
</style>
