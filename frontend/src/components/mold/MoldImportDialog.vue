<template>
  <LqDialog
    v-model:model-value="visible"
    :title="!preview ? '上传 Excel' : '导入预览'"
    :subtitle="!preview ? '选择包材系列后上传 Excel，预览后再落库。' : buildSubtitle()"
    :width="preview ? 'min(1600px, 94vw)' : 'min(720px, 92vw)'"
    mode="workspace"
    class="mold-import-dialog"
    :loading="uploading || confirming"
    :before-close="handleBeforeClose"
    :confirm-text="preview ? (showOnlyUnverified ? '只落库正常行' : '确认入库') : '开始解析'"
    :cancel-text="preview ? '取消' : '关闭'"
    @confirm="handleConfirm"
    @cancel="visible = false"
  >
    <!-- header actions slot: tabs + filters -->
    <template #actions v-if="preview">
      <div class="preview-header-actions">
        <el-tabs v-model="activeDisposition" class="preview-tabs" type="card" size="small">
          <el-tab-pane name="all">
            <template #label>
              <span>全部</span>
              <el-tag size="small" class="count-tag">{{ preview.rows.length }}</el-tag>
            </template>
          </el-tab-pane>
          <el-tab-pane name="new">
            <template #label>
              <span class="lbl-new">新增</span>
              <el-tag size="small" type="success" class="count-tag">{{ preview.newCount }}</el-tag>
            </template>
          </el-tab-pane>
          <el-tab-pane name="merge">
            <template #label>
              <span>合并</span>
              <el-tag size="small" type="warning" effect="plain" class="count-tag">{{ preview.mergeCount }}</el-tag>
            </template>
          </el-tab-pane>
          <el-tab-pane name="unverified">
            <template #label>
              <span>待核实</span>
              <el-tag size="small" type="danger" effect="light" class="count-tag">{{ preview.unverifiedCount }}</el-tag>
            </template>
          </el-tab-pane>
        </el-tabs>
        <div class="toolbar-actions">
          <el-checkbox v-model="showOnlyUnverified" size="small">跳过待核实</el-checkbox>
          <el-button link type="primary" size="small" @click="selectAllRows">全选</el-button>
          <el-button link type="primary" size="small" @click="deselectAllRows">取消全选</el-button>
        </div>
      </div>
    </template>

    <!-- 上传阶段 -->
    <div v-if="!preview" class="upload-stage">
      <!-- 选择包材系列 -->
      <div class="model-selector-section">
        <div class="selector-label">
          <el-icon class="selector-icon"><Link /></el-icon>
          <span>目标包材大系列</span>
          <span class="selector-hint">选择「全系列自动匹配」可根据模具尺寸自动匹配系列</span>
        </div>
        <el-select
          v-model="selectedModelId"
          placeholder="选择包材大系列（如：方盒系列、圆杯系列）"
          filterable
          size="large"
          style="width: 100%"
          :loading="loadingModels"
        >
          <el-option label="全系列自动匹配" :value="0" />
          <el-option
            v-for="m in packagingModels"
            :key="m.id"
            :label="m.name + (m.packagingKind ? ` (${m.packagingKind})` : '')"
            :value="m.id"
          />
        </el-select>
      </div>

      <!-- 上传区域 -->
      <el-upload
        class="mold-uploader"
        drag
        :auto-upload="false"
        :show-file-list="true"
        :limit="1"
        accept=".xlsx,.xls"
        :on-change="onFileChange"
        :on-remove="onFileRemove"
      >
        <el-icon class="uploader-icon"><upload-filled /></el-icon>
        <div class="el-upload__text">
          拖拽或点击选择 Excel 文件
        </div>
        <template #tip>
          <div class="el-upload__tip">
            支持 .xlsx / .xls 格式。模号列、高度列、容量列、配件列会被自动识别。
          </div>
        </template>
      </el-upload>
    </div>

    <!-- 预览阶段（抽离至 MoldImportPreviewTable） -->
    <MoldImportPreviewTable
      v-else-if="preview"
      :preview="preview"
      :filtered-rows="filteredRows"
      :packaging-models="packagingModels"
      :variant-overrides="variantOverrides"
      :model-overrides="modelOverrides"
      v-model:warnings-collapsed="warningsCollapsed"
      @override-variant="onOverrideVariant"
      @override-model="onOverrideModel"
      @selection-change="onSelectionChange"
      @select-mold="onSelectMold"
    />
  </LqDialog>
