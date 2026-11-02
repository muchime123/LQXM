<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { fetchWebsiteMediaBlob } from '@/api/website/media'
import WebsiteMediaPicker from './WebsiteMediaPicker.vue'

const props = withDefaults(defineProps<{
  modelValue?: string
  hint?: string
  aspectRatio?: string
}>(), {
  modelValue: '',
  hint: '选择官网媒体库中的图片。',
  aspectRatio: '16 / 9'
})
const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()
const mediaUrl = (id: number) => `/api/website/media/${id}/content`
const mediaId = computed<number | null>({
  get: () => {
    const match = /^\/api\/website\/media\/(\d+)\/content$/.exec(props.modelValue || '')
    return match ? Number(match[1]) : null
  },
  set: id => emit('update:modelValue', id ? mediaUrl(id) : '')
})

const previewUrl = ref('')
let objectUrl = ''
let requestVersion = 0
watch(() => props.modelValue, async value => {
  const version = ++requestVersion
  if (objectUrl) URL.revokeObjectURL(objectUrl)
  objectUrl = ''
  previewUrl.value = ''
  const id = /^\/api\/website\/media\/(\d+)\/content$/.exec(value || '')?.[1]
  if (!id) {
    previewUrl.value = value || '' // Existing published and legacy images remain visible during cutover.
    return
  }
  try {
    const blob = await fetchWebsiteMediaBlob(Number(id))
    if (version !== requestVersion) return
    objectUrl = URL.createObjectURL(blob)
    previewUrl.value = objectUrl
  } catch {
    if (version === requestVersion) previewUrl.value = ''
  }
}, { immediate: true })
onBeforeUnmount(() => {
  requestVersion++
  if (objectUrl) URL.revokeObjectURL(objectUrl)
})
</script>

<template>
  <div class="website-image-field">
    <div class="website-image-field__preview" :style="{ aspectRatio }">
      <img v-if="previewUrl" :src="previewUrl" alt="官网图片预览">
      <span v-else>尚未设置图片</span>
    </div>
    <WebsiteMediaPicker v-model="mediaId" />
    <p v-if="modelValue && !mediaId" class="website-image-field__hint">当前图片继续保留；选择官网媒体后才会替换。</p>
    <p class="website-image-field__hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.website-image-field { display: grid; gap: 10px; width: 100%; }
.website-image-field__preview { display: grid; place-items: center; width: min(100%, 480px); min-height: 150px; overflow: hidden; border: 1px solid var(--color-border-light); border-radius: var(--radius-md); background: var(--color-surface-alt); color: var(--color-text-faint); }
.website-image-field__preview img { display: block; width: 100%; height: 100%; object-fit: contain; }
.website-image-field__hint { margin: 0; color: var(--color-text-secondary); font-size: 12px; }
</style>
