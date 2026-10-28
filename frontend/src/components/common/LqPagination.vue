<template>
  <el-pagination
    ref="paginationRef"
    v-model:current-page="currentPage"
    v-model:page-size="pageSize"
    class="lq-pagination"
    :class="`lq-pagination--${mode}`"
    :total="total"
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

/**
 * LqPagination —— 两种状态接入方式，共用一套视觉样式与用户操作行为。
 *
 * 模式 A · 分页对象模式（现有 15 个调用方，行为与改造前完全一致）
 *   <LqPagination :pagination="pagination" @change="load()" />
 *   pagination 为 useListPagination() 的返回值；0/1 基换算与状态写入
 *   仍委托给 pagination.onPageChange / onPageSizeChange。
 *
 * 模式 B · 独立状态模式（B2 新增，面向拥有独立分页状态的页面）
 *   <LqPagination
 *     v-model:page="page"        <!-- 0 基，与后端 page 参数同语义 -->
 *     v-model:page-size="size"
 *     :total="total"
 *     @change="load()"
 *   />
 *   组件内部负责 +1 显示 / -1 回写，调用方始终面对 0 基页码。
 *
 * 统一规则
 *   - 模式判定唯一：传入 pagination → 模式 A；否则 → 模式 B（A 优先，无歧义）。
 *   - 0/1 基转换固定在组件内部，不扩散到调用方。
 *   - **请求触发只有 @change**：update:page / update:pageSize 只同步状态，
 *     不触发请求，因此一次用户操作不会产生重复请求。
 *   - page-change / size-change 为模式 B 的可选细分信号（0 基），
 *     与兼容事件 change 并存；模式 A 不派发，避免旧调用方误触发。
 */

const props = withDefaults(
  defineProps<{
    /** 模式 A：useListPagination() 返回值。传入时优先于 page/pageSize/total */
    pagination?: ListPagination
    /** 模式 B：总条数 */
    total?: number
    /** 模式 B：当前页码，0 基 */
    page?: number
    /** 模式 B：每页条数 */
    pageSize?: number
    mode?: 'full' | 'compact'
    pageSizes?: number[]
    pagerCount?: number
    disabled?: boolean
    hideOnSinglePage?: boolean
  }>(),
  {
    total: 0,
    page: 0,
    pageSize: 20,
    mode: 'full',
    pageSizes: () => [20, 50, 100],
    pagerCount: 7,
    disabled: false,
    hideOnSinglePage: false
  }
)

const emit = defineEmits<{
  /** 唯一的「业务数据重新加载」信号，两种模式语义一致 */
  change: []
  /** 模式 B：页码同步，0 基 */
  'update:page': [page: number]
  /** 模式 B：每页条数同步 */
  'update:pageSize': [size: number]
  /** 模式 B：细分事件，0 基页码 */
  'page-change': [page: number]
  /** 模式 B：细分事件，每页条数 */
  'size-change': [size: number]
}>()

const paginationRef = ref<{ $el?: HTMLElement } | null>(null)
const layout = computed(() => (props.mode === 'compact' ? 'prev, pager, next' : 'total, sizes, prev, pager, next'))

const total = computed(() => (props.pagination ? props.pagination.pageInfo.total : props.total))

const currentPage = computed({
  get: () => (props.pagination ? props.pagination.pageInfo.page : Math.max(0, props.page)) + 1,
  set: (page: number) => {
    if (props.pagination) {
      props.pagination.onPageChange(page)
      return
    }
    emit('update:page', Math.max(0, page - 1))
  }
})

const pageSize = computed({
  get: () => (props.pagination ? props.pagination.pageInfo.size : props.pageSize),
  set: (size: number) => {
    if (props.pagination) {
      props.pagination.onPageSizeChange(size)
      return
    }
    emit('update:pageSize', size)
    // 改每页条数时页码归零，与模式 A 的 onPageSizeChange 语义一致
    emit('update:page', 0)
  }
})

function handleCurrentChange(page: number) {
  emit('change')
  if (!props.pagination) emit('page-change', Math.max(0, page - 1))
}

function handleSizeChange(size: number) {
  emit('change')
  if (!props.pagination) emit('size-change', size)
}

onMounted(async () => {
  await nextTick()
  const root = paginationRef.value?.$el
  if (!root || typeof root.querySelector !== 'function') return
  root.querySelector<HTMLInputElement>('.el-pagination__sizes .el-select__input')
    ?.setAttribute('aria-label', '每页显示数量')
})
</script>
