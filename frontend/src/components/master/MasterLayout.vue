<template>
  <section class="catalog-view catalog-workbench" :class="pageClass">
    <PageHeader v-if="showHeader" :title="title" :description="description" />
    <slot name="toolbar" />
    <section class="catalog-shell" :class="{ 'is-workbench': isWorkbench }">
      <slot name="list" />
      <slot name="detail" />
      <slot name="empty" />
    </section>
    <slot name="modals" />
    <div v-if="globalLoading" class="global-loading-overlay">
      <div class="global-loading-spinner"></div>
      <span>{{ loadingText }}</span>
      <slot name="loading-extra" />
    </div>
  </section>
</template>

<script setup lang="ts">
import PageHeader from '../common/PageHeader.vue'

withDefaults(
  defineProps<{
    title: string
    description: string
    /** 是否显示内容区页面标题；页面说明可由主导航承载时可关闭。 */
    showHeader?: boolean
    /** 是否启用左右分栏（左列表 + 右详情）。false 时列表全宽、详情走抽屉/弹窗。 */
    isWorkbench?: boolean
    /** 附加到根节点的类名，用于页面级 CSS 变量覆盖（如 --master-list-col） */
    pageClass?: string
    globalLoading?: boolean
    loadingText?: string
  }>(),
  {
    showHeader: false,
    isWorkbench: false,
    pageClass: '',
    globalLoading: false,
    loadingText: '正在处理...'
  }
)
</script>
