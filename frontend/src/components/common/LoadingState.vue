<template>
  <div
class="loading-state"
:class="sizeClass"
role="status"
aria-live="polite"
aria-busy="true">
    <div class="loading-spinner" aria-hidden="true"></div>
    <p v-if="text !== ''">{{ text || '加载中...' }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    text?: string
    size?: 'sm' | 'md' | 'lg'
  }>(),
  { size: 'md' }
)

const sizeClass = computed(() => `loading-state--${props.size}`)
</script>

<style scoped>
.loading-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: var(--color-text-secondary);
  text-align: center;
}

.loading-state--sm {
  padding: var(--space-lg, 14px);
  gap: var(--space-sm, 6px);
}
.loading-state--sm .loading-spinner {
  width: 16px;
  height: 16px;
  border-width: 2px;
}
.loading-state--sm p {
  font-size: var(--fs-sm);
  margin: 0;
}

.loading-state--md {
  padding: var(--space-3xl, 32px) var(--space-xl, 18px);
  gap: var(--space-md, 10px);
}
.loading-state--md .loading-spinner {
  width: 28px;
  height: 28px;
  border-width: 3px;
}
.loading-state--md p {
  font-size: var(--fs-base);
  margin: 0;
}

.loading-state--lg {
  padding: 60px 20px;
  gap: var(--space-lg, 14px);
}
.loading-state--lg .loading-spinner {
  width: 40px;
  height: 40px;
  border-width: 4px;
}
.loading-state--lg p {
  font-size: var(--fs-md);
  margin: 0;
}

.loading-spinner {
  border: 3px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
