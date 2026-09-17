<template>
  <div class="file-preview-body" :class="`file-preview-body--${resolvedPreviewType}`">
    <div v-if="displayLoading" class="preview-loading">
      <div class="preview-spinner"></div>
      <span>正在加载预览...</span>
    </div>

    <div v-else-if="displayError" class="preview-unsupported">
      <div class="unsupported-icon">!</div>
      <p class="unsupported-text">预览失败</p>
      <p class="unsupported-hint">{{ displayError }}</p>
      <div class="preview-actions">
        <a
v-if="url"
:href="url"
target="_blank"
rel="noopener"
class="btn-sm">新窗口打开</a>
        <a
v-if="url"
:href="url"
download
class="btn-sm primary">下载文件</a>
      </div>
    </div>

    <section v-else-if="resolvedPreviewType === 'image' && !imageLoadError" class="preview-image-stage">
      <div class="preview-image-toolbar" aria-label="图片预览工具">
        <button
type="button"
class="tool-button"
title="缩小"
@click="zoomOut">-</button>
        <span class="zoom-value">{{ Math.round(imageScale * 100) }}%</span>
        <button
type="button"
class="tool-button"
title="放大"
@click="zoomIn">+</button>
        <button
type="button"
class="tool-button"
title="向左旋转"
@click="rotateLeft">左转</button>
        <button
type="button"
class="tool-button"
title="向右旋转"
@click="rotateRight">右转</button>
        <button
type="button"
class="tool-button"
title="重置"
@click="resetImageTransform">重置</button>
      </div>
      <div class="preview-image-scroll">
        <img
:src="url"
class="preview-image"
:style="imageStyle"
:alt="title"
@error="imageLoadError = true" />
      </div>
    </section>

    <div v-else-if="resolvedPreviewType === 'pdf'" class="preview-iframe-wrap">
      <iframe
:src="url"
class="preview-pdf"
@load="onIframeLoad"
@error="onIframeError"></iframe>
      <div v-if="iframeLoading" class="preview-iframe-overlay">
        <div class="preview-spinner"></div>
        <span>正在准备预览...</span>
      </div>
    </div>

    <div v-else-if="isOfficePreviewType(resolvedPreviewType)" class="preview-iframe-wrap">
      <iframe
:src="officeState === 'done' ? officePdfUrl : ''"
class="preview-pdf"
@load="onIframeLoad"
@error="onIframeError"></iframe>
      <div v-if="iframeLoading" class="preview-iframe-overlay">
        <div class="preview-spinner"></div>
        <span class="preview-office-phase">{{ officePhaseText }}</span>
        <div class="preview-progress-bar">
          <div class="preview-progress-fill" :style="{ width: officeProgressPercent + '%' }"></div>
        </div>
        <span v-if="officeElapsedMs > 0" class="preview-progress-time">已用时 {{ formatElapsed(officeElapsedMs) }}</span>
      </div>
    </div>

    <video
v-else-if="resolvedPreviewType === 'video'"
:src="url"
class="preview-video"
controls></video>

    <div v-else-if="resolvedPreviewType === 'audio'" class="preview-media-card">
      <div class="file-type-badge">{{ fileIcon(fileName) }}</div>
      <p class="unsupported-text">{{ displayName }}</p>
      <audio :src="url" controls class="preview-audio"></audio>
    </div>

    <article v-else-if="resolvedPreviewType === 'text'" class="preview-text">
      <div v-if="isMarkdown" class="preview-markdown" v-html="displayHtmlContent"></div>
      <pre v-else>{{ displayTextContent }}</pre>
    </article>

    <div v-else class="preview-unsupported">
      <div class="file-type-badge">{{ fileIcon(fileName) }}</div>
      <p class="unsupported-text">{{ unsupportedTitle }}</p>
      <p class="unsupported-hint">{{ unsupportedHint }}</p>
      <div class="preview-actions">
        <a
v-if="url"
:href="url"
target="_blank"
rel="noopener"
class="btn-sm">新窗口打开</a>
        <a
