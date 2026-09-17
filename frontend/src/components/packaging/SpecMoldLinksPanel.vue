<template>
  <section class="spec-mold-links-panel">
    <header class="sml-header">
      <h4>适配模具</h4>
      <el-button v-if="!loading" link type="primary" size="small" @click="showAddDialog = true">
        + 关联模具
      </el-button>
    </header>

    <div v-if="loading" class="sml-loading">
      <el-skeleton :rows="2" animated />
    </div>

    <template v-else>
      <div v-if="molds.length === 0" class="sml-empty">
        <span class="muted">暂无关联模具，点击「关联模具」添加</span>
      </div>

      <div v-else class="sml-list">
        <div
          v-for="mold in molds"
          :key="mold.linkId"
          class="sml-item"
          :class="{ default: mold.isDefault }"
        >
          <div class="sml-item-main">
            <span class="sml-mold-code">{{ mold.moldCode || `#${mold.moldId}` }}</span>
            <el-tag v-if="mold.isDefault" size="small" type="success">默认</el-tag>
            <el-tag size="small" :type="relationTagType(mold.relationType)" effect="light">
              {{ relationLabel(mold.relationType) }}
            </el-tag>
            <el-tag v-if="mold.moldTypeCode" size="small" type="info" effect="plain">
              {{ mold.moldTypeCode }}
            </el-tag>
            <span v-if="mold.cavityCount" class="sml-cavity">{{ mold.cavityCount }}出</span>
          </div>
          <div class="sml-item-actions">
            <el-button
              v-if="!mold.isDefault"
              link
              type="success"
              size="small"
              @click="onSetDefault(mold)"
            >设为默认</el-button>
            <el-popconfirm title="确定解除关联？" @confirm="onRemove(mold)">
              <template #reference>
                <el-button link type="danger" size="small">解除</el-button>
              </template>
            </el-popconfirm>
          </div>
        </div>
      </div>
    </template>

    <!-- 关联模具对话框 -->
    <LqDialog
      v-model:model-value="showAddDialog"
      title="关联模具到规格"
      subtitle="从当前系列下选择模具绑定到该规格"
      size="sm"
      mode="form"
      :dirty="addFormDirty"
      :loading="submitting"
      confirm-text="确认关联"
      cancel-text="取消"
      @confirm="onAdd"
      @cancel="showAddDialog = false"
    >
      <div class="sml-add-form">
        <div class="sml-form-row">
          <label class="sml-form-label">选择模具</label>
          <el-select
            v-model="addForm.moldId"
            placeholder="搜索模号（推荐项已置顶）"
            filterable
            remote
            :remote-method="searchMolds"
            :loading="searchingMolds"
            style="width: 100%"
            @change="onFormChange"
          >
            <el-option
              v-for="m in moldCandidates"
              :key="m.id"
              :value="m.id"
            >
              <div class="sml-option">
                <span class="sml-option-code">{{ m.moldCode }}</span>
                <span v-if="m.moldTypeCode" class="sml-option-meta">{{ m.moldTypeCode }}</span>
                <el-tag
                  v-if="recommendBadge(m)"
                  :type="recommendBadge(m) === '⭐推荐' ? 'success' : recommendBadge(m) === '✓形状匹配' ? 'warning' : 'danger'"
                  effect="light"
                  size="small"
                >{{ recommendBadge(m) }}</el-tag>
                <span v-if="m.status === 'stopped'" class="sml-option-stopped">[停用]</span>
              </div>
            </el-option>
          </el-select>
        </div>
        <div class="sml-form-row">
          <label class="sml-form-label">关系类型</label>
          <el-select v-model="addForm.relationType" style="width: 100%" @change="onFormChange">
            <el-option label="主模" value="main" />
            <el-option label="备用" value="backup" />
            <el-option label="配套" value="paired" />
          </el-select>
        </div>
        <div class="sml-form-row">
          <el-checkbox v-model="addForm.isDefault" @change="onFormChange">设为默认模具</el-checkbox>
        </div>
      </div>
    </LqDialog>
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  fetchMoldsByVariant,
  fetchPackagingMolds,
  createMoldVariantLink,
  updateMoldVariantLink,
  deleteMoldVariantLink,
  type MoldByVariantView,
  type PackagingMoldSummary
} from '@/api'
import { scoreMoldForVariant, moldRecommendBadge } from '@/composables/mold/shapeMatch'
import LqDialog from '@/components/common/LqDialog.vue'

const props = defineProps<{
  variantId: number
  modelId: number
  variantShapeType?: string
  variantCapacity?: string
}>()

const emit = defineEmits<{
  'changed': []
}>()

const RELATION_OPTIONS: Record<string, { label: string; tag: 'primary' | 'success' | 'info' | 'warning' }> = {
  main: { label: '主模', tag: 'success' },
  backup: { label: '备用', tag: 'info' },
  paired: { label: '配套', tag: 'primary' }
}

function relationLabel(r?: string) {
  return RELATION_OPTIONS[r || '']?.label || r || '—'
}

function relationTagType(r?: string): 'primary' | 'success' | 'info' | 'warning' {
  return RELATION_OPTIONS[r || '']?.tag || 'info'
}

/** 推荐评分：形状一致+40， in_use+20， 主模+10， 类型码+5 */
function scoreMold(m: PackagingMoldSummary): number {
  return scoreMoldForVariant(m, { shapeType: props.variantShapeType })
}

function recommendBadge(m: PackagingMoldSummary): string | null {
  return moldRecommendBadge(m, { shapeType: props.variantShapeType })
}

