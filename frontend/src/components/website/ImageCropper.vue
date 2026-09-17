<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { Check, Close, ZoomIn, ZoomOut, RefreshLeft, RefreshRight } from '@element-plus/icons-vue'

const props = withDefaults(defineProps<{
  modelValue?: string
  aspectRatio?: number
  aspectRatioText?: string
  outputWidth?: number
  outputHeight?: number
  title?: string
}>(), {
  modelValue: '',
  aspectRatio: 16 / 9,
  aspectRatioText: '16:9',
  outputWidth: 1920,
  outputHeight: 1080,
  title: '图片裁切',
})

const emit = defineEmits<{
  (e: 'cancel'): void
  (e: 'confirm', payload: { dataUrl: string; blob: Blob; width: number; height: number }): void
}>()

/* ---------------- 状态 ---------------- */
const containerRef = ref<HTMLElement | null>(null)

const trueW = ref(0)
const trueH = ref(0)

const offsetX = ref(0)
const offsetY = ref(0)
const scale = ref(1)
const rotation = ref(0)

const isDragging = ref(false)
let dragStartX = 0
let dragStartY = 0
let dragStartOffsetX = 0
let dragStartOffsetY = 0

const containerW = ref(600)
const containerH = ref(300)

let resizeObserver: ResizeObserver | null = null

/* ---------------- 计算属性 ---------------- */
const cropW = computed(() => {
  const maxByW = containerW.value * 0.88
  const maxByH = containerH.value * 0.88 * props.aspectRatio
  return Math.max(50, Math.min(maxByW, maxByH))
})
const cropH = computed(() => cropW.value / props.aspectRatio)
const cropX = computed(() => (containerW.value - cropW.value) / 2)
const cropY = computed(() => (containerH.value - cropH.value) / 2)

const displayImgW = computed(() => (rotation.value % 2 === 0 ? trueW.value : trueH.value) * scale.value)
const displayImgH = computed(() => (rotation.value % 2 === 0 ? trueH.value : trueW.value) * scale.value)

const outputActualW = computed(() => {
  if (!trueW.value || !displayImgW.value) return 0
  return Math.round(cropW.value * (trueW.value / displayImgW.value))
})
const outputActualH = computed(() => {
  if (!trueH.value || !displayImgH.value) return 0
  return Math.round(cropH.value * (trueH.value / displayImgH.value))
})

const outputStatus = computed(() => {
  const actual = outputActualW.value
  const ok = actual >= props.outputWidth && outputActualH.value >= props.outputHeight
  return {
    ok,
    label: ok ? '尺寸达标' : '偏小，建议换更大的原图',
    actualW: actual || props.outputWidth,
    actualH: outputActualH.value || props.outputHeight,
  }
})

const imgTransform = computed(() => {
  return `translate3d(${offsetX.value}px, ${offsetY.value}px, 0) scale(${scale.value}) rotate(${rotation.value * 90}deg)`
})

/* ---------------- 图片加载 ---------------- */
function loadImage(src: string) {
  const img = new Image()
  img.crossOrigin = 'anonymous'
  img.onload = () => {
    trueW.value = img.naturalWidth
    trueH.value = img.naturalHeight
    fitToCropBox()
  }
  img.onerror = () => {
    // 跨域失败：不带 crossOrigin 重试（仅用于测量尺寸，Canvas 输出时再处理）
    const img2 = new Image()
    img2.onload = () => {
      trueW.value = img2.naturalWidth
      trueH.value = img2.naturalHeight
      fitToCropBox()
    }
    img2.onerror = () => { /* 图片加载失败，不设尺寸 */ }
    img2.src = src
  }
  img.src = src
}

function fitToCropBox() {
  if (!trueW.value || !trueH.value) return
  const cw = cropW.value
  const ch = cropH.value
  const scaleX = cw / trueW.value
  const scaleY = ch / trueH.value
  scale.value = Math.max(scaleX, scaleY)
  offsetX.value = (containerW.value - trueW.value * scale.value) / 2
  offsetY.value = (containerH.value - trueH.value * scale.value) / 2
  rotation.value = 0
}

