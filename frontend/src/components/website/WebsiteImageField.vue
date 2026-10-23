<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, Crop, Delete, FolderOpened, Upload } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { getAssets, createAsset } from '@/api/company'
import type { CompanyAsset } from '@/types/company'
import { useFileUpload } from '@/composables/upload/useFileUpload'
import { notify } from '@/composables/ui/useToast'
import { fileUrl } from '@/utils/ui/fileUtils'
import ImageCropper from './ImageCropper.vue'

const props = withDefaults(defineProps<{
  modelValue?: string
  hint?: string
  aspectRatio?: string
  recommendedWidth?: number
  recommendedHeight?: number
  croppable?: boolean
}>(), {
  modelValue: '',
  hint: '支持 JPG、PNG、WebP，单张不超过 50MB',
  aspectRatio: '16 / 9',
  recommendedWidth: 0,
  recommendedHeight: 0,
  croppable: true,
})

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()
const pickerVisible = ref(false)
const libraryLoading = ref(false)
const library = ref<CompanyAsset[]>([])
const showUrlInput = ref(false)
const batchUploading = ref(false)
const cropperVisible = ref(false)
const cropperImageSrc = ref('')
/** 若当前 cropperImageSrc 是通过 fetch→objectUrl 创建的，在此记录以便用完释放 */
const cropperObjectUrl = ref<string | null>(null)
const { uploading, uploadSelectedFile } = useFileUpload({ notify })

const imageUrl = computed({ get: () => props.modelValue || '', set: value => emit('update:modelValue', value) })

const aspectRatioForCropper = computed(() => {
  const match = props.aspectRatio.match(/([\d.]+)\s*\/\s*([\d.]+)/)
  if (match) return parseFloat(match[1]) / parseFloat(match[2])
  return 16 / 9
})

const aspectRatioText = computed(() => {
  const match = props.aspectRatio.match(/([\d.]+)\s*\/\s*([\d.]+)/)
  if (match) return `${match[1]}:${match[2]}`
  return props.aspectRatio
})

const hasRecommendedSize = computed(() => props.recommendedWidth > 0 && props.recommendedHeight > 0)

const uploadFileUrl = (fileName: string) => fileUrl('company', fileName)

/* ---------------- 文件选择器 ---------------- */

function openImagePicker(multiple = false): Promise<File | File[]> {
  return new Promise((resolve, reject) => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = 'image/jpeg,image/png,image/webp,image/avif,image/gif'
    if (multiple) input.multiple = true
    input.onchange = () => {
      const files = Array.from(input.files || [])
      if (!files.length) { reject(new Error('未选择文件')); return }
      resolve(multiple ? files : files[0])
    }
    input.oncancel = () => reject(new Error('用户取消'))
    input.click()
  })
}

function fileToDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = e => resolve(e.target?.result as string)
    reader.onerror = () => reject(new Error('读取文件失败'))
    reader.readAsDataURL(file)
  })
}

function clearCropperObjectUrl() {
  if (cropperObjectUrl.value) {
    try { URL.revokeObjectURL(cropperObjectUrl.value) } catch { /* ignore */ }
    cropperObjectUrl.value = null
  }
}

/**
 * 传入任意图片源（File 对象、URL 字符串、DataURL），将其设置为裁切面板的源，并打开裁切对话框。
 * 对于外部 URL，先尝试 fetch→Blob→ObjectURL（避免 canvas CORS 污染）；
 * 如果 fetch 失败（跨域），直接用原始 URL 打开裁切器——图片仍可显示和拖动，
 * 仅在确认裁切时 Canvas 导出可能失败。
 */
