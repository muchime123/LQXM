<template>
  <div ref="galleryRefEl" class="asset-gallery-groups" :class="{ 'single-category': false }">
    <section
      v-for="group in assetGroups"
      :key="group.category"
      :ref="el => setAssetGroupRef(group.category, el)"
      :data-asset-section="group.category"
      class="asset-gallery-section"
    >
      <header>
        <h3>{{ categoryLabel(group.category) }}</h3>
        <span>{{ group.items.length }} 个</span>
        <button
v-if="canGroupByTag(group)"
type="button"
class="view-mode-toggle"
@click="$emit('toggle-view-mode')">
          {{ viewMode === 'grid' ? '分组视图' : '网格视图' }}
        </button>
      </header>
      <template v-for="(subGroup, sgIdx) in group.subGroups" :key="sgIdx">
        <div
          v-if="viewMode === 'grouped' && canGroupByTag(group) && subGroup.tag !== null"
          class="tag-sub-group-header"
          :class="{ collapsed: isTagGroupCollapsed(subGroup.tag) }"
          @click="$emit('toggle-tag-group-collapse', subGroup.tag)"
        >
          <span class="collapse-icon">{{ isTagGroupCollapsed(subGroup.tag) ? '\u25B6' : '\u25BC' }}</span>
          <strong>{{ subGroup.tag }}</strong>
          <span class="sub-group-count">{{ subGroup.items.length }} 张</span>
          <button
            v-if="batchMode"
            type="button"
            class="sub-group-select-all"
            @click.stop="$emit('select-group-assets', subGroup.items)"
          >
            全选
          </button>
        </div>
        <div
          v-if="viewMode === 'grouped' && canGroupByTag(group) && subGroup.tag === null"
          class="tag-sub-group-header ungrouped-header"
        >
          <span class="collapse-icon" style="opacity: 0">.</span>
          <strong>未分组</strong>
          <span class="sub-group-count">{{ subGroup.items.length }} 张</span>
        </div>
        <div
          v-show="
            !(
              viewMode === 'grouped' &&
              canGroupByTag(group) &&
              subGroup.tag !== null &&
              isTagGroupCollapsed(subGroup.tag)
            )
          "
          class="asset-preview-grid"
          :class="{ 'ungrouped-grid': viewMode === 'grouped' && canGroupByTag(group) && subGroup.tag === null }"
        >
          <article
            v-for="asset in subGroup.items"
            :key="asset.id"
            class="asset-preview-card"
            :class="{
              image: isPreviewableImageAsset(asset),
              document: !isPreviewableImageAsset(asset),
              active: asset.defaultAsset,
              selected: batchMode && selectedIds.has(asset.id),
              highlighted: highlightedId === asset.id,
              'menu-open': menuOpenId === asset.id,
              'drag-src': dragAssetId === asset.id,
              'drag-over': dragOverAssetId === asset.id
            }"
            :draggable="!batchMode"
            @dragstart.stop="$emit('card-drag-start', asset, $event)"
            @dragover.prevent.stop="$emit('card-drag-over', asset, $event)"
            @dragleave="$emit('card-drag-leave', asset)"
            @drop.prevent.stop="$emit('card-drop', asset, $event)"
            @dragend="$emit('card-drag-end')"
          >
            <div v-if="batchMode" class="asset-batch-check" @click.stop="$emit('toggle-asset-select', asset.id)">
              <input type="checkbox" :checked="selectedIds.has(asset.id)" />
            </div>
            <button
              v-if="batchMode"
              type="button"
              class="asset-select-hitarea"
              :aria-label="selectedIds.has(asset.id) ? '取消选择素材' : '选择素材'"
              @click.stop="$emit('toggle-asset-select', asset.id)"
            ></button>
            <span v-if="asset.defaultAsset" class="asset-default-badge">默认</span>
            <span v-if="asset.websiteVisible === false" class="asset-website-hidden-badge">官网隐藏</span>
            <span v-if="duplicateFileNames.has(asset.fileName)" class="asset-dup-badge">重复</span>
            <button type="button" class="asset-preview-face" @click="$emit('preview', asset)">
              <span class="asset-ext-corner">{{ fileExtLabel(asset.fileName) }}</span>
              <LqThumbnail
                :src="thumbnailUrlFor(asset)"
                :file-name="asset.fileName"
                :renderable="isThumbnailPreviewable(asset)"
                :fallback-label="fileTypeLabel(asset)"
                object-fit="cover"
              />
              <div v-if="!batchMode" class="card-hover-toolbar">
                <button type="button" class="htb-btn" @click.stop="$emit('preview', asset)">
                  <svg
