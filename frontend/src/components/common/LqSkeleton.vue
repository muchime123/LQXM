<!--
  LqSkeleton - 统一骨架屏组件
  基于 Element Plus el-skeleton 封装，提供卡片、列表、表格三种预设布局。
  骨架屏形状接近最终布局，减少内容出现时的布局跳动。

  - 不可量化等待使用骨架屏，不伪造进度
  - 已有数据刷新不切回整页骨架屏

  使用方式：
    <LqSkeleton variant="card" :count="3" />
    <LqSkeleton variant="list" :count="5" />
    <LqSkeleton variant="table" :rows="8" />
    <LqSkeleton :loading="isLoading"><MyContent /></LqSkeleton>
-->
<template>
  <template v-if="!loading && $slots.default">
    <slot />
  </template>
  <div
v-else
class="lq-skeleton"
:class="`lq-skeleton--${variant}`"
role="status"
aria-live="polite"
aria-label="加载中">
    <!-- 卡片预设 -->
    <template v-if="variant === 'card'">
      <div v-for="i in count" :key="i" class="lq-skeleton__card">
        <el-skeleton-item variant="rect" style="width: 100%; height: 140px; border-radius: var(--radius-md)" />
        <div class="lq-skeleton__card-body">
          <el-skeleton-item variant="h3" style="width: 60%" />
          <el-skeleton-item variant="text" style="width: 90%" />
          <el-skeleton-item variant="text" style="width: 75%" />
          <div class="lq-skeleton__card-footer">
            <el-skeleton-item variant="text" style="width: 40px" />
            <el-skeleton-item variant="text" style="width: 40px" />
          </div>
        </div>
      </div>
    </template>

    <!-- 列表预设 -->
    <template v-else-if="variant === 'list'">
      <div v-for="i in count" :key="i" class="lq-skeleton__list-row">
        <el-skeleton-item variant="circle" style="width: 36px; height: 36px" />
        <div class="lq-skeleton__list-body">
          <el-skeleton-item variant="h3" style="width: 50%" />
          <el-skeleton-item variant="text" style="width: 80%" />
        </div>
        <el-skeleton-item variant="text" style="width: 60px" />
      </div>
    </template>

    <!-- 表格预设 -->
    <template v-else-if="variant === 'table'">
      <div class="lq-skeleton__table-header">
        <el-skeleton-item
v-for="i in tableCols"
:key="i"
variant="text"
:style="{ width: `calc(100% / ${tableCols} - 12px)` }" />
      </div>
      <div v-for="r in rows" :key="r" class="lq-skeleton__table-row">
        <el-skeleton-item
v-for="c in tableCols"
:key="c"
variant="text"
:style="{ width: `calc(100% / ${tableCols} - 12px)` }" />
      </div>
    </template>

    <!-- 自定义预设 -->
    <template v-else>
      <el-skeleton :rows="rows" :animated="animated" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    /** 骨架屏预设类型 */
    variant?: 'card' | 'list' | 'table' | 'default'
    /** 卡片/列表预设的数量 */
    count?: number
    /** 表格预设的行数 */
    rows?: number
    /** 表格预设的列数 */
    columns?: number
    /** 是否显示动画 */
    animated?: boolean
    /** 是否处于加载状态（false 时渲染默认插槽内容） */
    loading?: boolean
  }>(),
  {
    variant: 'default',
    count: 3,
    rows: 5,
    columns: 4,
    animated: true,
    loading: true
  }
)

const tableCols = computed(() => Math.max(1, props.columns))
</script>

<style scoped>
.lq-skeleton {
  width: 100%;
}

/* 卡片预设 */
.lq-skeleton--card {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: var(--space-xl-2xl);
}

.lq-skeleton__card {
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  overflow: hidden;
  background: var(--color-surface);
}

.lq-skeleton__card-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm-md);
  padding: var(--space-lg-xl);
}

.lq-skeleton__card-footer {
  display: flex;
  gap: var(--space-md-lg);
  margin-top: var(--space-sm-md);
}

/* 列表预设 */
.lq-skeleton--list {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg-xl);
}

.lq-skeleton__list-row {
  display: flex;
  align-items: center;
  gap: var(--space-md-lg);
}

.lq-skeleton__list-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  min-width: 0;
}

/* 表格预设 */
.lq-skeleton--table {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm-md);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-md);
  padding: var(--space-lg-xl);
}

.lq-skeleton__table-header {
  display: flex;
  gap: var(--space-md-lg);
  padding-bottom: var(--space-md-lg);
  border-bottom: 1px solid var(--color-border-lighter);
}

.lq-skeleton__table-row {
  display: flex;
  gap: var(--space-md-lg);
  padding: var(--space-xs) 0;
}
</style>
