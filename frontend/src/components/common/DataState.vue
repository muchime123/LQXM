<template>
  <section
    class="data-state"
    :class="`data-state--${resolvedState}`"
    :aria-busy="refreshing || resolvedState === 'loading'"
  >
    <slot v-if="resolvedState === 'loading'" name="loading">
      <LqSkeleton v-if="skeleton" :variant="skeletonVariant" :rows="skeletonRows" />
      <LoadingState v-else :text="loadingText" />
    </slot>

    <slot v-else-if="resolvedState === 'error' || resolvedState === 'forbidden'" :name="resolvedState">
      <div class="data-state-error" :class="{ 'is-forbidden': resolvedState === 'forbidden' }" role="alert">
        <div class="data-state-error__icon" aria-hidden="true">{{ resolvedState === 'forbidden' ? '×' : '!' }}</div>
        <h3>{{ resolvedState === 'forbidden' ? forbiddenTitle : errorTitle }}</h3>
        <p>{{ resolvedState === 'forbidden' ? forbiddenMessage : errorMessage }}</p>
        <div v-if="resolvedState === 'error' || $slots.forbiddenActions" class="data-state-actions">
          <slot v-if="resolvedState === 'forbidden'" name="forbiddenActions" />
          <slot v-else name="errorActions">
            <el-button @click="emit('retry')">{{ retryText }}</el-button>
          </slot>
        </div>
      </div>
    </slot>

    <slot v-else-if="resolvedState === 'empty' || resolvedState === 'filtered-empty'" name="empty">
      <EmptyState
        :icon="emptyIcon"
        :text="resolvedState === 'filtered-empty' ? filteredEmptyText : emptyText"
        :hint="resolvedState === 'filtered-empty' ? filteredEmptyHint : emptyHint"
      >
        <div v-if="$slots.emptyActions" class="data-state-actions">
          <slot name="emptyActions" :state="resolvedState" />
        </div>
      </EmptyState>
    </slot>

    <div v-else class="data-state-content">
      <div v-if="resolvedState === 'stale'" class="data-state-stale" role="status">
        <div>
          <strong>{{ staleTitle }}</strong
          ><span>{{ staleMessage }}</span>
        </div>
        <slot name="staleActions"
          ><el-button link type="primary" @click="emit('retry')">{{ retryText }}</el-button></slot
        >
      </div>
      <slot />
      <div v-if="refreshing" class="data-state-refreshing" role="status">
        <el-icon class="is-loading"><Loading /></el-icon><span>{{ refreshingText }}</span>
      </div>
    </div>
  </section>
</template>

<script lang="ts">
export type DataStateStatus = 'ready' | 'loading' | 'empty' | 'filtered-empty' | 'error' | 'forbidden' | 'stale'
</script>

<script setup lang="ts">
import { computed } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import EmptyState from './EmptyState.vue'
import LoadingState from './LoadingState.vue'
import LqSkeleton from './LqSkeleton.vue'

const props = withDefaults(
  defineProps<{
    state?: DataStateStatus
    refreshing?: boolean
    loadingText?: string
    refreshingText?: string
    emptyIcon?: string
    emptyText?: string
    emptyHint?: string
    filteredEmptyText?: string
    filteredEmptyHint?: string
    errorTitle?: string
    errorMessage?: string
    forbiddenTitle?: string
    forbiddenMessage?: string
    staleTitle?: string
    staleMessage?: string
    retryText?: string
    skeleton?: boolean
    skeletonVariant?: 'card' | 'list' | 'table' | 'default'
    skeletonRows?: number
    /** @deprecated 使用 state="loading" */
    loading?: boolean
    /** @deprecated 使用 state="empty" */
    empty?: boolean
    /** @deprecated 使用 state="error" */
    error?: boolean | string | object
  }>(),
  {
    state: 'ready',
    refreshing: false,
    loadingText: '正在加载数据...',
    refreshingText: '正在刷新...',
    emptyIcon: '∅',
    emptyText: '暂无数据',
    emptyHint: '',
    filteredEmptyText: '没有匹配结果',
    filteredEmptyHint: '请调整或清除筛选条件',
    errorTitle: '加载失败',
    errorMessage: '请检查网络连接后重试。',
    forbiddenTitle: '暂无访问权限',
    forbiddenMessage: '请联系管理员开通权限，或返回上一页。',
    staleTitle: '刷新失败',
    staleMessage: '正在展示上次成功加载的数据。',
    retryText: '重新加载',
    skeleton: false,
    skeletonVariant: 'list',
    skeletonRows: 5,
    loading: false,
    empty: false,
    error: false
  }
)

const emit = defineEmits<{ retry: [] }>()

const resolvedState = computed<DataStateStatus>(() => {
  if (props.state !== 'ready') return props.state
  if (props.loading) return 'loading'
  if (props.error) return 'error'
  if (props.empty) return 'empty'
  return 'ready'
})
</script>

<style scoped>
.data-state {
  position: relative;
  min-width: 0;
}

.data-state-content {
  position: relative;
  min-width: 0;
}

.data-state-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 240px;
  padding: var(--space-3xl, 32px) var(--space-2xl, 24px);
  text-align: center;
  color: var(--color-text-secondary);
}

.data-state-error__icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  margin-bottom: var(--space-lg, 14px);
  border-radius: var(--radius-pill, 999px);
  background: var(--color-danger-bg-soft);
  color: var(--color-danger);
  font-weight: var(--fw-bold);
}

.data-state-error.is-forbidden .data-state-error__icon {
  background: var(--color-warning-bg-soft);
  color: var(--color-warning);
}

.data-state-error h3 {
  margin: 0;
  color: var(--color-text-heading);
  font-size: var(--fs-lg);
}

.data-state-error p {
  max-width: 420px;
  margin: var(--space-sm, 6px) 0 0;
  font-size: var(--fs-base);
  line-height: var(--lh-loose);
}

.data-state-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: var(--space-sm, 6px);
  margin-top: var(--space-lg, 14px);
}

.data-state-stale {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap-wide);
  margin-bottom: var(--space-control-gap-wide);
  padding: var(--space-control-gap) var(--space-control-gap-wide);
  border: 1px solid var(--color-warning-border);
  border-radius: var(--radius-md);
  background: var(--color-warning-bg-soft);
}

.data-state-stale > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: var(--space-control-gap-tight);
}

.data-state-stale strong {
  color: var(--color-warning-text);
  font-size: var(--fs-sm);
}

.data-state-stale span {
  color: var(--color-text-secondary);
  font-size: var(--fs-xs);
}

.data-state-refreshing {
  position: absolute;
  inset: 0;
  z-index: var(--z-base);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  gap: var(--space-control-gap);
  padding-top: var(--space-section-gap);
  background: color-mix(in srgb, var(--color-surface) 72%, transparent);
  color: var(--color-primary);
  font-size: var(--fs-sm);
  pointer-events: none;
}

@media (max-width: 767px) {
  .data-state-stale {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
