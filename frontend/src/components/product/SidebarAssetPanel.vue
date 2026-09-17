<script setup lang="ts">
/**
 * SidebarAssetPanel — 产品编辑器侧边栏的资料附件面板
 * 从 ProductEditorModal 提取，包含主图上传/切换/清空 和 默认说明书上传/预览/清空。
 *
 * Props:
 *   form             — reactive 编辑表单对象（包含 defaultImage）
 *   modelId          — 产品ID，用于文件URL
 *   imageUploading   — 主图上传中状态
 *   imageBroken      — 主图加载失败标记
 *   mainImageAssets  — 已有的主图素材列表
 *   mode             — 'create' | 'edit'
 *
 * Emits:
 *   uploadImage      — 上传主图文件事件
 *   chooseImage      — 选择已有主图作为默认
 *   previewAsset     — 预览素材（{ category, fileName }）
 *   update:dirty     — 标记表单已修改
 */
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { fileAssetThumbnailUrlByOptions } from '../../utils/ui/fileAssetUrls'
import { productFileOwnerType } from '../../utils/catalog/fileAssetUtils'
import { useLocalDropUpload } from '../../composables/upload/useLocalDropUpload'
import { filesFromClipboardEvent, isEditableElement } from '../../composables/upload/useFileUploadWorkflow'
import { ASSET_CATEGORY_KEYS } from '../../constants/fields'

const MAIN_IMAGE_PAGE_SIZE = 3

interface SidebarForm {
  name?: string
  defaultImage?: string
}

interface MainImageAsset {
  id?: number | null
  fileName: string
}

interface PreviewPayload {
  category: string
  fileName: string
  modelId?: number | null
  ownerType?: 'product_model' | 'product_variant'
  assetId?: number
}

type UploadLikeEvent = Event | { target: { files: File[] } }

const props = withDefaults(
  defineProps<{
    form: SidebarForm
    modelId?: number | null
    imageUploading?: boolean
    manualUploading?: boolean
    mainImageAssets?: MainImageAsset[]
    mode?: 'create' | 'edit' | string
  }>(),
  {
    modelId: null,
    imageUploading: false,
    manualUploading: false,
    mainImageAssets: () => [],
    mode: 'edit'
  }
)

const emit = defineEmits<{
  uploadImage: [event: UploadLikeEvent]
  chooseImage: [fileName: string]
  previewAsset: [payload: PreviewPayload]
  clearImage: []
  'update:dirty': [dirty: boolean]
}>()

const mainImageExpanded = ref(false)
const imageBroken = ref(false)

const imageDrop = useLocalDropUpload({
  accept: 'image/*',
  onFiles: files => {
    emit('uploadImage', { target: { files } })
  }
})

const visibleMainImages = computed(() =>
  mainImageExpanded.value ? props.mainImageAssets : props.mainImageAssets.slice(0, MAIN_IMAGE_PAGE_SIZE)
)

const mainImageHidden = computed(() => Math.max(0, props.mainImageAssets.length - MAIN_IMAGE_PAGE_SIZE))

const defaultImageAsset = computed(
  () => props.mainImageAssets.find(asset => asset?.fileName === props.form.defaultImage) || null
)

watch(
  () => props.mode,
  () => {
    mainImageExpanded.value = false
    imageBroken.value = false
  }
)
watch(
  () => props.form.defaultImage,
  () => {
    imageBroken.value = false
  }
)

function handleImageUpload(e: Event) {
  emit('uploadImage', e)
}
function handlePaste(e: ClipboardEvent) {
  if (props.imageUploading) return
  if (isEditableElement()) return
  const files = filesFromClipboardEvent(e, 'image/*')
  if (!files.length) return
  e.preventDefault()
  emit('uploadImage', { target: { files } })
}

onMounted(() => {
  document.addEventListener('paste', handlePaste)
})