async function prepareAndOpenCropper(source: File | string): Promise<void> {
  clearCropperObjectUrl()

  if (source instanceof File) {
    cropperImageSrc.value = await fileToDataUrl(source)
    cropperVisible.value = true
    return
  }

  const src = source

  // DataURL / Blob URL：直接使用
  if (src.startsWith('data:') || src.startsWith('blob:')) {
    cropperImageSrc.value = src
    cropperVisible.value = true
    return
  }

  // 同源 URL（/api/files/...、/images/...）：直接使用，Canvas 不会有 CORS 问题
  if (src.startsWith('/') || src.startsWith(window.location.origin)) {
    cropperImageSrc.value = src
    cropperVisible.value = true
    return
  }

  // 外部 URL：尝试 fetch 转 blob（避免 canvas 污染），失败则直接用 URL
  try {
    const res = await fetch(src, { credentials: 'same-origin' })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const blob = await res.blob()
    const objectUrl = URL.createObjectURL(blob)
    cropperObjectUrl.value = objectUrl
    cropperImageSrc.value = objectUrl
    cropperVisible.value = true
  } catch {
    // fetch 失败（跨域）：直接用原始 URL，图片仍可显示和拖动
    cropperImageSrc.value = src
    cropperVisible.value = true
  }
}

/* ---------------- 上传公共逻辑（上传 → 入库 → 设为当前） ---------------- */

async function uploadFileAndSet(file: File): Promise<string | null> {
  const result = await uploadSelectedFile('company', file)
  if (!result?.fileName) return null
  try {
    await createAsset({ category: 'website_media', fileName: result.fileName, tags: '官网用', defaultAsset: false, sortOrder: 0 })
  } catch (err) {
    notify.errorWithDetail('图片已上传，但登记到官网素材库失败', err)
  }
  imageUrl.value = uploadFileUrl(result.fileName)
  return result.fileName
}

/* ---------------- 裁切流程 ---------------- */

/** 给定一个本地 File，打开裁切编辑器（用于新上传） */
async function cropThenUpload(file: File) {
  await prepareAndOpenCropper(file)
}

/** 对“当前预览区已有图片”重新进行一次裁切（来源：素材库 / 上次直接上传 / URL 填写均可） */
async function recropCurrent() {
  if (!imageUrl.value) return
  if (!hasRecommendedSize.value) {
    notify('当前区域未配置建议尺寸，暂不支持重新裁切')
    return
  }
  await prepareAndOpenCropper(imageUrl.value)
}

/* 处理裁切确认：上传裁切 blob */
async function handleCropConfirm(payload: { dataUrl: string; blob: Blob }) {
  cropperVisible.value = false
  clearCropperObjectUrl()
  try {
    const ext = (payload.blob.type || 'image/png').split('/')[1] || 'png'
    const file = new File([payload.blob], `cropped-${Date.now()}.${ext}`, { type: payload.blob.type || 'image/png' })
    const uploaded = await uploadFileAndSet(file)
    if (uploaded) notify('图片裁切并上传成功', 'success')
  } catch {
    notify.error('裁切后的图片上传失败')
  }
}

function handleCropCancel() {
  cropperVisible.value = false
  clearCropperObjectUrl()
}

/* ---------------- 单张上传入口（已合并：直接上传 / 裁切上传） ---------------- */

/**
 * 统一入口：用户选择 1 张本地图片后
 *  - 若允许裁切且配置了推荐尺寸：弹窗让用户选择「按推荐尺寸裁切（推荐）」或「直接上传原图」
 *  - 否则：直接上传
 */
async function uploadImage() {
  let file: File
  try {
    file = await openImagePicker(false) as File
  } catch {
    return /* 用户取消，静默处理 */
  }

  const shouldOfferCrop = props.croppable && hasRecommendedSize.value

  if (shouldOfferCrop) {
    try {
      await ElMessageBox.confirm(
        `<p>已选择图片：<strong>${file.name}</strong></p>
         <p>当前区域建议尺寸 <strong>${props.recommendedWidth} × ${props.recommendedHeight}px（${aspectRatioText.value}）</strong>。</p>
         <p style="color:var(--el-color-warning);margin-top:8px">使用裁切可以保证图片正好符合官网展示比例，避免被拉伸或留白。</p>`,
        '选择上传方式',
        {
          confirmButtonText: '裁切后上传（推荐）',
          cancelButtonText: '直接上传原图',
          distinguishCancelAndClose: true,
          dangerouslyUseHTMLString: true,
          customClass: 'website-image-upload-choice-dialog',
        }
      )
      /* 确认 → 走裁切流程 */
      await cropThenUpload(file)
    } catch (action) {
      if (action === 'cancel') {
        /* 用户点了"直接上传原图" */
        const uploaded = await uploadFileAndSet(file)
        if (uploaded) notify('图片已上传', 'success')
      }
      /* action === 'close'（点 X 关闭）或其他：静默中断，不做任何事 */
    }
    return
  }

  /* 没有推荐尺寸 / 不允许裁切：直接上传 */
  const uploaded = await uploadFileAndSet(file)
  if (uploaded) notify('图片已上传', 'success')
}

