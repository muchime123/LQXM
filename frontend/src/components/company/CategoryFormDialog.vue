<template>
  <LqDialog
    :model-value="modelValue"
    :title="isEdit ? '编辑分类' : '新增分类'"
    size="sm"
    mode="form"
    :close-on-click-modal="false"
    destroy-on-close
    :loading="saving"
    @update:model-value="val => emit('update:modelValue', val)"
    @close="emit('close')"
    @confirm="handleSave"
  >
    <div class="form-grid">
      <div class="form-field full-field">
        <label class="required">分类名称</label>
        <input v-model="localForm.name" placeholder="如：企业制度" />
      </div>
      <div class="form-field full-field">
        <label>英文名称</label>
        <input v-model="localForm.nameEn" />
      </div>
      <div class="form-field full-field">
        <label>描述</label>
        <textarea v-model="localForm.description" rows="2" placeholder="选填"></textarea>
      </div>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import LqDialog from '../common/LqDialog.vue'

interface CategoryFormData {
  name: string
  nameEn: string
  description: string
}

const props = defineProps<{
  modelValue: boolean
  isEdit: boolean
  formData: CategoryFormData
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', val: boolean): void
  (e: 'save', formData: CategoryFormData): void
  (e: 'close'): void
}>()

const localForm = reactive<CategoryFormData>({ name: '', nameEn: '', description: '' })

watch(
  () => props.modelValue,
  val => {
    if (val) {
      Object.assign(localForm, props.formData || { name: '', nameEn: '', description: '' })
    }
  }
)

function handleSave() {
  emit('save', { ...localForm })
}
</script>

<style scoped>
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px 18px;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.form-field.full-field {
  grid-column: 1 / -1;
}

.form-field label {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-secondary);
  display: inline-flex;
  align-items: baseline;
}

.form-field > label.required::before {
  content: '*';
  color: var(--color-danger);
  margin-right: 3px;
  font-weight: 700;
}

.form-field input,
.form-field textarea {
  padding: 8px 12px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  font-size: 14px;
  color: var(--color-text-primary);
  background: var(--color-surface);
  font-family: inherit;
  transition: border-color var(--transition-fast);
}

.form-field input:focus,
.form-field textarea:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-ring);
}

.form-field textarea {
  resize: vertical;
  min-height: 60px;
}
</style>
