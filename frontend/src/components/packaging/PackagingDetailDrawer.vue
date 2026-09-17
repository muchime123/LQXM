<template>
  <section v-if="visible" class="packaging-folder-panel">
    <main v-if="loading" class="folder-state">正在打开系列文件夹...</main>
    <main v-else-if="!detail" class="folder-state">未找到包材系列</main>
    <template v-else>
      <MasterDetailHeader
        :tabs="detailTabs"
        :active="activeView"
        @update:active="activeView = $event as 'specs' | 'series' | 'molds' | 'assets'"
      >
        <template #actions>
          <button type="button" class="btn-sm" @click="emit('edit', modelId)">编辑</button>
          <button type="button" class="btn-sm danger" @click="removeModel">删除</button>
        </template>
      </MasterDetailHeader>
      <div v-if="activeView === 'specs'" class="specs-toolbar">
        <div class="specs-toolbar-left">
          <div class="spec-sort-dropdown">
            <select v-model="variantSortBy" class="sort-select">
              <option value="sortOrder">自定义排序</option>
              <option value="name">按名称</option>
              <option value="capacity">按容量</option>
              <option v-if="isFilmModel" value="bestPrice">按最低价</option>
            </select>
            <button
              type="button"
              class="sort-direction"
              :class="{ desc: !variantSortAsc }"
              :title="variantSortAsc ? '升序' : '降序'"
              @click="variantSortAsc = !variantSortAsc"
            >
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                width="14"
                height="14"
              >
                <path d="m18 15-6-6-6 6" />
              </svg>
            </button>
          </div>
          <div class="spec-search-bar">
            <svg
              class="search-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              width="16"
              height="16"
            >
              <circle cx="11" cy="11" r="8" />
              <path d="m21 21-4.35-4.35" />
            </svg>
            <input v-model="variantSearchKeyword" type="search" placeholder="搜索规格名称、容量、材质..." />
            <button v-if="variantSearchKeyword" type="button" class="search-clear" @click="variantSearchKeyword = ''">
              <svg
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                width="14"
                height="14"
              >
                <path d="M18 6 6 18" />
                <path d="m6 6 12 12" />
              </svg>
            </button>
          </div>
        </div>
        <div class="specs-toolbar-actions">
          <button type="button" class="btn-sm primary" @click="openVariantEditor()">
            {{ isFilmModel ? '新增膜材' : '新增规格' }}
          </button>
          <button type="button" class="btn-sm" :class="{ active: batchMode }" @click="enterBatchMode">批量管理</button>
          <button type="button" class="btn-sm" @click="openVariantTrash">回收站</button>
        </div>
      </div>
      <div v-if="activeView === 'specs' || activeView === 'series'" class="paste-hint-bar">
        <template v-if="!uploading">
          📋 粘贴图片将上传到「<strong>{{ pasteTargetLabel }}</strong
          >」
        </template>
        <template v-else><span class="spinner" /> 上传中...</template>
      </div>
      <section v-if="activeView === 'specs'" class="folder-body">
        <div class="specs-zone">
          <div v-if="filteredVariants.length === 0" class="empty-box">
            {{ detail.variants.length === 0 ? '该系列还没有规格型号。' : '未找到匹配的规格型号。' }}
          </div>
          <template v-else>
            <div v-if="batchMode" class="spec-batch-bar">
              <button type="button" class="batch-exit" @click="exitBatchMode">
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  width="16"
                  height="16"
                >
                  <path d="M18 6 6 18" />
                  <path d="m6 6 12 12" />
                </svg>
                退出批量管理
              </button>
              <label class="batch-checkbox">
                <input type="checkbox" :checked="isAllSelected" @change="toggleAllVariantSelection" />
                <span>全选</span>
              </label>
              <span v-if="selectedVariants.length > 0" class="batch-count">已选 {{ selectedVariants.length }} 项</span>
              <div class="batch-actions">
                <button v-if="selectedVariants.length > 0" type="button" class="btn-sm" @click="batchDuplicateVariants">
                  批量复制
                </button>
                <button v-if="selectedVariants.length > 0" type="button" class="btn-sm" @click="openBatchMoveModal">
                  移动到系列
                </button>
                <button
                  v-if="isFilmModel && selectedVariants.length > 0"
                  type="button"
                  class="btn-sm"
                  @click="openBatchFillModal"
                >
                  批量补膜字段
                </button>
                <button
                  v-if="selectedVariants.length > 0"
                  type="button"
                  class="btn-sm primary"
                  @click="openBatchEditModal"
                >
                  批量编辑
                </button>
                <button
                  v-if="selectedVariants.length > 0"
                  type="button"
                  class="btn-sm danger"
                  @click="batchDeleteVariants"
                >
                  批量删除
                </button>
                <button v-if="selectedVariants.length > 0" type="button" class="btn-sm" @click="exportSelectedVariants">
                  导出选中
                </button>
              </div>
            </div>
            <PackagingVariantCards
              :detail="detail"
              :model-id="props.modelId"
              :is-film-model="isFilmModel"
              :active-film-form="activeFilmForm"
              :exchange-rate="exchangeRate"
              :filtered-variants="filteredVariants"
              :batch-mode="batchMode"
              :selected-variant-ids="selectedVariantIds"
              :selected-variant="selectedVariant"
              :focus-variant-id="props.focusVariantId"
              :dragged-variant-id="draggedVariantId"
              :drag-over-variant-id="dragOverVariantId"
              :variant-carousel-index="variantCarouselIndex"
              :variant-missing-items="variantMissingItems"
              @update:carousel-index="(id, idx) => (variantCarouselIndex[id] = idx)"
              @manage-images="openImageManager"
              @card-click="handleVariantCardClick"
              @toggle-selection="toggleVariantSelection"
              @checkbox-click="handleVariantCheckboxClick"
              @drag-start="handleVariantDragStart"
              @drag-over="handleVariantDragOver"
              @drag-leave="handleVariantDragLeave"
              @drop="handleVariantDrop"
              @drag-end="handleVariantDragEnd"
              @image-drop="handleVariantImageDrop"
              @upload-image="uploadVariantImage"
              @preview-image="previewVariantImage"
              @make-default="makeDefault"
              @remove-asset="removeAsset"
              @copy-variant="copyVariant"
            />
          </template>
        </div>
        <PackagingSpecDetailPanel
          :variant="selectedVariant"
          :detail="detail"
          :model-id="props.modelId"
          :is-film-model="isFilmModel"
          :exchange-rate="exchangeRate"
          :carousel-index="variantCarouselIndex[selectedVariant?.id || 0] || 0"
          @update:carousel-index="idx => selectedVariant && (variantCarouselIndex[selectedVariant.id] = idx)"
          @manage-images="openImageManagerForSelected"
          @upload-image="uploadVariantImage"
          @preview-image="previewVariantImage"
          @open-history="openVariantHistory"
          @open-editor="openVariantEditor"
          @remove-variant="removeVariant"
          @quick-fix="handleQuickFixAction"
          @image-drop="handleVariantImageDrop"
          @make-default="makeDefault"
          @remove-asset="removeAsset"
        />
      </section>
      <section v-else-if="activeView === 'series'" class="series-body">
        <div class="series-hero">
          <div
            class="series-image"
            :class="{ 'drag-active': seriesImageDragging, 'has-image': mainImage }"
            role="button"
            tabindex="0"
            @click="mainImage ? previewSeriesImage() : uploadSeriesImage()"
            @keydown.enter.prevent="mainImage ? previewSeriesImage() : uploadSeriesImage()"
            @keydown.space.prevent="mainImage ? previewSeriesImage() : uploadSeriesImage()"
            @dragenter.prevent="seriesImageDragging = true"
            @dragover.prevent
            @dragleave.prevent="seriesImageDragging = false"
            @drop.prevent="handleSeriesImageDrop"
          >
            <img v-if="mainImage" :src="seriesMainImageUrl()" :alt="detail.model.name" />
            <span v-else class="series-placeholder">
              <svg
                class="series-placeholder-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                stroke-linecap="round"
                stroke-linejoin="round"
                width="40"
                height="40"
                aria-hidden="true"
              >
                <rect
                  x="3"
                  y="3"
                  width="18"
                  height="18"
                  rx="4"
                />
                <circle cx="9" cy="9" r="2" />
                <path d="m21 15-3.9-3.9a2 2 0 0 0-2.8 0L6 19" />
              </svg>
              <strong>暂无系列主图</strong>
              <em>点击上传，或把图片拖到这里</em>
            </span>
            <span class="image-actions">
              <button type="button" @click.stop="uploadSeriesImage">{{ mainImage ? '更换主图' : '上传主图' }}</button>
              <button v-if="mainImage" type="button" @click.stop="previewSeriesImage">预览</button>
            </span>
          </div>
          <article class="series-card series-info-card">
            <div class="series-identity">
              <span class="series-kind-chip">{{ kindLabel(detail.model.packagingKind) }}</span>
              <h2 class="series-hero-name">{{ detail.model.name }}</h2>
              <p v-if="detail.model.modelCode" class="series-hero-code">{{ detail.model.modelCode }}</p>
            </div>
            <h3>系列资料</h3>
            <dl class="detail-list">
              <div v-for="field in seriesFields" :key="field.label" class="series-field">
                <dt>{{ field.label }}</dt>
                <dd>{{ field.value || '-' }}</dd>
              </div>
            </dl>
          </article>
        </div>
        <article class="series-card series-card-wide">
          <h3>应用与备注</h3>
          <div class="series-note-blocks">
            <p v-if="detail.model.applicable" class="series-note">
              <span class="series-note-label">适用场景</span>
              <span class="series-note-text">{{ detail.model.applicable }}</span>
            </p>
            <p v-if="detail.model.advantages" class="series-note">
              <span class="series-note-label">优势亮点</span>
              <span class="series-note-text">{{ detail.model.advantages }}</span>
            </p>
            <p v-if="detail.model.remark" class="series-note">
              <span class="series-note-label">补充说明</span>
              <span class="series-note-text">{{ detail.model.remark }}</span>
            </p>
            <p
              v-if="!detail.model.applicable && !detail.model.advantages && !detail.model.remark"
              class="series-note-empty"
            >
              暂无应用与备注
            </p>
          </div>
        </article>
      </section>
      <PackagingMoldsPanel
        v-else-if="activeView === 'molds'"
        :model-id="props.modelId"
        :variants="detail?.variants"
        :assets="detail?.assets"
        @count-change="moldCount = $event"
        @open-variant="handleMoldVariantClick"
      />
      <PackagingAssetPanel
        v-else
        :detail="detail"
        :model-id="props.modelId"
        :file-owner-type-for-packaging-asset="fileOwnerTypeForPackagingAsset"
        :packaging-asset-thumbnail-url="packagingAssetThumbnailUrl"
        :preview-asset="previewAsset"
        @asset-changed="onAssetPanelChanged"
      />
    </template>
    <LqPreview
      v-model="preview.visible"
      :url="preview.url"
      :title="preview.title"
      :file-name="preview.fileName"
      :category="preview.category"
      :owner-type="preview.ownerType"
      :asset-id="preview.assetId"
      :gallery-index="preview.index"
      :gallery-count="preview.assets.length"
      @close="closePreview"
      @prev="shiftPreview(-1)"
      @next="shiftPreview(1)"
    />
    <PackagingVariantHistoryModal
      :visible="variantHistoryVisible"
      :variant-name="variantHistoryTitle"
      :history="variantHistory"
      :loading="variantHistoryLoading"
      @close="closeVariantHistory"
    />
    <PackagingBatchMoveModal
      :visible="batchMoveVisible"
      :selected-count="selectedVariants.length"
      :current-model-id="props.modelId"
      :loading="batchMoveLoading"
      @update:visible="closeBatchMoveModal"
      @confirm="confirmBatchMoveVariants"
    />
    <PackagingBatchFillModal
      :visible="batchFillVisible"
      :selected-variants="selectedVariants"
      :loading="batchFillLoading"
      @update:visible="closeBatchFillModal"
      @confirm="confirmBatchFillVariants"
    />
    <PackagingBatchEditModal
      :visible="batchEditVisible"
      :selected-count="selectedVariants.length"
      :is-film-model="isFilmModel"
      :active-film-form="activeFilmForm"
      :loading="batchEditLoading"
      @update:visible="closeBatchEditModal"
      @confirm="confirmBatchEditVariants"
    />
    <PackagingVariantTrashModal
      :model-id="props.modelId"
      :visible="variantTrashVisible"
      @update:visible="
        val => {
          if (!val) variantTrashVisible = false
        }
      "
      @restored="loadDetail"
      @deleted="loadDetail"
    />
    <PackagingVariantEditor
      :visible="variantEditorVisible"
      :is-edit="!!editingVariantId"
      :form="variantForm"
      :form-groups="visibleVariantFormGroups"
      :is-film-model="isFilmModel"
      :exchange-rate="exchangeRate"
      :dealer-price-usd="dealerPriceUsd"
      :retail-price-usd="retailPriceUsd"
      :unit-dealer-price="unitDealerPrice"
      :unit-retail-price="unitRetailPrice"
      :unit-dealer-price-usd="unitDealerPriceUsd"
      :unit-retail-price-usd="unitRetailPriceUsd"
      :saving="savingVariant"
      @update:visible="closeVariantEditor"
      @save="saveVariant"
      @update:form="
        (key, value) => {
          variantForm[key] = value
        }
      "
      @update:exchange-rate="exchangeRate = $event"
    />
    <!-- 批量操作进度 -->
    <PackagingBatchProgress
      :visible="batchProgress.visible"
      :title="batchProgress.title"
      :current="batchProgress.current"
      :total="batchProgress.total"
      :status="batchProgress.status"
    />
    <!-- 图片管理弹窗（规格图片统一管理） -->
    <VariantImageManager
      :visible="imageManagerVisible"
      :assets="imageManagerAssets"
      :variant-name="imageManagerVariant?.variantName || imageManagerVariant?.specLabel || ''"
      upload-label="上传更多图片"
      :thumbnail-url="asset => packagingAssetThumbnailUrl(asset)"
      @update:visible="val => (imageManagerVisible = val)"
      @make-default="handleImageManagerMakeDefault"
      @remove-asset="handleImageManagerRemoveAsset"
      @preview-image="handleImageManagerPreview"
      @upload-images="handleImageManagerUpload"
    />
  </section>
