<template>
  <LqDialog
    :model-value="modelValue"
    size="sm"
    width="560px"
    mode="form"
    :close-on-click-modal="false"
    destroy-on-close
    class="media-form-dialog update-record-dialog"
    confirm-text="保存更新"
    :loading="saving"
    @update:model-value="val => emit('update:modelValue', val)"
    @close="
      () => {
        emit('close')
        emit('update:modelValue', false)
      }
    "
    @confirm="handleSave"
  >
    <template #title>
      <div class="form-dialog-header">
        <div>
          <h3>{{ isEdit ? '编辑更新记录' : '记录更新' }}</h3>
          <p>{{ account?.accountName || '选择账号后记录内容更新' }}</p>
        </div>
        <span :class="['platform-badge', platformClass(account?.platform)]">
          {{ account?.platform || '-' }}
        </span>
      </div>
    </template>

    <div class="dialog-form-body">
      <div class="update-form-grid">
        <label class="suppl-field">
          <span class="suppl-label">更新日期 <span class="required">*</span></span>
          <input v-model="formData.updateDate" type="date" class="full-width-input" />
        </label>
        <label class="suppl-field">
          <span class="suppl-label">标题</span>
          <input v-model="formData.title" placeholder="如：产品展示视频、直播回放" />
        </label>
        <label class="suppl-field update-field-wide">
          <span class="suppl-label">内容链接</span>
          <input v-model="formData.contentUrl" placeholder="视频 / 图文 URL 地址" />
        </label>
        <label class="suppl-field update-field-wide">
          <span class="suppl-label">备注</span>
          <textarea v-model="formData.note" rows="3" placeholder="记录本次更新的说明、数据或后续跟进"></textarea>
        </label>
      </div>
    </div>
  </LqDialog>
</template>

<script setup>
import LqDialog from '../common/LqDialog.vue'
import { platformClass } from '../../utils/media/platformUtils'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  isEdit: { type: Boolean, default: false },
  account: { type: Object, default: () => ({}) },
  formData: { type: Object, required: true },
  saving: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'save', 'close'])

function handleSave() {
  emit('save', { ...props.formData })
}
</script>

<style scoped>
@import '../../styles/media-account-view.css';


.platform-badge {
  background: var(--color-surface-alt);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-pill);
  color: var(--color-text-strong);
  display: inline-flex;
  font-size: var(--fs-xs);
  font-weight: var(--fw-bold);
  padding: 3px 8px;
  white-space: nowrap;
}

.dialog-form-body {
  padding: 0;
}

.update-record-dialog .dialog-form-body {
  display: grid;
  gap: 14px;
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
.suppl-field textarea,
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
.suppl-field textarea:focus,
.full-width-input:focus {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 3px var(--color-primary-ring);
  outline: none;
}

.suppl-field textarea {
  line-height: var(--lh-relaxed);
  min-height: 76px;
  resize: vertical;
}

</style>
