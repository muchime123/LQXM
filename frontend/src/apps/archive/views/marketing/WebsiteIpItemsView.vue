<script setup lang="ts">
import { ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { createWebsiteIpItem, listWebsiteIpItems, publishWebsiteIpItem, unpublishWebsiteIpItem, updateWebsiteIpItem } from '@/api/website/ipItems'
import type { WebsiteIpItem, WebsiteIpItemFields } from '@/api/website/ipItems'
import WebsiteMediaPicker from '@/components/website/WebsiteMediaPicker.vue'
import { notify } from '@/composables/ui/useToast'
import { openCorporateWebsite } from '@/utils/websiteUrl'

const rows = ref<WebsiteIpItem[]>([])
const loading = ref(false)
const saving = ref(false)
const visible = ref(false)
const editingId = ref<number | null>(null)
const blank = (): WebsiteIpItemFields => ({ name: '', category: '专利', patentType: null,
  publicNumber: null, applicant: null, legalStatus: null, mediaId: null, sortOrder: 0 })
const form = ref<WebsiteIpItemFields>(blank())

async function load() {
  loading.value = true
  try { rows.value = await listWebsiteIpItems() }
  catch (error) { notify.errorWithDetail('加载官网知识产权失败', error) }
  finally { loading.value = false }
}
function create() { editingId.value = null; form.value = blank(); visible.value = true }
function edit(row: WebsiteIpItem) {
  editingId.value = row.id
  form.value = { name: row.name, category: row.category, patentType: row.patentType,
    publicNumber: row.publicNumber, applicant: row.applicant, legalStatus: row.legalStatus,
    mediaId: row.mediaId, sortOrder: row.sortOrder }
  visible.value = true
}
async function save() {
  if (!form.value.name.trim() || !form.value.category.trim()) { notify.warning('请填写名称和类别'); return }
  saving.value = true
  try {
    const fields = { ...form.value, name: form.value.name.trim(), category: form.value.category.trim() }
    if (editingId.value == null) await createWebsiteIpItem(fields)
    else await updateWebsiteIpItem(editingId.value, fields)
    visible.value = false
    await load()
    notify.success('官网知识产权草稿已保存')
  } catch (error) { notify.errorWithDetail('保存官网知识产权失败', error) }
  finally { saving.value = false }
}
async function changePublication(row: WebsiteIpItem) {
  const publishing = row.status === 'DRAFT'
  try {
    await ElMessageBox.confirm(publishing
      ? `确认发布“${row.name}”？请核对公开编号、申请人、法律状态及证书图片。当前官网仍展示静态内容。`
      : `确认下线“${row.name}”？`, publishing ? '确认发布' : '确认下线', { type: 'warning' })
  } catch { return }
  try {
    if (publishing) await publishWebsiteIpItem(row.id)
    else await unpublishWebsiteIpItem(row.id)
    await load()
    notify.success(publishing ? '已发布' : '已下线')
  } catch (error) { notify.errorWithDetail('更新发布状态失败', error) }
}
await load()
</script>

<template>
  <div class="website-ip-page">
    <header class="page-header">
      <div><p class="page-header__eyebrow">官网运营</p><h1 class="page-header__title">官网知识产权</h1>
        <p class="page-header__desc">独立维护对外展示内容。当前官网仍使用静态卡片；发布前须核实编号、申请人和图片。</p></div>
      <div class="page-header__actions"><el-button @click="openCorporateWebsite('/about#patents')">查看当前官网</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button><el-button type="primary" @click="create">新建草稿</el-button></div>
    </header>
    <el-table v-loading="loading" :data="rows" empty-text="暂无官网知识产权草稿">
      <el-table-column prop="name" label="名称" min-width="200" />
      <el-table-column prop="category" label="类别" width="100" />
      <el-table-column prop="publicNumber" label="公开编号" min-width="150" />
      <el-table-column prop="applicant" label="申请人" min-width="150" />
      <el-table-column prop="legalStatus" label="法律状态" width="110" />
      <el-table-column prop="status" label="发布状态" width="110" />
      <el-table-column label="操作" width="145"><template #default="{ row }">
        <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="edit(row)">编辑</el-button>
        <el-button link :type="row.status === 'DRAFT' ? 'success' : 'warning'" @click="changePublication(row)">
          {{ row.status === 'DRAFT' ? '发布' : '下线' }}</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog v-model="visible" :title="editingId == null ? '新建官网知识产权草稿' : '编辑官网知识产权草稿'"
      width="min(620px, 92vw)" :close-on-click-modal="false">
      <el-form :model="form" label-width="100px">
        <el-form-item label="名称" required><el-input v-model="form.name" maxlength="300" /></el-form-item>
        <el-form-item label="类别" required><el-select v-model="form.category"><el-option label="专利" value="专利" /><el-option label="软著" value="软著" /><el-option label="商标" value="商标" /></el-select></el-form-item>
        <el-form-item label="专利类型"><el-input v-model="form.patentType" maxlength="100" /></el-form-item>
        <el-form-item label="公开编号"><el-input v-model="form.publicNumber" maxlength="150" /></el-form-item>
        <el-form-item label="申请人"><el-input v-model="form.applicant" maxlength="300" /></el-form-item>
        <el-form-item label="法律状态"><el-input v-model="form.legalStatus" maxlength="100" /></el-form-item>
        <el-form-item label="证书图片"><WebsiteMediaPicker v-model="form.mediaId" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-dialog>
  </div>
</template>