/* ---------------- 批量上传（素材库专用，不进入裁切流程，也不设为当前） ---------------- */

async function batchUpload() {
  let files: File[]
  try {
    files = await openImagePicker(true) as File[]
  } catch {
    return
  }
  if (!files || !files.length) return
  batchUploading.value = true
  let firstUrl = ''
  let successCount = 0
  for (const file of files) {
    const result = await uploadSelectedFile('company', file)
    if (!result?.fileName) continue
    try {
      await createAsset({ category: 'website_media', fileName: result.fileName, tags: '官网用', defaultAsset: false, sortOrder: 0 })
      successCount++
      if (!firstUrl) firstUrl = uploadFileUrl(result.fileName)
    } catch (err) {
      notify.errorWithDetail(`图片 ${file.name} 入库失败`, err)
    }
  }
  batchUploading.value = false
  if (successCount > 0) {
    notify(`成功上传 ${successCount} 张图片到素材库`, 'success')
    if (!imageUrl.value && firstUrl) imageUrl.value = firstUrl
    await refreshLibrary()
  }
}

/* ---------------- 素材库 ---------------- */

async function openLibrary() {
  pickerVisible.value = true
  await refreshLibrary()
}

async function refreshLibrary() {
  libraryLoading.value = true
  try { library.value = await getAssets('website_media') }
  catch (err) { notify.errorWithDetail('加载官网素材失败', err) }
  finally { libraryLoading.value = false }
}

function choose(asset: CompanyAsset) {
  imageUrl.value = uploadFileUrl(asset.fileName)
  pickerVisible.value = false
}
</script>

