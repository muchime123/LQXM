<template>
  <header class="page-header" :aria-labelledby="titleId">
    <div class="page-header__title">
      <slot name="eyebrow"></slot>
      <h1 :id="titleId">{{ title }}</h1>
      <slot name="description">
        <p v-if="description">{{ description }}</p>
      </slot>
    </div>
    <div v-if="$slots.actions" class="page-header__actions">
      <slot name="actions"></slot>
    </div>
  </header>
</template>

<script setup lang="ts">
import { useId } from 'vue'

const titleId = `page-title-${useId()}`

withDefaults(
  defineProps<{
    title: string
    description?: string
  }>(),
  {
    description: ''
  }
)
</script>

<style scoped>
.page-header {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-section-gap-wide);
  min-height: 76px;
  padding: var(--space-section-gap) var(--space-xl);
  overflow: hidden;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  background: linear-gradient(135deg, var(--color-surface) 0%, var(--color-surface-alt) 100%);
  box-shadow: var(--shadow-sm);
}

.page-header::before {
  position: absolute;
  inset: var(--space-section-gap) auto var(--space-section-gap) 0;
  width: 3px;
  border-radius: 0 var(--radius-pill) var(--radius-pill) 0;
  background: var(--color-primary);
  content: '';
}

.page-header__title {
  position: relative;
  display: grid;
  gap: var(--space-control-gap-tight);
  min-width: 0;
}

.page-header__title h1 {
  margin: 0;
  color: var(--color-text-heading);
  font-size: var(--fs-2xl);
  font-weight: var(--fw-semibold);
  line-height: var(--lh-snug);
  letter-spacing: -0.02em;
}

.page-header__title p {
  max-width: 68ch;
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--fs-base);
  line-height: var(--lh-loose);
}

.page-header__actions {
  position: relative;
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--space-sm);
}

@media (max-width: 767px) {
  .page-header {
    align-items: stretch;
    flex-direction: column;
    gap: var(--space-control-gap-wide);
    min-height: 0;
    padding: var(--space-control-gap-wide);
  }

  .page-header::before {
    inset: 0 var(--space-control-gap-wide) auto;
    width: auto;
    height: 3px;
    border-radius: 0 0 var(--radius-pill) var(--radius-pill);
  }

  .page-header__title h1 {
    font-size: var(--fs-xl);
  }

  .page-header__actions {
    justify-content: flex-start;
  }

  .page-header__actions > :deep(*) {
    max-width: 100%;
  }
}
</style>
