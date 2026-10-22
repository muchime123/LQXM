<template>
  <section class="search-toolbar-batch" aria-label="批量管理操作">
    <span
      >批量管理 <strong aria-live="polite">{{ selectedCount }} / {{ modelsCount }}</strong></span
    >
    <em>点击列表项进行选择，当前范围：本页数据</em>
    <el-button size="small" @click="emit('toggle-select-all')">
      {{ isAllSelected ? '取消全选' : '全选当前页' }}
    </el-button>
    <slot />
    <el-button
v-if="showEdit"
size="small"
type="primary"
:disabled="selectedCount === 0"
@click="emit('edit')">
      批量编辑
    </el-button>
    <el-button
v-if="showRemove"
size="small"
type="danger"
plain
:disabled="selectedCount === 0"
@click="emit('remove')">
      批量删除所选
    </el-button>
    <el-button size="small" @click="emit('exit')">退出批量</el-button>
  </section>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    selectedCount?: number
    modelsCount?: number
    isAllSelected?: boolean
    /** 父级未监听 batch-edit 时不渲染内置按钮，避免点击后静默无响应 */
    showEdit?: boolean
    /** 父级未监听 batch-remove 时不渲染内置按钮 */
    showRemove?: boolean
  }>(),
  {
    selectedCount: 0,
    modelsCount: 0,
    isAllSelected: false,
    showEdit: true,
    showRemove: true
  }
)

const emit = defineEmits<{
  'toggle-select-all': []
  edit: []
  remove: []
  exit: []
}>()
</script>

<style scoped>
.search-toolbar-batch {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-control-gap);
  min-height: var(--control-height-default);
  box-sizing: border-box;
  padding-top: var(--space-control-gap);
  border-top: 1px dashed var(--color-border);
}

.search-toolbar-batch strong {
  font-weight: var(--fw-bold);
}
.search-toolbar-batch em {
  font-style: normal;
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
}

@media (max-width: 767px) {
  .search-toolbar-batch em {
    flex: 1 1 100%;
  }
}
</style>
