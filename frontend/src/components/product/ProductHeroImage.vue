<template>
  <div class="overview-hero">
    <div class="hero-image-wrap" :class="{ 'drag-over': galleryDrop.isDragOver }" v-bind="galleryDrop.dragHandlers">
      <template v-if="coverImage && !brokenIds.has(coverImage.id)">
        <img
          :src="mainImageThumbnailUrl(coverImage, 480)"
          :alt="coverImage.fileName"
          class="hero-cover"
          decoding="async"
          @click="$emit('preview-asset', coverImage)"
          @error="brokenIds.add(coverImage!.id)"
        />
      </template>
      <div v-else class="hero-placeholder">
        <span>📷</span>
        <p>拖入图片上传主图</p>
      </div>

      <div v-if="mainImages.length > 1" class="hero-image-count" @click="showAllImages = !showAllImages">
        共 {{ mainImages.length }} 张
      </div>
    </div>

    <div
      v-if="mainImages.length > 1 && showAllImages"
      class="main-image-gallery"
      :class="{ 'drag-over': galleryDrop.isDragOver }"
    >
      <button
        v-for="asset in visibleImages"
        :key="asset.id"
        type="button"
        class="main-image-card"
        :class="{
          active: asset.fileName === defaultImage,
          dragging: dragImageId === asset.id,
          'drag-over': dragOverImageId === asset.id && dragImageId !== asset.id
        }"
        draggable="true"
        @dragstart="onDragStart($event, asset)"
        @dragover.prevent="onDragOver($event, asset)"
        @dragleave="onDragLeave"
        @drop.prevent="onDrop($event, asset)"
        @dragend="onDragEnd"
        @click="!dragImageId && $emit('preview-asset', asset)"
      >
        <img
          :src="mainImageThumbnailUrl(asset)"
          :alt="asset.fileName"
          loading="lazy"
          decoding="async"
          @error="brokenIds.add(asset.id)"
        />
        <span>{{ asset.fileName }}</span>
        <em v-if="asset.fileName === defaultImage">默认封面</em>
      </button>
      <button
v-if="hiddenCount > 0 && !expanded"
type="button"
class="btn-show-more"
@click="expanded = true">
        展开更多 {{ hiddenCount }} 张 ↓
      </button>
      <button
        v-else-if="expanded && mainImages.length > IMAGE_PAGE_SIZE"
        type="button"
        class="btn-show-more"
        @click="expanded = false"
      >
        收起 ↑
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import type { CatalogAsset, CatalogModel } from '../../types/catalog'
import { fileAssetThumbnailUrlByOptions } from '../../utils/ui/fileAssetUrls'
import { productFileOwnerType } from '../../utils/catalog/fileAssetUtils'
import { useLocalDropUpload } from '@/composables'
import { ASSET_CATEGORY_KEYS } from '../../constants/fields'

const props = withDefaults(
  defineProps<{
    model: Pick<CatalogModel, 'id'>
    assets: CatalogAsset[]
    defaultImage?: string
  }>(),
  {
    defaultImage: ''
  }
)

const emit = defineEmits<{
  'preview-asset': [asset: CatalogAsset]
  'reorder-images': [ids: number[]]
  'drop-images': [files: File[]]
}>()

const IMAGE_PAGE_SIZE = 6
const expanded = ref(false)
const showAllImages = ref(false)
const dragImageId = ref<number | null>(null)
const dragOverImageId = ref<number | null>(null)
const brokenIds = ref<Set<number>>(new Set())

const galleryDrop = useLocalDropUpload({
  accept: 'image/*',
  onFiles: files => emit('drop-images', files)
})

const mainImages = computed(() => props.assets.filter(a => a.category === ASSET_CATEGORY_KEYS.mainImage))

const coverImage = computed(() => {
  if (!mainImages.value.length) return null
  return mainImages.value.find(a => a.fileName === props.defaultImage) || mainImages.value[0]
})

const visibleImages = computed(() => (expanded.value ? mainImages.value : mainImages.value.slice(0, IMAGE_PAGE_SIZE)))

const hiddenCount = computed(() => Math.max(0, mainImages.value.length - IMAGE_PAGE_SIZE))

function mainImageThumbnailUrl(asset: CatalogAsset, size?: number) {
  return fileAssetThumbnailUrlByOptions({
    ownerType: productFileOwnerType(asset),
    assetId: asset?.id,
    category: ASSET_CATEGORY_KEYS.mainImage,
    fileName: asset?.fileName || '',
    modelId: props.model.id,
    size
  })
}