</template>
<script setup lang="ts">
import {
  deletePackagingModel,
  fetchPackagingModel,
  fetchPackagingVariantHistory,
  reorderPackagingVariants,
  type PackagingModelDetail,
  type PackagingVariantHistoryView,
  type PackagingVariantView
} from '@/api/packaging'
import { useDragSort } from '@/composables/list/useDragSort'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { notify } from '@/composables/ui/useToast'
import { useVariantDisplay } from '@/composables/packaging/useVariantDisplay'
import { usePackagingAssetActions } from '@/composables/packaging/usePackagingAssetActions'
import { usePackagingPreview } from '@/composables/packaging/usePackagingPreview'
import { useVariantBatchOps } from '@/composables/packaging/useVariantBatchOps'
import { useSeriesPanel } from '@/composables/packaging/useSeriesPanel'
import { useVariantFilters } from '@/composables/packaging/useVariantFilters'
import { useVariantForm } from '@/composables/packaging/useVariantForm'
import { usePackagingAssetLookup } from '@/composables/packaging/usePackagingAssetLookup'
import { usePackagingUpload } from '@/composables/packaging/usePackagingUpload'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { kindLabel } from '@/constants/packaging'
import { useFileAssetActions } from '../../composables/upload/useFileAssetActions'
import LqPreview from '@/components/common/LqPreview.vue'
import VariantImageManager from '@/components/common/VariantImageManager.vue'
import MasterDetailHeader from '@/components/master/MasterDetailHeader.vue'
// 子组件导入
import PackagingAssetPanel from './PackagingAssetPanel.vue'
import PackagingMoldsPanel from './PackagingMoldsPanel.vue'
import PackagingBatchEditModal from './PackagingBatchEditModal.vue'
import PackagingVariantCards from './PackagingVariantCards.vue'
import PackagingBatchFillModal from './PackagingBatchFillModal.vue'
import PackagingBatchMoveModal from './PackagingBatchMoveModal.vue'
import PackagingBatchProgress from './PackagingBatchProgress.vue'
import PackagingVariantEditor from './PackagingVariantEditor.vue'
import PackagingSpecDetailPanel from './PackagingSpecDetailPanel.vue'
import PackagingVariantHistoryModal from './PackagingVariantHistoryModal.vue'
import PackagingVariantTrashModal from './PackagingVariantTrashModal.vue'
const props = withDefaults(
  defineProps<{
    visible: boolean
    modelId: number
    focusVariantId?: number | null
    pinned?: boolean
  }>(),
  {
    focusVariantId: null,
    pinned: true
  }
)
const emit = defineEmits<{
  close: []
  edit: [id: number]
  deleted: []
  'asset-changed': []
}>()
const fileAssetActions = useFileAssetActions({
  notify,
  notifySuccess: false
})
const loading = ref(false)
const detail = ref<PackagingModelDetail | null>(null)
const activeView = ref<'specs' | 'series' | 'molds' | 'assets'>('specs')
const moldCount = ref(0)