viewBox="0 0 24 24"
width="14"
height="14"
fill="none"
stroke="currentColor"
stroke-width="2">
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                    <circle cx="12" cy="12" r="3" />
                  </svg>
                  <span>预览</span>
                </button>
                <button
                  type="button"
                  class="htb-btn"
                  :class="{ active: asset.defaultAsset }"
                  @click.stop="$emit('set-default', asset)"
                >
                  <svg
viewBox="0 0 24 24"
width="14"
height="14"
fill="none"
stroke="currentColor"
stroke-width="2">
                    <polygon
                      points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"
                    />
                  </svg>
                  <span>{{ asset.defaultAsset ? '已默认' : '设默认' }}</span>
                </button>
                <button type="button" class="htb-btn" @click.stop="$emit('open-tag-editor', asset)">
                  <svg
viewBox="0 0 24 24"
width="14"
height="14"
fill="none"
stroke="currentColor"
stroke-width="2">
                    <path d="M20.59 13.41l-7.17 7.17a2 2 0 01-2.83 0L2 12V2h10l8.59 8.59a2 2 0 010 2.82z" />
                    <line
x1="7"
y1="7"
x2="7.01"
y2="7" />
                  </svg>
                  <span>标签</span>
                </button>
                <button type="button" class="htb-btn htb-more" @click.stop="$emit('toggle-menu', asset, $event)">
                  <svg
viewBox="0 0 24 24"
width="14"
height="14"
fill="none"
stroke="currentColor"
stroke-width="2">
                    <circle cx="12" cy="5" r="1" />
                    <circle cx="12" cy="12" r="1" />
                    <circle cx="12" cy="19" r="1" />
                  </svg>
                  <span>更多</span>
                </button>
              </div>
            </button>
            <div class="asset-card-body">
              <strong :title="asset.title || asset.fileName">{{ asset.title || asset.fileName }}</strong>
              <div v-if="assetTags(asset).length" class="asset-tag-list">
                <span v-for="tag in assetTags(asset)" :key="tag">{{ tag }}</span>
              </div>
              <small>
                <SourceTrace
                  :source-file="asset.sourceFile"
                  :source-sheet="asset.sourceSheet"
                  :source-row-number="asset.sourceRowNumber"
                  :import-batch-id="asset.importBatchId"
                  fallback="手动上传"
                />
                <span v-if="duplicateFileNames.has(asset.fileName)" class="asset-dup-text">同名文件</span>
              </small>
            </div>
          </article>
        </div>
      </template>
      <div v-if="group.items.length === 0" class="empty-section-hint">暂无{{ categoryLabel(group.category) }}</div>
    </section>
    <p v-if="assets.length === 0" class="empty-line">暂无素材附件</p>
    <p v-else-if="assetGroups.length === 0" class="empty-line">当前类型暂无素材</p>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { FileOwnerType } from '../../types/fileAsset'
import { assetTags } from '../../utils/catalog/assetUtils'
import { productFileOwnerType } from '../../utils/catalog/fileAssetUtils'
import { fileAssetThumbnailUrlByOptions } from '../../utils/ui/fileAssetUrls'
import SourceTrace from '../common/SourceTrace.vue'
import LqThumbnail from '../common/LqThumbnail.vue'
import { IMAGE_ASSET_CATEGORIES } from '../../constants/fields'

type AssetId = number

interface CatalogAssetView {
  id: AssetId
  modelId?: number | null
  ownerType?: string | null
  variantId?: number | null
  category: string
  fileName: string
  defaultAsset?: boolean
  tags?: string
  sourceFile?: string
  sourceSheet?: string
  sourceRowNumber?: number
  importBatchId?: number | string | null
  coverFileName?: string | null
  title?: string | null
  docTypeName?: string | null
  [key: string]: unknown
}

interface AssetCategoryOption {
  key: string
  label: string
  count?: number
}

interface AssetSubGroup {
  tag: string | null
  items: CatalogAssetView[]
}

interface AssetGroup {
  category: string
  label: string
  items: CatalogAssetView[]
  subGroups: AssetSubGroup[]
}

const PREVIEW_IMAGE_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg'])
const THUMBNAIL_EXTENSIONS = new Set(['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg', 'pdf'])

