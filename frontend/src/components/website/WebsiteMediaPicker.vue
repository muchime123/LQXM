<script setup lang="ts">
import { ref, watch } from 'vue'
import { fetchWebsiteMedia, uploadWebsiteMedia } from '@/api/website/media'
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

watch(() => props.modelValue, value => {
  if (!value) selectedName.value = ''
})

async function load() {
  loading.value = true
  try {
    const result = await fetchWebsiteMedia(page.value, 20)
    rows.value = result.content.filter(item => item.mediaType === 'IMAGE' && item.status !== 'ARCHIVED')
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
        <input ref="input" type="file" hidden accept="image/jpeg,image/png,image/gif,image/webp" @change="upload">
        <el-button type="primary" :loading="uploading" @click="input?.click()">上传新图片</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" empty-text="本页暂无可选图片">
        <el-table-column label="图片" min-width="260">
          <template #default="{ row }">{{ row.displayName || row.originalFilename }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }"><el-button link type="primary" @click="select(row)">选择</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination :current-page="page + 1" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="changePage" />
    </el-dialog>
  </div>
</template>

<style scoped>
.website-media-picker__name { margin-left: 12px; color: var(--el-text-color-regular); }
.website-media-picker__actions { margin-bottom: 12px; }
</style>
