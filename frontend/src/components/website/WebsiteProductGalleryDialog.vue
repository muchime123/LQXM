<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { addWebsiteProductGallery, fetchWebsiteProductReview, updateWebsiteProductGallery } from '@/api/website/products'
import type { WebsiteProductGalleryFields, WebsiteProductReview } from '@/api/website/products'
import { notify } from '@/composables/ui/useToast'
import WebsiteMediaPicker from './WebsiteMediaPicker.vue'

const props = defineProps<{ modelValue: boolean; productId: number | null; productName: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const loading = ref(false)
const saving = ref(false)
const review = ref<WebsiteProductReview | null>(null)
const editingAssignmentId = ref<number | null>(null)
const form = reactive<{ mediaId: number | null; role: WebsiteProductGalleryFields['role']; altText: string; sortOrder: number }>({
  mediaId: null, role: 'GALLERY', altText: '', sortOrder: 0
})
const roles = [
  { value: 'HERO', label: '主图' }, { value: 'GALLERY', label: '图库' }, { value: 'DETAIL', label: '细节' },
  { value: 'SCENE', label: '场景' }, { value: 'PARAMETER', label: '参数图' }
] as const

async function load() {
  if (props.productId === null) return
  loading.value = true
  try {
    review.value = await fetchWebsiteProductReview(props.productId)
  } catch (error) {
    notify.errorWithDetail('加载官网产品图库失败', error)
  } finally {
    loading.value = false
  }
}

function reset() {
  editingAssignmentId.value = null
  Object.assign(form, { mediaId: null, role: 'GALLERY', altText: '', sortOrder: review.value?.gallery.length || 0 })
}

function edit(row: WebsiteProductReview['gallery'][number]) {
  editingAssignmentId.value = row.id
  Object.assign(form, { mediaId: row.mediaId, role: row.role, altText: row.altText || '', sortOrder: row.sortOrder })
}

async function save() {
  if (props.productId === null || form.mediaId === null) {
    notify.error('请选择官网图片')
    return
  }
  saving.value = true
  try {
    const fields: WebsiteProductGalleryFields = {
      mediaId: form.mediaId, role: form.role, altText: form.altText.trim() || null, sortOrder: form.sortOrder
    }
    if (editingAssignmentId.value === null) await addWebsiteProductGallery(props.productId, fields)
    else await updateWebsiteProductGallery(props.productId, editingAssignmentId.value, fields)
    await load()
    reset()
    notify.success('官网产品图库已保存')
  } catch (error) {
    notify.errorWithDetail('保存官网产品图库失败', error)
  } finally {
    saving.value = false
  }
}

watch(() => [props.modelValue, props.productId] as const, ([visible]) => {
  if (visible) { reset(); load() }
})
</script>

<template>
  <el-dialog :model-value="modelValue" :title="`${productName} · 官网图库`" width="min(780px, 94vw)"
    @update:model-value="emit('update:modelValue', $event)">
    <el-table v-loading="loading" :data="review?.gallery || []" empty-text="暂无官网图库图片">
      <el-table-column prop="originalFilename" label="文件" min-width="210" />
      <el-table-column prop="role" label="角色" width="110" />
      <el-table-column prop="sortOrder" label="顺序" width="90" />
      <el-table-column prop="altText" label="替代文本" min-width="150" />
      <el-table-column label="操作" width="80">
        <template #default="{ row }"><el-button link type="primary" @click="edit(row)">编辑</el-button></template>
      </el-table-column>
    </el-table>
    <el-divider>{{ editingAssignmentId === null ? '添加官网图片' : '编辑图片关联' }}</el-divider>
    <el-form label-position="top">
      <el-form-item label="官网图片"><WebsiteMediaPicker v-model="form.mediaId" /></el-form-item>
      <el-form-item label="展示角色">
        <el-select v-model="form.role">
          <el-option v-for="item in roles" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="替代文本"><el-input v-model="form.altText" maxlength="500" /></el-form-item>
      <el-form-item label="顺序"><el-input-number v-model="form.sortOrder" :min="0" :max="100000" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button v-if="editingAssignmentId !== null" @click="reset">取消编辑</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存图库关联</el-button>
    </template>
  </el-dialog>
</template>
