<template>
  <div class="summary-category-tabs" role="tablist" :aria-label="ariaLabel">
    <button
      v-for="tab in tabs"
      :key="tab.value"
      class="summary-category-tab"
      :class="{ 'summary-category-tab--active': modelValue === tab.value }"
      type="button"
      role="tab"
      :aria-selected="modelValue === tab.value"
      @click="$emit('update:modelValue', tab.value)"
    >
      {{ tab.label }}
      <span class="summary-category-tab__count">{{ tab.count }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
export interface CategorySummaryTab {
  label: string
  value: string
  count: number
}

withDefaults(
  defineProps<{
    tabs: CategorySummaryTab[]
    modelValue: string
    ariaLabel?: string
  }>(),
  { ariaLabel: '分类筛选' }
)

defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<style scoped>
.summary-category-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
  border-bottom: 1px solid var(--color-border);
}

.summary-category-tab {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  margin-bottom: -1px;
  padding: var(--space-sm) var(--space-md);
  border: none;
  border-bottom: 2px solid transparent;
  background: none;
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  cursor: pointer;
  transition:
    color 0.15s ease,
    border-color 0.15s ease;
}

.summary-category-tab:hover {
  color: var(--color-text-primary);
}

.summary-category-tab--active {
  border-bottom-color: var(--color-primary);
  color: var(--color-primary);
  font-weight: var(--fw-semibold);
}

.summary-category-tab__count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: var(--radius-full);
  background: var(--color-surface-hover);
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-semibold);
  line-height: 1;
}

.summary-category-tab--active .summary-category-tab__count {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}
</style>