const detailTabs = computed(() => [
  { key: 'specs' as const, label: '规格型号', badge: detail.value?.variants.length },
  { key: 'series' as const, label: '包材概览' },
  { key: 'molds' as const, label: '模具与配件', badge: moldCount.value },
  { key: 'assets' as const, label: '素材管理', badge: detail.value?.assets.length }
])
const selectedVariant = ref<PackagingVariantView | null>(null)
const variantHistoryVisible = ref(false)
const variantHistoryLoading = ref(false)
const variantHistory = ref<PackagingVariantHistoryView[]>([])
const variantHistoryTitle = ref('')
const variantTrashVisible = ref(false)
const variantCarouselIndex = reactive<Record<number, number>>({})
const imageManagerVisible = ref(false)
const imageManagerVariant = ref<PackagingVariantView | null>(null)
const { fileOwnerTypeForPackagingAsset, packagingAssetPreviewUrl, packagingAssetThumbnailUrl } =
  usePackagingAssetLookup({
    detail,
    modelId: props.modelId
  })
const exchangeRate = ref(parseFloat(localStorage.getItem('cny_to_usd_rate') || '7.2') || 7.2)
const {
  variantForm,
  variantEditorVisible,
  editingVariantId,
  savingVariant,
  isFilmModel,
  activeFilmForm,
  unitDealerPrice,
  unitRetailPrice,
  dealerPriceUsd,
  retailPriceUsd,
  unitDealerPriceUsd,
  unitRetailPriceUsd,
  visibleVariantFormGroups,
  openVariantEditor,
  closeVariantEditor,
  saveVariant,
  removeVariant,
  copyVariant
} = useVariantForm(detail, () => props.modelId, exchangeRate, loadDetail)
const {
  isBlank,
  hasVariantDimension,
  tierFields,
  filmPricedTierFields,
  filmFeatureMissingLabel,
  filmUnifiedRemark,
  filmBestPriceNumber
} = useVariantDisplay(detail, isFilmModel, activeFilmForm, exchangeRate)
const {
  uploadTarget,
  uploading,
  pickFiles,
  uploadFiles,
  pasteTargetLabel,
  contextPasteHandler,
  variantImageAssets,
  variantImage,
  handleVariantImageDrop,
  uploadVariantImage
} = usePackagingUpload({
  detail,
  modelId: props.modelId,
  activeView,
  selectedVariant,
  notify,
  fileAssetActions,
  onAssetChanged: () => emit('asset-changed')
})
const { variantSearchKeyword, variantSortBy, variantSortAsc, variantMissingItems, filteredVariants, resetFilters } =
  useVariantFilters(detail, isFilmModel, activeFilmForm, {
    isBlank,
    hasVariantDimension,
    tierFields,
    filmPricedTierFields,
    filmFeatureMissingLabel,
    filmUnifiedRemark,
    filmBestPriceNumber,
    variantImage
  })
