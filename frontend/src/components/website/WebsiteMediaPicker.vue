<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { fetchWebsiteMedia, fetchWebsiteMediaBlob, uploadWebsiteMedia } from '@/api/website/media'
import type { WebsiteMediaItem } from '@/api/website/media'
import { notify } from '@/composables/ui/useToast'

const props = defineProps<{ modelValue?: number | null }>()
const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()
const visible = ref(false)
const loading = ref(false)
const uploading = ref(false)
const rows = ref<WebsiteMediaItem[]>([])
const page = ref(0)
const total = ref(0)
const selectedName = ref('')
const input = ref<HTMLInputElement | null>(null)
const previewItem = ref<WebsiteMediaItem | null>(null)
const previewUrl = ref('')
const previewLoading = ref(false)
let previewObjectUrl = ''
let previewVersion = 0

function clearPreview() {
  previewVersion++
  if (previewObjectUrl) URL.revokeObjectURL(previewObjectUrl)
  previewObjectUrl = ''
  previewUrl.value = ''
  previewItem.value = null
  previewLoading.value = false
}

async function preview(item: WebsiteMediaItem) {
  clearPreview()
  previewItem.value = item
  previewLoading.value = true
  const version = previewVersion
  try {
    const blob = await fetchWebsiteMediaBlob(item.id)
    if (version !== previewVersion) return
    previewObjectUrl = URL.createObjectURL(blob)
    previewUrl.value = previewObjectUrl
  } catch (error) {
    if (version === previewVersion) notify.errorWithDetail('官网图片预览失败', error)
  } finally {
    if (version === previewVersion) previewLoading.value = false
  }
}
onBeforeUnmount(clearPreview)

watch(() => props.modelValue, value => {
  if (!value) selectedName.value = ''
})

async function load() {
  loading.value = true
  try {
    const result = await fetchWebsiteMedia(page.value, 20, true)
    rows.value = result.content
    total.value = result.totalElements
    const current = result.content.find(item => item.id === props.modelValue)
    if (current) selectedName.value = current.displayName || current.originalFilename
  } catch (error) {
    notify.errorWithDetail('加载官网媒体失败', error)
  } finally {
    loading.value = false
  }
}

function open() {
  page.value = 0
  visible.value = true
  load()
}

function select(item: WebsiteMediaItem) {
  selectedName.value = item.displayName || item.originalFilename
  emit('update:modelValue', item.id)
  visible.value = false
}

function changePage(value: number) {
  page.value = value - 1
  load()
}

async function upload(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  target.value = ''
  if (!file) return
  uploading.value = true
  try {
    const item = await uploadWebsiteMedia(file)
    select(item)
  } catch (error) {
    notify.errorWithDetail('上传官网图片失败', error)
  } finally {
    uploading.value = false
  }
}
</script>

<template>
  <div class="website-media-picker">
    <el-button @click="open">{{ modelValue ? '更换官网图片' : '选择官网图片' }}</el-button>
    <span v-if="modelValue" class="website-media-picker__name">{{ selectedName || `媒体 #${modelValue}` }}</span>
    <el-button v-if="modelValue" link @click="emit('update:modelValue', null)">清除</el-button>
    <el-dialog v-model="visible" title="选择官网图片" width="min(760px, 90vw)">
      <div class="website-media-picker__actions">
        <input
ref="input"
type="file"
hidden
accept="image/jpeg,image/png,image/gif,image/webp"
@change="upload">
        <el-button type="primary" :loading="uploading" @click="input?.click()">上传新图片</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" empty-text="本页暂无可选图片">
        <el-table-column label="图片" min-width="260">
          <template #default="{ row }">{{ row.displayName || row.originalFilename }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link @click="preview(row)">预览</el-button>
            <el-button link type="primary" @click="select(row)">选择</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
:current-page="page + 1"
:page-size="20"
:total="total"
layout="total, prev, pager, next"
@current-change="changePage" />
    </el-dialog>
    <el-dialog
:model-value="previewItem !== null"
append-to-body
title="核对官网图片"
width="min(640px, 90vw)"
@close="clearPreview">
      <div v-loading="previewLoading" class="website-media-picker__preview">
        <img v-if="previewUrl" :src="previewUrl" :alt="previewItem?.altText || previewItem?.displayName || previewItem?.originalFilename || '官网图片'">
        <span v-else-if="!previewLoading">图片无法预览</span>
      </div>
      <p v-if="previewItem" class="website-media-picker__preview-name">{{ previewItem.displayName || previewItem.originalFilename }}</p>
      <template #footer>
        <el-button @click="clearPreview">关闭</el-button>
        <el-button v-if="previewItem && previewUrl" type="primary" @click="select(previewItem); clearPreview()">选用此图</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.website-media-picker__name { margin-left: 12px; color: var(--el-text-color-regular); }
.website-media-picker__actions { margin-bottom: 12px; }
.website-media-picker__preview { display: grid; place-items: center; min-height: 240px; padding: 12px; border: 1px solid var(--color-border-light); border-radius: var(--radius-md); }
.website-media-picker__preview img { max-width: 100%; max-height: 60vh; object-fit: contain; }
.website-media-picker__preview-name { margin-top: 10px; color: var(--color-text-secondary); overflow-wrap: anywhere; }
</style>
