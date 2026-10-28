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
          <EmptyState :text="emptyText" :icon="emptyIcon" :hint="emptyHint" :size="emptySize" />
        </slot>
      </template>
    </el-table>
  </div>
</template>

<script setup lang="ts" generic="T extends Record<PropertyKey, any> = any">
/**
 * LqTable —— 业务列表表格的统一入口。
 *
 * ## 能力矩阵
 *
 * ### 1. 已声明的 props（有类型提示，走显式绑定）
 * data / loading / stripe / border / density / emptyText / emptyIcon /
 * emptyHint / emptySize / rowClassName
 *
 * ### 2. 已声明的 emits（单次派发，不会二次触发）
 * selection-change / sort-change / row-click / row-dblclick
 *
 * ### 3. 经 `$attrs` 透传给 el-table 的能力（**不要为它们声明 props / emits**）
 * 属性：row-key、max-height、height、span-method、default-sort、show-summary、
 *       tree-props、fit、size、highlight-current-row、tooltip-effect …
 * 事件：expand-change、select、select-all、filter-change、
 *       current-change、header-click、cell-click …
 *
 * > 透传靠 `inheritAttrs: false` + `v-bind="$attrs"`（见 defineOptions 与 template）。
 * > **条件式规则**：若将来为上面任一项显式声明 prop / emit，必须**同步实现向内部
 * > el-table 的转发**，并让 `LqTable.attrs.test.ts` 的透传用例继续通过；否则该项会
 * > 从 `$attrs` 移除并静默失效。判据是「声明后行为是否与现状一致」，不是「有没有声明」。
 *
 * ### 4. 插槽
 * default（表格列）/ empty（覆盖空态）/ append（表尾追加）
 *
 * ### 5. 暴露的实例方法
 * clearSelection / toggleRowSelection / toggleAllSelection / toggleRowExpansion /
 * setCurrentRow / clearSort / clearFilter / doLayout / sort
 *
 * ### 6. 泛型
 * `generic="T = any"`：`data` 与行事件载荷随 `T` 推导；未推导出来时退化为 `any`，
 * 与改造前行为一致，因此现有调用方零改动。
 *
 * 使用规范见 docs/frontend-ui-lqtable-usage.md
 */
import { ref } from 'vue'
import { ElTable } from 'element-plus'
import type { TableInstance } from 'element-plus'
import EmptyState from './EmptyState.vue'

defineOptions({ inheritAttrs: false })

/** sort-change 载荷。`column` 保留宽松类型：Element Plus 未公开稳定的列上下文类型。 */
interface SortChangePayload {
  column: any
  prop: string | null
  order: 'ascending' | 'descending' | null
}

const props = withDefaults(
  defineProps<{
    data: T[]
    loading?: boolean
    stripe?: boolean
    border?: boolean
    /** 行密度：compact / default / comfortable，控制单元格纵向内边距 */
    density?: 'compact' | 'default' | 'comfortable'
    emptyText?: string
    emptyIcon?: string
    /** 空态补充提示，透传给 EmptyState.hint；不传则不渲染提示行 */
    emptyHint?: string
    /** 空态尺寸，透传给 EmptyState.size（sm / md / lg）；不传时为 md */
    emptySize?: 'sm' | 'md' | 'lg'
    rowClassName?: string | ((data: { row: T; rowIndex: number }) => string)
  }>(),
  {
    loading: false,
    stripe: true,
    border: false,
    density: 'default',
    emptyText: '暂无数据',
    emptyIcon: undefined,
    emptyHint: undefined,
    emptySize: undefined,
    rowClassName: undefined
  }
)

const emit = defineEmits<{
  'selection-change': [rows: T[]]
  'sort-change': [payload: SortChangePayload]
  'row-click': [row: T]
  'row-dblclick': [row: T]
}>()

const tableRef = ref<TableInstance>()

function mergedRowClass(data: { row: T; rowIndex: number }): string {
  if (typeof props.rowClassName === 'function') {
    return props.rowClassName(data)
  }
  return props.rowClassName ?? ''
}

defineExpose({
  clearSelection: () => tableRef.value?.clearSelection(),
  toggleRowSelection: (row: T, selected?: boolean) =>
    tableRef.value?.toggleRowSelection(row, selected),
  toggleAllSelection: () => tableRef.value?.toggleAllSelection(),
  toggleRowExpansion: (row: T, expanded?: boolean) =>
    tableRef.value?.toggleRowExpansion(row, expanded),
  setCurrentRow: (row?: T) => tableRef.value?.setCurrentRow(row),
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