function sortCandidates(list: PackagingMoldSummary[]): PackagingMoldSummary[] {
  return [...list]
    .filter(m => scoreMold(m) !== -1) // 形状硬过滤：scoreMoldForVariant 返回 -1 表示形状不匹配
    .sort((a, b) => scoreMold(b) - scoreMold(a))
}

// ====== 数据加载 ======

const loading = ref(false)
const molds = ref<MoldByVariantView[]>([])

async function loadMolds() {
  if (!props.variantId) {
    molds.value = []
    return
  }
  loading.value = true
  try {
    molds.value = await fetchMoldsByVariant(props.variantId)
  } catch {
    molds.value = []
  } finally {
    loading.value = false
  }
}

watch(() => props.variantId, () => loadMolds(), { immediate: true })

// ====== 设为默认 ======

async function onSetDefault(mold: MoldByVariantView) {
  try {
    await updateMoldVariantLink(mold.linkId, {
      moldId: mold.moldId!,
      variantId: props.variantId,
      relationType: mold.relationType,
      isDefault: true,
      remark: undefined
    })
    ElMessage.success(`已将 ${mold.moldCode} 设为默认模具`)
    await loadMolds()
    emit('changed')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '设置默认失败')
  }
}

// ====== 解除关联 ======

async function onRemove(mold: MoldByVariantView) {
  try {
    await deleteMoldVariantLink(mold.linkId)
    ElMessage.success(`已解除 ${mold.moldCode} 的关联`)
    await loadMolds()
    emit('changed')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '解除关联失败')
  }
}

// ====== 关联模具对话框 ======

const showAddDialog = ref(false)
const submitting = ref(false)
const searchingMolds = ref(false)
const addFormDirty = ref(false)
const moldCandidates = ref<PackagingMoldSummary[]>([])
const addForm = ref({
  moldId: undefined as number | undefined,
  relationType: 'main',
  isDefault: false
})

function onFormChange() {
  addFormDirty.value = true
}

async function searchMolds(keyword: string) {
  if (!keyword?.trim()) {
    // 无搜索词时加载当前系列下所有模具
    moldCandidates.value = sortCandidates(await loadModelMolds())
    return
  }
  searchingMolds.value = true
  try {
    const res = await fetchPackagingMolds({ keyword, modelId: props.modelId, size: 50 })
    const all = (res.content || []) as PackagingMoldSummary[]
    // 排除已关联的模具
    const linkedIds = new Set(molds.value.map(m => m.moldId))
    moldCandidates.value = sortCandidates(all.filter(m => !linkedIds.has(m.id)))
  } finally {
    searchingMolds.value = false
  }
}

async function loadModelMolds(): Promise<PackagingMoldSummary[]> {
  const res = await fetchPackagingMolds({ modelId: props.modelId, size: 100 })
  const all = (res.content || []) as PackagingMoldSummary[]
  const linkedIds = new Set(molds.value.map(m => m.moldId))
  return all.filter(m => !linkedIds.has(m.id))
}

watch(showAddDialog, async (v) => {
  if (v) {
    addForm.value = { moldId: undefined, relationType: 'main', isDefault: molds.value.length === 0 }
    moldCandidates.value = sortCandidates(await loadModelMolds())
  }
})

async function onAdd() {
  if (!addForm.value.moldId) {
    ElMessage.warning('请选择要关联的模具')
    return
  }
  submitting.value = true
  try {
    await createMoldVariantLink({
      moldId: addForm.value.moldId,
      variantId: props.variantId,
      relationType: addForm.value.relationType,
      isDefault: addForm.value.isDefault,
      remark: undefined
    })
    ElMessage.success('关联成功')
    showAddDialog.value = false
    await loadMolds()
    emit('changed')
  } catch (e: any) {
    ElMessage.error(e?.response?.data?.message || '关联失败')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.spec-mold-links-panel {
  padding: var(--space-s, 8px) 0;
}

.sml-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-s, 8px);
}

.sml-header h4 {
  font-size: var(--fs-sm, 13px);
  font-weight: var(--fw-bold, 700);
  color: var(--color-text-heading, #0f172a);
  margin: 0;
}

.sml-loading {
  padding: var(--space-s, 8px) 0;
}

.sml-empty {
  padding: var(--space-m, 12px) 0;
  text-align: center;
}

.muted {
  color: var(--color-text-tertiary, #9ca3af);
  font-size: 12px;
}

.sml-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sml-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-s, 8px);
  padding: 6px var(--space-s, 8px);
  border-radius: var(--radius-sm, 6px);
  background: var(--color-surface-alt, #f8fafc);
  border: 1px solid var(--color-border-subtle, #e5e7eb);
}

.sml-item.default {
  background: var(--color-success-bg-soft, #f0fdf4);
  border-color: var(--color-success-border, #bbf7d0);
}

.sml-item-main {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  min-width: 0;
}

.sml-mold-code {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-weight: 600;
  font-size: 13px;
  color: var(--color-text-primary, #111827);
}

.sml-cavity {
  font-size: 12px;
  color: var(--color-text-secondary, #4b5563);
}

.sml-item-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.sml-add-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-m, 12px);
}

.sml-form-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.sml-form-label {
  font-size: var(--fs-xs, 12px);
  font-weight: 600;
  color: var(--color-text-secondary, #475569);
}

.sml-option {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: 100%;
}

.sml-option-code {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
  font-weight: 600;
  font-size: 13px;
  color: var(--color-text-primary, #111827);
}

.sml-option-meta {
  font-size: 12px;
  color: var(--color-text-tertiary, #64748b);
}

.sml-option-stopped {
  margin-left: auto;
  font-size: 11px;
  color: var(--color-danger-500, #dc2626);
}
</style>
