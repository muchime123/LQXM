<template>
  <Teleport to="body">
    <Transition name="lq-loading">
      <div v-if="visible" class="lq-loading-overlay" :class="{ 'lq-loading--fullscreen': fullscreen }" role="status"
        aria-live="polite" aria-label="loading">
        <div class="lq-loading-content">
          <div class="lq-loading-spinner" aria-hidden="true">
            <svg viewBox="0 0 50 50" width="36" height="36">
              <circle cx="25" cy="25" r="20" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round"
                opacity="0.25" />
              <circle cx="25" cy="25" r="20" fill="none" stroke="currentColor" stroke-width="4" stroke-linecap="round"
                stroke-dasharray="80 200" class="lq-loading-spinner-arc" />
            </svg>
          </div>
          <p v-if="text" class="lq-loading-text">{{ text }}</p>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { loadingState } from '../../composables/ui/useLoading'

const props = withDefaults(
  defineProps<{
    modelValue?: boolean
    text?: string
    fullscreen?: boolean
  }>(),
  {
    modelValue: undefined,
    text: undefined,
    fullscreen: true
  }
)

const visible = computed(() => {
  if (props.modelValue !== undefined) return props.modelValue
  return loadingState.visible
})

const text = computed(() => props.text ?? loadingState.text)
</script>

<style scoped>
.lq-loading-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-toast);
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-overlay-backdrop);
  backdrop-filter: blur(1px);
}

.lq-loading-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-md-lg);
  background: var(--color-surface);
  border-radius: var(--radius-xl);
  box-shadow: var(--shadow-lg);
  padding: var(--space-2xl) var(--space-3xl);
}

.lq-loading-spinner {
  color: var(--color-primary);
  display: flex;
  justify-content: center;
}

.lq-loading-spinner-arc {
  transform-origin: center;
  animation: lq-loading-spin 0.9s linear infinite;
}

@keyframes lq-loading-spin {
  to {
    transform: rotate(360deg);
  }
}

.lq-loading-text {
  font-size: var(--fs-sm);
  color: var(--color-text-secondary);
  margin: 0;
}

.lq-loading-enter-active,
.lq-loading-leave-active {
  transition: opacity var(--transition-fast);
}

.lq-loading-enter-from,
.lq-loading-leave-to {
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .lq-loading-spinner-arc {
    animation-duration: 2.5s;
  }
}
</style>