watch(exchangeRate, newVal => {
  localStorage.setItem('cny_to_usd_rate', String(newVal))
})
const {
  draggedId: draggedVariantId,
  dragOverId: dragOverVariantId,
  suppressClick: suppressVariantClick,
  handleDragStart: handleVariantDragStart,
  handleDragOver: handleVariantDragOver,
  handleDragLeave: handleVariantDragLeave,
  handleDrop: handleVariantDrop,
  handleDragEnd: handleVariantDragEnd
} = useDragSort({
  items: filteredVariants,
  canDrag: computed(() => activeView.value === 'specs'),
  onReorder: async ({ items }: { items: { id: number; sortOrder: number }[] }) => {
    try {
      await reorderPackagingVariants(items)
      if (detail.value) {
        items.forEach(item => {
          const variant = detail.value!.variants.find(v => v.id === item.id)
          if (variant) {
            variant.sortOrder = item.sortOrder
          }
        })
        detail.value.variants.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
      }
    } catch {
      notify('排序保存失败', 'error')
    }
  }
})
const {
  selectedVariantIds,
  batchMode,
  batchMoveVisible,
  batchMoveLoading,
  batchFillVisible,
  batchFillLoading,
  batchEditVisible,
  batchEditLoading,
  batchProgress,
  selectedVariants,
  isAllSelected,
  toggleVariantSelection,
  toggleAllVariantSelection,
  enterBatchMode,
  exitBatchMode,
  handleVariantCheckboxClick,
  handleVariantCardClick,
  batchDeleteVariants,
  batchDuplicateVariants,
  openBatchMoveModal,
  closeBatchMoveModal,
  confirmBatchMoveVariants,
  openBatchFillModal,
  closeBatchFillModal,
  confirmBatchFillVariants,
  openBatchEditModal,
  closeBatchEditModal,
  confirmBatchEditVariants,
  exportSelectedVariants
} = useVariantBatchOps(detail, {
  isFilmModel,
  filteredVariants,
  selectedVariant,
  suppressClick: suppressVariantClick,
  notify,
  onAssetChanged: () => emit('asset-changed'),
  onReloadDetail: loadDetail
})
const {
  mainImage,
  seriesFields,
  seriesMainImageUrl,
  seriesImageDragging,
  uploadSeriesImage,
  handleSeriesImageDrop
} = useSeriesPanel(detail, {
    isFilmModel,
    packagingAssetPreviewUrl,
    upload: { uploadTarget, pickFiles, uploadFiles }
  })
