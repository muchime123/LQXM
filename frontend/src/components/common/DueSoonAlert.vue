<template>
  <div v-if="count > 0" class="due-soon-alert" role="status">
    <strong>{{ title }}（{{ count }}项）：</strong>
    <slot />
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{ count: number; title?: string }>(), { title: '到期提醒' })
</script>

<style scoped>
.due-soon-alert {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-md);
  padding: 12px 16px;
  border: 1px solid var(--color-warning-border);
  border-radius: var(--radius-md);
  background: var(--color-warning-bg-soft);
  font-size: var(--fs-base);
}
</style>