onBeforeUnmount(() => {
  document.removeEventListener('paste', handlePaste)
})
function chooseImage(fileName: string) {
  emit('update:dirty', true)
  emit('chooseImage', fileName)
}
function clearImage() {
  emit('clearImage')
  emit('update:dirty', true)
}
function mainImageThumbnailUrl(asset: MainImageAsset, size?: number) {
  return fileAssetThumbnailUrlByOptions({
    ownerType: productFileOwnerType(asset),
    assetId: asset?.id || null,
    category: ASSET_CATEGORY_KEYS.mainImage,
    fileName: asset?.fileName || '',
    modelId: props.modelId,
    size
  })
}
function previewDefaultImage() {
  if (!props.form.defaultImage) return
  const asset = defaultImageAsset.value
  const ownerType = productFileOwnerType(asset) as 'product_model' | 'product_variant'
  emit('previewAsset', {
    category: ASSET_CATEGORY_KEYS.mainImage,
    fileName: props.form.defaultImage,
    modelId: props.modelId,
    ownerType: asset?.id ? ownerType : undefined,
    assetId: asset?.id || undefined
  })
}
</script>

<template>
  <div class="sidebar-asset-panel">
    <div class="sidebar-cover-card" :class="{ 'drag-over': imageDrop.isDragOver }" v-bind="imageDrop.dragHandlers">
      <div class="sidebar-card-title">
        <span>产品主图</span>
        <em :class="{ done: form.defaultImage }">{{ form.defaultImage ? '已设置' : '待上传' }}</em>
      </div>
      <button
        type="button"
        class="sidebar-cover-preview"
        :disabled="!form.defaultImage || imageBroken"
        @click="form.defaultImage && !imageBroken && previewDefaultImage()"
      >
        <img
          v-if="form.defaultImage && !imageBroken"
          :src="mainImageThumbnailUrl(defaultImageAsset || { fileName: form.defaultImage }, 480)"
          :alt="form.name || '产品主图'"
          decoding="async"
          @error="imageBroken = true"
        />
        <span v-else>{{ form.defaultImage ? '文件未找到' : '暂无主图' }}</span>
      </button>
      <div class="sidebar-asset-actions">
        <p>{{ form.defaultImage || '未设置产品主图' }}</p>
        <div class="sidebar-action-row">
          <label class="file-button">
            {{ imageUploading ? '上传中...' : '上传/替换' }}
            <input
type="file"
accept="image/*"
:disabled="imageUploading"
@change="handleImageUpload" />
          </label>
          <button v-if="form.defaultImage" type="button" @click="clearImage">清空</button>
        </div>
      </div>
    </div>

    <div
      v-if="mode === 'edit' && mainImageAssets.length"
      class="sidebar-image-strip"
      :class="{ expanded: mainImageExpanded }"
    >
      <button
        v-for="asset in visibleMainImages"
        :key="asset.id || asset.fileName"
        type="button"
        :class="{ active: form.defaultImage === asset.fileName }"
        @click="chooseImage(asset.fileName)"
      >
        <img :src="mainImageThumbnailUrl(asset)" :alt="asset.fileName" />
      </button>
      <button
        v-if="mainImageHidden > 0 && !mainImageExpanded"
        type="button"
        class="sidebar-more-button"
        @click="mainImageExpanded = true"
      >
        +{{ mainImageHidden }}
      </button>
      <button
        v-else-if="mainImageExpanded && mainImageAssets.length > MAIN_IMAGE_PAGE_SIZE"
        type="button"
        class="sidebar-more-button"
        @click="mainImageExpanded = false"
      >
        收起
      </button>
    </div>

  </div>
</template>

<style scoped>
.sidebar-asset-panel {
  display: grid;
  gap: var(--space-card-gap);
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
}

.sidebar-card-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap);
}

.sidebar-card-title span {
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
  font-weight: var(--fw-extrabold);
}

.sidebar-card-title em {
  padding: 2px 7px;
  border-radius: var(--radius-pill);
  background: var(--color-border);
  color: var(--color-text-muted);
  font-style: normal;
  font-size: var(--fs-xxs);
  font-weight: var(--fw-bold);
  white-space: nowrap;
}

