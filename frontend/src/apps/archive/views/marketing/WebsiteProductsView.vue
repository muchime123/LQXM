<script setup lang="ts">
import { reactive, ref } from 'vue'
import {
  createWebsiteProductDraft,
  fetchWebsiteProductDrafts,
  updateWebsiteProductDraft
} from '@/api/website/products'
import type { WebsiteProductDraft, WebsiteProductDraftFields } from '@/api/website/products'
import { notify } from '@/composables/ui/useToast'

const rows = ref<WebsiteProductDraft[]>([])
const loading = ref(false)
const saving = ref(false)
const page = ref(0)
const total = ref(0)
const editorVisible = ref(false)
const editingId = ref<number | null>(null)

function emptyFields(): WebsiteProductDraftFields {
  return {
    name: '', nameEn: null, publicModel: null, slug: '', summary: null,
    summaryEn: null, seoTitle: null, seoDescription: null, sortOrder: 0, featured: false
  }
}
const form = reactive<WebsiteProductDraftFields>(emptyFields())

async function loadData() {
  loading.value = true
  try {
    const result = await fetchWebsiteProductDrafts(page.value)
    rows.value = result.content
    total.value = result.totalElements
  } catch (error) {
    notify.errorWithDetail('加载官网产品草稿失败', error)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editingId.value = null
  Object.assign(form, emptyFields())
  editorVisible.value = true
}

function openEdit(item: WebsiteProductDraft) {
  editingId.value = item.id
  Object.assign(form, {
    name: item.name, nameEn: item.nameEn, publicModel: item.publicModel,
    slug: item.slug, summary: item.summary, summaryEn: item.summaryEn,
    seoTitle: item.seoTitle, seoDescription: item.seoDescription,
    sortOrder: item.sortOrder, featured: item.featured
  })
  editorVisible.value = true
}

async function save() {
  if (!form.name.trim() || !form.slug.trim()) {
    notify.error('请填写产品名称和 URL 标识')
    return
  }
  saving.value = true
  try {
    const fields = { ...form, name: form.name.trim(), slug: form.slug.trim() }
    if (editingId.value === null) await createWebsiteProductDraft(fields)
    else await updateWebsiteProductDraft(editingId.value, fields)
    editorVisible.value = false
    await loadData()
    notify.success('官网产品草稿已保存，当前不会出现在官网')
  } catch (error) {
    notify.errorWithDetail('保存官网产品草稿失败', error)
  } finally {
    saving.value = false
  }
}

function changePage(next: number) {
  page.value = next - 1
  loadData()
}

await loadData()
</script>

<template>
  <div class="website-products-page">
    <header class="page-header">
      <div class="page-header__content">
        <p class="page-header__eyebrow">官网运营</p>
        <h1 class="page-header__title">官网产品草稿</h1>
        <p class="page-header__desc">独立维护官网产品内容。草稿暂不发布，现有官网产品展示保持不变。</p>
      </div>
      <div class="page-header__actions">
        <el-button type="primary" @click="openCreate">新增草稿</el-button>
        <el-button :loading="loading" @click="loadData">刷新</el-button>
      </div>
    </header>

    <el-table v-loading="loading" :data="rows" empty-text="暂无官网产品草稿">
      <el-table-column prop="name" label="产品名称" min-width="220" />
      <el-table-column prop="publicModel" label="公开型号" min-width="160" />
      <el-table-column prop="slug" label="URL 标识" min-width="180" />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="row.status !== 'DRAFT'" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      :current-page="page + 1"
      class="website-products-page__pagination"
      background
      layout="total, prev, pager, next"
      :total="total"
      :page-size="20"
      @current-change="changePage"
    />

    <el-dialog v-model="editorVisible" :title="editingId === null ? '新增官网产品草稿' : '编辑官网产品草稿'" width="min(680px, 92vw)">
      <el-form label-position="top">
        <el-form-item label="产品名称" required><el-input v-model="form.name" maxlength="300" show-word-limit /></el-form-item>
        <el-form-item label="公开型号"><el-input v-model="form.publicModel" maxlength="200" /></el-form-item>
        <el-form-item label="URL 标识" required>
          <el-input v-model="form.slug" maxlength="200" placeholder="小写字母、数字和连字符" />
        </el-form-item>
        <el-form-item label="公开摘要"><el-input v-model="form.summary" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="英文名称"><el-input v-model="form.nameEn" maxlength="300" /></el-form-item>
        <el-form-item label="英文摘要"><el-input v-model="form.summaryEn" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="SEO 标题"><el-input v-model="form.seoTitle" maxlength="300" /></el-form-item>
        <el-form-item label="SEO 描述"><el-input v-model="form.seoDescription" type="textarea" maxlength="500" show-word-limit /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
        <el-form-item label="未来首页精选"><el-switch v-model="form.featured" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.website-products-page__pagination { margin-top: 20px; justify-content: flex-end; }
</style>
