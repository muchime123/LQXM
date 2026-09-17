<template>
  <LqDialog
    :model-value="visible"
    title="批量编辑规格"
    :subtitle="`已选 ${selectedCount} 个规格，勾选要修改的字段并填入新值。`"
    width="min(680px, 94vw)"
    mode="form"
    :close-on-click-modal="true"
    :loading="loading"
    :confirm-disabled="!hasCheckedFields"
    confirm-text="应用修改"
    @update:model-value="emit('update:visible', false)"
    @confirm="handleConfirm"
  >
    <div class="batch-edit-body">
      <p class="batch-edit-hint">未勾选的字段不会被修改；空值将清空该字段。</p>
      <div class="batch-edit-grid">
        <template v-for="group in fieldGroups" :key="group.title">
          <div class="batch-edit-group-title">{{ group.title }}</div>
          <template v-for="field in group.fields" :key="field.key">
            <label class="batch-edit-field">
              <span class="batch-edit-field-label">
                <input type="checkbox" :checked="checkedFields[field.key]" @change="handleCheck(field.key, $event)" />
                {{ field.label }}
              </span>
              <input
                v-model.trim="values[field.key]"
                type="text"
                :disabled="!checkedFields[field.key]"
                :placeholder="checkedFields[field.key] ? '输入新值' : '不修改'"
              />
            </label>
          </template>
        </template>
      </div>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'

const props = defineProps<{
  visible: boolean
  selectedCount: number
  isFilmModel: boolean
  activeFilmForm: string
  loading: boolean
}>()

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'confirm', payload: Record<string, unknown>): void
}>()

const checkedFields = reactive<Record<string, boolean>>({})
const values = reactive<Record<string, string>>({})

const fieldGroups = computed(() => {
  if (props.isFilmModel) {
    return [
      {
        title: '基础信息',
        fields: [
          { key: 'variantName', label: '规格型号' },
          { key: 'filmSupplyType', label: '供货类型' },
          { key: 'filmMaterial', label: '膜材质' },
          { key: 'filmFeatures', label: '功能/工艺' }
        ]
      },
      {
        title: '膜参数',
        fields: [
          { key: 'peelability', label: props.activeFilmForm === 'sheet' ? '揭膜手感' : '易揭性' },
          { key: 'thicknessUm', label: '厚度' },
          { key: 'priceUnit', label: '计价单位' }
        ]
      },
      {
        title: '价格信息',
        fields: [
          { key: 'dealerPrice', label: '经销价' },
          { key: 'retailPrice', label: '零售价' },
          { key: 'plateFee', label: '版费' }
        ]
      },
      {
        title: '阶梯价',
        fields: [
          { key: 'tier5099', label: '50-99kg' },
          { key: 'tier100299', label: '100-299kg' },
          { key: 'tier300499', label: '300-499kg' },
          { key: 'tier500999', label: '500-999kg' },
          { key: 'tier10001999', label: '1000-1999kg' },
          { key: 'tier2000Up', label: '2000kg+' }
        ]
      },
      {
        title: '补充说明',
        fields: [{ key: 'remark', label: '备注' }]
      }
    ]
  }
  return [
    {
      title: '基础信息',
      fields: [
        { key: 'variantName', label: '规格型号' },
        { key: 'capacityMl', label: '容量(ml)' },
        { key: 'materialDetail', label: '材质' }
      ]
    },
    {
      title: '尺寸参数',
      fields: [
        { key: 'lengthMm', label: '长(mm)' },
        { key: 'widthMm', label: '宽(mm)' },
        { key: 'heightMm', label: '高(mm)' },
        { key: 'topDiameterMm', label: '上直径(mm)' },
        { key: 'bottomDiameterMm', label: '下直径(mm)' },
        { key: 'weightG', label: '克重(g)' }
      ]
    },
    {
      title: '装箱信息',
      fields: [
        { key: 'variantMoq', label: '每箱数量' },
        { key: 'cartonSize', label: '纸箱尺寸' },
        { key: 'cartonWeight', label: '整箱重量(kg)' },
        { key: 'cartonVolume', label: '整箱体积(m³)' }
      ]
    },
    {
      title: '价格信息',
      fields: [
        { key: 'dealerPrice', label: '经销价' },
        { key: 'retailPrice', label: '零售价' },
        { key: 'plateFee', label: '版费' }
      ]
    },
    {
      title: '补充说明',
      fields: [{ key: 'remark', label: '备注' }]
    }
  ]
})

const hasCheckedFields = computed(() => Object.values(checkedFields).some(Boolean))

function toggleField(key: string, checked: boolean) {
  checkedFields[key] = checked
  if (!checked) {
    values[key] = ''
  }
}

function handleCheck(key: string, e: Event) {
  toggleField(key, (e.target as HTMLInputElement).checked)
}

function handleConfirm() {
  if (!hasCheckedFields.value) return
  const payload: Record<string, unknown> = {}
  for (const [key, checked] of Object.entries(checkedFields)) {
    if (checked) {
      payload[key] = values[key] ?? ''
    }
  }
  if (Object.keys(payload).length === 0) return
  emit('confirm', payload)
}

watch(
  () => props.visible,
  v => {
    if (v) {
      Object.keys(checkedFields).forEach(key => delete checkedFields[key])
      Object.keys(values).forEach(key => delete values[key])
    }
  },
  { immediate: true }
)
</script>

<style scoped>
.batch-edit-body {
  padding: var(--space-control-gap-wide);
  background: var(--color-surface-alt);
}

.batch-edit-hint {
  margin: 0 0 var(--space-sm);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.batch-edit-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-sm) var(--space-md);
}

.batch-edit-group-title {
  grid-column: 1 / -1;
  margin-top: var(--space-sm);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
}

.batch-edit-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
}

.batch-edit-field-label {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
  font-weight: var(--fw-semibold);
}

.batch-edit-field input[type='checkbox'] {
  width: 16px;
  height: 16px;
  accent-color: var(--color-primary);
}

.batch-edit-field input[type='text'] {
  height: 34px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: 0 var(--space-sm);
  background: var(--color-surface);
  color: var(--color-text-heading);
  font-size: var(--fs-sm);
}

.batch-edit-field input[type='text']:disabled {
  background: var(--color-surface-hover);
  color: var(--color-text-muted);
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

@media (max-width: 640px) {
  .batch-edit-grid {
    grid-template-columns: 1fr;
  }
}
</style>