/* ---------------- 测量容器 ---------------- */
function measureContainer() {
  if (containerRef.value) {
    const rect = containerRef.value.getBoundingClientRect()
    if (rect.width > 0 && rect.height > 0) {
      containerW.value = rect.width
      containerH.value = rect.height
    }
  }
}

/* ---------------- 鼠标/触屏拖动 ---------------- */
function onPointerDown(e: MouseEvent | TouchEvent) {
  e.preventDefault()
  e.stopPropagation()
  isDragging.value = true
  const point = getPoint(e)
  dragStartX = point.x
  dragStartY = point.y
  dragStartOffsetX = offsetX.value
  dragStartOffsetY = offsetY.value

  window.addEventListener('mousemove', onPointerMove)
  window.addEventListener('mouseup', onPointerUp)
  window.addEventListener('touchmove', onPointerMove, { passive: false })
  window.addEventListener('touchend', onPointerUp)
}

function onPointerMove(e: MouseEvent | TouchEvent) {
  if (!isDragging.value) return
  e.preventDefault()
  const point = getPoint(e)
  offsetX.value = dragStartOffsetX + (point.x - dragStartX)
  offsetY.value = dragStartOffsetY + (point.y - dragStartY)
}

function onPointerUp() {
  isDragging.value = false
  window.removeEventListener('mousemove', onPointerMove)
  window.removeEventListener('mouseup', onPointerUp)
  window.removeEventListener('touchmove', onPointerMove)
  window.removeEventListener('touchend', onPointerUp)
}

function getPoint(e: MouseEvent | TouchEvent) {
  if ('touches' in e && e.touches.length > 0) {
    return { x: e.touches[0].clientX, y: e.touches[0].clientY }
  }
  if ('changedTouches' in e && e.changedTouches.length > 0) {
    return { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY }
  }
  return { x: (e as MouseEvent).clientX, y: (e as MouseEvent).clientY }
}

/* ---------------- 滚轮缩放 ---------------- */
function onWheel(e: WheelEvent) {
  e.preventDefault()
  const delta = e.deltaY > 0 ? 0.95 : 1.05
  const newScale = Math.max(0.1, Math.min(10, scale.value * delta))
  const cx = containerW.value / 2
  const cy = containerH.value / 2
  const ratio = newScale / scale.value
  offsetX.value = cx - (cx - offsetX.value) * ratio
  offsetY.value = cy - (cy - offsetY.value) * ratio
  scale.value = newScale
}

/* ---------------- 工具按钮 ---------------- */
function zoomIn() {
  const newScale = Math.min(10, scale.value * 1.15)
  const cx = containerW.value / 2
  const cy = containerH.value / 2
  const ratio = newScale / scale.value
  offsetX.value = cx - (cx - offsetX.value) * ratio
  offsetY.value = cy - (cy - offsetY.value) * ratio
  scale.value = newScale
}
function zoomOut() {
  const newScale = Math.max(0.1, scale.value / 1.15)
  const cx = containerW.value / 2
  const cy = containerH.value / 2
  const ratio = newScale / scale.value
  offsetX.value = cx - (cx - offsetX.value) * ratio
  offsetY.value = cy - (cy - offsetY.value) * ratio
  scale.value = newScale
}
function rotateLeft() {
  rotation.value = (rotation.value + 3) % 4
  fitToCropBox()
}
function rotateRight() {
  rotation.value = (rotation.value + 1) % 4
  fitToCropBox()
}

