<template>
  <section v-if="selectedCount > 0" class="batch-action-bar" aria-live="polite">
    <div class="batch-action-bar__summary">
      <strong>已选 {{ selectedCount }} 项</strong>
      <span v-if="hint">{{ hint }}</span>
    </div>
    <div class="batch-action-bar__actions">
      <slot />
      <el-button :disabled="loading" @click="$emit('clear')">取消选择</el-button>
    </div>
  </section>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    selectedCount: number
    loading?: boolean
    hint?: string
  }>(),
  {
    loading: false,
    hint: ''
  }
)

defineEmits<{ clear: [] }>()
</script>

<style scoped>
.batch-action-bar {
  position: relative;
  z-index: var(--z-base);
  display: flex;
  width: 100%;
  min-height: var(--toolbar-min-height);
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap-wide);
  padding: var(--space-control-gap) var(--space-control-gap-wide);
  border: 1px solid var(--color-primary-border);
  border-radius: var(--radius-lg);
  background: var(--color-primary-bg-light);
  box-shadow: var(--shadow-sm);
}

.batch-action-bar__summary,
.batch-action-bar__actions {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
}

.batch-action-bar__summary strong {
  color: var(--color-primary);
}

.batch-action-bar__summary span {
  color: var(--color-text-secondary);
  font-size: var(--fs-xs);
}

@media (max-width: 767px) {
  .batch-action-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .batch-action-bar__actions {
    flex-wrap: wrap;
  }
}
</style>
