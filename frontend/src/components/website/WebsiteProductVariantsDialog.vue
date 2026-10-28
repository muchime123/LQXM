<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import {
  createWebsiteProductVariant, fetchWebsiteProductVariants, updateWebsiteProductVariant
} from '@/api/website/products'
import type { WebsiteProductVariant, WebsiteProductVariantFields } from '@/api/website/products'
import { notify } from '@/composables/ui/useToast'

const props = defineProps<{ modelValue: boolean; productId: number | null; productName: string }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})
const rows = ref<WebsiteProductVariant[]>([])
const loading = ref(false)
const saving = ref(false)
const editorVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<WebsiteProductVariantFields>({
  name: '', publicSku: null, dimensions: null, description: null, sortOrder: 0
})

async function load() {
  if (props.productId === null) return
  loading.value = true
  try {
    rows.value = await fetchWebsiteProductVariants(props.productId)
  } catch (error) {
    notify.errorWithDetail('加载官网产品规格失败', error)
  } finally {
    loading.value = false
  }
}

watch(() => [props.modelValue, props.productId], ([opened]) => {
  if (opened) load()
})

function create() {
  editingId.value = null
  Object.assign(form, { name: '', publicSku: null, dimensions: null, description: null, sortOrder: 0 })
  editorVisible.value = true
}

function edit(row: WebsiteProductVariant) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name, publicSku: row.publicSku, dimensions: row.dimensions,
    description: row.description, sortOrder: row.sortOrder
  })
  editorVisible.value = true
}

async function save() {
  if (props.productId === null || !form.name.trim()) {
    notify.error('请填写规格名称')
    return
  }
  saving.value = true
  try {
    const fields = { ...form, name: form.name.trim() }
    if (editingId.value === null) await createWebsiteProductVariant(props.productId, fields)
    else await updateWebsiteProductVariant(props.productId, editingId.value, fields)
    editorVisible.value = false
    await load()
    notify.success('官网产品规格草稿已保存')
  } catch (error) {
    notify.errorWithDetail('保存官网产品规格失败', error)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="`${productName} · 官网规格草稿`" width="min(800px, 94vw)">
    <div class="website-variant-actions">
      <span>规格仅归属该官网产品，保存后不会自动展示在官网。</span>
      <el-button type="primary" @click="create">新增规格</el-button>
    </div>
    <el-table v-loading="loading" :data="rows" empty-text="暂无官网规格草稿">
      <el-table-column prop="name" label="展示名称" min-width="170" />
      <el-table-column prop="publicSku" label="公开规格标识" min-width="140" />
      <el-table-column prop="dimensions" label="展示尺寸" min-width="140" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="row.status !== 'DRAFT'" @click="edit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editorVisible" :title="editingId === null ? '新增官网规格' : '编辑官网规格'" width="min(560px, 92vw)" append-to-body>
      <el-form label-position="top">
        <el-form-item label="展示名称" required><el-input v-model="form.name" maxlength="300" /></el-form-item>
        <el-form-item label="公开规格标识"><el-input v-model="form.publicSku" maxlength="200" /></el-form-item>
        <el-form-item label="展示尺寸"><el-input v-model="form.dimensions" maxlength="300" /></el-form-item>
        <el-form-item label="公开说明"><el-input v-model="form.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<style scoped>
.website-variant-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; color: var(--el-text-color-secondary); }
</style>