/* ---------------- 确认裁切（Canvas 输出） ---------------- */
async function handleConfirm() {
  if (!trueW.value || !trueH.value) { emit('cancel'); return }

  const canvas = document.createElement('canvas')
  const outW = outputActualW.value
  const outH = outputActualH.value
  canvas.width = outW
  canvas.height = outH
  const ctx = canvas.getContext('2d')
  if (!ctx) { emit('cancel'); return }

  const img = new Image()
  img.crossOrigin = 'anonymous'
  const loaded = await new Promise<boolean>(resolve => {
    img.onload = () => resolve(true)
    img.onerror = () => resolve(false)
    img.src = props.modelValue
  })
  if (!loaded) { emit('cancel'); return }

  const srcX = (cropX.value - offsetX.value) / scale.value
  const srcY = (cropY.value - offsetY.value) / scale.value
  const srcW = cropW.value / scale.value
  const srcH = cropH.value / scale.value

  ctx.save()
  if (rotation.value % 4 !== 0) {
    ctx.translate(outW / 2, outH / 2)
    ctx.rotate((rotation.value * 90 * Math.PI) / 180)
    ctx.translate(-outW / 2, -outH / 2)
  }

  try {
    ctx.drawImage(img, srcX, srcY, srcW, srcH, 0, 0, outW, outH)
  } catch {
    ctx.restore()
    emit('cancel')
    return
  }
  ctx.restore()

  try {
    const dataUrl = canvas.toDataURL('image/png')
    const blob = await new Promise<Blob | null>(resolve => canvas.toBlob(b => resolve(b), 'image/png', 1))
    if (!blob) { emit('cancel'); return }
    emit('confirm', { dataUrl, blob, width: outW, height: outH })
  } catch {
    // Canvas 被 CORS 污染，无法导出
    emit('cancel')
  }
}

function handleCancel() {
  emit('cancel')
}

/* ---------------- 生命周期 ---------------- */
onMounted(() => {
  nextTick(() => {
    measureContainer()
    if (props.modelValue) loadImage(props.modelValue)
  })
  // 使用 ResizeObserver 确保容器尺寸正确
  if (containerRef.value) {
    resizeObserver = new ResizeObserver(() => {
      measureContainer()
    })
    resizeObserver.observe(containerRef.value)
  }
})

onBeforeUnmount(() => {
  onPointerUp()
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
})

watch(() => props.modelValue, (src) => {
  if (src) {
    nextTick(() => {
      measureContainer()
      loadImage(src)
    })
  }
})
</script>

<template>
  <div class="image-cropper">
    <div class="image-cropper__header">
      <h3>{{ title }}</h3>
      <div class="image-cropper__info">
        <span class="chip chip--primary">
          建议 {{ outputWidth }} × {{ outputHeight }}px（{{ aspectRatioText }}）
        </span>
        <span v-if="trueW" class="chip">
          原图 {{ trueW }} × {{ trueH }}px
        </span>
        <span v-if="trueW" class="chip" :class="outputStatus.ok ? 'chip--ok' : 'chip--warn'">
          实际输出 {{ outputStatus.actualW }} × {{ outputStatus.actualH }}px
          · {{ outputStatus.label }}
        </span>
      </div>
      <div class="image-cropper__actions">
        <el-button size="small" :icon="ZoomOut" @click="zoomOut">缩小</el-button>
        <el-button size="small" :icon="ZoomIn" @click="zoomIn">放大</el-button>
        <el-button size="small" :icon="RefreshLeft" @click="rotateLeft">向左 90°</el-button>
        <el-button size="small" :icon="RefreshRight" @click="rotateRight">向右 90°</el-button>
      </div>
      <div class="image-cropper__hint">
        <strong>操作说明：</strong>
        <strong>拖动图片</strong>可调整构图位置 · 滚轮或上方按钮可<strong>缩放</strong> · 可<strong>旋转</strong>后裁切。
        裁剪框已锁定（比例 {{ aspectRatioText }}），请拖动图片来选择保留的区域。
      </div>
    </div>

    <div
      ref="containerRef"
      class="image-cropper__body"
      @mousedown="onPointerDown"
      @touchstart="onPointerDown"
      @wheel.prevent="onWheel"
    >
      <img
        v-if="modelValue"
        :src="modelValue"
        class="image-cropper__img"
        :style="{ transform: imgTransform, transformOrigin: '0 0' }"
        @dragstart.prevent
        draggable="false"
      />
      <!-- 裁剪框遮罩 -->
      <div class="image-cropper__overlay" aria-hidden="true">
        <div class="overlay-piece overlay-top"    :style="{ height: cropY + 'px' }"></div>
        <div class="overlay-piece overlay-bottom" :style="{ top: (cropY + cropH) + 'px' }"></div>
        <div class="overlay-piece overlay-left"   :style="{ top: cropY + 'px', height: cropH + 'px', width: cropX + 'px' }"></div>
        <div class="overlay-piece overlay-right"  :style="{ top: cropY + 'px', height: cropH + 'px', left: (cropX + cropW) + 'px' }"></div>
      </div>
      <!-- 裁剪框边框 -->
      <div class="image-cropper__cropbox" :style="{ left: cropX + 'px', top: cropY + 'px', width: cropW + 'px', height: cropH + 'px' }">
        <span class="cropbox-corner cropbox-corner--tl"></span>
        <span class="cropbox-corner cropbox-corner--tr"></span>
        <span class="cropbox-corner cropbox-corner--bl"></span>
        <span class="cropbox-corner cropbox-corner--br"></span>
      </div>
    </div>

    <div class="image-cropper__footer">
      <el-button :icon="Close" @click="handleCancel">取消</el-button>
      <el-button type="primary" :icon="Check" @click="handleConfirm">确认裁切并上传</el-button>
    </div>
  </div>
