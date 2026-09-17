<template>
  <article class="metric-card" :class="[
    `metric-card--${tone}`,
    {
      'metric-card--clickable': clickable,
      'metric-card--loading': loading,
      'metric-card--empty': empty
    }
  ]">
    <component :is="clickable ? 'button' : 'div'" class="metric-card__inner" type="button"
      :disabled="clickable && (loading || empty)" @click="clickable && $emit('click')">
      <div class="metric-card__head">
        <span class="metric-card__title">{{ title }}</span>
        <span v-if="$slots.icon" class="metric-card__icon">
          <slot name="icon"></slot>
        </span>
      </div>

      <div class="metric-card__value">
        <template v-if="loading">
          <span class="metric-card__skeleton metric-card__skeleton--value" aria-hidden="true"></span>
        </template>
        <template v-else-if="empty">
          <strong class="metric-card__empty-value">—</strong>
        </template>
        <template v-else>
          <strong>{{ value }}</strong>
          <span v-if="unit" class="metric-card__unit">{{ unit }}</span>
        </template>
      </div>

      <div class="metric-card__meta">
        <span v-if="trend && !loading && !empty" class="metric-card__trend" :class="`metric-card__trend--${trend}`">
          <svg v-if="trend !== 'flat'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round" width="12" height="12" aria-hidden="true">
            <polyline :points="trend === 'up' ? '7 17 17 7' : '17 17 7 7'" />
            <polyline :points="trend === 'up' ? '7 7 17 7' : '7 17 17 17'" />
          </svg>
          {{ trendText }}
        </span>
        <span v-if="hint && !loading && !empty" class="metric-card__hint">{{ hint }}</span>
      </div>
    </component>
  </article>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  value?: string | number
  unit?: string
  trend?: 'up' | 'down' | 'flat'
  trendText?: string
  hint?: string
  tone?: 'default' | 'primary' | 'success' | 'warning' | 'danger'
  loading?: boolean
  empty?: boolean
  clickable?: boolean
}>()

defineEmits<{ click: [] }>()
</script>

<style scoped>
.metric-card {
  min-width: 0;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
  box-shadow: var(--shadow-sm);
  transition:
    box-shadow var(--transition-base),
    border-color var(--transition-base),
    transform var(--transition-base);
}

.metric-card__inner {
  display: grid;
  gap: var(--space-control-gap);
  width: 100%;
  height: 100%;
  padding: var(--card-padding);
  text-align: left;
  color: inherit;
  background: transparent;
  border: none;
  cursor: default;
  font: inherit;
}

.metric-card--clickable .metric-card__inner {
  cursor: pointer;
}

.metric-card--clickable .metric-card__inner:hover {
  border-color: var(--color-primary-border);
  box-shadow: var(--shadow-md);
  transform: translateY(-1px);
}

.metric-card--clickable .metric-card__inner:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
  border-radius: var(--radius-xl);
}

.metric-card--clickable .metric-card__inner:disabled {
  cursor: default;
  opacity: 0.7;
}

.metric-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  min-width: 0;
}

.metric-card__title {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-card__icon {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  border-radius: var(--radius-md);
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
}

.metric-card__value {
  display: flex;
  align-items: baseline;
  gap: var(--space-control-gap-tight);
  min-height: 32px;
}

.metric-card__value strong {
  color: var(--color-text-heading);
  font-size: var(--fs-3xl);
  font-weight: var(--fw-semibold);
  font-variant-numeric: tabular-nums;
  line-height: var(--lh-tight);
  letter-spacing: -0.02em;
}

.metric-card__unit {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
}

.metric-card__empty-value {
  color: var(--color-text-faint);
}

.metric-card__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--space-control-gap-tight) var(--space-sm);
  min-height: 18px;
}

.metric-card__trend {
  display: inline-flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  padding: 1px 8px;
  border-radius: var(--radius-pill);
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
}

.metric-card__trend--up {
  background: var(--color-success-bg);
  color: var(--color-success-text);
}

.metric-card__trend--down {
  background: var(--color-danger-bg);
  color: var(--color-danger-text);
}

.metric-card__trend--flat {
  background: var(--color-surface-alt);
  color: var(--color-text-muted);
}

.metric-card__hint {
  color: var(--color-text-faint);
  font-size: var(--fs-xs);
  line-height: var(--lh-snug);
}

.metric-card__skeleton {
  display: block;
  border-radius: var(--radius-sm);
  background: var(--color-surface-hover);
  animation: metric-card-pulse 1.4s ease-in-out infinite;
}

.metric-card__skeleton--value {
  width: 72px;
  height: 30px;
}

@keyframes metric-card-pulse {
  0%,
  100% {
    opacity: 0.5;
  }
  50% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .metric-card__skeleton {
    animation: none;
  }

  .metric-card--clickable .metric-card__inner:hover {
    transform: none;
  }
}
</style>
