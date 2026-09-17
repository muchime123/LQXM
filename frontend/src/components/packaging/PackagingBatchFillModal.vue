<template>
  <LqDialog
    :model-value="visible"
    title="批量补膜字段"
    :subtitle="`已选 ${selectedVariants.length} 个膜规格，只会写入空字段，已有内容不会被覆盖。`"
    width="min(600px, 94vw)"
    mode="form"
    :close-on-click-modal="true"
    :loading="loading"
    :confirm-disabled="!hasValue"
    confirm-text="批量补齐"
    @update:model-value="emit('update:visible', false)"
    @confirm="handleConfirm"
  >
    <div class="batch-fill-grid">
      <label>
        <span>补版费</span>
        <input v-model.trim="form.plateFee" placeholder="例：200-260" />
      </label>
      <label>
        <span>补计价单位</span>
        <select v-model="form.priceUnit">
          <option value="">不处理</option>
          <option v-for="option in PRICE_UNITS" :key="option.key" :value="option.key">{{ option.label }}</option>
        </select>
      </label>
      <label class="wide">
        <span>补备注/报价说明</span>
        <textarea v-model.trim="form.remark" rows="3" placeholder="例：报价不含税运，特殊规格按图稿另行报价。" />
      </label>
      <fieldset class="batch-fill-tier">
        <legend>补阶梯价</legend>
        <label class="batch-tier-paste">
          <span>粘贴一行阶梯价</span>
          <input
            v-model.trim="pasteText"
            placeholder="例：17 16.5 16 15.5 15 14.5"
            @paste="handleTierPaste"
            @keyup.enter="applyTierPaste"
          />
          <button
type="button"
class="btn-sm"
:disabled="!pasteText.trim()"
@click="applyTierPaste">拆分填入</button>
        </label>
        <label><span>50-99kg</span><input v-model.trim="form.tier5099" placeholder="17" /></label>
        <label><span>100-299kg</span><input v-model.trim="form.tier100299" placeholder="-" /></label>
        <label><span>300-499kg</span><input v-model.trim="form.tier300499" placeholder="-" /></label>
        <label><span>500-999kg</span><input v-model.trim="form.tier500999" placeholder="-" /></label>
        <label><span>1000-1999kg</span><input v-model.trim="form.tier10001999" placeholder="-" /></label>
        <label><span>2000kg+</span><input v-model.trim="form.tier2000Up" placeholder="-" /></label>
      </fieldset>
    </div>
    <div v-if="hasValue" class="batch-fill-preview">
      <strong>预计写入 {{ preview.updatedCount }} / {{ selectedVariants.length }} 个规格</strong>
      <span v-if="preview.fields.length"> 字段：{{ preview.fields.join('、') }} </span>
      <span v-else>当前选中规格没有可补的空字段。</span>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import { PRICE_UNITS } from '@/constants/packaging'
import type { PackagingVariantView } from '@/api/packaging'

const props = defineProps<{
  visible: boolean
  selectedVariants: PackagingVariantView[]
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'confirm', ids: number[], payload: Record<string, unknown>): void
}>()

const filmTierFieldKeys = ['tier5099', 'tier100299', 'tier300499', 'tier500999', 'tier10001999', 'tier2000Up'] as const

const form = reactive({
  plateFee: '',
  priceUnit: '',
  remark: '',
  tier5099: '',
  tier100299: '',
  tier300499: '',
  tier500999: '',
  tier10001999: '',
  tier2000Up: ''
})

const pasteText = ref('')

const hasValue = computed(() => Object.values(form).some(value => String(value || '').trim()))

function isBlank(value: unknown) {
  return value === null || value === undefined || String(value).trim() === ''
}

function mergeRemarkFields(remark?: string, pricingNotes?: string) {
  const parts = [remark?.trim(), pricingNotes?.trim()].filter(Boolean)
  return parts.join('\n')
}

function filmUnifiedRemark(variant: PackagingVariantView) {
  return mergeRemarkFields(variant.remark, variant.pricingNotes)
}

function buildPayload(variant: PackagingVariantView) {
  const payload: Record<string, unknown> = {}
  if (form.plateFee.trim() && isBlank(variant.plateFee)) {
    payload.plateFee = form.plateFee.trim()
  }
  if (form.priceUnit && isBlank(variant.priceUnit)) {
    payload.priceUnit = form.priceUnit
  }
  if (form.remark.trim() && !filmUnifiedRemark(variant)) {
    payload.remark = form.remark.trim()
    payload.pricingNotes = ''
  }
  for (const field of filmTierFieldKeys) {
    if (form[field].trim() && isBlank(variant[field as keyof PackagingVariantView])) {
      payload[field] = form[field].trim()
    }
  }
  return payload
}