const props = defineProps<{
  assetGroups: AssetGroup[]
  assets: CatalogAssetView[]
  viewMode: 'grid' | 'grouped'
  batchMode: boolean
  selectedIds: Set<number>
  highlightedId: number | null
  menuOpenId: number | null
  dragAssetId: number | null
  dragOverAssetId: number | null
  duplicateFileNames: Set<string>
  collapsedTagGroups: Set<string>
  categories: AssetCategoryOption[]
  modelId: number | null
}>()

const emit = defineEmits<{
  'toggle-asset-select': [id: number]
  preview: [asset: CatalogAssetView]
  'set-default': [asset: CatalogAssetView]
  'open-tag-editor': [asset: CatalogAssetView]
  'toggle-menu': [asset: CatalogAssetView, event: MouseEvent]
  'toggle-view-mode': []
  'toggle-tag-group-collapse': [tag: string]
  'select-group-assets': [items: CatalogAssetView[]]
  'card-drag-start': [asset: CatalogAssetView, event: DragEvent]
  'card-drag-over': [asset: CatalogAssetView, event: DragEvent]
  'card-drag-leave': [asset: CatalogAssetView]
  'card-drop': [asset: CatalogAssetView, event: DragEvent]
  'card-drag-end': []
  'image-error': [url: string]
  'set-asset-group-ref': [category: string, el: Element | null]
  'gallery-ref': [el: HTMLElement | null]
}>()

const galleryRefEl = ref<HTMLElement | null>(null)

function setAssetGroupRef(category: string, el: Element | { $el?: Element } | null) {
  const element = el instanceof Element ? el : el?.$el instanceof Element ? el.$el : null
  emit('set-asset-group-ref', category, element)
}

function categoryLabel(cat: string) {
  return props.categories.find(c => c.key === cat)?.label || cat
}

function isImageAsset(category: string) {
  return IMAGE_ASSET_CATEGORIES.includes(category)
}

function fileExtension(fileName: string | null | undefined) {
  const text = String(fileName || '')
  const dot = text.lastIndexOf('.')
  return dot >= 0 ? text.slice(dot + 1).toLowerCase() : ''
}

function fileExtLabel(fileName: string | null | undefined) {
  return fileExtension(fileName).toUpperCase() || 'FILE'
}

function isPreviewableImageAsset(asset: CatalogAssetView) {
  return isImageAsset(asset.category) || PREVIEW_IMAGE_EXTENSIONS.has(fileExtension(asset.fileName))
}

function fileOwnerTypeForAsset(asset: CatalogAssetView): FileOwnerType {
  return productFileOwnerType(asset)
}

function thumbnailUrlFor(asset: CatalogAssetView) {
  return fileAssetThumbnailUrlByOptions({
    ownerType: fileOwnerTypeForAsset(asset),
    assetId: asset?.id || null,
    category: asset.category,
    fileName: asset.fileName,
    modelId: props.modelId,
    size: 480
  })
}

function isThumbnailPreviewable(asset: CatalogAssetView) {
  if (!asset || !asset.id) return false
  // 有自定义封面时，后端 thumbnail 端点会直接返回封面图片，前端应渲染 <img>
  if (asset.coverFileName) return true
  return isImageAsset(asset.category) || THUMBNAIL_EXTENSIONS.has(fileExtension(asset.fileName))
}

function canGroupByTag(group: AssetGroup) {
  return isImageAsset(group.category) && group.items.length > 0
}

function isTagGroupCollapsed(tagKey: string | null) {
  if (tagKey === null) return false
  return props.collapsedTagGroups.has(tagKey)
}

function fileTypeLabel(asset: CatalogAssetView) {
  const category = asset.category
  const ext = fileExtLabel(asset.fileName)
  const imageTypes = IMAGE_ASSET_CATEGORIES
  const videoTypes = ['device_video', 'operation_video']
  if (imageTypes.includes(category) || PREVIEW_IMAGE_EXTENSIONS.has(fileExtension(asset.fileName))) return `${ext} 图片`
  if (videoTypes.includes(category)) return '视频'
  if (category === 'manual') return '文档'
  if (category === 'brochure') return '彩页'
  if (category === 'drawing') return `${ext} 图纸`
  if (category === 'certificate') return '证书'
  return `${ext} 文件`
}
</script>

<style scoped>
@import '../../styles/asset-gallery.css';
</style>
