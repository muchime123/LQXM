<template>
  <span v-if="dot" class="status-dot" :class="type" :title="label" :aria-label="label"></span>
  <span v-else class="status-tag" :class="[type, sizeClass]">{{ label }}</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    type: string
    label: string
    size?: 'sm' | 'md'
    dot?: boolean
  }>(),
  { size: 'md', dot: false }
)

const sizeClass = computed(() => `status-tag--${props.size}`)
</script>

<style scoped>
.status-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: var(--radius-sm);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  line-height: var(--lh-relaxed);
  white-space: nowrap;
  border: 1px solid transparent;
}

.status-tag--sm {
  padding: 1px 6px;
  font-size: var(--fs-xxs);
}

.status-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}

/* 成功/有效 */
.tag-ok,
.tag-success {
  background: var(--color-success-bg);
  color: var(--color-success-text);
  border-color: var(--color-success-border);
}

.status-dot.tag-ok,
.status-dot.tag-success {
  background: var(--color-success);
  border: none;
}

/* 待处理/未生效 */
.tag-pending,
.tag-info {
  background: var(--color-info-bg);
  color: var(--color-info-text);
  border-color: var(--color-info-border);
}

.status-dot.tag-pending,
.status-dot.tag-info {
  background: var(--color-info);
  border: none;
}

/* 已失效/已过期 */
.tag-expired,
.tag-danger {
  background: var(--color-danger-bg);
  color: var(--color-danger-text);
  border-color: var(--color-danger-border);
}

.status-dot.tag-expired,
.status-dot.tag-danger {
  background: var(--color-danger);
  border: none;
}

/* 警告 */
.tag-warn,
.tag-warning {
  background: var(--color-warning-bg);
  color: var(--color-warning-text);
  border-color: var(--color-warning-border);
}

.status-dot.tag-warn,
.status-dot.tag-warning {
  background: var(--color-warning);
  border: none;
}

/* 已转让/特殊 */
.tag-trans,
.tag-purple {
  background: var(--color-purple-bg);
  color: var(--color-purple);
  border-color: var(--color-purple-border);
}

.status-dot.tag-trans,
.status-dot.tag-purple {
  background: var(--color-purple);
  border: none;
}

/* 主要/进行中 */
.tag-primary {
  background: var(--color-primary-bg);
  color: var(--color-primary);
  border-color: var(--color-primary-border-light);
}

.status-dot.tag-primary {
  background: var(--color-primary);
  border: none;
}

/* 中性/草稿 */
.tag-neutral,
.tag-default {
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
  border-color: var(--color-border);
}

.status-dot.tag-neutral,
.status-dot.tag-default {
  background: var(--color-text-muted);
  border: none;
}
</style>