function fieldLabel(key: string) {
  const labels: Record<string, string> = {
    plateFee: '版费',
    priceUnit: '计价单位',
    remark: '备注/报价说明',
    tier5099: '50-99kg',
    tier100299: '100-299kg',
    tier300499: '300-499kg',
    tier500999: '500-999kg',
    tier10001999: '1000-1999kg',
    tier2000Up: '2000kg+'
  }
  return labels[key] || key
}

const preview = computed(() => {
  const fields = new Set<string>()
  let updatedCount = 0
  for (const variant of props.selectedVariants) {
    const payload = buildPayload(variant)
    const keys = Object.keys(payload).filter(key => key !== 'pricingNotes')
    if (keys.length) updatedCount += 1
    keys.forEach(key => fields.add(fieldLabel(key)))
  }
  return { updatedCount, fields: Array.from(fields) }
})

function parseTierPriceLine(text: string) {
  const normalized = text
    .replace(/[，、；;]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  if (!normalized) return []
  const withoutRanges = normalized
    .replace(/\b\d+\s*-\s*\d+\s*kg\b/gi, ' ')
    .replace(/\b\d+\s*kg\+\b/gi, ' ')
    .replace(/\b\d+\s*kg以上\b/g, ' ')
  return (withoutRanges.match(/\d+(?:\.\d+)?/g) || []).slice(0, filmTierFieldKeys.length)
}

function handleTierPaste(event: ClipboardEvent) {
  const text = event.clipboardData?.getData('text') || ''
  if (!text.trim()) return
  event.preventDefault()
  pasteText.value = text.trim()
  applyTierPaste()
}

function applyTierPaste() {
  const prices = parseTierPriceLine(pasteText.value)
  filmTierFieldKeys.forEach((field, index) => {
    if (prices[index]) {
      form[field] = prices[index]
    }
  })
}

function handleConfirm() {
  const fillPayload: Record<string, unknown> = {}
  if (form.plateFee.trim()) fillPayload.plateFee = form.plateFee.trim()
  if (form.priceUnit) fillPayload.priceUnit = form.priceUnit
  if (form.remark.trim()) {
    fillPayload.remark = form.remark.trim()
    fillPayload.pricingNotes = ''
  }
  for (const field of filmTierFieldKeys) {
    if (form[field].trim()) fillPayload[field] = form[field].trim()
  }
  const ids = props.selectedVariants
    .filter(v => {
      for (const key of Object.keys(fillPayload)) {
        if (key === 'pricingNotes') continue
        if (isBlank(v[key as keyof PackagingVariantView])) return true
      }
      return false
    })
    .map(v => v.id)
  if (ids.length === 0) {
    emit('update:visible', false)
    return
  }
  emit('confirm', ids, fillPayload)
}

watch(
  () => props.visible,
  v => {
    if (v) {
      pasteText.value = ''
      Object.keys(form).forEach(key => {
        ;(form as Record<string, string>)[key] = ''
      })
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.batch-fill-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-md);
  padding: var(--space-control-gap-wide);
  background: var(--color-surface-alt);
  max-height: min(560px, 70vh);
  overflow-y: auto;
}

.batch-fill-grid label {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
}

.batch-fill-grid label.wide {
  grid-column: 1 / -1;
}

.batch-fill-grid label span {
  color: var(--color-text-heading);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
}

.batch-fill-grid input,
.batch-fill-grid select,
.batch-fill-grid textarea {
  width: 100%;
  min-width: 0;
  height: 36px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 0 12px;
  background: var(--color-surface);
  color: var(--color-text-heading);
  font: inherit;
  font-size: var(--fs-sm);
}

.batch-fill-grid textarea {
  height: auto;
  padding: 8px 12px;
  resize: vertical;
}

.batch-fill-grid select {
  cursor: pointer;
}

.batch-fill-tier {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-sm) var(--space-md);
}

.batch-fill-tier legend {
  padding: 0 var(--space-control-gap-tight);
  color: var(--color-primary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-extrabold);
}

.batch-tier-paste {
  grid-column: 1 / -1;
  display: flex;
  align-items: flex-end;
  gap: var(--space-sm);
}

.batch-tier-paste input {
  flex: 1;
}

.batch-fill-preview {
  padding: 10px 16px;
  border-top: 1px solid var(--color-border-light);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.batch-fill-preview strong {
  display: block;
  margin-bottom: 4px;
  color: var(--color-primary);
}

.btn-sm {
  height: 32px;
  border-radius: var(--radius-md);
  padding: 0 14px;
  background: var(--color-surface);
  color: var(--color-text-heading);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  cursor: pointer;
  border: 1px solid var(--color-border);
}

.btn-sm:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.btn-sm.primary {
  background: var(--color-primary);
  color: var(--color-text-on-primary);
  border-color: var(--color-primary);
}

@media (max-width: 600px) {
  .batch-fill-grid,
  .batch-fill-tier {
    grid-template-columns: 1fr;
  }
}
</style>
