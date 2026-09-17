<!--
  LqPreview - 统一预览组件
  用于图片、文档、资源等的全屏预览。

  使用方式：
    <LqPreview
      v-model="visible"
      title="产品图片预览"
      subtitle="主图 - front_view.jpg"
      :url="imageUrl"
      :file-name="front_view.jpg"
      :gallery-index="0"
      :gallery-count="5"
      @prev="handlePrev"
      @next="handleNext"
      @close="handleClose"
    >
      <template #actions>
        <button>下载</button>
        <button>删除</button>
      </template>
    </LqPreview>

  也可以用 slot 完全自定义内容：
    <LqPreview v-model="visible" title="对比预览">
      <div class="custom-content">自定义内容</div>
    </LqPreview>
-->
<template>
  <Teleport to="body">
    <Transition name="lq-preview">
      <div v-if="modelValue" class="lq-preview" data-testid="file-asset-preview-modal" role="dialog" aria-modal="true"
        :aria-label="title || '预览'" tabindex="-1" @click.self="handleClose" @keydown.esc="handleClose"
        @keydown.left="handleGalleryPrev" @keydown.right="handleGalleryNext">
        <div class="lq-preview__dialog">
          <header class="lq-preview__header">
            <div class="lq-preview__title">
              <span v-if="eyebrow" class="lq-preview__eyebrow">{{ eyebrow }}</span>
              <span v-if="title" class="lq-preview__title-text" :title="title">{{ title }}</span>
              <p v-if="subtitle" class="lq-preview__subtitle">{{ subtitle }}</p>
            </div>
            <div class="lq-preview__actions">
              <button v-if="canOpenFolder" type="button" class="lq-preview__action" data-testid="file-asset-open-folder"
                :disabled="openingFolder" @click="openFolder">
                {{ openingFolder ? '&#27491;&#22312;&#25171;&#24320;...' : '&#25171;&#24320;&#25991;&#20214;&#22841;' }}
              </button>
              <a v-if="url && showOpenInNewWindow" :href="url" target="_blank" rel="noopener"
                class="lq-preview__action">
                &#26032;&#31383;&#21475;
              </a>
              <button v-if="canDownload" type="button" class="lq-preview__action" :disabled="downloading"
                @click="downloadFile">
                {{ downloading ? '&#27491;&#22312;&#19979;&#36733;...' : '&#19979;&#36733;' }}
              </button>
              <slot name="actions" />
              <span v-if="galleryCount > 1" class="lq-preview__counter">
                {{ galleryIndex + 1 }} / {{ galleryCount }}
              </span>
              <button v-if="galleryCount > 1" type="button" class="lq-preview__nav-btn" title="上一张"
                @click="handleGalleryPrev">
                <svg viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">
                  <path d="M10 4L6 8l4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"
                    stroke-linejoin="round" />
                </svg>
              </button>
              <button v-if="galleryCount > 1" type="button" class="lq-preview__nav-btn" title="下一张"
                @click="handleGalleryNext">
                <svg viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">
                  <path d="M6 4l4 4-4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"
                    stroke-linejoin="round" />
                </svg>
              </button>
              <button v-if="showClose" type="button" class="lq-preview__close" aria-label="关闭" @click="handleClose">
                <svg viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">
                  <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
                </svg>
              </button>
            </div>
          </header>

          <div class="lq-preview__body" :class="{ 'has-toolbar': showImageToolbar }" :style="imageToolbarStyle">
            <div v-if="showImageToolbar" class="lq-preview__toolbar" aria-label="图片预览工具">
              <button type="button" class="tool-btn" title="缩小" @click="zoomOut">−</button>
              <span class="tool-value">{{ Math.round(imageScale * 100) }}%</span>
              <button type="button" class="tool-btn" title="放大" @click="zoomIn">+</button>
              <button type="button" class="tool-btn" title="向左旋转" @click="rotateLeft">左转</button>
              <button type="button" class="tool-btn" title="向右旋转" @click="rotateRight">右转</button>
              <button type="button" class="tool-btn" title="重置" @click="resetTransform">重置</button>
            </div>
            <div class="lq-preview__content">
              <slot>
                <FilePreviewBody v-if="url" :url="url" :file-name="fileName" :title="title" :preview-type="previewType"
                  :loading="loading" :html-content="htmlContent" :text-content="textContent"
                  :error-message="errorMessage" />
                <div v-else class="lq-preview__empty">
                  <EmptyState description="没有可预览的内容" />
                </div>
              </slot>
            </div>
          </div>

          <footer v-if="$slots.footer" class="lq-preview__footer">
            <slot name="footer" />
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import FilePreviewBody from './FilePreviewBody.vue'
import EmptyState from './EmptyState.vue'
import { filePreviewType } from '@/utils/ui/fileUtils'
import { notify } from '@/composables/ui/useToast'
import { FILE_ASSET_FAILURE_PREFIX } from '@/constants/fileAssetMessages'
import {
  fileAssetDownloadUrlById,
  fileAssetOpenFolderMessage,
  openFileAssetFolderByOptions
} from '@/utils/ui/fileAssetUrls'
import { isFileOwnerType } from '@/types/fileAsset'
import { downloadByUrl } from '@/utils/ui/downloadBlob'

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    eyebrow?: string
    title?: string
    subtitle?: string
    url?: string
    fileName?: string
    previewType?: string
    loading?: boolean
    htmlContent?: string
    textContent?: string | null
    errorMessage?: string
    ownerType?: string
    assetId?: number | null
    category?: string
    modelId?: number | null
    showOpenInNewWindow?: boolean
    showDownload?: boolean
    galleryIndex?: number
    galleryCount?: number
    showClose?: boolean
    /** 是否显示图片工具栏（默认图片类型自动显示） */
    showImageToolbar?: boolean
  }>(),
  {
    eyebrow: '',
    title: '预览',
    subtitle: '',
    url: '',
    fileName: '',
    previewType: '',
    loading: false,
    htmlContent: '',
    textContent: null,
    errorMessage: '',
    ownerType: '',
    assetId: null,
    category: '',
    modelId: null,
    showOpenInNewWindow: true,
    showDownload: true,
    galleryIndex: 0,
    galleryCount: 0,
    showClose: true,
    showImageToolbar: undefined
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  close: []
  prev: []
  next: []
}>()