function onDragStart(e: DragEvent, asset: CatalogAsset) {
  dragImageId.value = asset.id
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    e.dataTransfer.setData('text/plain', String(asset.id))
  }
}

function onDragOver(_e: DragEvent, asset: CatalogAsset) {
  dragOverImageId.value = asset.id
}

function onDragLeave() {
  dragOverImageId.value = null
}

function onDrop(_e: DragEvent, targetAsset: CatalogAsset) {
  dragOverImageId.value = null
  const draggedId = dragImageId.value
  if (draggedId && draggedId !== targetAsset.id) {
    const images = [...mainImages.value]
    const fromIdx = images.findIndex(a => a.id === draggedId)
    const toIdx = images.findIndex(a => a.id === targetAsset.id)
    if (fromIdx !== -1 && toIdx !== -1) {
      images.splice(fromIdx, 1)
      images.splice(toIdx, 0, mainImages.value[fromIdx])
      const orderedIds = images.map(a => a.id)
      emit('reorder-images', orderedIds)
    }
  }
  dragImageId.value = null
}

function onDragEnd() {
  dragImageId.value = null
  dragOverImageId.value = null
}
</script>

<style scoped>
.overview-hero {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-wide);
}

.hero-image-wrap {
  position: relative;
  width: 100%;
  aspect-ratio: 1;
  border-radius: var(--radius-xl);
  overflow: hidden;
  border: 1px solid var(--color-border-light);
  background: var(--color-surface-alt);
  cursor: pointer;
  box-shadow: var(--shadow-sm);
  transition: border-color var(--transition-fast);
}
.hero-image-wrap:hover {
  border-color: var(--color-text-faint);
}
.hero-image-wrap.drag-over {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-ring-strong);
  background: var(--color-primary-bg-light);
}

.hero-cover {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.hero-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  gap: var(--space-control-gap);
  color: var(--color-text-faint);
}
.hero-placeholder span {
  font-size: var(--fs-7xl);
}
.hero-placeholder p {
  font-size: var(--fs-xs);
  margin: 0;
}

.hero-image-count {
  position: absolute;
  bottom: 10px;
  right: 10px;
  padding: 3px 8px;
  background: var(--color-overlay-image);
  color: var(--color-text-on-overlay);
  font-size: var(--fs-xxs);
  border-radius: var(--radius-xl);
  cursor: pointer;
}
.hero-image-count:hover {
  background: rgba(15, 118, 110, 0.8);
}

.main-image-gallery {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: var(--space-section-gap);
  margin-bottom: var(--space-control-gap-wide);
  padding: var(--space-control-gap-wide);
  border: 2px dashed transparent;
  border-radius: var(--radius-xl);
  transition:
    border-color 0.15s,
    box-shadow 0.15s,
    background 0.15s;
}
.main-image-gallery.drag-over {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-ring-strong);
  background: var(--color-primary-bg-light);
}
.main-image-card {
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
  border: 2px solid var(--color-border);
  border-radius: var(--radius-lg);
  overflow: hidden;
  cursor: pointer;
  transition: all var(--transition-base);
}
.main-image-card:hover {
  border-color: var(--color-border-input-hover);
  box-shadow: 0 2px 8px var(--shadow-color-soft);
}
.main-image-card.active {
  border-color: var(--color-primary);
  background: var(--color-primary-bg-light);
}
.main-image-card.dragging {
  opacity: 0.5;
}
.main-image-card.drag-over {
  border-color: var(--color-primary);
  border-style: dashed;
  background: var(--color-primary-bg);
}
.main-image-card img {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
}
.main-image-card span {
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
  padding: 8px 10px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: center;
}
.main-image-card em {
  font-size: var(--fs-xxs);
  color: var(--color-primary);
  font-style: normal;
  font-weight: var(--fw-medium);
  padding: 0 10px 8px;
  text-align: center;
}

.btn-show-more {
  width: 100%;
  padding: var(--space-md);
  background: var(--color-surface-alt);
  border: 1px dashed var(--color-border-input-hover);
  border-radius: var(--radius-md);
  font-size: var(--fs-sm);
  color: var(--color-text-muted);
  cursor: pointer;
}
.btn-show-more:hover {
  background: var(--color-surface-hover);
  border-color: var(--color-text-faint);
}
</style>
