<!--
  CategorySummaryTabs - 带计数的分类切换控件

  两种形态：
    variant="tab"   下划线式 tab（默认），用于区块/页签切换
    variant="chip"  圆角 chip 筛选行，用于「候选项 + 计数 + 单选高亮 + 清除」的快捷筛选

  chip 形态的数据契约与 tab 形态完全一致（label/value/count），仅皮肤不同，
  皮肤由公共模块 styles/modules/list-controls.css 的 .filter-chip* 提供。

  使用方式：
    <CategorySummaryTabs v-model="activeTab" :tabs="tabs" />
    <CategorySummaryTabs
      v-model="activeQuickFilter"
      variant="chip"
      :tabs="quickFilters"
      :clearable="!!activeQuickFilter"
      @clear="activeQuickFilter = null"
    />
-->
<template>
  <div
    v-if="variant === 'chip'"
    class="filter-chip-row"
    role="tablist"
    :aria-label="ariaLabel"
  >
    <template v-for="tab in tabs" :key="tab.value">
      <span v-if="tab.separatorBefore" class="filter-chip-sep" aria-hidden="true"></span>
      <button
        type="button"
        role="tab"
        class="filter-chip"
        :class="{ 'filter-chip--active': modelValue === tab.value }"
        :aria-selected="modelValue === tab.value"
        @click="$emit('update:modelValue', tab.value)"
      >
        {{ tab.label }}
        <span v-if="showCount(tab)" class="filter-chip__count">{{ tab.count }}</span>
      </button>
    </template>
    <button
      v-if="clearable"
      type="button"
      class="filter-chip filter-chip--clear"
      @click="$emit('clear')"
    >
      {{ clearText }}
    </button>
  </div>

  <div v-else class="summary-category-tabs" role="tablist" :aria-label="ariaLabel">
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
  count?: number
  /** chip 形态专用：在该项前插入一条竖线分隔符，用于分隔不同分组（如快捷筛选与标签） */
  separatorBefore?: boolean
}

withDefaults(
  defineProps<{
    tabs: CategorySummaryTab[]
    modelValue: string
    ariaLabel?: string
    /** tab：下划线页签；chip：圆角筛选 chip 行 */
    variant?: 'tab' | 'chip'
    /** chip 形态：是否显示「清除」按钮 */
    clearable?: boolean
    clearText?: string
  }>(),
  { ariaLabel: '分类筛选', variant: 'tab', clearable: false, clearText: '清除' }
)

defineEmits<{ 'update:modelValue': [value: string]; clear: [] }>()

// 仅 chip 形态使用：数量为 0 时不显示角标，避免满屏 "0" 干扰
function showCount(tab: CategorySummaryTab): boolean {
  return (tab.count ?? 0) > 0
}
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