const { preview, closePreview, shiftPreview, previewSeriesImage, previewVariantImage, previewAsset } =
  usePackagingPreview(detail, {
    mainImage,
    variantImageAssets,
    variantCarouselIndex,
    packagingAssetPreviewUrl,
    fileOwnerTypeForPackagingAsset
  })
function openVariantTrash() {
  variantTrashVisible.value = true
}
const { makeDefault, removeAsset } = usePackagingAssetActions(detail, {
  fileAssetActions,
  filteredAssets: computed(() => detail.value?.assets || []),
  fileOwnerTypeForPackagingAsset,
  notify,
  onAssetChanged: () => emit('asset-changed')
})

const imageManagerAssets = computed(() => {
  if (!imageManagerVariant.value || !detail.value) return []
  return variantImageAssets(imageManagerVariant.value)
})

function openImageManager(variant: PackagingVariantView) {
  imageManagerVariant.value = variant
  imageManagerVisible.value = true
}

function openImageManagerForSelected() {
  if (selectedVariant.value) {
    imageManagerVariant.value = selectedVariant.value
    imageManagerVisible.value = true
  }
}

function handleImageManagerMakeDefault(assetId: number) {
  makeDefault(assetId)
}

function handleImageManagerRemoveAsset(asset: { id: number }) {
  removeAsset(asset.id)
}

