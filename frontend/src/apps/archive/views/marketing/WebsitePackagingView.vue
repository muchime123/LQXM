<script setup lang="ts">
import { ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { createWebsitePackaging, createWebsitePackagingVariant, fetchWebsitePackagingReadiness, listWebsitePackaging, listWebsitePackagingVariants, publishWebsitePackaging, removeWebsitePackagingVariant, unpublishWebsitePackaging, updateWebsitePackaging, updateWebsitePackagingVariant } from '@/api/website/packaging'
import type { WebsitePackagingCutoverReadiness, WebsitePackagingFields, WebsitePackagingItem, WebsitePackagingVariant, WebsitePackagingVariantFields } from '@/api/website/packaging'
import WebsiteMediaPicker from '@/components/website/WebsiteMediaPicker.vue'
import { notify } from '@/composables/ui/useToast'

const rows = ref<WebsitePackagingItem[]>([])
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const variantOwner = ref<WebsitePackagingItem | null>(null)
const variantRows = ref<WebsitePackagingVariant[]>([])
const variantLoading = ref(false)
const variantDialogVisible = ref(false)
const variantEditingId = ref<number | null>(null)
const variantSaving = ref(false)
const readiness = ref<WebsitePackagingCutoverReadiness | null>(null)
const readinessLoading = ref(false)

async function checkReadiness() {
  readinessLoading.value = true
  try {
    readiness.value = await fetchWebsitePackagingReadiness()
  } catch (error) {
    notify.errorWithDetail('检查官网包材发布内容失败', error)
  } finally {
    readinessLoading.value = false
  }
}

function emptyVariant(): WebsitePackagingVariantFields {
  return { name: '', publicSku: null, dimensions: null, capacityMl: null, description: null, sortOrder: 0 }
}
const variantForm = ref<WebsitePackagingVariantFields>(emptyVariant())

function emptyFields(): WebsitePackagingFields {
  return { slug: '', name: '', packagingKind: null, material: null, shapeType: null,
    capacityMl: null, applicable: null, customizable: false, coverMediaId: null, sortOrder: 0 }
}
const form = ref<WebsitePackagingFields>(emptyFields())

async function load() {
  loading.value = true
  try {
    const result = await listWebsitePackaging(page.value)
    rows.value = result.content
    total.value = result.totalElements
  } catch (error) {
    notify.errorWithDetail('加载官网包材草稿失败', error)
  } finally {
    loading.value = false
  }
}

function create() {
  editingId.value = null
  form.value = emptyFields()
  dialogVisible.value = true
}

function edit(item: WebsitePackagingItem) {
  editingId.value = item.id
  form.value = { slug: item.slug, name: item.name, packagingKind: item.packagingKind,
    material: item.material, shapeType: item.shapeType, capacityMl: item.capacityMl,
    applicable: item.applicable, customizable: item.customizable,
    coverMediaId: item.coverMediaId, sortOrder: item.sortOrder }
  dialogVisible.value = true
}

async function save() {
  if (!form.value.name.trim() || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(form.value.slug)) {
    notify.warning('请填写名称和小写英文 URL 标识')
    return
  }
  saving.value = true
  try {
    const fields = { ...form.value, name: form.value.name.trim(), slug: form.value.slug.trim() }
    if (editingId.value == null) await createWebsitePackaging(fields)
    else await updateWebsitePackaging(editingId.value, fields)
    dialogVisible.value = false
    await load()
    notify.success('官网包材草稿已保存')
  } catch (error) {
    notify.errorWithDetail('保存官网包材草稿失败', error)
  } finally {
    saving.value = false
  }
}

function changePage(next: number) {
  page.value = next - 1
  load()
}

async function publish(item: WebsitePackagingItem) {
  try {
    await ElMessageBox.confirm(`确认发布“${item.name}”？官网包材目录会立即展示这条内容。`, '确认发布', { type: 'warning' })
  } catch { return }
  try {
    await publishWebsitePackaging(item.id)
    await load()
    notify.success('官网包材已发布')
  } catch (error) {
    notify.errorWithDetail('发布官网包材失败', error)
  }
}

async function unpublish(item: WebsitePackagingItem) {
  try {
    await ElMessageBox.confirm(`确认下线“${item.name}”？它会从官网包材目录移除，并可作为草稿继续编辑。`, '确认下线', { type: 'warning' })
  } catch { return }
  try {
    await unpublishWebsitePackaging(item.id)
    await load()
    notify.success('官网包材已下线并转为草稿')
  } catch (error) {
    notify.errorWithDetail('下线官网包材失败', error)
  }
}

async function openVariants(item: WebsitePackagingItem) {
  variantOwner.value = item
  variantLoading.value = true
  try {
    variantRows.value = await listWebsitePackagingVariants(item.id)
  } catch (error) {
    notify.errorWithDetail('加载官网包材规格失败', error)
  } finally {
    variantLoading.value = false
  }
}

function createVariant() {
  variantEditingId.value = null
  variantForm.value = emptyVariant()
  variantDialogVisible.value = true
}

function editVariant(row: WebsitePackagingVariant) {
  variantEditingId.value = row.id
  variantForm.value = { name: row.name, publicSku: row.publicSku, dimensions: row.dimensions,
    capacityMl: row.capacityMl, description: row.description, sortOrder: row.sortOrder }
  variantDialogVisible.value = true
}

async function saveVariant() {
  const owner = variantOwner.value
  if (!owner || !variantForm.value.name.trim()) {
    notify.warning('请填写规格名称')
    return
  }
  variantSaving.value = true
  try {
    const fields = { ...variantForm.value, name: variantForm.value.name.trim() }
    if (variantEditingId.value == null) await createWebsitePackagingVariant(owner.id, fields)
    else await updateWebsitePackagingVariant(owner.id, variantEditingId.value, fields)
    variantDialogVisible.value = false
    await openVariants(owner)
    notify.success('官网包材规格已保存')
  } catch (error) {
    notify.errorWithDetail('保存官网包材规格失败', error)
  } finally {
    variantSaving.value = false
  }
}

async function removeVariant(row: WebsitePackagingVariant) {
  const owner = variantOwner.value
  if (!owner) return
  try {
    await ElMessageBox.confirm(`确认移除规格“${row.name}”？`, '确认移除', { type: 'warning' })
  } catch { return }
  try {
    await removeWebsitePackagingVariant(owner.id, row.id)
    await openVariants(owner)
    notify.success('官网包材规格已移除')
  } catch (error) {
    notify.errorWithDetail('移除官网包材规格失败', error)
  }
}

await load()
</script>

<template>
  <div class="website-packaging-page">
    <header class="page-header">
      <div>
        <p class="page-header__eyebrow">官网运营</p>
        <h1 class="page-header__title">官网包材草稿</h1>
        <p class="page-header__desc">独立维护对外展示内容。已发布系列直接进入官网包材目录。</p>
      </div>
      <div class="page-header__actions">
        <el-button :loading="readinessLoading" @click="checkReadiness">检查发布内容</el-button>
        <el-button :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" @click="create">新建草稿</el-button>
      </div>
    </header>

    <section v-if="readiness" class="website-packaging-page__readiness">
      <el-alert :type="readiness.technicallyReady ? 'success' : 'warning'" :closable="false"
        :title="readiness.technicallyReady ? '已发布包材内容完整' : '当前没有完整的已发布包材系列'" />
      <p>已发布系列 {{ readiness.publishedItems }} / 全部系列 {{ readiness.totalItems }}；已发布系列下的规格 {{ readiness.publishedVariants }} 条。</p>
      <ul v-if="readiness.blockers.length"><li v-for="reason in readiness.blockers" :key="reason">{{ reason }}</li></ul>
      <ul v-if="readiness.itemIssues.length">
        <li v-for="issue in readiness.itemIssues" :key="issue.id">{{ issue.name || `#${issue.id}` }}：{{ issue.reasons.join('、') }}</li>
      </ul>
    </section>

    <el-table v-loading="loading" :data="rows" empty-text="暂无官网包材草稿">
      <el-table-column prop="name" label="公开名称" min-width="200" />
      <el-table-column prop="slug" label="URL 标识" min-width="160" />
      <el-table-column prop="packagingKind" label="类别" width="140" />
      <el-table-column prop="status" label="状态" width="110" />
      <el-table-column prop="sortOrder" label="排序" width="90" />
      <el-table-column label="操作" width="240">
        <template #default="{ row }">
          <el-button link @click="openVariants(row)">规格</el-button>
          <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="edit(row)">编辑</el-button>
          <el-button v-if="row.status === 'DRAFT'" link type="success" @click="publish(row)">发布</el-button>
          <el-button v-if="row.status === 'PUBLISHED'" link type="warning" @click="unpublish(row)">下线</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination :current-page="page + 1" :page-size="20" :total="total" layout="total, prev, pager, next" class="website-packaging-page__pagination" @current-change="changePage" />

    <el-dialog v-model="dialogVisible" :title="editingId == null ? '新建官网包材草稿' : '编辑官网包材草稿'" width="min(680px, 92vw)" :close-on-click-modal="false">
      <el-form :model="form" label-width="110px">
        <el-form-item label="公开名称" required><el-input v-model="form.name" maxlength="300" /></el-form-item>
        <el-form-item label="URL 标识" required><el-input v-model="form.slug" maxlength="160" placeholder="例如 food-tray" /></el-form-item>
        <el-form-item label="类别"><el-input v-model="form.packagingKind" maxlength="80" /></el-form-item>
        <el-form-item label="材质"><el-input v-model="form.material" maxlength="200" /></el-form-item>
        <el-form-item label="形状"><el-input v-model="form.shapeType" maxlength="120" /></el-form-item>
        <el-form-item label="容量"><el-input v-model="form.capacityMl" maxlength="100" /></el-form-item>
        <el-form-item label="适用场景"><el-input v-model="form.applicable" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="支持定制"><el-switch v-model="form.customizable" /></el-form-item>
        <el-form-item label="封面图片"><WebsiteMediaPicker v-model="form.coverMediaId" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortOrder" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog :model-value="variantOwner != null" :title="`${variantOwner?.name || ''} · 官网规格`" width="min(800px, 94vw)" @close="variantOwner = null">
      <div v-if="variantOwner?.status === 'DRAFT'" class="website-packaging-page__variant-actions">
        <el-button type="primary" @click="createVariant">新增规格</el-button>
      </div>
      <el-table v-loading="variantLoading" :data="variantRows" empty-text="暂无官网规格">
        <el-table-column prop="name" label="公开规格名称" min-width="170" />
        <el-table-column prop="publicSku" label="公开编号" width="130" />
        <el-table-column prop="dimensions" label="尺寸" min-width="140" />
        <el-table-column prop="capacityMl" label="容量" width="100" />
        <el-table-column v-if="variantOwner?.status === 'DRAFT'" label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="editVariant(row)">编辑</el-button>
            <el-button link type="danger" @click="removeVariant(row)">移除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="variantDialogVisible" :title="variantEditingId == null ? '新增官网包材规格' : '编辑官网包材规格'" width="min(600px, 92vw)" :close-on-click-modal="false">
      <el-form :model="variantForm" label-width="100px">
        <el-form-item label="规格名称" required><el-input v-model="variantForm.name" maxlength="300" /></el-form-item>
        <el-form-item label="公开编号"><el-input v-model="variantForm.publicSku" maxlength="160" /></el-form-item>
        <el-form-item label="尺寸"><el-input v-model="variantForm.dimensions" maxlength="300" /></el-form-item>
        <el-form-item label="容量"><el-input v-model="variantForm.capacityMl" maxlength="100" /></el-form-item>
        <el-form-item label="公开说明"><el-input v-model="variantForm.description" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="variantForm.sortOrder" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="variantDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="variantSaving" @click="saveVariant">保存规格</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.website-packaging-page__pagination { margin-top: 20px; justify-content: flex-end; }
.website-packaging-page__readiness { margin-bottom: 20px; padding: 16px; border: 1px solid var(--el-border-color); border-radius: 8px; }
.website-packaging-page__readiness p { margin: 10px 0; }
.website-packaging-page__variant-actions { margin-bottom: 12px; }
</style>
