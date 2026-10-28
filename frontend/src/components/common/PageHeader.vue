<!--
  PageHeader - 页面级头部
  外观统一由 styles/modules/list-page-surfaces.css 的 .page-surface-header 维护，
  与 ListPageLayout 的头部共用同一份公共样式（B5-B：二者此前各自维护重复外观）。
  本组件只负责结构与插槽，不再自带 duplicated scoped 外观样式。
-->
<template>
  <header class="page-surface-header" :aria-labelledby="titleId">
    <div class="page-surface-header__title">
      <slot name="eyebrow"></slot>
      <h1 :id="titleId">{{ title }}</h1>
      <slot name="description">
        <p v-if="description">{{ description }}</p>
      </slot>
    </div>
    <div v-if="$slots.actions" class="page-surface-header__actions">
      <slot name="actions"></slot>
    </div>
  </header>
</template>

<script setup lang="ts">
import { useId } from 'vue'

const titleId = `page-title-${useId()}`

withDefaults(
  defineProps<{
    title: string
    description?: string
  }>(),
  {
    description: ''
  }
)
</script>