.sidebar-card-title em.done {
  background: var(--color-primary-bg-light);
  color: var(--color-primary);
}

.sidebar-cover-card {
  display: grid;
  gap: var(--space-control-gap);
  padding: 0;
  border: none;
  border-radius: 0;
  background: transparent;
  transition:
    border-color 0.15s,
    box-shadow 0.15s,
    background 0.15s;
}

.sidebar-cover-card.drag-over,
.sidebar-manual-row.drag-over {
  border: 1px dashed var(--color-primary);
  border-radius: var(--radius-md);
  background: var(--color-primary-bg);
  padding: 8px;
}

.sidebar-cover-preview {
  display: grid;
  place-items: center;
  width: 100%;
  aspect-ratio: 4 / 3;
  padding: 0;
  overflow: hidden;
  border: none;
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-muted);
  cursor: pointer;
  transition:
    box-shadow 0.15s ease,
    transform 0.15s ease;
}

.sidebar-cover-preview:not(:disabled):hover {
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.1);
  transform: translateY(-1px);
}

.sidebar-cover-preview:disabled {
  cursor: default;
}

.sidebar-cover-preview img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  filter: drop-shadow(0 10px 14px rgba(15, 23, 42, 0.1));
}

.sidebar-cover-preview span {
  font-size: var(--fs-xs);
  padding: var(--space-control-gap);
}

.sidebar-asset-actions {
  display: grid;
  gap: var(--space-control-gap);
}

.sidebar-action-row,
.sidebar-manual-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.sidebar-asset-actions p,
.sidebar-manual-row p {
  width: 100%;
  margin: 0;
  overflow: hidden;
  color: var(--color-text-strong);
  font-size: var(--fs-xs);
  line-height: var(--lh-normal);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-asset-actions button,
.sidebar-manual-actions button {
  min-height: 28px;
  padding: 5px 8px;
  border: none;
  border-radius: 6px;
  font-size: var(--fs-xs);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  transition: color 0.15s ease;
}

.sidebar-asset-actions button:hover,
.sidebar-manual-actions button:hover {
  color: var(--color-primary);
  background: var(--color-primary-bg);
}

.sidebar-asset-actions .file-button,
.sidebar-manual-actions .file-button {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  min-height: 28px;
  padding: 5px 10px;
  border-radius: 6px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  white-space: nowrap;
  background: var(--color-primary);
  color: var(--color-surface);
  cursor: pointer;
}

.sidebar-asset-actions .file-button input,
.sidebar-manual-actions .file-button input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  border: 0;
  margin: -1px;
  overflow: hidden;
  opacity: 0;
  clip-path: inset(50%);
  white-space: nowrap;
}

.sidebar-image-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 6px;
  padding: 0;
  overflow: hidden;
}

.sidebar-image-strip:not(.expanded) {
  grid-auto-rows: 40px;
  max-height: 44px;
}

.sidebar-image-strip.expanded {
  overflow: visible;
}

.sidebar-image-strip button {
  display: grid;
  place-items: center;
  aspect-ratio: 1;
  padding: 2px;
  border: none;
  border-radius: 6px;
  background: var(--color-surface);
  cursor: pointer;
  transition: box-shadow 0.15s ease;
}

.sidebar-image-strip button.active {
  box-shadow: 0 0 0 2px var(--color-primary-ring);
}

.sidebar-image-strip img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.sidebar-more-button {
  background: var(--color-surface) !important;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  border: none !important;
  box-shadow: none !important;
}

.sidebar-more-button:hover {
  background: var(--color-primary-bg) !important;
  color: var(--color-primary);
}

.sidebar-manual-row {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: var(--space-control-gap);
  align-items: center;
  padding: 8px 0;
  border: none;
  border-radius: 0;
  background: transparent;
}

.sidebar-card-title-full {
  grid-column: 1 / -1;
}

.sidebar-manual-row .catalog-file-icon {
  width: 40px;
  height: 40px;
  border-radius: var(--radius-lg);
  border: none;
  background: var(--color-surface);
  font-size: var(--fs-xs);
}
</style>
