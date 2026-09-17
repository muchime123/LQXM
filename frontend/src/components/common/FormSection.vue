<template>
  <section class="form-section" :class="{ 'form-section--collapsed': isCollapsed }">
    <header class="form-section__header">
      <div class="form-section__title-wrap">
        <h3 class="form-section__title">{{ title }}</h3>
        <p v-if="description" class="form-section__description">{{ description }}</p>
      </div>
      <div class="form-section__extra">
        <span v-if="requiredCount > 0" class="form-section__required">必填 {{ requiredCount }} 项</span>
        <slot name="extra"></slot>
        <button
          v-if="collapsible"
          type="button"
          class="form-section__collapse"
          :aria-expanded="!isCollapsed"
          :aria-label="`${isCollapsed ? '展开' : '收起'}${title}`"
          @click="toggle"
        >
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
            <polyline :points="isCollapsed ? '9 18 15 12 9 6' : '6 9 12 15 18 9'" />
          </svg>
        </button>
      </div>
    </header>

    <div v-if="error" class="form-section__error" role="alert">
      <slot name="error">
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
          <circle cx="12" cy="12" r="10" />
          <line
x1="12"
y1="8"
x2="12"
y2="12" />
          <line
x1="12"
y1="16"
x2="12.01"
y2="16" />
        </svg>
        <span>{{ error }}</span>
      </slot>
    </div>

    <div v-show="!isCollapsed" class="form-section__body">
      <slot></slot>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    title: string
    description?: string
    requiredCount?: number
    error?: string
    collapsible?: boolean
    modelValue?: boolean
  }>(),
  {
    description: '',
    requiredCount: 0,
    error: '',
    collapsible: false,
    modelValue: true
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
}>()

const isCollapsed = computed(() => (props.collapsible ? !props.modelValue : false))

function toggle() {
  if (props.collapsible) emit('update:modelValue', !props.modelValue)
}
</script>

<style scoped>
.form-section {
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-sm);
  overflow: hidden;
}

.form-section__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap-wide);
  padding: var(--space-control-gap-wide) var(--space-form-item-gap);
  border-bottom: 1px solid var(--color-border-lighter);
  background: var(--color-surface-alt);
}

.form-section--collapsed .form-section__header {
  border-bottom: none;
}

.form-section__title-wrap {
  display: grid;
  gap: var(--space-control-gap-tight);
  min-width: 0;
}

.form-section__title {
  margin: 0;
  color: var(--color-text-heading);
  font-size: var(--fs-base);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
}

.form-section__description {
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  line-height: var(--lh-normal);
}

.form-section__extra {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex: 0 0 auto;
}

.form-section__required {
  color: var(--color-text-muted);
  font-size: var(--fs-xs);
  white-space: nowrap;
}

.form-section__collapse {
  display: grid;
  place-items: center;
  width: var(--control-height-sm);
  height: var(--control-height-sm);
  padding: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  color: var(--color-text-muted);
  cursor: pointer;
  transition: all var(--transition-base);
}

.form-section__collapse:hover,
.form-section__collapse:focus-visible {
  border-color: var(--color-primary-border);
  color: var(--color-text-heading);
}

.form-section__error {
  display: flex;
  align-items: flex-start;
  gap: var(--space-control-gap-tight);
  padding: var(--space-sm) var(--space-control-gap-wide);
  border-bottom: 1px solid var(--color-danger-border);
  background: var(--color-danger-bg-soft);
  color: var(--color-danger-text);
  font-size: var(--fs-xs);
  line-height: var(--lh-normal);
}

.form-section__error svg {
  flex-shrink: 0;
  margin-top: 1px;
}

.form-section__body {
  padding: var(--space-form-item-gap);
}

@media (max-width: 767px) {
  .form-section__header {
    align-items: flex-start;
  }

  .form-section__body {
    padding: var(--space-control-gap-wide);
  }
}
</style>
