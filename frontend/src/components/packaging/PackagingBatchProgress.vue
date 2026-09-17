<template>
  <LqDialog
    :model-value="visible"
    :title="title"
    mode="detail"
    size="sm"
    :close-on-click-modal="false"
    :show-cancel="false"
    :show-confirm="false"
    :show-close="false"
  >
    <div class="batch-progress-body">
      <div class="batch-progress-bar">
        <div class="batch-progress-fill" :style="{ width: `${Math.round((current / Math.max(total, 1)) * 100)}%` }" />
      </div>
      <div class="batch-progress-meta">
        <span>{{ current }} / {{ total }}</span>
        <span v-if="status === 'running'" class="batch-progress-status running">处理中...</span>
        <span v-else-if="status === 'done'" class="batch-progress-status done">完成</span>
        <span v-else-if="status === 'error'" class="batch-progress-status error">失败</span>
      </div>
    </div>
  </LqDialog>
</template>

<script setup lang="ts">
import LqDialog from '../common/LqDialog.vue'

withDefaults(
  defineProps<{
    visible: boolean
    title?: string
    current?: number
    total?: number
    status?: 'running' | 'done' | 'error'
  }>(),
  {
    title: '',
    current: 0,
    total: 0,
    status: 'running'
  }
)
</script>

<style scoped>
.batch-progress-body {
  text-align: center;
}

.batch-progress-bar {
  height: 8px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-alt);
  overflow: hidden;
}

.batch-progress-fill {
  height: 100%;
  border-radius: var(--radius-pill);
  background: var(--color-primary);
  transition: width 0.3s ease;
}

.batch-progress-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: var(--space-md);
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

.batch-progress-status.running {
  color: var(--color-primary);
  font-weight: var(--fw-bold);
}

.batch-progress-status.done {
  color: var(--color-success);
  font-weight: var(--fw-bold);
}

.batch-progress-status.error {
  color: var(--color-danger-text);
  font-weight: var(--fw-bold);
}
</style>
