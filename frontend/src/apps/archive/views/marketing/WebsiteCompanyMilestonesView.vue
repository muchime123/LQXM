<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { archiveWebsiteCompanyMilestone, createWebsiteCompanyMilestone, fetchWebsiteCompanyMilestoneParity, fetchWebsiteCompanyMilestones, publishWebsiteCompanyMilestone, updateWebsiteCompanyMilestone } from '@/api/website/companyMilestones'
import type { WebsiteCompanyMilestone, WebsiteCompanyMilestoneFields, WebsiteCompanyMilestoneParity } from '@/api/website/companyMilestones'
import { notify } from '@/composables/ui/useToast'

const rows = ref<WebsiteCompanyMilestone[]>([])
const loading = ref(false)
const saving = ref(false)
const comparing = ref(false)
const parity = ref<WebsiteCompanyMilestoneParity | null>(null)
const dialog = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<WebsiteCompanyMilestoneFields>({ year: new Date().getFullYear(), title: '', description: null, sortOrder: 0 })

async function load() {
  loading.value = true
  try { rows.value = await fetchWebsiteCompanyMilestones() }
  catch (error) { notify.errorWithDetail('加载官网发展历程失败', error) }
  finally { loading.value = false }
}
async function compare() {
  comparing.value = true
  try { parity.value = await fetchWebsiteCompanyMilestoneParity() }
  catch (error) { notify.errorWithDetail('对拍发展历程失败', error) }
  finally { comparing.value = false }
}
function open(item?: WebsiteCompanyMilestone) {
  editingId.value = item?.id ?? null
  form.year = item?.year ?? new Date().getFullYear()
  form.title = item?.title ?? ''
  form.description = item?.description ?? null
  form.sortOrder = item?.sortOrder ?? 0
  dialog.value = true
}
async function save() {
  if (!form.title.trim()) { notify.error('请填写历程标题'); return }
  saving.value = true
  try {
    const fields = { ...form, title: form.title.trim(), description: form.description?.trim() || null }
    if (editingId.value == null) await createWebsiteCompanyMilestone(fields)
    else await updateWebsiteCompanyMilestone(editingId.value, fields)
    dialog.value = false
    parity.value = null
    await load()
    notify.success('官网发展历程草稿已保存')
  } catch (error) { notify.errorWithDetail('保存官网发展历程失败', error) }
  finally { saving.value = false }
}
async function publish(item: WebsiteCompanyMilestone) {
  try {
    await ElMessageBox.confirm(`确认发布“${item.title}”？部署环境若已使用 native 公司资料来源，发布将立即改变官网历程。`, '确认发布', { type: 'warning' })
  } catch { return }
  try { await publishWebsiteCompanyMilestone(item.id); parity.value = null; await load(); notify.success('官网发展历程已发布') }
  catch (error) { notify.errorWithDetail('发布官网发展历程失败', error) }
}
async function archive(item: WebsiteCompanyMilestone) {
  try {
    await ElMessageBox.confirm(`确认归档“${item.title}”？已发布记录归档后会从 native 官网历程中移除。`, '确认归档', { type: 'warning' })
  } catch { return }
  try { await archiveWebsiteCompanyMilestone(item.id); parity.value = null; await load(); notify.success('官网发展历程已归档') }
  catch (error) { notify.errorWithDetail('归档官网发展历程失败', error) }
}

await load()
</script>

<template>
  <div class="website-company-milestones-page">
    <header class="page-header">
      <div class="page-header__content">
        <p class="page-header__eyebrow">官网运营</p>
        <h1 class="page-header__title">官网发展历程</h1>
        <p class="page-header__desc">独立维护官网历程；内部公司档案的修改不会自动同步。公开接口默认仍使用旧来源。</p>
      </div>
      <div class="page-header__actions">
        <el-button @click="load">刷新</el-button>
        <el-button :loading="comparing" @click="compare">对拍公开历程</el-button>
        <el-button type="primary" @click="open()">新增草稿</el-button>
      </div>
    </header>
    <section v-if="parity" class="parity">
      <h2>当前公开历程对拍</h2>
      <el-alert :title="`旧来源公开 ${parity.legacyCount} 条；官网独立记录 ${parity.websiteCount} 条；已发布一致 ${parity.publishedMatches} 条；草稿一致 ${parity.draftMatches} 条。仅作人工核对，不自动复制。`" type="info" :closable="false" />
      <el-table :data="parity.legacyRows" border>
        <el-table-column prop="year" label="年份" width="90" />
        <el-table-column prop="title" label="旧公开标题" min-width="180" />
        <el-table-column prop="description" label="旧公开描述" min-width="240" show-overflow-tooltip />
        <el-table-column prop="websiteId" label="官网记录 ID" width="115" />
        <el-table-column prop="matchStatus" label="核对结果" width="130" />
      </el-table>
      <el-alert v-if="parity.unmatchedWebsiteRows.length" :title="`另有 ${parity.unmatchedWebsiteRows.length} 条官网独立记录与旧公开历程不完全一致，请逐条审阅。`" type="warning" :closable="false" />
      <el-table v-if="parity.unmatchedWebsiteRows.length" :data="parity.unmatchedWebsiteRows" border>
        <el-table-column prop="websiteId" label="官网记录 ID" width="115" />
        <el-table-column prop="year" label="年份" width="90" />
        <el-table-column prop="title" label="官网标题" min-width="200" />
        <el-table-column prop="description" label="官网描述" min-width="240" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="110" />
      </el-table>
    </section>
    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="year" label="年份" width="90" />
      <el-table-column prop="title" label="标题" min-width="200" />
      <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button v-if="row.status === 'DRAFT'" text @click="open(row)">编辑</el-button>
          <el-button v-if="row.status === 'DRAFT'" text @click="publish(row)">发布</el-button>
          <el-button text type="danger" @click="archive(row)">归档</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dialog" :title="editingId == null ? '新增官网历程草稿' : '编辑官网历程草稿'" width="600px">
      <el-form label-position="top">
        <el-form-item label="年份"><el-input-number v-model="form.year" :min="1800" :max="2200" /></el-form-item>
        <el-form-item label="标题"><el-input v-model="form.title" maxlength="200" show-word-limit /></el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="5" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dialog = false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存草稿</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.website-company-milestones-page { max-width: 1100px; }
.parity { margin-bottom: 24px; }
</style>
