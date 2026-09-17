<template>
  <div class="empty-state" :class="sizeClass" role="status" aria-live="polite">
    <div v-if="$slots.icon" class="empty-icon">
      <slot name="icon"></slot>
    </div>
    <div v-else-if="icon" class="empty-icon empty-icon--text">{{ icon }}</div>
    <p class="empty-text">{{ text || '暂无数据' }}</p>
    <p v-if="hint" class="empty-hint">{{ hint }}</p>
    <div v-if="$slots.default" class="empty-actions">
      <slot></slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    icon?: string
    text?: string
    hint?: string
    size?: 'sm' | 'md' | 'lg'
  }>(),
  { size: 'md' }
)

const sizeClass = computed(() => `empty-state--${props.size}`)
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  color: var(--color-text-secondary);
}

.empty-state--sm {
  padding: var(--space-2xl, 24px) var(--space-lg, 14px);
}
.empty-state--md {
  padding: var(--space-3xl, 32px) var(--space-xl, 18px);
}
.empty-state--lg {
  padding: 60px 20px;
}

.empty-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: var(--space-lg, 14px);
  color: var(--color-border-input);
}
.empty-state--sm .empty-icon {
  margin-bottom: var(--space-sm, 6px);
}

.empty-icon--text {
  font-size: var(--fs-7xl);
  line-height: 1;
}

.empty-text {
  font-size: var(--fs-base);
  margin: 0;
  color: var(--color-text-secondary);
  font-weight: var(--fw-medium);
}

.empty-hint {
  font-size: var(--fs-sm);
  color: var(--color-text-faint);
  margin: var(--space-sm, 6px) 0 0;
  max-width: 420px;
}

.empty-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-sm, 6px);
  margin-top: var(--space-lg, 14px);
}
</style>
