<template>
  <LqDialog
    :model-value="visible"
    :title="mode === 'batch' ? '批量编辑标签' : '编辑标签'"
    mode="form"
    size="sm"
    :close-on-click-modal="true"
    @update:model-value="emit('update:visible', false)"
    @confirm="emit('save')"
  >
    <label class="tag-editor-field">
      <span v-if="mode === 'batch'">批量替换标签（所有选中素材将被设置为相同标签）</span>
      <span v-else>素材标签</span>
      <!-- R4 配方：LqTagInput 替代文本输入 -->
      <LqTagInput
        :model-value="tags"
        placeholder="输入标签后按 Enter 添加"
        @update:model-value="onTagsChange"
      />
    </label>
  </LqDialog>
</template>

<script setup lang="ts">
import LqDialog from '../common/LqDialog.vue'
import LqTagInput from '../common/LqTagInput.vue'

const props = defineProps<{
  visible: boolean
  tags: string
  mode?: 'single' | 'batch'
}>()

// props 通过模板自动暴露，无需在 script 中显式引用
void props

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  (e: 'save'): void
  (e: 'update:tags', value: string): void
}>()

function onTagsChange(val: string) {
  emit('update:tags', val)
}
</script>

<style scoped>
.tag-editor-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.tag-editor-field span {
  font-size: var(--fs-sm);
  color: var(--color-text-muted);
}

.tag-editor-field input {
  padding: 8px 12px;
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-heading);
  font: inherit;
  font-size: var(--fs-sm);
}

.tag-editor-field input:focus {
  outline: none;
  border-color: var(--color-primary);
}

.btn-sm {
  display: inline-flex;
  align-items: center;
  min-height: 32px;
  padding: 0 16px;
  border: 1px solid var(--color-border-input);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-heading);
  font: inherit;
  font-size: var(--fs-sm);
  font-weight: var(--fw-bold);
  cursor: pointer;
}

.btn-sm.primary {
  border-color: var(--color-primary);
  background: var(--color-primary);
  color: var(--color-surface);
}
</style>
