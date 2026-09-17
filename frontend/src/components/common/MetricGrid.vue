<template>
  <div class="metric-grid" :class="gridClass" :style="gridStyle">
    <slot></slot>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    columns?: number
    minWidth?: string
    density?: 'default' | 'compact'
  }>(),
  {
    columns: 0,
    minWidth: '200px',
    density: 'default'
  }
)

const gridClass = computed(() => {
  const classes = [`metric-grid--${props.density}`]
  classes.push(props.columns > 0 ? 'metric-grid--fixed' : 'metric-grid--auto')
  return classes
})

const gridStyle = computed(() => {
  if (props.columns > 0) {
    return { '--metric-grid-columns': props.columns }
  }
  return { '--metric-grid-min': props.minWidth }
})
</script>

<style scoped>
.metric-grid {
  display: grid;
  gap: var(--space-card-gap);
}

.metric-grid--fixed {
  grid-template-columns: repeat(var(--metric-grid-columns), minmax(0, 1fr));
}

.metric-grid--auto {
  grid-template-columns: repeat(auto-fit, minmax(min(var(--metric-grid-min), 100%), 1fr));
}

.metric-grid--compact {
  gap: var(--space-control-gap);
}

.metric-grid > :deep(.metric-card) {
  height: 100%;
}

@media (max-width: 767px) {
  .metric-grid--fixed {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 420px) {
  .metric-grid--fixed {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
