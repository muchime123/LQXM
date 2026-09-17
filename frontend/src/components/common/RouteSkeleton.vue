<!--
  RouteSkeleton - 路由级骨架屏
  根据 router.meta.layout 渲染与目标页面布局匹配的骨架，减少 Suspense fallback 期间的布局跳动。

  - 骨架形状由 meta.layout 决定，未声明时默认 list
  - prefers-reduced-motion: reduce 时静态渲染
  - 已有数据刷新不切回整页骨架屏

  使用方式：
    <RouterView v-slot="{ Component }">
      <Suspense>
        <component :is="Component" />
        <template #fallback>
          <RouteSkeleton :layout="currentLayout" />
        </template>
      </Suspense>
    </RouterView>
-->
<template>
  <div class="route-skeleton" :class="`route-skeleton--${layout}`" role="status" aria-live="polite" aria-label="页面加载中"
    data-testid="route-skeleton">
    <!-- 列表布局：工具栏 + 表格骨架 -->
    <template v-if="layout === 'list'">
      <div class="route-skeleton__header" />
      <div class="route-skeleton__toolbar">
        <div class="route-skeleton__toolbar-filters">
          <div class="route-skeleton__bar route-skeleton__bar--input" />
          <div class="route-skeleton__bar route-skeleton__bar--select" />
          <div class="route-skeleton__bar route-skeleton__bar--select" />
        </div>
        <div class="route-skeleton__toolbar-actions">
          <div class="route-skeleton__bar route-skeleton__bar--btn" />
          <div class="route-skeleton__bar route-skeleton__bar--btn" />
        </div>
      </div>
      <LqSkeleton variant="table" :rows="8" :columns="5" />
    </template>

    <!-- 工作台布局：左右分栏骨架 -->
    <template v-else-if="layout === 'workspace'">
      <div class="route-skeleton__header route-skeleton__header--workspace" />
      <div class="route-skeleton__workspace">
        <div class="route-skeleton__workspace-master">
          <div class="route-skeleton__toolbar">
            <div class="route-skeleton__toolbar-filters">
              <div class="route-skeleton__bar route-skeleton__bar--input" />
            </div>
            <div class="route-skeleton__toolbar-actions">
              <div class="route-skeleton__bar route-skeleton__bar--btn" />
            </div>
          </div>
          <LqSkeleton variant="list" :count="4" />
        </div>
        <div class="route-skeleton__workspace-detail">
          <div class="route-skeleton__detail-title" />
          <div v-for="i in 6" :key="i" class="route-skeleton__detail-row" />
        </div>
      </div>
    </template>

    <!-- 详情布局：字段块骨架 -->
    <template v-else-if="layout === 'detail'">
      <div class="route-skeleton__header" />
      <div class="route-skeleton__detail">
        <div class="route-skeleton__detail-title" />
        <div v-for="i in 6" :key="i" class="route-skeleton__detail-row" />
      </div>
    </template>

    <!-- 操作终端布局：步骤条 + 主区骨架 -->
    <template v-else-if="layout === 'operation'">
      <div class="route-skeleton__header" />
      <div class="route-skeleton__stepper">
        <div v-for="i in 3" :key="i" class="route-skeleton__step">
          <div class="route-skeleton__step-circle" />
          <div class="route-skeleton__step-label" />
        </div>
      </div>
      <LqSkeleton :rows="10" />
    </template>

    <!-- 默认布局：通用 LoadingState -->
    <template v-else>
      <LoadingState text="加载中..." />
    </template>
  </div>
</template>

<script setup lang="ts">
import LoadingState from './LoadingState.vue'
import LqSkeleton from './LqSkeleton.vue'

withDefaults(
  defineProps<{
    /** 路由布局类型，对应 router.meta.layout */
    layout?: 'list' | 'workspace' | 'detail' | 'operation' | 'default' | 'auth'
  }>(),
  {
    layout: 'list'
  }
)
</script>

<style scoped>
.route-skeleton {
  display: flex;
  flex-direction: column;
  gap: var(--space-section-gap);
  width: 100%;
  min-height: 200px;
  padding: var(--page-block-padding) var(--page-inline-padding);
}

/* 页头骨架 */
.route-skeleton__header {
  height: 76px;
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  background: linear-gradient(135deg, var(--color-surface) 0%, var(--color-surface-alt) 100%);
  box-shadow: var(--shadow-sm);
}

.route-skeleton__header--workspace {
  height: 60px;
}

/* 工具栏骨架 */
.route-skeleton__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-control-gap);
  min-height: var(--toolbar-min-height);
  padding: var(--space-sm-md) var(--space-md-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
}

.route-skeleton__toolbar-filters,
.route-skeleton__toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-control-gap);
}

.route-skeleton__bar {
  height: var(--control-height-default);
  border-radius: var(--radius-md);
  background: var(--color-surface-hover);
}

.route-skeleton__bar--input {
  width: 200px;
}

.route-skeleton__bar--select {
  width: 140px;
}

.route-skeleton__bar--btn {
  width: 80px;
}

/* 工作台分栏 */
.route-skeleton__workspace {
  display: grid;
  grid-template-columns: 360px 1fr;
  gap: var(--space-section-gap);
  align-items: start;
}

.route-skeleton__workspace-master {
  display: flex;
  flex-direction: column;
  gap: var(--space-section-gap);
}

.route-skeleton__workspace-detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-form-item-gap);
  padding: var(--space-lg-xl);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
}

/* 详情字段块 */
.route-skeleton__detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-form-item-gap);
  padding: var(--space-lg-xl);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
}

.route-skeleton__detail-title {
  height: 24px;
  width: 40%;
  margin-bottom: var(--space-sm-md);
  border-radius: var(--radius-sm);
  background: var(--color-surface-hover);
}

.route-skeleton__detail-row {
  height: 20px;
  width: 100%;
  border-radius: var(--radius-sm);
  background: var(--color-surface-hover);
}

.route-skeleton__detail-row:nth-child(even) {
  width: 85%;
}

/* 步骤条骨架 */
.route-skeleton__stepper {
  display: flex;
  align-items: center;
  gap: var(--space-section-gap-wide);
  padding: var(--space-lg-xl) var(--space-md-lg);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-xl);
  background: var(--color-surface);
}

.route-skeleton__step {
  display: flex;
  align-items: center;
  gap: var(--space-control-gap);
}

.route-skeleton__step-circle {
  width: 28px;
  height: 28px;
  border-radius: var(--radius-pill);
  background: var(--color-surface-hover);
}

.route-skeleton__step-label {
  height: 16px;
  width: 80px;
  border-radius: var(--radius-sm);
  background: var(--color-surface-hover);
}

/* 响应式 */
@media (max-width: 767px) {
  .route-skeleton {
    padding: var(--space-md-lg);
  }

  .route-skeleton__workspace {
    grid-template-columns: 1fr;
  }

  .route-skeleton__toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .route-skeleton__toolbar-filters,
  .route-skeleton__toolbar-actions {
    justify-content: flex-start;
  }
}

/* 骨架脉冲动画 */
.route-skeleton__bar,
.route-skeleton__detail-title,
.route-skeleton__detail-row,
.route-skeleton__step-circle,
.route-skeleton__step-label {
  animation: route-skeleton-pulse 1.4s ease-in-out infinite;
}

@keyframes route-skeleton-pulse {

  0%,
  100% {
    opacity: 0.5;
  }

  50% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {

  .route-skeleton__bar,
  .route-skeleton__detail-title,
  .route-skeleton__detail-row,
  .route-skeleton__step-circle,
  .route-skeleton__step-label {
    animation: none;
  }
}
</style>
