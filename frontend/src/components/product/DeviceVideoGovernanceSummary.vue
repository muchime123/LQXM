<template>
  <section v-if="summary" class="governance-summary" aria-label="视频治理概览">
    <button type="button" :class="{ active: activeMissingItem === '' }" @click="$emit('select-missing', '')">
      <strong>{{ summary.total }}</strong><span>全部</span>
    </button>
    <span class="summary-item summary-ok">
      <strong>{{ summary.complete }}</strong><span>信息完整</span>
    </span>
    <button
      v-for="(count, item) in summary.missingItems"
      :key="item"
      type="button"
      :class="{ active: activeMissingItem === item }"
      @click="$emit('select-missing', item)"
    >
      <strong>{{ count }}</strong><span>缺{{ item }}</span>
    </button>
  </section>
</template>

<script setup lang="ts">
import type { VideoGovernanceSummary } from '@/api/catalog/assets'

defineProps<{ summary: VideoGovernanceSummary | null; activeMissingItem: string }>()
defineEmits<{ 'select-missing': [item: string] }>()
</script>

<style scoped>
.governance-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  margin: 0 0 var(--space-3);
}
.governance-summary button,
.summary-item {
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
  padding: 7px 11px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-secondary);
}
.governance-summary button { cursor: pointer; }
.governance-summary button.active {
  border-color: var(--color-primary);
  background: var(--color-primary-bg-light);
  color: var(--color-primary);
}
.governance-summary strong { color: var(--color-text); }
.summary-ok strong { color: var(--color-success-text, #047857); }
</style>
