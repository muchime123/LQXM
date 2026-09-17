<template>
  <LqDialog
    :model-value="visible"
    title="机型档位管理"
    subtitle="按机器物理尺寸划分机型档位（如 330 / 390 / 500），维护设备尺寸、重量、包装尺寸与可适配口径范围。"
    width="min(1080px, 94vw)"
    mode="workspace"
    :scrollable="false"
    :z-index="UI_LAYER.nestedModal"
    :close-on-click-modal="false"
    @close="handleClose"
  >
    <div class="tier-manager-body">
      <div class="tier-layout">
        <!-- 内联表单：新增 / 编辑共用 -->
        <FormSection
          class="tier-form-panel"
          :title="editingId ? '编辑机型档位' : '新增机型档位'"
          description="输入机型编码与名称，并配置圆形 / 方形口径范围边界。"
        >
          <el-form label-position="top" @submit.prevent>
            <div class="tier-form-grid">
              <el-form-item label="机型编码" required>
                <el-input v-model.trim="form.tierCode" placeholder="如 330" maxlength="50" clearable />
              </el-form-item>
              <el-form-item label="机型名称">
                <el-input v-model.trim="form.tierLabel" placeholder="如 330机型" maxlength="100" clearable />
              </el-form-item>
            </div>

            <el-form-item label="圆形口径范围（mm）">
              <div class="tier-range-inputs">
                <el-input v-model.trim="form.roundDiameterMin" inputmode="decimal" placeholder="下限" />
                <span class="tier-range-sep">~</span>
                <el-input v-model.trim="form.roundDiameterMax" inputmode="decimal" placeholder="上限" />
              </div>
            </el-form-item>
            <el-form-item label="方形长边范围（mm）">
              <div class="tier-range-inputs">
                <el-input v-model.trim="form.rectLengthMin" inputmode="decimal" placeholder="下限" />
                <span class="tier-range-sep">~</span>
                <el-input v-model.trim="form.rectLengthMax" inputmode="decimal" placeholder="上限" />
              </div>
            </el-form-item>
            <el-form-item label="方形短边范围（mm）">
              <div class="tier-range-inputs">
                <el-input v-model.trim="form.rectWidthMin" inputmode="decimal" placeholder="下限" />
                <span class="tier-range-sep">~</span>
                <el-input v-model.trim="form.rectWidthMax" inputmode="decimal" placeholder="上限" />
              </div>
            </el-form-item>

            <el-divider content-position="left">尺寸与重量</el-divider>

            <div class="tier-form-grid">
              <el-form-item label="机器尺寸">
                <el-input v-model.trim="form.machineSize" placeholder="如 420×420×700mm" maxlength="100" clearable />
              </el-form-item>
              <el-form-item label="净重(kg)">
                <el-input v-model.trim="form.netWeightKg" placeholder="如 85" maxlength="100" clearable />
              </el-form-item>
              <el-form-item label="纸箱包装尺寸">
                <el-input v-model.trim="form.cartonSize" placeholder="如 500×500×800mm" maxlength="100" clearable />
              </el-form-item>
              <el-form-item label="木箱包装尺寸">
                <el-input v-model.trim="form.woodBoxSize" placeholder="如 520×520×820mm" maxlength="100" clearable />
              </el-form-item>
              <el-form-item label="毛重(kg)">
                <el-input v-model.trim="form.grossWeightKg" placeholder="如 95" maxlength="100" clearable />
              </el-form-item>
            </div>

            <el-alert
              class="tier-rule-help"
              type="info"
              :closable="false"
              show-icon
              title="范围表示机型能力边界；常规尺寸请在该机型下建立精确规格并标为基准规格。例如 500 机型的常规 370×270，应建立「500机型｜方模具｜370×270」规格。"
            />

            <el-form-item label="备注">
              <el-input
                v-model.trim="form.remark"
                placeholder="可选，如 330机型适用圆形≤200、方形≤221×168"
                maxlength="200"
                clearable
              />
            </el-form-item>

            <el-alert
              v-if="formMessage"
              class="tier-form-message"
              type="error"
              :closable="false"
              show-icon
              :title="formMessage"
            />

            <div class="tier-form-actions">
              <el-button v-if="editingId" @click="resetForm">取消编辑</el-button>
              <el-button type="primary" :loading="saving" @click="handleSave">
                {{ editingId ? '保存修改' : '新增机型' }}
              </el-button>
            </div>
          </el-form>
        </FormSection>

        <!-- 已有档位列表 -->
        <FormSection
          class="tier-list-panel"
          :title="`已有机型档位（${tiers.length}）`"
          description="机型档位用于素材归属和适配包材校验，不再派生模具或出数规格。"
        >
          <el-skeleton v-if="loading" :rows="3" animated />
          <el-empty v-else-if="tiers.length === 0" description="暂无机型档位，请在上方新增。" :image-size="72" />
          <ul v-else class="tier-list">
            <li v-for="tier in tiers" :key="tier.id" class="tier-item">
              <div class="tier-item__main">
                <div class="tier-item__head">
                  <code class="tier-item__code">{{ tier.tierCode }}</code>
                  <strong class="tier-item__name">{{ tier.tierLabel || tier.tierCode }}</strong>
                </div>
                <div class="tier-item__ranges">
                  <span v-if="hasRange(tier.roundDiameterMin, tier.roundDiameterMax)" class="tier-range-chip">
                    <span class="tier-range-chip__label">圆形</span>
                    {{ rangeLabel(tier.roundDiameterMin, tier.roundDiameterMax) }}
                  </span>
                  <span
                    v-if="
                      hasRange(tier.rectLengthMin, tier.rectLengthMax) || hasRange(tier.rectWidthMin, tier.rectWidthMax)
                    "
                    class="tier-range-chip"
                  >
                    <span class="tier-range-chip__label">方形</span>
                    {{ rectRangeLabel(tier) }}
                  </span>
                  <span
                    v-if="
                      !hasRange(tier.roundDiameterMin, tier.roundDiameterMax) &&
                      !hasRange(tier.rectLengthMin, tier.rectLengthMax) &&
                      !hasRange(tier.rectWidthMin, tier.rectWidthMax)
                    "
                    class="tier-item__no-range"
                  >
                    未配置范围
                  </span>
                </div>
                <div v-if="hasSpecs(tier)" class="tier-item__specs">
                  <span v-if="tier.machineSize" class="tier-spec-chip">
                    <span class="tier-range-chip__label">机器尺寸</span>
                    {{ tier.machineSize }}
                  </span>
                  <span v-if="tier.netWeightKg" class="tier-spec-chip">
                    <span class="tier-range-chip__label">净重</span>
                    {{ tier.netWeightKg }}kg
                  </span>
                  <span v-if="tier.cartonSize" class="tier-spec-chip">
                    <span class="tier-range-chip__label">纸箱</span>
                    {{ tier.cartonSize }}
                  </span>
                  <span v-if="tier.woodBoxSize" class="tier-spec-chip">
                    <span class="tier-range-chip__label">木箱</span>
                    {{ tier.woodBoxSize }}
                  </span>
                  <span v-if="tier.grossWeightKg" class="tier-spec-chip">
                    <span class="tier-range-chip__label">毛重</span>
                    {{ tier.grossWeightKg }}kg
                  </span>
                </div>
                <p v-if="tier.remark" class="tier-item__remark">{{ tier.remark }}</p>
              </div>
              <div class="tier-item__actions">
                <el-button link type="primary" @click="handleEdit(tier)">编辑</el-button>
                <el-button link type="danger" @click="handleDelete(tier)">删除</el-button>
              </div>
            </li>
          </ul>
        </FormSection>
      </div>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import LqDialog from '@/components/common/LqDialog.vue'
