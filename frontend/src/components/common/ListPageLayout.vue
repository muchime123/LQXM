<template>
  <section class="list-page-layout" :class="{
    'list-page-layout--with-header': showHeader,
    'list-page-layout--with-summary': Boolean($slots.summary),
    'list-page-layout--with-toolbar': Boolean($slots.toolbar)
  }" :aria-labelledby="showHeader && title && !$slots.title ? titleId : undefined">
    <!-- 头部外观与 PageHeader 共用 styles/modules/list-page-surfaces.css 的 .page-surface-header -->
    <header v-if="showHeader" class="page-surface-header">
      <div class="page-surface-header__title">
        <slot name="eyebrow"></slot>
        <slot name="title">
          <h1 :id="titleId">{{ title }}</h1>
        </slot>
        <slot name="description">
          <p v-if="description">{{ description }}</p>
        </slot>
      </div>

      <div v-if="$slots.actions" class="page-surface-header__actions">
        <slot name="actions"></slot>
      </div>
    </header>

    <div v-if="$slots.summary" class="list-page-layout__summary">
      <slot name="summary"></slot>
    </div>

    <div v-if="$slots.notice" class="list-page-layout__notice">
      <slot name="notice"></slot>
    </div>

    <div v-if="$slots.toolbar" class="list-page-layout__toolbar">
      <slot name="toolbar"></slot>
    </div>

    <div v-if="$slots.filterChips" class="list-page-layout__filter-chips">
      <slot name="filterChips"></slot>
    </div>

    <div class="list-page-layout__body" :aria-busy="busy || undefined" :aria-label="contentLabel || undefined">
      <slot>
        <DataState :state="state" :refreshing="refreshing" :loading-text="loadingText" :refreshing-text="refreshingText"
          :empty-icon="emptyIcon" :empty-text="emptyText" :empty-hint="emptyHint"
          :filtered-empty-text="filteredEmptyText" :filtered-empty-hint="filteredEmptyHint" :error-title="errorTitle"
          :error-message="errorMessage" :forbidden-title="forbiddenTitle" :forbidden-message="forbiddenMessage"
          :stale-title="staleTitle" :stale-message="staleMessage" :retry-text="retryText" :skeleton="skeleton"
          :skeleton-variant="skeletonVariant" :skeleton-rows="skeletonRows" @retry="$emit('retry')">
          <template v-if="$slots.emptyActions" #emptyActions="{ state: slotState }">
            <slot name="emptyActions" :state="slotState"></slot>
          </template>

          <template v-if="$slots.errorActions" #errorActions>
            <slot name="errorActions"></slot>
          </template>

          <div class="list-page-layout__list-container">
            <div v-if="$slots.listActions" class="list-page-layout__list-header">
              <div class="list-page-layout__list-actions">
                <slot name="listActions"></slot>
              </div>
            </div>

            <div class="list-page-layout__list-scroll">
              <slot name="list"></slot>
            </div>

            <div v-if="pagination && showPagination" class="list-page-layout__pagination-bar">
              <LqPagination :pagination="pagination" :mode="paginationMode" :page-sizes="pageSizes"
                :hide-on-single-page="false" :disabled="refreshing" @change="$emit('pageChange')" />
            </div>
          </div>
        </DataState>
      </slot>
      <div v-if="$slots.pagination" class="list-page-layout__pagination-bar">
        <slot name="pagination"></slot>
      </div>
    </div>
  </section>
</template>

<script lang="ts">
export type ListPageLayoutState =
  | 'ready'
  | 'loading'
  | 'empty'
  | 'filtered-empty'
  | 'error'
  | 'forbidden'
  | 'stale'
</script>

<script setup lang="ts">
import { computed, useId } from 'vue'
import type { ListPagination } from '@/composables/list/useListPagination'
import DataState from './DataState.vue'
import LqPagination from './LqPagination.vue'

const titleId = `list-page-title-${useId()}`

const props = withDefaults(
  defineProps<{
    /* ---- Header ---- */
    title?: string
    description?: string
    showHeader?: boolean

    /* ---- DataState ---- */
    state?: ListPageLayoutState
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

    /* ---- Skeleton ---- */
    skeleton?: boolean
    skeletonVariant?: 'card' | 'list' | 'table' | 'default'
    skeletonRows?: number

    /* ---- Pagination ---- */
    pagination?: ListPagination
    paginationMode?: 'full' | 'compact'
    pageSizes?: number[]
    hideOnSinglePage?: boolean

    /* ---- Misc ---- */
    contentLabel?: string
    busy?: boolean
  }>(),
  {
    title: '',
    description: '',
    showHeader: false,
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
    skeletonVariant: 'table',
    skeletonRows: 8,
    paginationMode: 'full',
    pageSizes: () => [20, 50, 100],
    hideOnSinglePage: false,
    contentLabel: '',
    busy: false
  }
)

defineEmits<{
  retry: []
  pageChange: []
}>()

const showPagination = computed(() => {
  if (!props.pagination) return false
  if (props.hideOnSinglePage && props.pagination.pageInfo.total <= props.pagination.pageInfo.size) return false
  return true
})
</script>

<style scoped>
/* ==================== Layout Shell ==================== */
.list-page-layout {
  display: flex;
  flex-direction: column;
  gap: var(--space-section-gap);
  width: 100%;
  min-width: 0;
}

/* ==================== Header ====================
   页面头部外观统一由 styles/modules/list-page-surfaces.css 的
   .page-surface-header 维护，与 PageHeader 共用，此处不再重复定义（B5-B）。 */

/* ==================== Summary & Toolbar ==================== */
.list-page-layout__summary {
  min-width: 0;
}

.list-page-layout__notice {
  min-width: 0;
}

.list-page-layout__toolbar {
  position: relative;
  z-index: var(--z-base);
  min-width: 0;
}

/* ==================== Filter Chips ==================== */
.list-page-layout__filter-chips {
  min-width: 0;
}

/* ==================== Body ==================== */
.list-page-layout__body {
  flex: 1 0 auto;
  min-width: 0;
}

/* ==================== List Container ==================== */
.list-page-layout__list-container {
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  background: var(--color-surface);
  overflow: hidden;
}

/* ==================== List Header (view toggle + actions) ==================== */
.list-page-layout__list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap);
  padding: var(--space-control-gap-tight) var(--space-control-gap);
  border-bottom: 1px solid var(--color-border-lighter);
  background: var(--color-surface-alt);
}

.list-page-layout__list-actions {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap-tight);
  min-width: 0;
}

/* ==================== List Scroll Area ==================== */
.list-page-layout__list-scroll {
  overflow-x: auto;
  width: 100%;
}

/* ==================== Pagination Bar ==================== */
.list-page-layout__pagination-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: var(--space-control-gap-tight) var(--space-control-gap);
  border-top: 1px solid var(--color-border-lighter);
  background: var(--color-surface);
}

/* ==================== Responsive ==================== */
@media (max-width: 767px) {
  .list-page-layout {
    gap: var(--space-control-gap-wide);
  }

  /* 头部的窄屏断点同样由 .page-surface-header 统一维护，此处不再重复 */

  .list-page-layout__pagination-bar {
    justify-content: flex-start;
  }

  .list-page-layout__list-header {
    flex-wrap: wrap;
  }
}
</style>