const imageScale = ref(1)
const imageRotation = ref(0)
const openingFolder = ref(false)
const downloading = ref(false)
let lastFocused: HTMLElement | null = null
let previousBodyOverflow = ''

const hasFileIdentity = computed(() => isFileOwnerType(props.ownerType) && Boolean(props.assetId))
const canOpenFolder = computed(() => Boolean(hasFileIdentity.value || (props.category && props.fileName)))
const canDownload = computed(() => props.showDownload && Boolean(props.url) && hasFileIdentity.value)

const resolvedPreviewType = computed(() => props.previewType || filePreviewType(props.fileName))

const showImageToolbar = computed(() => {
  if (props.showImageToolbar !== undefined) return props.showImageToolbar
  return resolvedPreviewType.value === 'image'
})

const imageToolbarStyle = computed(() => ({
  '--img-scale': imageScale.value,
  '--img-rotate': `${imageRotation.value}deg`
}))

async function downloadFile() {
  if (!props.url || downloading.value) return
  downloading.value = true
  try {
    if (!isFileOwnerType(props.ownerType) || !props.assetId) return
    const downloadUrl = fileAssetDownloadUrlById(props.ownerType, props.assetId)
    await downloadByUrl(downloadUrl, props.fileName || props.title || 'download')
  } catch (error) {
    notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.download, error)
  } finally {
    downloading.value = false
  }
}

async function openFolder() {
  if (!canOpenFolder.value || openingFolder.value) return
  openingFolder.value = true
  try {
    const result = await openFileAssetFolderByOptions({
      ownerType: props.ownerType,
      assetId: props.assetId,
      category: props.category,
      fileName: props.fileName,
      modelId: props.modelId
    })
    const feedback = fileAssetOpenFolderMessage(result)
    notify(feedback.message, feedback.type)
  } catch (error) {
    notify.errorWithDetail(FILE_ASSET_FAILURE_PREFIX.openFolder, error)
  } finally {
    window.setTimeout(() => {
      openingFolder.value = false
    }, 2000)
  }
}

function handleClose() {
  emit('update:modelValue', false)
  emit('close')
}

function handleGalleryPrev() {
  if (props.galleryCount > 1) emit('prev')
}

function handleGalleryNext() {
  if (props.galleryCount > 1) emit('next')
}

function zoomIn() {
  imageScale.value = Math.min(3, Number((imageScale.value + 0.1).toFixed(2)))
}

function zoomOut() {
  imageScale.value = Math.max(0.2, Number((imageScale.value - 0.1).toFixed(2)))
}

function rotateLeft() {
  imageRotation.value = imageRotation.value - 90
}

function rotateRight() {
  imageRotation.value = imageRotation.value + 90
}

function resetTransform() {
  imageScale.value = 1
  imageRotation.value = 0
}

watch(
  () => props.modelValue,
  async val => {
    if (val) {
      lastFocused = document.activeElement as HTMLElement
      previousBodyOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
      await nextTick()
      const el = document.querySelector('.lq-preview') as HTMLElement | null
      el?.focus()
    } else {
      document.body.style.overflow = previousBodyOverflow
      if (lastFocused) {
        lastFocused.focus()
        lastFocused = null
      }
      resetTransform()
    }
  }
)

