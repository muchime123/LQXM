<!--
  LqThumbnail - 通用文件缩略图组件
  负责：img 加载缩略图 + 失败降级到文件类型图标（FileTypeIcon）。

  与 LqPreview 协作但不合并：LqThumbnail 是卡片静态小图，LqPreview 是点击后的全屏交互预览。
  点击缩略图由父组件处理（外层容器的 @click），本组件不接管点击。

  使用方式：
    <LqThumbnail
      :src="thumbnailUrl"
      :file-name="asset.fileName"
      object-fit="contain"
      @error="onError"
    >
      <template #overlay>
        <span class="ext-corner">JPG</span>
      </template>
    </LqThumbnail>

  根元素用 <span>（button-safe）：当外层是 <button> 时，放 <div> 是无效 HTML。
-->
<template>
  <span class="lq-thumbnail" :class="{ 'is-fallback': !showImg }">
    <img
      v-if="showImg"
      class="lq-thumbnail__img"
      :src="src"
      :alt="resolvedAlt"
      :loading="loading"
      :decoding="decoding"
      :style="{ objectFit }"
      @error="onError"
      @load="emit('loaded')"
    />
    <span v-else class="lq-thumbnail__fallback">
      <slot name="fallback">
        <span class="lq-thumbnail__icon">
          <FileTypeIcon :ext="resolvedExt" :size="fallbackIconSize" />
        </span>
        <small class="lq-thumbnail__label">{{ resolvedLabel }}</small>
      </slot>
    </span>
    <slot name="overlay" />
  </span>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import FileTypeIcon from './FileTypeIcon.vue'
import { fileExtension, isImageFile, isPdfFile } from '@/utils/ui/fileUtils'

export interface LqThumbnailProps {
  /** 缩略图/预览 URL。空串表示无图，直接走 fallback。由父组件生成（不接管 URL 生成） */
  src: string
  /** 文件名。用于：默认 renderable 判断、默认 fallback 扩展名、img alt 回退 */
  fileName: string
  /** img alt 文本，默认取 fileName */
  alt?: string
  /** img loading 属性，默认 'lazy' */
  loading?: 'lazy' | 'eager'
  /** img decoding 属性，默认 'async' */
  decoding?: 'async' | 'sync' | 'auto'
  /**
   * 是否可渲染为 img。不传时组件默认用 isImageFile(fileName) || isPdfFile(fileName)。
   * 覆盖场景：仅 image 传 isImageFile(...)；含 category 覆盖传自定义判断结果。
   */
  renderable?: boolean
  /** img object-fit，默认 'contain' */
  objectFit?: 'contain' | 'cover' | 'fill' | 'none'
  /** fallback 小标签文本。不传时默认 fileExtension(fileName).toUpperCase() || 'FILE' */
  fallbackLabel?: string
  /** 传给 FileTypeIcon 的 ext；不传时由 fileName 推导 */
  fallbackIconExt?: string
  /** FileTypeIcon 尺寸，默认不传（继承 .lq-thumbnail__icon 的字号） */
  fallbackIconSize?: string | number
}

export interface LqThumbnailErrorPayload {
  src: string
  fileName: string
}

const props = withDefaults(defineProps<LqThumbnailProps>(), {
  alt: '',
  loading: 'lazy',
  decoding: 'async',
  renderable: undefined,
  objectFit: 'contain',
  fallbackLabel: undefined,
  fallbackIconExt: undefined,
  fallbackIconSize: ''
})

const emit = defineEmits<{
  /** img 加载失败时触发。组件内部已自动切换 fallback，此事件仅用于父组件可选上报/统计 */
  error: [payload: LqThumbnailErrorPayload]
  /** img 加载成功时触发 */
  loaded: []
}>()

const errored = ref(false)

// src 变化时重置错误态（切换 asset 时重新尝试加载）
watch(
  () => props.src,
  () => {
    errored.value = false
  }
)

const resolvedExt = computed(() => props.fallbackIconExt ?? fileExtension(props.fileName))

const isRenderable = computed(
  () => props.renderable ?? (isImageFile(props.fileName) || isPdfFile(props.fileName))
)

const showImg = computed(() => Boolean(props.src) && isRenderable.value && !errored.value)

const resolvedAlt = computed(() => props.alt || props.fileName)

const resolvedLabel = computed(
  () => props.fallbackLabel ?? (resolvedExt.value ? resolvedExt.value.toUpperCase() : 'FILE')
)

function onError() {
  errored.value = true
  emit('error', { src: props.src, fileName: props.fileName })
}
</script>

<style scoped>
/* ====== ====== ====== ====== ====== ====== ====== ====== ====== ====== */
/* LqThumbnail - 通用文件缩略图组件 */
/* 负责：img 加载缩略图 + 失败降级到文件类型图标 */
/* 不接管外层容器（div/button）、点击逻辑、hover 工具栏、badge */
/* ====== ====== ====== ====== ====== ====== ====== ====== ====== ====== */

.lq-thumbnail {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  box-sizing: border-box;
}

/* 根元素用 <span>：button-safe（外层可能是 <button>，放 <div> 是无效 HTML） */

.lq-thumbnail__img {
  width: 100%;
  height: 100%;
  /* img 内边距由父级通过 --lq-thumb-pad 控制（如品牌资产需 4px 内边距） */
  padding: var(--lq-thumb-pad, 0);
  box-sizing: border-box;
  /* object-fit 由组件 inline style 注入（props.objectFit） */
}

.lq-thumbnail__fallback {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-control-gap, 8px);
  padding: var(--space-2xl, 24px);
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  color: var(--color-text-faint);
}

.lq-thumbnail__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  /* FileTypeIcon 默认 1em，跟随 font-size 控制尺寸 */
  font-size: var(--fs-5xl, 32px);
  line-height: 1;
  color: var(--color-text-faint);
}

.lq-thumbnail__label {
  font-size: var(--fs-xs, 12px);
  color: var(--color-text-faint);
  word-break: break-all;
  text-align: center;
}
</style>
