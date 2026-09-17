<template>
  <el-pagination
    ref="paginationRef"
    v-model:current-page="currentPage"
    v-model:page-size="pageSize"
    class="lq-pagination"
    :class="`lq-pagination--${mode}`"
    :total="pagination.pageInfo.total"
    :page-sizes="pageSizes"
    :layout="layout"
    :pager-count="pagerCount"
    :disabled="disabled"
    :hide-on-single-page="hideOnSinglePage"
    background
    @current-change="handleCurrentChange"
    @size-change="handleSizeChange"
  />
</template>

<script setup lang="ts">
import type { ListPagination } from '@/composables/list/useListPagination'
import { computed, nextTick, onMounted, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    pagination: ListPagination
    mode?: 'full' | 'compact'
    pageSizes?: number[]
    pagerCount?: number
    disabled?: boolean
    hideOnSinglePage?: boolean
  }>(),
  {
    mode: 'full',
    pageSizes: () => [20, 50, 100],
    pagerCount: 7,
    disabled: false,
    hideOnSinglePage: false
  }
)

const emit = defineEmits<{ change: [] }>()
const paginationRef = ref<{ $el?: HTMLElement } | null>(null)
const layout = computed(() => (props.mode === 'compact' ? 'prev, pager, next' : 'total, sizes, prev, pager, next'))
const currentPage = computed({
  get: () => props.pagination.pageInfo.page + 1,
  set: page => props.pagination.onPageChange(page)
})
const pageSize = computed({
  get: () => props.pagination.pageInfo.size,
  set: size => props.pagination.onPageSizeChange(size)
})

function handleCurrentChange() {
  emit('change')
}

function handleSizeChange() {
  emit('change')
}

onMounted(async () => {
  await nextTick()
  const root = paginationRef.value?.$el
  if (!root || typeof root.querySelector !== 'function') return
  root.querySelector<HTMLInputElement>('.el-pagination__sizes .el-select__input')
    ?.setAttribute('aria-label', '每页显示数量')
})
</script>