onMounted(async () => {
  if (props.modelValue) {
    lastFocused = document.activeElement as HTMLElement
    previousBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    await nextTick()
    const el = document.querySelector('.lq-preview') as HTMLElement | null
    el?.focus()
  }
})

onUnmounted(() => {
  document.body.style.overflow = previousBodyOverflow
})
</script>

<style scoped>
.lq-preview {
  position: fixed;
  inset: 0;
  z-index: var(--z-preview);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-section-gap-wide);
  background: var(--color-overlay);
  backdrop-filter: blur(6px);
  outline: none;
}

.lq-preview__dialog {
  display: flex;
  flex-direction: column;
  width: min(1200px, 92vw);
  max-height: 90vh;
  overflow: hidden;
  border-radius: var(--radius-xl, 14px);
  background: var(--color-surface);
  box-shadow: 0 32px 80px rgba(15, 23, 42, 0.4);
}

.lq-preview__header {
  display: flex;
  align-items: flex-start;
  flex-shrink: 0;
  gap: var(--space-md);
  min-height: 56px;
  padding: 14px 20px 14px 22px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
}

.lq-preview__title {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}

.lq-preview__eyebrow {
  display: inline-flex;
  align-items: center;
  flex: 0 0 auto;
  width: fit-content;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-bold);
  line-height: 1.4;
  white-space: nowrap;
}

.lq-preview__title-text {
  overflow: hidden;
  color: var(--color-text-heading);
  font-size: var(--fs-md);
  font-weight: var(--fw-extrabold);
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lq-preview__subtitle {
  margin: 0;
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lq-preview__actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: var(--space-control-gap);
}

.lq-preview__action {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 0 var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  color: var(--color-text-primary);
  background: var(--color-surface);
  cursor: pointer;
  text-decoration: none;
}

.lq-preview__action:disabled {
  cursor: wait;
  opacity: 0.6;
}

.lq-preview__counter {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 10px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-size: var(--fs-xxs);
  font-weight: var(--fw-bold);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.lq-preview__nav-btn,
.lq-preview__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  transition: all 0.15s ease;
}

.lq-preview__nav-btn:hover,
.lq-preview__close:hover {
  border-color: var(--color-border);
  background: var(--color-surface-hover);
  color: var(--color-text-strong);
}

.lq-preview__body {
  display: grid;
  grid-template-rows: minmax(0, 1fr);
  flex: 1 1 auto;
  min-height: 0;
  background: var(--color-surface-alt);
}

.lq-preview__body.has-toolbar {
  grid-template-rows: auto minmax(0, 1fr);
}

.lq-preview__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  padding: var(--space-md) var(--space-lg) 0;
}

.tool-btn {
  min-width: 32px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-strong);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  cursor: pointer;
  transition: all 0.15s ease;
}

.tool-btn:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.tool-value {
  min-width: 52px;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.lq-preview__content {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--space-section-gap);
}

.lq-preview__content > :deep(.file-preview-body) {
  padding: 0;
  background: transparent;
}

.lq-preview__content > :deep(.file-preview-body--image) {
  align-items: flex-start;
}

.lq-preview__content > :deep(.file-preview-body--image) .preview-image-stage {
  min-height: 0;
}

.lq-preview__content > :deep(.file-preview-body--image) .preview-image-toolbar {
  display: none;
}

.lq-preview__content > :deep(.file-preview-body--image) .preview-image {
  max-height: calc(90vh - 180px);
  transform: scale(var(--img-scale, 1)) rotate(var(--img-rotate, 0deg));
}

.lq-preview__empty {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
}

.lq-preview__footer {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-control-gap);
  padding: 12px 22px;
  border-top: 1px solid var(--color-border);
  background: var(--color-surface);
}

.lq-preview-enter-active,
.lq-preview-leave-active {
  transition: opacity 0.18s ease;
}

.lq-preview-enter-active .lq-preview__dialog,
.lq-preview-leave-active .lq-preview__dialog {
  transition:
    transform 0.22s ease,
    opacity 0.18s ease;
}

.lq-preview-enter-from,
.lq-preview-leave-to {
  opacity: 0;
}

.lq-preview-enter-from .lq-preview__dialog,
.lq-preview-leave-to .lq-preview__dialog {
  opacity: 0;
  transform: translateY(12px) scale(0.98);
}

@media (max-width: 767px) {
  .lq-preview {
    padding: var(--space-md);
  }

  .lq-preview__header,
  .lq-preview__footer {
    padding: 10px 14px;
  }

  .lq-preview__actions {
    flex-wrap: wrap;
  }
}
</style>