</template>

<style scoped>
.image-cropper { display: flex; flex-direction: column; gap: 18px; }
.image-cropper__header h3 { margin: 0 0 10px; font-size: 16px; font-weight: 600; color: var(--color-text); }
.image-cropper__info { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.chip {
  display: inline-flex; align-items: center;
  padding: 3px 12px; border-radius: 999px;
  background: var(--color-bg-subtle);
  color: var(--color-text-secondary);
  font-size: 12px;
  border: 1px solid var(--color-border-light);
}
.chip--primary { background: var(--color-primary-bg); color: var(--color-primary); border-color: var(--color-primary-soft); }
.chip--ok { background: #e6f4ea; color: #1b7a3a; border-color: #b8dfc3; }
.chip--warn { background: #fff6e5; color: #b26a00; border-color: #f1d59a; }
.image-cropper__actions { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.image-cropper__hint {
  margin: 0; padding: 10px 14px;
  background: var(--color-bg-subtle);
  border-radius: var(--radius-md);
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.7;
}
.image-cropper__hint strong { color: var(--color-text); }

.image-cropper__body {
  width: 100%;
  max-width: 640px;
  height: 360px;
  margin: 0 auto;
  border-radius: var(--radius-md);
  overflow: hidden;
  background: #101417;
  position: relative;
  cursor: grab;
  user-select: none;
  -webkit-user-select: none;
  touch-action: none;
}
.image-cropper__body:active { cursor: grabbing; }

.image-cropper__img {
  position: absolute;
  top: 0;
  left: 0;
  max-width: none;
  max-height: none;
  pointer-events: none;
  user-select: none;
  -webkit-user-drag: none;
  will-change: transform;
}

/* 四块遮罩 */
.image-cropper__overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 2;
}
.overlay-piece {
  position: absolute;
  background: rgba(0, 0, 0, 0.55);
}
.overlay-top    { top: 0;    left: 0; right: 0; }
.overlay-bottom { left: 0;   right: 0; bottom: 0; }
.overlay-left   { left: 0; }
.overlay-right  { right: 0; }

/* 裁剪框 */
.image-cropper__cropbox {
  position: absolute;
  z-index: 3;
  pointer-events: none;
  border: 1px solid rgba(255, 255, 255, 0.8);
  box-shadow: 0 0 0 1px rgba(0, 0, 0, 0.3);
}
.cropbox-corner {
  position: absolute;
  width: 10px;
  height: 10px;
  border: 2px solid #fff;
  background: rgba(51, 153, 255, 0.8);
}
.cropbox-corner--tl { top: -2px; left: -2px; }
.cropbox-corner--tr { top: -2px; right: -2px; }
.cropbox-corner--bl { bottom: -2px; left: -2px; }
.cropbox-corner--br { bottom: -2px; right: -2px; }

.image-cropper__footer { display: flex; justify-content: flex-end; gap: 8px; padding-top: 4px; }
</style>