function handleImageManagerPreview(index: number) {
  if (imageManagerVariant.value) {
    previewVariantImage(imageManagerVariant.value, index)
  }
}

function handleImageManagerUpload() {
  if (imageManagerVariant.value) {
    uploadVariantImage(imageManagerVariant.value)
  }
}

function handleQuickFixAction(field: string) {
  if (!selectedVariant.value) return
  if (field === 'image') {
    uploadVariantImage(selectedVariant.value)
    return
  }
  openVariantEditor(selectedVariant.value, field)
}

function handleMoldVariantClick(variant: PackagingVariantView) {
  activeView.value = 'specs'
  selectedVariant.value = variant
}
async function loadDetail() {
  loading.value = true
  try {
    detail.value = await fetchPackagingModel(props.modelId)
    selectedVariant.value = variantById(props.focusVariantId) || detail.value.variants[0] || null
  } finally {
    loading.value = false
  }
}
/** 素材面板变更时，刷新详情并通知父级（标签自动关联规格需同步新 variant 和 asset.variantId） */
async function onAssetPanelChanged() {
  await loadDetail()
  emit('asset-changed')
}
function variantById(id?: number | null) {
  if (!id) return null
  return detail.value?.variants.find(variant => variant.id === id) || null
}
async function openVariantHistory(variant: PackagingVariantView) {
  variantHistoryVisible.value = true
  variantHistoryLoading.value = true
  variantHistoryTitle.value = variant.variantName || variant.specLabel || '未命名规格'
  variantHistory.value = []
  try {
    variantHistory.value = await fetchPackagingVariantHistory(variant.id)
  } catch (error) {
    notify.errorWithDetail('历史记录加载失败', error)
  } finally {
    variantHistoryLoading.value = false
  }
}
function closeVariantHistory() {
  variantHistoryVisible.value = false
  variantHistory.value = []
  variantHistoryTitle.value = ''
}
async function removeModel() {
  const ok = await askConfirm({
    title: '删除包材系列',
    message: '确定删除该包材系列？删除后可在回收站恢复。',
    danger: true
  })
  if (!ok) return
  await deletePackagingModel(props.modelId)
  emit('deleted')
}
watch(
  () => props.modelId,
  () => {
    activeView.value = 'series'
    resetFilters()
    selectedVariantIds.clear()
    loadDetail()
  }
)
watch(
  () => props.focusVariantId,
  id => {
    if (!id) return
    activeView.value = 'specs'
    resetFilters()
    const target = variantById(id)
    if (target) {
      selectedVariant.value = target
    }
  }
)
watch(filteredVariants, variants => {
  const visibleIds = new Set(variants.map(variant => variant.id))
  Array.from(selectedVariantIds).forEach(id => {
    if (!visibleIds.has(id)) selectedVariantIds.delete(id)
  })
  if (selectedVariant.value && !visibleIds.has(selectedVariant.value.id)) {
    selectedVariant.value = variants[0] || null
  }
})
onMounted(() => {
  loadDetail()
  document.addEventListener('paste', contextPasteHandler)
})
onBeforeUnmount(() => {
  document.removeEventListener('paste', contextPasteHandler)
})
</script>
<style scoped>
@import '../../styles/packaging-detail-drawer.css';
</style>
