<template>
  <LqDialog
    :model-value="modelValue"
    size="sm"
    width="560px"
    mode="form"
    :close-on-click-modal="false"
    destroy-on-close
    class="media-form-dialog assign-owner-dialog"
    confirm-text="确认分配"
    :loading="loading"
    :confirm-disabled="targetCount === 0"
    @update:model-value="val => emit('update:modelValue', val)"
    @close="emit('update:modelValue', false)"
    @confirm="handleSave"
  >
    <template #title>
      <div class="form-dialog-header">
        <div>
          <h3>分配发布负责人</h3>
          <p>{{ targetLabel }}</p>
        </div>
        <span class="batch-count-pill">{{ targetCount }} 个账号</span>
      </div>
    </template>

    <div class="dialog-form-body">
      <div class="batch-summary-box">
        <strong>{{ scope === 'selected' ? '已选账号' : '当前筛选结果' }}</strong>
        <span>{{ filterText }}</span>
      </div>
      <div class="update-form-grid">
        <label class="suppl-field update-field-wide">
          <span class="suppl-label">发布负责人 <span class="required">*</span></span>
          <input v-model="formData.publishOwner" placeholder="负责批量发布的人员" list="publish-owner-list" />
          <datalist id="publish-owner-list">
            <option v-for="owner in publishOwners" :key="owner" :value="owner" />
          </datalist>
        </label>
      </div>
    </div>
  </LqDialog>
</template>

<script setup>
import LqDialog from '../common/LqDialog.vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  targetLabel: { type: String, default: '' },
  targetCount: { type: Number, default: 0 },
  filterText: { type: String, default: '' },
  scope: { type: String, default: 'filters' },
  publishOwners: { type: Array, default: () => [] },
  formData: { type: Object, required: true },
  loading: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'save'])

function handleSave() {
  emit('save', { ...props.formData })
}
</script>

<style scoped>
.form-dialog-header {
  align-items: center;
  display: flex;
  gap: var(--space-control-gap-wide);
  justify-content: space-between;
  padding: 0;
}

.form-dialog-header h3 {
  color: var(--color-text-heading);
  font-size: var(--fs-xl);
  font-weight: var(--fw-extrabold);
  line-height: var(--lh-tight);
  margin: 0;
}

.form-dialog-header p {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  margin: 5px 0 0;
}

.batch-count-pill {
  align-items: center;
  background: var(--color-primary-bg);
  border: 1px solid var(--color-primary-border-light);
  border-radius: var(--radius-pill);
  color: var(--color-success-text);
  display: inline-flex;
  font-size: var(--fs-xs);
  font-weight: var(--fw-extrabold);
  padding: 5px 10px;
  white-space: nowrap;
}

.dialog-form-body {
  display: grid;
  gap: 14px;
  padding: 0;
}

.batch-summary-box {
  align-items: center;
  background: var(--color-primary-bg);
  border: 1px solid var(--color-primary-border-light);
  border-radius: var(--radius-xl);
  color: var(--color-text-strong);
  display: flex;
  gap: 10px;
  margin-bottom: 0;
  padding: 10px 12px;
}

.batch-summary-box::before {
  background: var(--color-primary);
  border-radius: 50%;
  box-shadow: 0 0 0 3px var(--color-primary-ring);
  content: '';
  flex: 0 0 auto;
  height: 6px;
  width: 6px;
}

.batch-summary-box strong {
  color: var(--color-primary);
  font-size: var(--fs-xs);
  font-weight: var(--fw-extrabold);
  white-space: nowrap;
}

.batch-summary-box span {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  line-height: 1.5;
  min-width: 0;
}

.update-form-grid {
  background: var(--color-surface);
  display: grid;
  gap: 12px 14px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
}

.update-field-wide {
  grid-column: 1 / -1;
}

.suppl-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
}

.suppl-label {
  color: var(--color-text-strong);
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
}

.required {
  color: var(--color-danger);
}

.suppl-field input,
.full-width-input {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  color: var(--color-text-heading);
  min-height: 34px;
  padding: 7px 9px;
  width: 100%;
}

.suppl-field input:focus,
.full-width-input:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-ring);
  outline: none;
}

</style>