import FormSection from '@/components/common/FormSection.vue'
import { UI_LAYER } from '@/constants/uiLayers'
import { askConfirm } from '@/composables/ui/useConfirmDialog'
import { notify } from '@/composables/ui/useToast'
import {
  fetchMachineTiers,
  createMachineTier,
  updateMachineTier,
  deleteMachineTier,
  type CatalogMachineTier,
  type MachineTierPayload
} from '@/api/catalog/machineTiers'

const props = defineProps<{
  visible: boolean
  modelId: number
  modelName?: string
}>()

const emit = defineEmits<{
  close: []
  changed: []
}>()

const tiers = ref<CatalogMachineTier[]>([])
const loading = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const formMessage = ref('')
const formError = ref(false)

interface TierForm {
  tierCode: string
  tierLabel: string
  roundDiameterMin: string
  roundDiameterMax: string
  rectLengthMin: string
  rectLengthMax: string
  rectWidthMin: string
  rectWidthMax: string
  machineSize: string
  netWeightKg: string
  cartonSize: string
  woodBoxSize: string
  grossWeightKg: string
  remark: string
}

const form = reactive<TierForm>({
  tierCode: '',
  tierLabel: '',
  roundDiameterMin: '',
  roundDiameterMax: '',
  rectLengthMin: '',
  rectLengthMax: '',
  rectWidthMin: '',
  rectWidthMax: '',
  machineSize: '',
  netWeightKg: '',
  cartonSize: '',
  woodBoxSize: '',
  grossWeightKg: '',
  remark: ''
})