</template>

<script setup lang="ts">
import { ref, computed, watch, reactive, onMounted, onBeforeUnmount } from 'vue'
import { UploadFilled, Link } from '@element-plus/icons-vue'
import {
  type ImportPreview,
  type PackagingModelSummary,
  previewPackagingMoldImport,
  confirmPackagingMoldImport,
  fetchPackagingModels,
  type ImportConfirmRequest,
  type ImportConfirmResult
} from '@/api'
import { askConfirm } from '@/composables'
import { ElMessage } from 'element-plus'
import LqDialog from '@/components/common/LqDialog.vue'
import MoldImportPreviewTable from './MoldImportPreviewTable.vue'

const props = withDefaults(defineProps<{
  visible: boolean
  /** 打开时预选的目标包材系列 ID，用于从系列详情页进入时跳过系列选择 */
  defaultModelId?: number
}>(), {
  defaultModelId: undefined
})
const emit = defineEmits<{
  'update:visible': [v: boolean]
  confirmed: [result: ImportConfirmResult]
}>()

const visible = ref(props.visible)
watch(() => props.visible, v => (visible.value = v))
watch(visible, v => emit('update:visible', v))

const uploading = ref(false)
const confirming = ref(false)
const currentFile = ref<File | null>(null)
const preview = ref<ImportPreview | null>(null)
const activeDisposition = ref('all')
const showOnlyUnverified = ref(true)
const selectedRowIndexes = ref<number[]>([])
const warningsCollapsed = ref(false)

const packagingModels = ref<PackagingModelSummary[]>([])
const loadingModels = ref(false)
const selectedModelId = ref<number | undefined>(props.defaultModelId)

// 打开弹窗时，若外部传入了 defaultModelId，则自动选中该系列
watch(
  () => props.visible,
  v => {
    if (v && props.defaultModelId !== undefined) {
      selectedModelId.value = props.defaultModelId
    }
  }
)

// 用户手动覆盖的 variantId（rowIndex → variantId）
const variantOverrides = reactive<Record<number, number | null>>({})
// 用户手动覆盖的 modelId（rowIndex → modelId）
const modelOverrides = reactive<Record<number, number>>({})

async function loadPackagingModels() {
  loadingModels.value = true
  try {
    const res = await fetchPackagingModels({ size: 500 })
    packagingModels.value = Array.isArray(res) ? res : (res?.content || [])
  } catch { /* ignore */ } finally {
    loadingModels.value = false
  }
}

const filteredRows = computed(() => {
  if (!preview.value) return []
  if (activeDisposition.value === 'all') return preview.value.rows
  return preview.value.rows.filter(r => r.disposition === activeDisposition.value)
})

function buildSubtitle() {
  if (!preview.value) return ''
  const p = preview.value
  return `共 ${p.totalRows} 行，展开为 ${p.expandedRows} 条模具记录，请核对后确认入库。`
}

function onOverrideVariant(globalIdx: number, variantId: number | null) {
  variantOverrides[globalIdx] = variantId
}

function onOverrideModel(globalIdx: number, modelId: number) {
  const rows = preview.value?.rows
  const sourceRow = rows?.[globalIdx]
  if (!rows || !sourceRow) return
  // 系列归属属于模具级属性：同一模号的所有高度展开行必须同步切换。
  rows.forEach((row, index) => {
    if (row.moldCode !== sourceRow.moldCode) return
    modelOverrides[index] = modelId
    const candidates = row.matchCandidates?.filter(candidate => candidate.modelId === modelId) || []
    variantOverrides[index] = candidates.length > 0 ? candidates[0].variantId : null
  })
}

function effectiveModelId(index: number) {
  const row = preview.value?.rows[index]
  return modelOverrides[index] ?? preview.value?.modelId ?? row?.matchedModelId
}

function effectiveCandidates(index: number) {
  const row = preview.value?.rows[index]
  const modelId = effectiveModelId(index)
  return row?.matchCandidates?.filter(candidate => modelId == null || candidate.modelId === modelId) || []
}

