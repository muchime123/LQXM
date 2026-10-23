<template>
  <LqDialog
    :model-value="modelValue"
    size="sm"
    width="560px"
    mode="form"
    :close-on-click-modal="false"
    destroy-on-close
    class="media-form-dialog publish-task-dialog"
    confirm-text="保存发布任务"
    :confirm-disabled="saving || targetCount === 0"
    @update:model-value="val => emit('update:modelValue', val)"
    @close="emit('update:modelValue', false)"
    @confirm="handleSave"
  >
    <template #title>
      <div class="form-dialog-header">
        <div>
          <h3>批量发布记录</h3>
          <p>{{ targetLabel }}</p>
        </div>
        <span class="batch-count-pill">{{ targetCount }} 个账号</span>
      </div>
    </template>

    <div class="dialog-form-body">
      <div class="batch-summary-box">
        <strong>当前筛选结果</strong>
        <span>{{ filterText }}</span>
      </div>
      <div class="update-form-grid">
        <label class="suppl-field update-field-wide">
          <span class="suppl-label">账号归属发布负责人 <span class="required">*</span></span>
          <select v-model="formData.publishOwner" class="full-width-input">
            <option value="">请选择已有发布负责人</option>
            <option v-for="owner in publishOwners" :key="owner" :value="owner">{{ owner }}</option>
          </select>
        </label>
        <label class="suppl-field update-field-wide">
          <span class="suppl-label">任务名称 <span class="required">*</span></span>
          <input v-model="formData.taskName" placeholder="如：7月封口机混剪批量发布" />
        </label>
        <label class="suppl-field">
          <span class="suppl-label">发布日期 <span class="required">*</span></span>
          <input v-model="formData.publishDate" type="date" class="full-width-input" />
        </label>
        <label class="suppl-field">
          <span class="suppl-label">发布软件</span>
          <input v-model="formData.publishSoftware" placeholder="如：矩阵通、易媒助手" />
        </label>
        <label class="suppl-field update-field-wide">
          <span class="suppl-label">内容标题</span>
          <input v-model="formData.contentTitle" placeholder="这批内容在账号更新记录里显示的标题" />
        </label>
        <label class="suppl-field update-field-wide">
          <span class="suppl-label">备注</span>
          <textarea v-model="formData.note" rows="3" placeholder="记录发布批次、异常账号、后续补链接等信息"></textarea>
        </label>
        <label class="batch-check-row update-field-wide">
          <input v-model="formData.skipExisting" type="checkbox" />
          <span>跳过发布日期已经有更新记录的账号</span>
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
  publishOwners: { type: Array, default: () => [] },
  formData: { type: Object, required: true },
  saving: { type: Boolean, default: false }
})

const emit = defineEmits(['update:modelValue', 'save'])

function handleSave() {
  emit('save', { ...props.formData })
}
</script>

<style scoped>


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
.suppl-field select,
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
.suppl-field select:focus,
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

.batch-check-row {
  align-items: center;
  color: var(--color-text-strong);
  display: flex;
  gap: var(--space-control-gap);
  font-size: var(--fs-sm);
}

.batch-check-row input {
  height: 15px;
  width: 15px;
}

</style>