watch(
  () => [props.visible, props.modelId] as const,
  async ([visible]) => {
    if (!visible) return
    resetForm()
    await loadTiers()
  },
  { immediate: true }
)

async function loadTiers() {
  loading.value = true
  try {
    tiers.value = await fetchMachineTiers(props.modelId)
  } catch (error) {
    notify.error('加载机型档位失败')
    console.error(error)
  } finally {
    loading.value = false
  }
}

function resetForm() {
  editingId.value = null
  form.tierCode = ''
  form.tierLabel = ''
  form.roundDiameterMin = ''
  form.roundDiameterMax = ''
  form.rectLengthMin = ''
  form.rectLengthMax = ''
  form.rectWidthMin = ''
  form.rectWidthMax = ''
  form.machineSize = ''
  form.netWeightKg = ''
  form.cartonSize = ''
  form.woodBoxSize = ''
  form.grossWeightKg = ''
  form.remark = ''
  formMessage.value = ''
  formError.value = false
}

function handleEdit(tier: CatalogMachineTier) {
  editingId.value = tier.id
  form.tierCode = tier.tierCode
  form.tierLabel = tier.tierLabel || ''
  form.roundDiameterMin = tier.roundDiameterMin != null ? String(tier.roundDiameterMin) : ''
  form.roundDiameterMax = tier.roundDiameterMax != null ? String(tier.roundDiameterMax) : ''
  form.rectLengthMin = tier.rectLengthMin != null ? String(tier.rectLengthMin) : ''
  form.rectLengthMax = tier.rectLengthMax != null ? String(tier.rectLengthMax) : ''
  form.rectWidthMin = tier.rectWidthMin != null ? String(tier.rectWidthMin) : ''
  form.rectWidthMax = tier.rectWidthMax != null ? String(tier.rectWidthMax) : ''
  form.machineSize = tier.machineSize || ''
  form.netWeightKg = tier.netWeightKg || ''
  form.cartonSize = tier.cartonSize || ''
  form.woodBoxSize = tier.woodBoxSize || ''
  form.grossWeightKg = tier.grossWeightKg || ''
  form.remark = tier.remark || ''
  formMessage.value = ''
  formError.value = false
}

async function handleSave() {
  formMessage.value = ''
  formError.value = false
  if (!form.tierCode) {
    formMessage.value = '请填写机型编码'
    formError.value = true
    return
  }
  const payload = buildPayload()
  const rangeError = validateRanges(payload)
  if (rangeError) {
    formMessage.value = rangeError
    formError.value = true
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updateMachineTier(editingId.value, payload)
      notify('机型档位已更新', 'success')
    } else {
      await createMachineTier(props.modelId, payload)
      notify('机型档位已新增', 'success')
    }
    resetForm()
    await loadTiers()
    emit('changed')
  } catch (error: any) {
    formMessage.value = error?.response?.data?.message || error?.message || '保存失败'
    formError.value = true
  } finally {
    saving.value = false
  }
}

async function handleDelete(tier: CatalogMachineTier) {
  const confirmed = await askConfirm({
    message: `确定要删除机型档位「${tier.tierLabel || tier.tierCode}」吗？`,
    danger: true
  })
  if (!confirmed) return
  try {
    await deleteMachineTier(tier.id)
    notify('机型档位已删除', 'success')
    if (editingId.value === tier.id) resetForm()
    await loadTiers()
    emit('changed')
  } catch (error: any) {
    notify.error(error?.response?.data?.message || '删除失败')
    console.error(error)
  }
}

function buildPayload(): MachineTierPayload {
  return {
    tierCode: form.tierCode,
    tierLabel: form.tierLabel || null,
    roundDiameterMin: toNumberOrNull(form.roundDiameterMin),
    roundDiameterMax: toNumberOrNull(form.roundDiameterMax),
    rectLengthMin: toNumberOrNull(form.rectLengthMin),
    rectLengthMax: toNumberOrNull(form.rectLengthMax),
    rectWidthMin: toNumberOrNull(form.rectWidthMin),
    rectWidthMax: toNumberOrNull(form.rectWidthMax),
    machineSize: form.machineSize || null,
    netWeightKg: form.netWeightKg || null,
    cartonSize: form.cartonSize || null,
    woodBoxSize: form.woodBoxSize || null,
    grossWeightKg: form.grossWeightKg || null,
    remark: form.remark || null
  }
}