function effectiveVariantId(index: number) {
  if (Object.prototype.hasOwnProperty.call(variantOverrides, index)) return variantOverrides[index]
  return preview.value?.rows[index]?.selectedVariantId
}

async function onFileChange(uploadFile: any) {
  if (!uploadFile.raw) return
  currentFile.value = uploadFile.raw
  await doUpload()
}

function onFileRemove() {
  currentFile.value = null
  preview.value = null
  warningsCollapsed.value = false
  resetOverrides()
}

function resetOverrides() {
  Object.keys(variantOverrides).forEach(k => delete variantOverrides[Number(k)])
  Object.keys(modelOverrides).forEach(k => delete modelOverrides[Number(k)])
}

async function doUpload() {
  if (!currentFile.value) return
  if (selectedModelId.value === undefined) {
    ElMessage.warning('请先选择包材大系列或全系列自动匹配')
    return
  }
  try {
    uploading.value = true
    // 0 = 全系列自动匹配，传 undefined 给 API
    const apiModelId = selectedModelId.value === 0 ? undefined : selectedModelId.value
    preview.value = await previewPackagingMoldImport(currentFile.value, apiModelId)
    warningsCollapsed.value = preview.value.errors.length > 8
    // 默认勾选：new + merge
    selectedRowIndexes.value = preview.value.rows
      .map((r, i) => ({ r, i }))
      .filter(x => x.r.disposition === 'new' || x.r.disposition === 'merge')
      .map(x => x.i)
    resetOverrides()
    ElMessage.success(`解析完成，${preview.value.rows.length} 条预览`)
  } catch (e: any) {
    ElMessage.error(e?.message || '解析失败')
    preview.value = null
  } finally {
    uploading.value = false
  }
}

function onSelectionChange(globalIndexes: number[]) {
  selectedRowIndexes.value = globalIndexes
}

function onSelectMold(globalIndexes: number[]) {
  selectedRowIndexes.value = [...new Set([...selectedRowIndexes.value, ...globalIndexes])]
  ElMessage.success(`已整组选中 ${globalIndexes.length} 条高度记录`)
}

function selectAllRows() {
  if (!preview.value) return
  if (activeDisposition.value === 'all') {
    selectedRowIndexes.value = preview.value.rows.map((_, i) => i)
  } else {
    const idxs: number[] = []
    preview.value.rows.forEach((r, i) => {
      if (r.disposition === activeDisposition.value) idxs.push(i)
    })
    selectedRowIndexes.value = idxs
  }
}

function deselectAllRows() {
  selectedRowIndexes.value = []
}

async function handleConfirm() {
  if (!preview.value) {
    await doUpload()
    return
  }
  let rowIndexes = selectedRowIndexes.value
  if (showOnlyUnverified.value) {
    rowIndexes = rowIndexes.filter(i => {
      const r = preview.value?.rows[i]
      return r && (r.disposition === 'new' || r.disposition === 'merge')
    })
  }
  if (rowIndexes.length === 0) {
    ElMessage.warning('没有可落库的行')
    return
  }
  const unresolvedMulti = rowIndexes.filter(index => {
    const candidates = effectiveCandidates(index)
    const selected = effectiveVariantId(index)
    return candidates.length > 1 && !candidates.some(candidate => candidate.variantId === selected)
  }).length
  const withoutVariant = rowIndexes.filter(index => {
    const candidates = effectiveCandidates(index)
    const selected = effectiveVariantId(index)
    return !candidates.some(candidate => candidate.variantId === selected)
  }).length
  const modelsByMold = new Map<string, Set<number | undefined>>()
  rowIndexes.forEach(index => {
    const code = preview.value?.rows[index]?.moldCode || `#${index}`
    const ids = modelsByMold.get(code) || new Set<number | undefined>()
    ids.add(effectiveModelId(index))
    modelsByMold.set(code, ids)
  })
  if ([...modelsByMold.values()].some(ids => ids.size !== 1 || ids.has(undefined))) {
    ElMessage.error('同一模号存在不同系列或缺少系列，请先修正后再入库')
    return
  }
  if (withoutVariant > 0) {
    const ok = await askConfirm({
      title: '部分模具不会关联规格',
      message: `所选记录中有 ${withoutVariant} 条未确认规格，其中 ${unresolvedMulti} 条仍有多个候选。继续后只导入模具并跳过这些规格关联。`,
      confirmText: '继续导入',
      cancelText: '返回修正'
    })
    if (ok !== true) return
  }
  try {
    confirming.value = true
    const req: ImportConfirmRequest = {
      preview: preview.value,
      rowIndexes,
      importBatchId: undefined,
      variantOverrides: { ...variantOverrides },
      modelOverrides: { ...modelOverrides }
    }
    const result = await confirmPackagingMoldImport(req)
    const parts = [`新增模具 ${result.createdMolds}`, `合并 ${result.mergedMolds}`]
    if (result.createdGroups) parts.push(`规格族 ${result.createdGroups}`)
    if (result.createdVariantLinks) parts.push(`规格关联 ${result.createdVariantLinks}`)
    parts.push(`配件 ${result.createdAccessories}`)
    ElMessage.success(`入库完成：${parts.join(' / ')}`)
    if (result.issues?.length) {
      result.issues.forEach(issue => ElMessage.warning(issue))
    }
    emit('confirmed', result)
    visible.value = false
    // 重置
    currentFile.value = null
    preview.value = null
    resetOverrides()
  } catch (e: any) {
    ElMessage.error(e?.message || '入库失败')
  } finally {
    confirming.value = false
  }
}

