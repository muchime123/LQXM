<!--
  在同一资产池内把资产【换分类】（不跨产品/不跨实体）。
  与 components/product/MoveAssetModal.vue 的差异（勿合并）：
    本组件：只选目标分类，选中态由父级通过 targetCategory 受控（emit selectCategory）。
    MoveAssetModal：跨产品移动，需选「目标产品」，状态自管。
  两者 UI 形态（分类按钮列表 vs 表单下拉）与数据契约均不同，属合理业务差异。
-->
<template>
  <LqDialog
    :model-value="modelValue"
    :title="dialogTitle"
    size="sm"
    mode="form"
    :close-on-click-modal="false"
    destroy-on-close
    confirm-text="确认移动"
    :confirm-disabled="!targetCategory || saving"
    @update:model-value="val => emit('update:modelValue', val)"
    @close="emit('close')"
    @confirm="emit('confirm')"
  >
    <div class="move-modal-content">
      <p class="move-hint">选择目标分类：</p>
      <div class="move-category-list">
        <button
          v-for="cat in categoryOptions"
          :key="cat.key"
          :class="{ selected: targetCategory === cat.key }"
          :disabled="cat.key === currentCategoryKey"
          @click="emit('selectCategory', cat.key)"
        >
          <span class="cat-icon">{{ cat.icon }}</span>
          {{ cat.label }}
          <span v-if="cat.key === currentCategoryKey" class="current-hint">（当前）</span>
        </button>
      </div>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import LqDialog from '../common/LqDialog.vue'

const props = defineProps<{
  modelValue: boolean
  targetIds: any[] | null
  categoryOptions: { key: string; label: string; icon?: string }[]
  currentCategoryKey: string | null
  targetCategory: string | null
  saving: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', val: boolean): void
  (e: 'selectCategory', key: string): void
  (e: 'confirm'): void
  (e: 'close'): void
}>()

const dialogTitle = computed(() => {
  const count = props.targetIds?.length || 0
  return count > 1 ? `移动 ${count} 个资产` : '移动到其他分类'
})
</script>

<style scoped>
.move-modal-content {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-wide);
}

.move-hint {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin: 0;
}

.move-category-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
  max-height: 360px;
  overflow-y: auto;
}

.move-category-list button {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
  padding: 10px 14px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-primary);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  transition: all var(--transition-fast);
}

.move-category-list button:hover:not(:disabled) {
  border-color: var(--color-primary-border);
  background: var(--color-primary-bg);
}

.move-category-list button.selected {
  border-color: var(--color-primary);
  background: var(--color-primary-bg);
  color: var(--color-primary);
  font-weight: 600;
}

.move-category-list button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.cat-icon {
  font-size: 16px;
}

.current-hint {
  font-size: 12px;
  color: var(--color-text-faint);
  margin-left: auto;
}
</style>