<template>
  <div class="website-image-field">
    <div class="website-image-field__preview" :style="{ aspectRatio }">
      <img v-if="imageUrl" :src="imageUrl" alt="官网图片预览" />
      <div v-else class="website-image-field__empty"><Upload :size="26" /><span>尚未设置图片，点击下方上传或从素材库选择</span></div>
      <div v-if="hasRecommendedSize" class="website-image-field__badge">
        建议 {{ recommendedWidth }} × {{ recommendedHeight }}
      </div>
    </div>
    <div class="website-image-field__actions">
      <el-button type="primary" plain :icon="Upload" :loading="uploading" @click="uploadImage">上传 / 替换图片</el-button>
      <el-button :icon="FolderOpened" @click="openLibrary">从素材库选择</el-button>
      <el-button
        v-if="imageUrl && hasRecommendedSize && croppable"
        :icon="Crop"
        @click="recropCurrent"
      >重新裁切当前图片</el-button>
      <el-button v-if="imageUrl" text type="danger" :icon="Delete" @click="imageUrl = ''">清除</el-button>
      <el-button text @click="showUrlInput = !showUrlInput">{{ showUrlInput ? '收起路径' : '高级：填写路径' }}</el-button>
    </div>
    <el-input v-if="showUrlInput" v-model="imageUrl" placeholder="/images/...、/api/files/... 或完整 URL" clearable />
    <p class="website-image-field__hint">
      {{ hint }}
      <span v-if="hasRecommendedSize">建议尺寸 {{ recommendedWidth }} × {{ recommendedHeight }}px（{{ aspectRatioText }}），上传或从素材库选择后可按该比例重新裁切。</span>
    </p>

    <!-- 裁切对话框 -->
    <el-dialog v-model="cropperVisible" title="图片裁切" width="720px" append-to-body :close-on-click-modal="false">
      <ImageCropper
        v-if="cropperVisible"
        :model-value="cropperImageSrc"
        :aspect-ratio="aspectRatioForCropper"
        :aspect-ratio-text="aspectRatioText"
        :output-width="recommendedWidth"
        :output-height="recommendedHeight"
        @confirm="handleCropConfirm"
        @cancel="handleCropCancel"
      />
    </el-dialog>

    <!-- 素材库对话框 -->
    <el-dialog v-model="pickerVisible" title="选择官网图片" width="min(900px, 92vw)" append-to-body>
      <div class="website-image-library__toolbar">
        <span class="website-image-library__count">共 {{ library.length }} 张</span>
        <el-button type="primary" plain :icon="Upload" :loading="batchUploading" @click="batchUpload">批量上传到素材库</el-button>
        <el-button text :icon="FolderOpened" @click="refreshLibrary" :loading="libraryLoading">刷新</el-button>
      </div>
      <div v-loading="libraryLoading" class="website-image-library">
        <button
          v-for="asset in library"
          :key="asset.id"
          type="button"
          class="website-image-library__item"
          :class="{ 'is-selected': uploadFileUrl(asset.fileName) === imageUrl }"
          @click="choose(asset)"
        >
          <img :src="uploadFileUrl(asset.fileName)" :alt="asset.fileName" loading="lazy" />
          <span class="website-image-library__name">{{ asset.fileName }}</span>
          <i v-if="uploadFileUrl(asset.fileName) === imageUrl" class="website-image-library__check"><el-icon><Check /></el-icon></i>
        </button>
        <el-empty v-if="!libraryLoading && !library.length" description="素材库还没有图片，点击上方批量上传" />
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.website-image-field { display: grid; width: 100%; gap: 10px; position: relative; }
.website-image-field__preview { width: min(100%, 480px); overflow: hidden; border: 1px solid var(--color-border-light); border-radius: var(--radius-md); background: var(--color-surface-alt); position: relative; }
.website-image-field__preview img { width: 100%; height: 100%; object-fit: contain; display: block; }
.website-image-field__empty { height: 100%; min-height: 150px; display: grid; place-content: center; justify-items: center; gap: 8px; color: var(--color-text-faint); }
.website-image-field__badge { position: absolute; bottom: 8px; right: 8px; padding: 2px 8px; background: rgba(0,0,0,0.65); color: #fff; border-radius: 4px; font-size: 11px; pointer-events: none; }
.website-image-field__actions { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.website-image-field__hint { margin: 0; color: var(--color-text-faint); font-size: 12px; }
.website-image-field__hint span { margin-left: 4px; color: var(--color-text-secondary); font-weight: 500; }

.website-image-library__toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.website-image-library__count { color: var(--color-text-secondary); font-size: 12px; }
.website-image-library { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; min-height: 180px; }
.website-image-library__item { position: relative; padding: 0; overflow: hidden; border: 2px solid var(--color-border-light); border-radius: var(--radius-md); background: var(--color-surface); color: var(--color-text-secondary); cursor: pointer; text-align: left; transition: border-color .15s; }
.website-image-library__item:hover { border-color: var(--color-primary); }
.website-image-library__item.is-selected { border-color: var(--color-primary); box-shadow: 0 0 0 1px var(--color-primary); }
.website-image-library__item img { width: 100%; aspect-ratio: 4 / 3; object-fit: contain; background: var(--color-surface-alt); display: block; }
.website-image-library__name { display: block; padding: 8px; overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.website-image-library__check { position: absolute; top: 4px; right: 4px; width: 20px; height: 20px; display: inline-flex; align-items: center; justify-content: center; border-radius: 50%; background: var(--color-primary); color: #fff; font-size: 12px; }
@media(max-width:760px){.website-image-library{grid-template-columns:repeat(2,minmax(0,1fr));}}

/* 选择上传方式对话框的按钮更清晰（Element Plus 默认样式即可，这里只是加类以便后续扩展） */
:deep(.website-image-upload-choice-dialog .el-message-box__message) { line-height: 1.7; }
</style>
