<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { archiveWebsiteSocialLink, createWebsiteSocialLink, fetchWebsiteSocialLinkParity, fetchWebsiteSocialLinks, publishWebsiteSocialLink, updateWebsiteSocialLink } from '@/api/website/socialLinks'
import type { WebsiteSocialLink, WebsiteSocialLinkFields, WebsiteSocialLinkParity } from '@/api/website/socialLinks'
import { notify } from '@/composables/ui/useToast'

const rows = ref<WebsiteSocialLink[]>([])
const loading = ref(false)
const saving = ref(false)
const parity = ref<WebsiteSocialLinkParity | null>(null)
const comparing = ref(false)
const dialog = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<WebsiteSocialLinkFields>({ platform: '', accountName: '', link: '', sortOrder: 0 })

async function load() {
  loading.value = true
  try { rows.value = await fetchWebsiteSocialLinks() }
  catch (error) { notify.errorWithDetail('加载官网官方链接失败', error) }
  finally { loading.value = false }
}
async function compare() {
  comparing.value = true
  try { parity.value = await fetchWebsiteSocialLinkParity() }
  catch (error) { notify.errorWithDetail('对拍官网官方链接失败', error) }
  finally { comparing.value = false }
}
function open(item?: WebsiteSocialLink) {
  editingId.value = item?.id ?? null
  form.platform = item?.platform ?? ''
  form.accountName = item?.accountName ?? ''
  form.link = item?.link ?? ''
  form.sortOrder = item?.sortOrder ?? 0
  dialog.value = true
}
async function save() {
  if (!form.platform.trim() || !form.accountName.trim() || !form.link.trim()) {
    notify.error('请填写平台、账号名称和公开链接'); return
  }
  saving.value = true
  try {
    const fields = { platform: form.platform.trim(), accountName: form.accountName.trim(), link: form.link.trim(), sortOrder: form.sortOrder }
    if (editingId.value == null) await createWebsiteSocialLink(fields)
    else await updateWebsiteSocialLink(editingId.value, fields)
    dialog.value = false
    parity.value = null
    await load()
    notify.success('官网官方链接草稿已保存')
  } catch (error) { notify.errorWithDetail('保存官网官方链接失败', error) }
  finally { saving.value = false }
}
async function publish(item: WebsiteSocialLink) {
  try { await ElMessageBox.confirm(`确认发布“${item.accountName}”？若部署环境已使用 native 公司来源，官网将立即展示该链接。`, '确认发布', { type: 'warning' }) }
  catch { return }
  try { await publishWebsiteSocialLink(item.id); parity.value = null; await load(); notify.success('官网官方链接已发布') }
  catch (error) { notify.errorWithDetail('发布官网官方链接失败', error) }
}
async function archive(item: WebsiteSocialLink) {
  try { await ElMessageBox.confirm(`确认归档“${item.accountName}”？已发布链接将从 native 官网移除。`, '确认归档', { type: 'warning' }) }
  catch { return }
  try { await archiveWebsiteSocialLink(item.id); parity.value = null; await load(); notify.success('官网官方链接已归档') }
  catch (error) { notify.errorWithDetail('归档官网官方链接失败', error) }
}

await load()
</script>

<template>
  <div class="website-social-links-page">
    <header class="page-header">
      <div class="page-header__content">
        <p class="page-header__eyebrow">官网运营</p>
        <h1 class="page-header__title">官网官方链接</h1>
        <p class="page-header__desc">独立维护公开社交账号。内部自媒体账号的变更不会自动同步。</p>
      </div>
      <div class="page-header__actions"><el-button @click="load">刷新</el-button><el-button :loading="comparing" @click="compare">对拍公开链接</el-button><el-button type="primary" @click="open()">新增草稿</el-button></div>
    </header>
    <section v-if="parity" class="parity">
      <h2>当前公开链接对拍</h2>
      <el-alert :title="`旧来源公开 ${parity.legacyCount} 条；官网独立记录 ${parity.websiteCount} 条；已发布一致 ${parity.publishedMatches} 条；草稿一致 ${parity.draftMatches} 条。系统不会自动复制。`" type="info" :closable="false" />
      <el-table :data="parity.legacyRows" border>
        <el-table-column prop="platform" label="平台" width="120" />
        <el-table-column prop="accountName" label="旧公开账号" min-width="180" />
        <el-table-column prop="link" label="旧公开链接" min-width="260" show-overflow-tooltip />
        <el-table-column prop="websiteId" label="官网记录 ID" width="115" />
        <el-table-column prop="matchStatus" label="结果" width="120" />
      </el-table>
      <el-alert v-if="parity.unmatchedWebsiteRows.length" :title="`官网侧另有 ${parity.unmatchedWebsiteRows.length} 条不同或额外链接，请逐条审阅。`" type="warning" :closable="false" />
      <el-table v-if="parity.unmatchedWebsiteRows.length" :data="parity.unmatchedWebsiteRows" border>
        <el-table-column prop="websiteId" label="官网记录 ID" width="115" />
        <el-table-column prop="platform" label="平台" width="120" />
        <el-table-column prop="accountName" label="官网账号" min-width="180" />
        <el-table-column prop="link" label="官网链接" min-width="260" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="110" />
      </el-table>
    </section>
    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="platform" label="平台" width="120" />
      <el-table-column prop="accountName" label="账号名称" min-width="180" />
      <el-table-column prop="link" label="公开链接" min-width="260" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" text @click="open(row)">编辑</el-button>
          <el-button v-if="row.status === 'DRAFT'" text @click="publish(row)">发布</el-button>
          <el-button text type="danger" @click="archive(row)">归档</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dialog" :title="editingId == null ? '新增官网官方链接' : '编辑官网官方链接'" width="600px">
      <el-form label-position="top">
        <el-form-item label="平台"><el-input v-model="form.platform" maxlength="50" /></el-form-item>
        <el-form-item label="账号名称"><el-input v-model="form.accountName" maxlength="200" /></el-form-item>
        <el-form-item label="公开链接"><el-input v-model="form.link" maxlength="1000" placeholder="https://" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.website-social-links-page { max-width: 1100px; }
.parity { margin-bottom: 24px; }
</style>
