<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { fetchWebsiteMedia, fetchWebsiteMediaBlob, uploadWebsiteMedia } from '@/api/website/media'
import type { WebsiteMediaItem } from '@/api/website/media'
import { notify } from '@/composables/ui/useToast'

const loading = ref(false)
const uploading = ref(false)
const rows = ref<WebsiteMediaItem[]>([])
const page = ref(0)
const size = ref(20)
const total = ref(0)
const input = ref<HTMLInputElement | null>(null)
const previewVisible = ref(false)
const previewUrl = ref('')
const previewItem = ref<WebsiteMediaItem | null>(null)

async function loadData() {
  loading.value = true
  try {
    const result = await fetchWebsiteMedia(page.value, size.value)
    rows.value = result.content
    total.value = result.totalElements
  } catch (error) {
    notify.errorWithDetail('加载官网媒体失败', error)
  } finally {
    loading.value = false
  }
}

async function onFilesSelected(event: Event) {
  const target = event.target as HTMLInputElement
  const files = Array.from(target.files || [])
  target.value = ''
  if (!files.length) return
  uploading.value = true
  try {
    for (const file of files) await uploadWebsiteMedia(file)
    page.value = 0
    await loadData()
    notify.success(`已登记 ${files.length} 个官网媒体文件`)
  } catch (error) {
    notify.errorWithDetail('官网媒体上传失败', error)
    await loadData()
  } finally {
    uploading.value = false
  }
}

function closePreview() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  previewItem.value = null
  previewVisible.value = false
}

async function openPreview(item: WebsiteMediaItem) {
  closePreview()
  try {
    const blob = await fetchWebsiteMediaBlob(item.id)
    previewUrl.value = URL.createObjectURL(blob)
    previewItem.value = item
    previewVisible.value = true
  } catch (error) {
    notify.errorWithDetail('读取官网媒体失败', error)
  }
}

function changePage(next: number) {
  page.value = next - 1
  loadData()
}

onBeforeUnmount(closePreview)
await loadData()
</script>

<template>
  <div class="website-media-page">
    <header class="page-header">
      <div class="page-header__content">
        <p class="page-header__eyebrow">官网运营</p>
        <h1 class="page-header__title">官网媒体库</h1>
        <p class="page-header__desc">官网文件独立保存。上传后先作为草稿登记，不会自动发布到官网。</p>
      </div>
      <div class="page-header__actions">
        <input ref="input" type="file" multiple hidden accept="image/jpeg,image/png,image/gif,image/webp,application/pdf,video/mp4,video/webm" @change="onFilesSelected">
        <el-button type="primary" :loading="uploading" @click="input?.click()">上传官网文件</el-button>
        <el-button :loading="loading" @click="loadData">刷新</el-button>
      </div>
    </header>

    <el-table v-loading="loading" :data="rows" empty-text="暂无官网媒体；上传后先作为草稿保存">
      <el-table-column prop="displayName" label="文件" min-width="240">
        <template #default="{ row }">{{ row.displayName || row.originalFilename }}</template>
      </el-table-column>
      <el-table-column prop="mediaType" label="类型" width="110" />
      <el-table-column label="大小" width="110">
        <template #default="{ row }">{{ (row.sizeBytes / 1024 / 1024).toFixed(2) }} MB</template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button link type="primary" @click="openPreview(row)">预览</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      :current-page="page + 1"
      class="website-media-page__pagination"
      background
      layout="total, prev, pager, next"
      :total="total"
      :page-size="size"
      @current-change="changePage"
    />

    <el-dialog v-model="previewVisible" :title="previewItem?.displayName || previewItem?.originalFilename || '官网媒体预览'" width="min(900px, 90vw)" @closed="closePreview">
      <img v-if="previewItem?.mediaType === 'IMAGE'" :src="previewUrl" alt="官网媒体预览" style="display: block; max-width: 100%; max-height: 70vh; margin: auto">
      <video v-else-if="previewItem?.mediaType === 'VIDEO'" :src="previewUrl" controls style="width: 100%; max-height: 70vh" />
      <iframe v-else :src="previewUrl" title="官网文档预览" style="width: 100%; height: 70vh; border: 0" />
    </el-dialog>
  </div>
</template>

<style scoped>
.website-media-page__pagination { margin-top: 20px; justify-content: flex-end; }
</style>