v-if="url"
:href="url"
download
class="btn-sm primary">下载文件</a>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { fileExtension, fileIcon, filePreviewType, isOfficePreviewType } from '../../utils/ui/fileUtils'
import { sanitizeHtml } from '../../utils/ui/htmlSanitizer'
import { markdownToHtml } from '../../utils/common/markdown'
import { useOfficePreviewPolling } from '../../composables/ui/useOfficePreviewPolling'

const props = defineProps<{
  url?: string
  fileName?: string
  title?: string
  previewType?: string
  loading?: boolean
  htmlContent?: string
  textContent?: string | null
  errorMessage?: string
}>()

const internalLoading = ref(false)
const internalHtmlContent = ref('')
const internalTextContent = ref('')
const internalError = ref('')
const imageLoadError = ref(false)
// iframe（pdf / office 转 PDF）加载状态。office 类型按设计不前端 fetch，
// 仅靠 iframe 自身的 @load/@error 事件给出加载占位与失败兜底。
const iframeLoading = ref(false)
const iframeError = ref(false)
const imageScale = ref(1)
const imageRotation = ref(0)
let requestToken = 0

const extension = computed(() => fileExtension(props.fileName))
const isMarkdown = computed(() => extension.value === 'md')
const displayName = computed(() => props.fileName || props.title || '未命名文件')
const resolvedPreviewType = computed(() => props.previewType || filePreviewType(props.fileName))
const displayLoading = computed(() => props.loading || internalLoading.value)
const displayHtmlContent = computed(() => sanitizeHtml(props.htmlContent || internalHtmlContent.value))
const displayTextContent = computed(() => props.textContent ?? internalTextContent.value)
const displayError = computed(
  () =>
    props.errorMessage ||
    internalError.value ||
    (imageLoadError.value ? '图片加载失败，请下载后查看。' : '') ||
    (iframeError.value ? '预览加载失败，请下载或新窗口打开。' : '')
)
const imageStyle = computed(() => ({
  transform: `scale(${imageScale.value}) rotate(${imageRotation.value}deg)`
}))

// Office 文件统一走后端 LibreOffice 转 PDF：把原始 preview URL 重写为 preview-pdf。
// 支持三种 URL 格式：
// 1. /api/file-assets/{ownerType}/assets/{assetId}/preview（链路 B 主入口，file-assets 分支）
// 2. /api/files/{category}?encoded=xxx 或 /api/files/{modelId}/{category}?encoded=xxx（files 分支，需带 query）
// 3. /api/documents/{id}/preview（文档弹窗链路 A，DocumentController 直连）
const pdfPreviewUrl = computed(() => {
  if (!props.url || !isOfficePreviewType(resolvedPreviewType.value)) return ''
  if (props.url.includes('/file-assets/')) {
    return props.url.replace(/\/preview(\?|$)/, '/preview-pdf$1')
  }
  if (/\/api\/documents\/\d+\/preview$/.test(props.url)) {
    return props.url.replace(/\/preview$/, '/preview/pdf')
  }
  return props.url.replace(/\/api\/files\/([^?]+)\?/, '/api/files/$1/preview-pdf?')
})

// Office 预览异步轮询：GET 状态接口触发后端转换，done 后才加载 iframe，避免白屏阻塞
const {
  state: officeState,
  pdfUrl: officePdfUrl,
  errorMessage: officeErrorMessage,
  elapsedMs: officeElapsedMs,
  progressPercent: officeProgressPercent,
  phaseText: officePhaseText,
  start: startOffice,
  stop: stopOffice
} = useOfficePreviewPolling()

// 状态查询 URL：将 PDF 预览路径改写为 status 路径
// - /preview-pdf → /preview-pdf/status（file-assets / files 链路）
// - /preview/pdf → /preview/pdf/status（documents 链路）
const officeStatusUrl = computed(() => {
  const pdfUrl = pdfPreviewUrl.value
  if (!pdfUrl) return ''
  if (pdfUrl.includes('/preview/pdf')) {
    return pdfUrl.replace(/\/preview\/pdf$/, '/preview/pdf/status')
  }
  return pdfUrl.replace(/\/preview-pdf(\?|$)/, '/preview-pdf/status$1')
})

