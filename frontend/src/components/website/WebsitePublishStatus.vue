<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  enabled?: boolean
  eligible?: boolean
  reason?: string
  compact?: boolean
}>(), { enabled: false, eligible: true, reason: '', compact: false })

const state = computed(() => {
  if (!props.eligible) return { key: 'blocked', label: '不可发布', type: 'danger' as const }
  if (props.enabled) return { key: 'published', label: '已发布', type: 'success' as const }
  return { key: 'ready', label: '待发布', type: 'warning' as const }
})
</script>

<template>
  <span class="publish-state" :class="[`is-${state.key}`, { compact }]" :title="reason || state.label">
    <i></i><strong>{{ state.label }}</strong><small v-if="reason && !compact">{{ reason }}</small>
  </span>
</template>

<style scoped>
.publish-state { display: inline-flex; align-items: center; gap: 6px; min-width: 0; color: var(--color-text-secondary); }.publish-state i { width: 7px; height: 7px; flex: none; border-radius: 50%; background: var(--color-warning); }.publish-state strong { font-size: 11px; white-space: nowrap; }.publish-state small { overflow: hidden; color: var(--color-text-faint); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }.publish-state.is-blocked i { background: var(--color-danger); }.publish-state.is-blocked strong { color: var(--color-danger); }.publish-state.is-ready strong { color: var(--color-warning); }.publish-state.is-published i { background: var(--color-success); }.publish-state.is-published strong { color: var(--color-success); }.publish-state.compact { gap: 4px; }
</style>
