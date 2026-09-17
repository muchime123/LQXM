<template>
  <LqDialog
    :model-value="visible"
    eyebrow="批量编辑"
    :title="`修改 ${selectedCount} 个${entityName}`"
    subtitle="勾选要修改的字段并填入新值，未勾选的字段不会被修改"
    size="lg"
    mode="form"
    :loading="loading"
    :z-index="zIndex"
    :confirm-disabled="!hasCheckedFields"
    confirm-text="应用修改"
    @update:model-value="emit('update:visible', $event)"
    @close="emit('close')"
    @confirm="handleApply"
  >
    <section class="field-batch-edit-body">
      <template v-for="group in fieldGroups" :key="group.title">
        <div class="field-batch-edit-group-title">{{ group.title }}</div>
        <div class="field-batch-edit-grid">
          <div
            v-for="field in group.fields"
            :key="field.key"
            class="field-batch-edit-item"
            :class="{ 'is-full': field.full }"
          >
            <label class="field-batch-edit-label">
              <input
                type="checkbox"
                :checked="checkedFields[field.key]"
                :aria-label="`勾选${field.label}`"
                @change="e => handleCheck(field, (e.target as HTMLInputElement).checked)"
              />
              {{ field.label }}
            </label>
            <div class="field-batch-edit-control">
              <el-input
                v-if="field.type === 'text'"
                v-model="values[field.key]"
                :disabled="!checkedFields[field.key]"
                :placeholder="checkedFields[field.key] ? '输入新值' : '不修改'"
                clearable
              />
              <el-input
                v-else-if="field.type === 'textarea'"
                v-model="values[field.key]"
                type="textarea"
                :rows="2"
                :disabled="!checkedFields[field.key]"
                :placeholder="checkedFields[field.key] ? '输入新值' : '不修改'"
              />
              <el-input-number
                v-else-if="field.type === 'number'"
                v-model="values[field.key]"
                :disabled="!checkedFields[field.key]"
                :placeholder="checkedFields[field.key] ? '输入新值' : '不修改'"
                :controls="false"
                style="width: 100%"
              />
              <el-select
                v-else-if="field.type === 'select'"
                v-model="values[field.key]"
                :disabled="!checkedFields[field.key]"
                :placeholder="checkedFields[field.key] ? '选择新值' : '不修改'"
                style="width: 100%"
              >
                <el-option
                  v-for="opt in field.options || []"
                  :key="String(opt.value)"
                  :label="opt.label"
                  :value="opt.value"
                />
              </el-select>
              <LqDatePicker
                v-else-if="field.type === 'date'"
                v-model="values[field.key]"
                :disabled="!checkedFields[field.key]"
                :placeholder="checkedFields[field.key] ? '选择日期' : '不修改'"
              />
              <LqTagInput
                v-else-if="field.type === 'tags'"
                v-model="values[field.key]"
                :disabled="!checkedFields[field.key]"
                :placeholder="checkedFields[field.key] ? '输入标签后按 Enter 添加' : '不修改'"
              />
              <el-switch
                v-else-if="field.type === 'switch'"
                v-model="values[field.key]"
                :disabled="!checkedFields[field.key]"
              />
            </div>
          </div>
        </div>
      </template>
    </section>

    <template #footer>
      <span class="field-batch-edit-status">{{ footerText }}</span>
      <button type="button" @click="emit('close')">取消</button>
      <button type="button" class="primary" :disabled="!hasCheckedFields" :loading="loading" @click="handleApply">
        应用修改
      </button>
    </template>
  </LqDialog>
</template>

<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'
import LqDatePicker from '../common/LqDatePicker.vue'
import LqTagInput from '../common/LqTagInput.vue'

export interface BatchEditFieldOption {
  value: string | number | boolean
  label: string
}

export interface BatchEditField {
  key: string
  label: string
  /** text / textarea / number / select / date / tags / switch */
  type: string
  options?: BatchEditFieldOption[]
  /** select / date / tags 使用 */
  full?: boolean
}

export interface BatchEditFieldGroup {
  title: string
  fields: BatchEditField[]
}

const props = withDefaults(
  defineProps<{
    visible: boolean
    selectedCount: number
    fieldGroups: BatchEditFieldGroup[]
    loading?: boolean
    entityName?: string
    zIndex?: number
  }>(),
  {
    loading: false,
    entityName: '记录'
  }
)

const emit = defineEmits<{
  'update:visible': [value: boolean]
  close: []
  apply: [payload: Record<string, unknown>]
}>()

const checkedFields = reactive<Record<string, boolean>>({})
const values = reactive<Record<string, any>>({})

function initState() {
  for (const key of Object.keys(checkedFields)) delete checkedFields[key]
  for (const key of Object.keys(values)) delete values[key]
}

watch(
  () => props.visible,
  v => {
    if (v) initState()
  }
)

const hasCheckedFields = computed(() => Object.values(checkedFields).some(Boolean))

const checkedCount = computed(() => Object.values(checkedFields).filter(Boolean).length)

const footerText = computed(() => {
  if (checkedCount.value === 0) return '未勾选任何字段，不会修改任何内容'
  return `已勾选 ${checkedCount.value} 个字段，将覆盖到 ${props.selectedCount} 个${props.entityName}`
})

function handleCheck(field: BatchEditField, checked: boolean) {
  checkedFields[field.key] = checked
  if (!checked) {
    values[field.key] = defaultValue(field)
  }
}

function defaultValue(field: BatchEditField): unknown {
  if (field.type === 'switch') return false
  if (field.type === 'tags') return ''
  if (field.type === 'number') return undefined
  return ''
}

function handleApply() {
  if (!hasCheckedFields.value) return
  const payload: Record<string, unknown> = {}
  for (const [key, checked] of Object.entries(checkedFields)) {
    if (checked) {
      payload[key] = values[key] ?? null
    }
  }
  if (Object.keys(payload).length === 0) return
  emit('apply', payload)
}
</script>

<style scoped>
.field-batch-edit-body {
  display: flex;
  flex-direction: column;
  padding: 0 4px;
}

.field-batch-edit-group-title {
  font-size: var(--fs-base);
  font-weight: var(--fw-semibold);
  color: var(--color-text-heading-alt);
  padding: 12px 0 8px;
  border-top: 1px solid var(--color-border);
  margin-top: var(--space-control-gap);
}

.field-batch-edit-group-title:first-child {
  margin-top: 0;
  border-top: none;
}

.field-batch-edit-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: var(--space-form-item-gap);
}

@media (max-width: 640px) {
  .field-batch-edit-grid {
    grid-template-columns: 1fr;
  }
}

.field-batch-edit-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.field-batch-edit-item.is-full {
  grid-column: 1 / -1;
}

.field-batch-edit-label {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  color: var(--color-text-strong);
}

.field-batch-edit-label input[type='checkbox'] {
  width: 16px;
  height: 16px;
  cursor: pointer;
  accent-color: var(--color-primary);
}

.field-batch-edit-control {
  min-width: 0;
}

.field-batch-edit-status {
  font-size: var(--fs-sm);
  color: var(--color-text-muted);
}
</style>