/** 格式化已用时间为可读文案（如 "15秒" / "1分30秒"）。 */
function formatElapsed(ms: number): string {
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${seconds}秒`
  const minutes = Math.floor(seconds / 60)
  return `${minutes}分${seconds % 60}秒`
}

// 轮询失败时复用 internalError 展示下载兜底
watch(officeState, (state) => {
  if (state === 'error') {
    internalError.value = officeErrorMessage.value || '预览转换失败，请下载后查看'
    iframeLoading.value = false
  }
})

const unsupportedTitle = computed(() => {
  if (resolvedPreviewType.value === 'archive') return '压缩包暂不支持内嵌预览'
  return '此文件格式暂不支持内嵌预览'
})

const unsupportedHint = computed(() => {
  if (resolvedPreviewType.value === 'archive') return '请下载后在本地解压查看。'
  return '可以下载文件，或在新窗口交给浏览器/系统处理。'
})

watch(() => [props.url, props.fileName, props.previewType], loadPreview, { immediate: true })

function resetPreview() {
  stopOffice()
  imageLoadError.value = false
  resetImageTransform()
  internalLoading.value = false
  internalHtmlContent.value = ''
  internalTextContent.value = ''
  internalError.value = ''
  iframeLoading.value = false
  iframeError.value = false
}

function onIframeLoad() {
  iframeLoading.value = false
}

function onIframeError() {
  iframeLoading.value = false
  iframeError.value = true
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

function resetImageTransform() {
  imageScale.value = 1
  imageRotation.value = 0
}

async function loadPreview() {
  const token = ++requestToken
  resetPreview()
  if (!props.url || !props.fileName) return
  if (props.loading) return

  const type = resolvedPreviewType.value
  // pdf 走 iframe 直连后端 preview-pdf：加载期间显示占位，失败由 @error 兜底
  if (type === 'pdf') {
    iframeLoading.value = true
    return
  }
  // office 走异步轮询：GET 状态接口触发后端转换，done 后才加载 iframe
  if (isOfficePreviewType(type)) {
    iframeLoading.value = true
    startOffice({
      statusUrl: officeStatusUrl.value,
      pdfUrl: pdfPreviewUrl.value
    })
    return
  }
  if (['image', 'audio', 'video'].includes(type)) return
  if (type !== 'text') return
  if (props.htmlContent || props.textContent != null) return

  internalLoading.value = true
  try {
    const response = await fetch(props.url)
    if (!response.ok) throw new Error(`文件读取失败：${response.status}`)
    const buffer = await response.arrayBuffer()
    if (token !== requestToken) return

    if (type === 'text') {
      const text = decodeText(buffer)
      internalTextContent.value = text
      internalHtmlContent.value = isMarkdown.value ? markdownToHtml(text, { emptyText: '' }) : ''
    }
  } catch (err: unknown) {
    console.error('文件预览失败:', err)
    internalError.value = err instanceof Error ? err.message : String(err)
  } finally {
    if (token === requestToken) internalLoading.value = false
  }
}

function decodeText(buffer: ArrayBuffer) {
  const decoders = ['utf-8', 'gb18030']
  for (const encoding of decoders) {
    try {
      return new TextDecoder(encoding, { fatal: encoding === 'utf-8' }).decode(buffer)
    } catch {
      // Try the next encoding.
    }
  }
  return new TextDecoder().decode(buffer)
}
</script>

<style scoped>
.file-preview-body {
  flex: 1;
  overflow: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  padding: var(--space-section-gap);
  background: var(--color-surface-alt);
}

.file-preview-body--pdf,
.file-preview-body--text,
.file-preview-body--word,
.file-preview-body--legacy-word,
.file-preview-body--excel,
.file-preview-body--ppt {
  align-items: stretch;
  justify-content: stretch;
}

.preview-loading,
.preview-unsupported,
.preview-media-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-control-gap-wide);
  width: min(560px, 100%);
  padding: var(--space-3xl);
  text-align: center;
  color: var(--color-text-muted);
}

.preview-media-card {
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg, 8px);
  background: var(--color-surface);
}

.preview-spinner {
  width: 40px;
  height: 40px;
  border: 3px solid var(--color-border-light);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.preview-image {
  max-width: 100%;
  max-height: calc(90vh - 168px);
  object-fit: contain;
  border-radius: var(--radius-sm);
  box-shadow: 0 2px 12px var(--shadow-color-dark);
  transform-origin: center center;
  transition: transform 0.16s ease;
}

.preview-image-stage {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: var(--space-md);
  width: 100%;
  height: 100%;
  min-height: 400px;
}

.preview-image-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  min-height: 34px;
}

.preview-image-scroll {
  display: grid;
  place-items: center;
  min-height: 0;
  overflow: auto;
}

.tool-button {
  min-width: 32px;
  height: 30px;
  padding: 0 10px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md, 6px);
  background: var(--color-surface);
  color: var(--color-text-strong);
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  cursor: pointer;
}

.tool-button:hover {
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.zoom-value {
  min-width: 52px;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.preview-pdf {
  width: 100%;
  min-height: calc(90vh - 120px);
  border: none;
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}

/* pdf / office 转 PDF iframe 容器：承载 iframe 与加载占位层 */
.preview-iframe-wrap {
  position: relative;
  width: 100%;
  min-height: calc(90vh - 120px);
}

.preview-iframe-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-control-gap-wide);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text-muted);
  pointer-events: none;
}

.preview-office-phase {
  font-size: 13px;
  color: var(--color-text-secondary, var(--color-text-muted));
}

.preview-progress-bar {
  width: 240px;
  max-width: 80%;
  height: 4px;
  border-radius: var(--radius-sm);
  background: var(--color-border-light);
  overflow: hidden;
}

.preview-progress-fill {
  height: 100%;
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  transition: width 1s linear;
}

.preview-progress-time {
  font-size: 12px;
  color: var(--color-text-placeholder, var(--color-text-muted));
  font-variant-numeric: tabular-nums;
}

.preview-video {
  width: 100%;
  max-height: calc(90vh - 120px);
  border-radius: var(--radius-md);
  background: var(--color-text-heading);
}

.preview-audio {
  width: 100%;
}

.preview-text {
  width: 100%;
  min-height: 100%;
  padding: var(--space-section-gap-wide);
  overflow: auto;
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-primary);
  line-height: 1.65;
}

.preview-empty {
  color: var(--color-text-muted);
  text-align: center;
}

.preview-text pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: var(--font-mono);
  font-size: var(--fs-sm);
}

.preview-markdown :deep(h1) {
  margin: 0 0 14px;
  font-size: var(--fs-4xl);
}

.preview-markdown :deep(h2) {
  margin: 18px 0 10px;
  font-size: var(--fs-2xl);
}

.preview-markdown :deep(h3) {
  margin: 16px 0 8px;
  font-size: var(--fs-lg);
}

.preview-markdown :deep(p) {
  margin: 8px 0;
}

.preview-markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 12px 0;
}

.preview-markdown :deep(td),
.preview-markdown :deep(th) {
  border: 1px solid var(--color-border-light);
  padding: var(--space-control-gap);
}

.file-type-badge,
.unsupported-icon {
  display: grid;
  place-items: center;
  min-width: 54px;
  height: 54px;
  padding: 0 8px;
  border-radius: var(--radius-lg, 8px);
  background: var(--color-surface);
  color: var(--color-text-strong);
  font-size: var(--fs-md);
  font-weight: var(--fw-extrabold);
  box-shadow: inset 0 0 0 1px var(--color-border-light);
}

.unsupported-icon {
  color: var(--color-danger);
}

.unsupported-text {
  margin: 0;
  color: var(--color-text-strong);
  font-size: var(--fs-base);
  font-weight: var(--fw-semibold);
}

.unsupported-hint {
  max-width: 420px;
  margin: 0;
  color: var(--color-text-faint);
  font-size: var(--fs-sm);
  line-height: var(--lh-loose);
}

.preview-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-control-gap);
}

.btn-sm {
  padding: 6px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md, 6px);
  background: var(--color-surface);
  color: var(--color-text-strong);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
  text-decoration: none;
}

.btn-sm.primary {
  border-color: transparent;
  background: var(--color-primary);
  color: var(--color-surface);
}
</style>
