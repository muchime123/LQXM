<template>
  <div class="global-status" aria-live="polite">
    <div v-if="isOffline" class="global-status__banner global-status__banner--offline" role="status">
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        width="16"
        height="16"
        aria-hidden="true"
      >
        <path d="M22.61 16.95A5 5 0 0 0 18 10h-1.26a8 8 0 0 0-7.05-6M16.24 7.76A6 6 0 0 0 6.15 7M8 16a6 6 0 0 1 8.72-.24M2 2l20 20" />
        <path d="M12 19h.01" />
      </svg>
      <div class="global-status__body">
        <strong>当前处于离线状态</strong>
        <span>部分操作暂不可用，网络恢复后会自动重连。</span>
      </div>
    </div>

    <div v-if="backendUnavailable" class="global-status__banner global-status__banner--danger" role="status">
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        width="16"
        height="16"
        aria-hidden="true"
      >
        <rect
x="2"
y="2"
width="20"
height="8"
rx="2"
ry="2" />
        <rect
x="2"
y="14"
width="20"
height="8"
rx="2"
ry="2" />
        <line
x1="6"
y1="6"
x2="6.01"
y2="6" />
        <line
x1="6"
y1="18"
x2="6.01"
y2="18" />
      </svg>
      <div class="global-status__body">
        <strong>后端服务暂时不可用</strong>
        <span>请求未完成，请稍后重试；恢复后当前页面数据会自动续载。</span>
      </div>
    </div>

    <div
      v-for="item in announcements"
      :key="item.id"
      class="global-status__banner"
      :class="`global-status__banner--${item.kind}`"
      role="status"
    >
      <div class="global-status__body">
        <strong>{{ item.title }}</strong>
        <span v-if="item.message">{{ item.message }}</span>
      </div>
      <button
        v-if="item.dismissible !== false"
        type="button"
        class="global-status__dismiss"
        :aria-label="`关闭提示：${item.title}`"
        @click="dismiss(item.id)"
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
          width="14"
          height="14"
          aria-hidden="true"
        >
          <path d="M18 6 6 18" />
          <path d="m6 6 12 12" />
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useAppStatus } from '@/composables/ui/useAppStatus'

const { announcements, backendUnavailable, dismiss } = useAppStatus()
const isOffline = ref(typeof navigator !== 'undefined' && !navigator.onLine)

function syncOnlineStatus() {
  isOffline.value = !navigator.onLine
}

onMounted(() => {
  window.addEventListener('online', syncOnlineStatus)
  window.addEventListener('offline', syncOnlineStatus)
})

onBeforeUnmount(() => {
  window.removeEventListener('online', syncOnlineStatus)
  window.removeEventListener('offline', syncOnlineStatus)
})
</script>

<style scoped>
.global-status {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin-bottom: var(--space-control-gap-wide);
}

.global-status__banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap-wide);
  padding: var(--space-control-gap) var(--space-control-gap-wide);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  line-height: var(--lh-snug);
}

.global-status__banner--offline,
.global-status__banner--warning {
  border-color: var(--color-warning-border);
  background: var(--color-warning-bg);
  color: var(--color-warning-text);
}

.global-status__banner--danger {
  border-color: var(--color-danger-border);
  background: var(--color-danger-bg);
  color: var(--color-danger-text);
}

.global-status__banner--info {
  border-color: var(--color-info-border);
  background: var(--color-info-bg);
  color: var(--color-info-text);
}

.global-status__banner > svg {
  flex-shrink: 0;
}

.global-status__body {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: var(--space-control-gap-tight) var(--space-md);
  min-width: 0;
}

.global-status__body strong {
  font-weight: var(--fw-semibold);
  white-space: nowrap;
}

.global-status__dismiss {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: var(--control-height-sm);
  height: var(--control-height-sm);
  padding: 0;
  border: none;
  border-radius: var(--radius-md);
  background: transparent;
  color: currentColor;
  opacity: 0.7;
  cursor: pointer;
}

.global-status__dismiss:hover,
.global-status__dismiss:focus-visible {
  opacity: 1;
  background: var(--shadow-color-soft);
}

@media (max-width: 767px) {
  .global-status__banner {
    align-items: flex-start;
  }
}
</style>
