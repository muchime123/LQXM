<script setup lang="ts">
import { onBeforeUnmount, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { createWebsiteCredential, listWebsiteCredentials, publishWebsiteCredential, unpublishWebsiteCredential, updateWebsiteCredential } from '@/api/website/credentials'
import type { WebsiteCredential, WebsiteCredentialFields } from '@/api/website/credentials'
import WebsiteMediaPicker from '@/components/website/WebsiteMediaPicker.vue'
import { fetchWebsiteMediaBlob } from '@/api/website/media'
import { notify } from '@/composables/ui/useToast'
import { openCorporateWebsite } from '@/utils/websiteUrl'

const rows = ref<WebsiteCredential[]>([])
const loading = ref(false)
const saving = ref(false)
const visible = ref(false)
const editingId = ref<number | null>(null)
const blank = (): WebsiteCredentialFields => ({ name: '', category: null, publicDescription: null,
  issuer: null, validUntil: null, mediaId: null, sortOrder: 0 })
const form = ref<WebsiteCredentialFields>(blank())
const reviewing = ref<WebsiteCredential | null>(null)
const reviewImageUrl = ref('')
const reviewImageLoading = ref(false)
let reviewObjectUrl = ''
let reviewVersion = 0

function clearReviewImage() {
  reviewVersion++
  if (reviewObjectUrl) URL.revokeObjectURL(reviewObjectUrl)
  reviewObjectUrl = ''
  reviewImageUrl.value = ''
}

async function review(row: WebsiteCredential) {
  clearReviewImage()
  reviewing.value = row
  if (!row.mediaId) return
  const version = reviewVersion
  reviewImageLoading.value = true
  try {
    const blob = await fetchWebsiteMediaBlob(row.mediaId)
    if (version !== reviewVersion) return
    reviewObjectUrl = URL.createObjectURL(blob)
    reviewImageUrl.value = reviewObjectUrl
  } catch (error) {
    if (version === reviewVersion) notify.errorWithDetail('证书图片预览失败', error)
  } finally {
    if (version === reviewVersion) reviewImageLoading.value = false
  }
}
onBeforeUnmount(clearReviewImage)

async function load() {
  loading.value = true
  try { rows.value = await listWebsiteCredentials() }
  catch (error) { notify.errorWithDetail('加载官网资质失败', error) }
  finally { loading.value = false }
}
function create() { editingId.value = null; form.value = blank(); visible.value = true }
function edit(row: WebsiteCredential) {
  editingId.value = row.id
  form.value = { name: row.name, category: row.category, publicDescription: row.publicDescription,
    issuer: row.issuer, validUntil: row.validUntil, mediaId: row.mediaId, sortOrder: row.sortOrder }
  visible.value = true
}
async function save() {
  if (!form.value.name.trim()) { notify.warning('请填写资质名称'); return }
  saving.value = true
  try {
    const fields = { ...form.value, name: form.value.name.trim() }
    if (editingId.value == null) await createWebsiteCredential(fields)
    else await updateWebsiteCredential(editingId.value, fields)
    visible.value = false
    await load()
    notify.success('官网资质草稿已保存')
  } catch (error) { notify.errorWithDetail('保存官网资质失败', error) }
  finally { saving.value = false }
}
async function changePublication(row: WebsiteCredential) {
  const publishing = row.status === 'DRAFT'
  try { await ElMessageBox.confirm(
    publishing ? `确认发布“${row.name}”？请先核对证书真实有效。当前官网页面尚未读取新表。`
      : `确认下线“${row.name}”？记录和图片引用仍会保留。`,
    publishing ? '确认发布' : '确认下线', { type: 'warning' }) }
  catch { return }
  try {
    if (publishing) await publishWebsiteCredential(row.id)
    else await unpublishWebsiteCredential(row.id)
    await load()
    notify.success(publishing ? '官网资质已发布' : '官网资质已下线')
  } catch (error) { notify.errorWithDetail('修改官网资质发布状态失败', error) }
}
await load()
</script>

<template>
  <div class="website-credentials-page">
    <header class="page-header">
      <div><p class="page-header__eyebrow">官网运营</p><h1 class="page-header__title">官网资质</h1>
        <p class="page-header__desc">独立维护对外资质草稿。当前官网展示仍由原有静态内容提供，发布记录不会自动替换页面。</p></div>
      <div class="page-header__actions"><el-button @click="openCorporateWebsite('/about#credentials')">查看当前官网</el-button><el-button :loading="loading" @click="load">刷新</el-button><el-button type="primary" @click="create">新建草稿</el-button></div>
    </header>
    <el-table v-loading="loading" :data="rows" empty-text="暂无官网资质草稿">
      <el-table-column prop="name" label="资质名称" min-width="200" />
      <el-table-column prop="category" label="类别" width="130" />
      <el-table-column prop="issuer" label="颁发机构" min-width="150" />
      <el-table-column prop="validUntil" label="到期日期" width="130" />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column label="操作" width="210"><template #default="{ row }">
        <el-button link @click="review(row)">核对</el-button>
        <el-button
v-if="row.status === 'DRAFT'"
link
type="primary"
          @click="edit(row)">编辑</el-button>
        <el-button link :type="row.status === 'DRAFT' ? 'success' : 'warning'" @click="changePublication(row)">{{ row.status === 'DRAFT' ? '发布' : '下线' }}</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog
:model-value="reviewing !== null"
title="官网资质内容核对"
width="min(620px, 92vw)"
      @close="reviewing = null; clearReviewImage()">
      <template v-if="reviewing">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="名称">{{ reviewing.name }}</el-descriptions-item>
          <el-descriptions-item label="类别">{{ reviewing.category || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="公开说明">{{ reviewing.publicDescription || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="颁发机构">{{ reviewing.issuer || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="有效期至">{{ reviewing.validUntil || '长期或未填写' }}</el-descriptions-item>
          <el-descriptions-item label="发布状态">{{ reviewing.status }}</el-descriptions-item>
        </el-descriptions>
        <div v-loading="reviewImageLoading" class="credential-review-image">
          <img v-if="reviewImageUrl" :src="reviewImageUrl" :alt="reviewing.name + '证书图片'">
          <span v-else>{{ reviewing.mediaId ? '图片未能预览' : '未设置证书图片' }}</span>
        </div>
        <p class="credential-review-hint">请与当前官网关于页逐条核对。此预览只读取官网独立记录，不会更改发布状态或切换官网数据源。</p>
      </template>
      <template #footer><el-button @click="openCorporateWebsite('/about#credentials')">打开当前官网</el-button><el-button @click="reviewing = null; clearReviewImage()">关闭</el-button></template>
    </el-dialog>
    <el-dialog
v-model="visible"
:title="editingId == null ? '新建官网资质草稿' : '编辑官网资质草稿'"
width="min(620px, 92vw)"
      :close-on-click-modal="false">
      <el-form :model="form" label-width="100px">
        <el-form-item label="资质名称" required><el-input v-model="form.name" maxlength="300" /></el-form-item>
        <el-form-item label="类别"><el-input v-model="form.category" maxlength="100" /></el-form-item>
        <el-form-item label="颁发机构"><el-input v-model="form.issuer" maxlength="200" /></el-form-item>
        <el-form-item label="有效期至"><el-date-picker
v-model="form.validUntil"
type="date"
value-format="YYYY-MM-DD"
          clearable /></el-form-item>
        <el-form-item label="公开说明"><el-input v-model="form.publicDescription" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="证书图片"><WebsiteMediaPicker v-model="form.mediaId" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.credential-review-image { display: grid; place-items: center; min-height: 180px; margin-top: 16px; padding: 12px; border: 1px solid var(--color-border-light); border-radius: var(--radius-md); color: var(--color-text-secondary); }
.credential-review-image img { max-width: 100%; max-height: 400px; object-fit: contain; }
.credential-review-hint { color: var(--color-text-secondary); font-size: 13px; }
</style>
