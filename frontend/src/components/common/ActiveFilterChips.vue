<template>
  <div v-if="filters.length" class="active-filter-chips" :data-testid="dataTestid">
    <span class="active-filter-chips__label">{{ label }}</span>
    <span v-for="filter in filters" :key="filter.key" class="active-filter-chips__chip">
      <span class="active-filter-chips__chip-label">{{ filter.label }}</span>
      <button type="button" class="active-filter-chips__chip-value"
        :title="`移除条件：${filter.label} = ${filter.valueLabel}`" @click="emit('remove', filter.key)">
        {{ filter.valueLabel }}
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
          stroke-linejoin="round" width="12" height="12" aria-hidden="true">
          <path d="M18 6 6 18" />
          <path d="m6 6 12 12" />
        </svg>
      </button>
    </span>
    <button v-if="showClear" type="button" class="active-filter-chips__clear" @click="emit('clear')">全部清除</button>
  </div>
</template>

<script setup lang="ts">
export interface ActiveFilter {
  key: string
  label: string
  valueLabel: string
}

withDefaults(
  defineProps<{
    filters: ActiveFilter[]
    label?: string
    showClear?: boolean
    dataTestid?: string
  }>(),
  {
    label: '已应用条件：',
    showClear: true,
    dataTestid: 'active-filter-chips'
  }
)

const emit = defineEmits<{
  remove: [key: string]
  clear: []
}>()
</script>

<style scoped>
.active-filter-chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-sm);
  padding: var(--space-sm) 0;
}

.active-filter-chips__label {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  white-space: nowrap;
}

.active-filter-chips__chip {
  display: inline-flex;
  align-items: stretch;
  overflow: hidden;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  font-size: var(--fs-xs);
}

.active-filter-chips__chip-label {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-right: 1px solid var(--color-border-lighter);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  white-space: nowrap;
}

.active-filter-chips__chip-value {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  padding: 2px 8px;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  white-space: nowrap;
}

.active-filter-chips__chip-value:hover,
.active-filter-chips__chip-value:focus-visible {
  background: var(--color-danger-bg);
  color: var(--color-danger-text);
  border-radius: 0 var(--radius-pill) var(--radius-pill) 0;
}

.active-filter-chips__clear {
  padding: 2px 6px;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.active-filter-chips__clear:hover,
.active-filter-chips__clear:focus-visible {
  color: var(--color-danger-text);
}
</style>
