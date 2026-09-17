<template>
  <div class="lq-table-panel" :class="`lq-table-panel--${density}`">
    <el-table
      ref="tableRef"
      v-bind="$attrs"
      v-loading="loading"
      :data="data"
      :stripe="stripe"
      :border="border"
      :row-class-name="mergedRowClass"
      @selection-change="emit('selection-change', $event)"
      @sort-change="emit('sort-change', $event)"
      @row-click="emit('row-click', $event)"
      @row-dblclick="emit('row-dblclick', $event)"
    >
      <slot />

      <template v-if="$slots.append" #append>
        <slot name="append" />
      </template>

      <template #empty>
        <slot name="empty">
          <EmptyState :text="emptyText" :icon="emptyIcon" />
        </slot>
      </template>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElTable } from 'element-plus'
import type { TableInstance } from 'element-plus'
import EmptyState from './EmptyState.vue'

defineOptions({ inheritAttrs: false })

const props = withDefaults(
  defineProps<{
    data: any[]
    loading?: boolean
    stripe?: boolean
    border?: boolean
    /** 行密度：compact / default / comfortable，控制单元格纵向内边距 */
    density?: 'compact' | 'default' | 'comfortable'
    emptyText?: string
    emptyIcon?: string
    rowClassName?: string | ((data: { row: any; rowIndex: number }) => string)
  }>(),
  {
    loading: false,
    stripe: true,
    border: false,
    density: 'default',
    emptyText: '暂无数据',
    emptyIcon: undefined,
    rowClassName: undefined
  }
)

const emit = defineEmits<{
  'selection-change': [rows: any[]]
  'sort-change': [payload: { column: any; prop: string | null; order: 'ascending' | 'descending' | null }]
  'row-click': [row: any]
  'row-dblclick': [row: any]
}>()

const tableRef = ref<TableInstance>()

function mergedRowClass(data: { row: any; rowIndex: number }): string {
  if (typeof props.rowClassName === 'function') {
    return props.rowClassName(data)
  }
  return props.rowClassName ?? ''
}

defineExpose({
  clearSelection: () => tableRef.value?.clearSelection(),
  toggleRowSelection: (row: any, selected?: boolean) =>
    tableRef.value?.toggleRowSelection(row, selected),
  toggleAllSelection: () => tableRef.value?.toggleAllSelection(),
  toggleRowExpansion: (row: any, expanded?: boolean) =>
    tableRef.value?.toggleRowExpansion(row, expanded),
  setCurrentRow: (row?: any) => tableRef.value?.setCurrentRow(row),
  clearSort: () => tableRef.value?.clearSort(),
  clearFilter: (columnKey?: string) => tableRef.value?.clearFilter(columnKey),
  doLayout: () => tableRef.value?.doLayout(),
  sort: (prop: string, order: string) => tableRef.value?.sort(prop, order)
})
</script>

<style scoped>
/* ===== 列表页表格外壳：由 LqTable 统一接管结构样式 ===== */
.lq-table-panel {
  width: 100%;
  background: var(--color-surface);
  border: 1px solid var(--color-border-light);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.lq-table-panel :deep(.el-table) {
  font-size: var(--fs-sm);
  background: transparent;
}

/* 表头：nowrap 防止列名换行，统一表头行高。
   同时作用于 th 和内部 .cell div，防止 Element Plus 默认样式覆盖 */
.lq-table-panel :deep(.el-table th.el-table__cell),
.lq-table-panel :deep(.el-table th.el-table__cell .cell) {
  background: var(--color-surface-alt);
  color: var(--color-text-strong);
  font-weight: var(--fw-semibold);
  white-space: nowrap !important;
  overflow: visible !important;
  text-overflow: clip !important;
  line-height: 1.4;
  opacity: 1 !important;
  visibility: visible !important;
}

/* 表头 .cell 使用 flex 布局，确保文字与排序图标同行 */
.lq-table-panel :deep(.el-table th.el-table__cell .cell) {
  display: inline-flex !important;
  align-items: center !important;
  gap: 4px;
}

/* 表头排序图标：保持与文字同行，不换行 */
.lq-table-panel :deep(.el-table th.el-table__cell .sort-caret-wrapper) {
  display: inline-flex !important;
  align-items: center;
  flex-shrink: 0;
}

/* 斑马纹 */
.lq-table-panel :deep(.el-table--striped .el-table__body tr.el-table__row--striped td.el-table__cell) {
  background: var(--color-surface-raised);
}

/* hover 行 */
.lq-table-panel :deep(.el-table .el-table__row:hover > td.el-table__cell),
.lq-table-panel :deep(.el-table__body tr.hover-row > td.el-table__cell) {
  background: var(--color-surface-hover);
}

/* 当前选中行 */
.lq-table-panel :deep(.el-table__body tr.current-row > td.el-table__cell) {
  background: var(--color-primary-bg);
}

/* 固定列分隔线 */
.lq-table-panel :deep(.el-table__fixed::before),
.lq-table-panel :deep(.el-table__fixed-right::before) {
  background: var(--color-border-light);
}

/* ===== 密度：控制单元格纵向内边距 ===== */
.lq-table-panel--default :deep(.el-table .el-table__cell) {
  padding: var(--space-control-gap) var(--space-control-gap-wide);
}

.lq-table-panel--compact :deep(.el-table .el-table__cell) {
  padding: var(--space-control-gap-tight) var(--space-control-gap);
}

.lq-table-panel--comfortable :deep(.el-table .el-table__cell) {
  padding: var(--space-control-gap-wide) var(--space-section-gap);
}
</style>