function validateRanges(payload: MachineTierPayload): string | null {
  const checks: Array<{ label: string; min: number | null | undefined; max: number | null | undefined }> = [
    { label: '圆形口径', min: payload.roundDiameterMin, max: payload.roundDiameterMax },
    { label: '方形长边', min: payload.rectLengthMin, max: payload.rectLengthMax },
    { label: '方形短边', min: payload.rectWidthMin, max: payload.rectWidthMax }
  ]
  for (const check of checks) {
    if (check.min != null && check.max != null && check.min > check.max) {
      return `${check.label}下限不能大于上限`
    }
  }
  return null
}

function toNumberOrNull(value: string): number | null {
  if (!value.trim()) return null
  const num = Number(value)
  return Number.isFinite(num) && num > 0 ? num : null
}

function hasRange(min: number | null | undefined, max: number | null | undefined): boolean {
  return min != null || max != null
}

function hasSpecs(tier: CatalogMachineTier): boolean {
  return Boolean(tier.machineSize || tier.netWeightKg || tier.cartonSize || tier.woodBoxSize || tier.grossWeightKg)
}

function rangeLabel(min: number | null | undefined, max: number | null | undefined): string {
  if (min != null && max != null) return `${min}~${max}mm`
  if (min != null) return `≥${min}mm`
  if (max != null) return `≤${max}mm`
  return ''
}

function rectRangeLabel(tier: CatalogMachineTier): string {
  const lengthPart = rangeLabel(tier.rectLengthMin, tier.rectLengthMax)
  const widthPart = rangeLabel(tier.rectWidthMin, tier.rectWidthMax)
  return `长${lengthPart} × 宽${widthPart}`
}

function handleClose() {
  emit('close')
}
</script>

<style scoped>
.tier-manager-body {
  display: flex;
  flex: 1;
  min-height: 0;
}

.tier-layout {
  display: grid;
  grid-template-columns: minmax(0, 420px) minmax(0, 1fr);
  gap: var(--space-section-gap);
  flex: 1;
  min-height: 0;
  width: 100%;
}

.tier-layout :deep(.form-section) {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.tier-layout :deep(.form-section__body) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
}

/* ==================== Form ==================== */
.tier-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 var(--space-form-item-gap);
}

.tier-range-inputs {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  width: 100%;
}

.tier-range-inputs :deep(.el-input) {
  flex: 1;
}

.tier-range-sep {
  flex: 0 0 auto;
  color: var(--color-text-faint);
  font-size: var(--fs-md);
  user-select: none;
}

.tier-rule-help {
  margin: 0 0 var(--space-form-item-gap);
}

.tier-form-message {
  margin: 0 0 var(--space-control-gap-wide);
}

.tier-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--space-control-gap);
  margin-top: var(--space-control-gap-wide);
}

/* ==================== List ==================== */
.tier-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-wide);
}

.tier-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-form-item-gap);
  padding: var(--space-md-lg) var(--space-form-item-gap);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  transition:
    border-color var(--transition-base),
    box-shadow var(--transition-base);
}

.tier-item:hover {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-sm);
}

.tier-item__main {
  display: grid;
  gap: var(--space-control-gap);
  min-width: 0;
}

.tier-item__head {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  flex-wrap: wrap;
}

.tier-item__code {
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-family: var(--font-mono, monospace);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
}

.tier-item__name {
  color: var(--color-text-heading);
  font-size: var(--fs-md);
  font-weight: var(--fw-semibold);
}

.tier-item__ranges {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.tier-range-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  padding: 3px 10px;
  border: 1px solid var(--color-border-lighter);
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text-strong);
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
}

.tier-range-chip__label {
  padding-right: var(--space-control-gap-tight);
  border-right: 1px solid var(--color-border-lighter);
  color: var(--color-text-muted);
  font-weight: var(--fw-medium);
}

.tier-item__specs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.tier-spec-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  padding: 3px 10px;
  border: 1px dashed var(--color-border-light);
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  color: var(--color-text-strong);
  font-size: var(--fs-xs);
  font-variant-numeric: tabular-nums;
}

.tier-spec-chip .tier-range-chip__label {
  border-right-color: var(--color-border-light);
}

.tier-item__no-range {
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
}

.tier-item__remark {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  line-height: var(--lh-normal);
}

.tier-item__actions {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
  gap: var(--space-control-gap);
}

/* ==================== Responsive ==================== */
@media (max-width: 900px) {
  .tier-layout {
    grid-template-columns: 1fr;
    overflow-y: auto;
  }

  .tier-layout :deep(.form-section) {
    display: block;
    overflow: visible;
  }

  .tier-layout :deep(.form-section__body) {
    overflow: visible;
  }
}

@media (max-width: 680px) {
  .tier-form-grid {
    grid-template-columns: 1fr;
  }

  .tier-item {
    align-items: flex-start;
    flex-direction: column;
    gap: var(--space-control-gap-wide);
  }
}
</style>
