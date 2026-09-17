<template>
  <Teleport to="body">
    <Transition name="error-overlay">
      <div v-if="error" class="error-boundary-overlay" role="alertdialog" aria-modal="true"
        aria-labelledby="error-boundary-title">
        <div class="error-boundary-card">
          <div class="error-boundary-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"
              stroke-linejoin="round" width="48" height="48">
              <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
              <line x1="12" y1="9" x2="12" y2="13" />
              <line x1="12" y1="17" x2="12.01" y2="17" />
            </svg>
          </div>
          <h1 id="error-boundary-title" class="error-boundary-title">页面出了点问题</h1>
          <p class="error-boundary-desc">{{ error.message }}</p>
          <div v-if="error.source === 'async'" class="error-boundary-meta">
            <span class="error-boundary-badge">异步错误</span>
            <span class="error-boundary-time">{{ formatTime(error.timestamp) }}</span>
          </div>
          <div class="error-boundary-actions">
            <button class="error-boundary-btn error-boundary-btn--primary" type="button" @click="handleReload">
              刷新页面
            </button>
            <button class="error-boundary-btn" type="button" @click="handleDismiss">
              关闭提示
            </button>
          </div>
          <details v-if="error.stack && isDev" class="error-boundary-details">
            <summary>技术详情</summary>
            <pre>{{ error.stack }}</pre>
          </details>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { errorState, clearError } from '../../composables/ui/useErrorBoundary'

const error = computed(() => errorState.value)
const isDev = import.meta.env.DEV

function formatTime(ts: number): string {
  return new Date(ts).toLocaleTimeString('zh-CN')
}

function handleReload() {
  clearError()
  window.location.reload()
}

function handleDismiss() {
  clearError()
}
</script>

<style scoped>
.error-boundary-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-toast);
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-overlay);
  backdrop-filter: blur(2px);
  padding: var(--space-3xl);
}

.error-boundary-card {
  background: var(--color-surface);
  border-radius: var(--radius-2xl);
  box-shadow: var(--shadow-xl);
  padding: var(--space-3xl);
  max-width: 440px;
  width: 100%;
  text-align: center;
}

.error-boundary-icon {
  color: var(--color-danger);
  margin-bottom: var(--space-lg-xl);
  display: flex;
  justify-content: center;
}

.error-boundary-title {
  font-size: var(--fs-xl);
  font-weight: var(--fw-medium);
  color: var(--color-text-heading);
  margin: 0 0 var(--space-sm-md) 0;
}

.error-boundary-desc {
  font-size: var(--fs-base);
  color: var(--color-text-secondary);
  line-height: var(--lh-loose);
  margin: 0 0 var(--space-lg-xl) 0;
}

.error-boundary-meta {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-md-lg);
  margin-bottom: var(--space-lg-xl);
}

.error-boundary-badge {
  font-size: var(--fs-xxs);
  font-weight: var(--fw-medium);
  color: var(--color-danger-text);
  background: var(--color-danger-bg);
  border-radius: var(--radius-pill);
  padding: 2px var(--space-sm-md);
}

.error-boundary-time {
  font-size: var(--fs-xs);
  color: var(--color-text-faint);
}

.error-boundary-actions {
  display: flex;
  gap: var(--space-sm-md);
  justify-content: center;
}

.error-boundary-btn {
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  padding: var(--space-sm-md) var(--space-xl-2xl);
  border-radius: var(--radius-md);
  border: 1px solid var(--color-border-input);
  background: var(--color-surface);
  color: var(--color-text-primary);
  cursor: pointer;
  transition: var(--transition-fast);
}

.error-boundary-btn:hover {
  border-color: var(--color-border-input-hover);
  background: var(--color-surface-hover);
}

.error-boundary-btn--primary {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-text-on-primary);
}

.error-boundary-btn--primary:hover {
  background: var(--color-primary-hover);
  border-color: var(--color-primary-hover);
}

.error-boundary-details {
  margin-top: var(--space-xl-2xl);
  text-align: left;
}

.error-boundary-details summary {
  font-size: var(--fs-xs);
  color: var(--color-text-faint);
  cursor: pointer;
}

.error-boundary-details pre {
  font-size: var(--fs-xs);
  color: var(--color-text-muted);
  background: var(--color-surface-alt);
  border-radius: var(--radius-md);
  padding: var(--space-sm-md);
  overflow-x: auto;
  margin-top: var(--space-sm-md);
}

.error-overlay-enter-active,
.error-overlay-leave-active {
  transition: opacity var(--transition-base);
}

.error-overlay-enter-from,
.error-overlay-leave-to {
  opacity: 0;
}
</style>