async function handleBeforeClose(): Promise<boolean> {
  if (uploading.value || confirming.value) return false
  if (preview.value) {
    const ok = await askConfirm({
      title: '关闭导入预览？',
      message: '当前已解析的预览结果将丢失，确定关闭？',
      confirmText: '关闭',
      cancelText: '继续',
      danger: true
    })
    const confirmed = ok === true
    if (confirmed) {
      preview.value = null
      currentFile.value = null
      resetOverrides()
    }
    return confirmed
  }
  return true
}

// 接收页面广播的 file（用于 toolbar 快捷上传）
function onImportFileReady(e: any) {
  if (!visible.value) return
  const file = (e as CustomEvent<File>).detail
  if (file instanceof File) {
    currentFile.value = file
    if (selectedModelId.value !== undefined) {
      doUpload()
    } else {
      ElMessage.info('请先选择包材系列或全系列自动匹配')
    }
  }
}

onMounted(() => {
  loadPackagingModels()
  window.addEventListener('mold-import-file-ready', onImportFileReady)
})

onBeforeUnmount(() => {
  window.removeEventListener('mold-import-file-ready', onImportFileReady)
})
</script>

<style>
.lq-dialog.mold-import-dialog .el-dialog__body {
  padding: 10px 14px;
}
</style>

<style scoped>
.upload-stage {
  padding: var(--space-section-gap, 24px) 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-m, 12px);
}

.model-selector-section {
  background: var(--color-bg-elevated, #f9fafb);
  border: 1px solid var(--color-border-subtle, #e5e7eb);
  border-radius: var(--radius-card, 10px);
  padding: var(--space-m, 12px);
}

.selector-label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 13px;
  margin-bottom: var(--space-s, 8px);
}

.selector-icon {
  color: var(--color-primary, #2563eb);
}

.selector-hint {
  font-weight: normal;
  font-size: 12px;
  color: var(--color-text-tertiary, #9ca3af);
}

.mold-uploader :deep(.el-upload-dragger) {
  padding: 48px 24px;
  border-style: dashed;
}

.uploader-icon {
  font-size: 48px;
  color: var(--color-primary, #2563eb);
}

.el-upload__text em {
  color: var(--color-primary, #2563eb);
  font-style: normal;
}

.preview-header-actions {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap, 8px);
  flex-wrap: wrap;
  flex: 1;
  min-width: 0;
}

.preview-tabs {
  flex: 1;
  min-width: 0;
  margin: 0;
}

.count-tag {
  margin-left: var(--space-xs, 4px);
  transform: scale(0.92);
  transform-origin: left center;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap, 8px);
  flex-wrap: wrap;
  padding-top: 4px;
}

.lbl-new {
  color: var(--color-success-700, #047857);
  font-weight: 600;
}
</style>
