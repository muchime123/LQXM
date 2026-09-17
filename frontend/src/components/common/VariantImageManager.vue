<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import LqDialog from './LqDialog.vue'

interface AssetItem {
  id: number
  fileName: string
  defaultAsset: boolean
  variantId?: number | null
  [key: string]: any
}

interface Props {
  visible: boolean
  assets: readonly AssetItem[]
  variantName?: string
  uploadLabel?: string
  zIndex?: number
  thumbnailUrl: (asset: Record<string, any>) => string
}

const props = defineProps<Props>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
  'make-default': [assetId: number]
  'remove-asset': [asset: AssetItem]
  'preview-image': [index: number]
  'upload-images': []
}>()

const carouselIndex = ref(0)

watch(
  () => props.visible,
  val => {
    if (val) carouselIndex.value = 0
  }
)

const sortedAssets = computed(() => {
  const items = [...props.assets]
  const defaultIndex = items.findIndex(a => a.defaultAsset)
  if (defaultIndex > 0) {
    const [defaultItem] = items.splice(defaultIndex, 1)
    items.unshift(defaultItem)
  }
  return items
})

function close() {
  emit('update:visible', false)
}

function onPreview(index: number) {
  emit('preview-image', index)
}
</script>

<template>
  <LqDialog
    :model-value="visible"
    :title="variantName ? `管理图片 · ${variantName}` : '管理图片'"
    subtitle="集中查看和维护当前规格的全部图片"
    size="lg"
    mode="detail"
    :z-index="zIndex"
    destroy-on-close
    @close="close"
  >
    <div class="variant-image-workspace">
      <div class="image-workspace-toolbar">
        <span>共 {{ assets.length }} 张图片</span>
        <button type="button" class="btn-sm primary" @click="emit('upload-images')">
          {{ uploadLabel || '上传更多图片' }}
        </button>
      </div>
      <div class="image-workspace-grid">
        <article
          v-for="(asset, index) in sortedAssets"
          :key="asset.id"
          class="image-workspace-item"
          :class="{ active: carouselIndex === index }"
        >
          <button type="button" class="image-workspace-preview" @click="onPreview(index)">
            <img :src="thumbnailUrl(asset)" :alt="asset.fileName" loading="lazy" decoding="async" />
            <em v-if="asset.defaultAsset">默认图片</em>
          </button>
          <strong :title="asset.fileName">{{ asset.fileName }}</strong>
          <div class="image-workspace-actions">
            <button
              v-if="!asset.defaultAsset"
              type="button"
              @click="emit('make-default', asset.id)"
            >
              设为默认
            </button>
            <button type="button" @click="onPreview(index)">预览</button>
            <button type="button" class="danger" @click="emit('remove-asset', asset)">删除</button>
          </div>
        </article>
      </div>
    </div>
  </LqDialog>
</template>

<style scoped>
.variant-image-workspace {
  display: grid;
  gap: var(--space-section-gap, 24px);
  min-height: 360px;
}

.image-workspace-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--color-text-muted, #6b7280);
}

.image-workspace-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: var(--space-section-gap, 24px);
}

.image-workspace-item {
  min-width: 0;
  padding: var(--space-md-lg, 16px);
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: var(--radius-xl, 12px);
  background: var(--color-surface, #ffffff);
  transition:
    border-color 0.16s ease,
    box-shadow 0.16s ease,
    transform 0.16s ease;
}

.image-workspace-item:hover,
.image-workspace-item.active {
  border-color: var(--color-primary, #3b82f6);
  box-shadow: var(--shadow-sm, 0 1px 2px 0 rgb(0 0 0 / 0.05));
}

.image-workspace-item:hover {
  transform: translateY(-2px);
}

.image-workspace-preview {
  position: relative;
  display: block;
  width: 100%;
  aspect-ratio: 4 / 3;
  padding: 0;
  overflow: hidden;
  border: 0;
  border-radius: var(--radius-lg, 8px);
  background: var(--color-surface-alt, #f3f4f6);
  cursor: zoom-in;
}

.image-workspace-preview img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.image-workspace-preview em {
  position: absolute;
  top: var(--space-control-gap, 4px);
  left: var(--space-control-gap, 4px);
  padding: 3px 8px;
  border-radius: var(--radius-pill, 999px);
  background: var(--color-primary, #3b82f6);
  color: var(--color-text-on-primary, #ffffff);
  font-size: 12px;
  font-style: normal;
}

.image-workspace-item > strong {
  display: block;
  margin-top: var(--space-control-gap, 4px);
  overflow: hidden;
  color: var(--color-text-primary, #111827);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.image-workspace-actions {
  display: flex;
  gap: var(--space-control-gap, 4px);
  margin-top: var(--space-md-lg, 16px);
}

.image-workspace-actions button {
  flex: 1;
  padding: 6px 8px;
  border: 1px solid var(--color-border, #e5e7eb);
  border-radius: var(--radius-md, 6px);
  background: var(--color-surface, #ffffff);
  color: var(--color-text-secondary, #6b7280);
  cursor: pointer;
  transition: all 0.15s ease;
}

.image-workspace-actions button:hover {
  border-color: var(--color-primary, #3b82f6);
  color: var(--color-primary, #3b82f6);
}

.image-workspace-actions button.danger:hover {
  border-color: var(--color-danger, #ef4444);
  color: var(--color-danger, #ef4444);
}
</style>
