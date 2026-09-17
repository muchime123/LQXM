<script setup lang="ts">
/**
 * C18 LqTimeline — el-timeline + el-timeline-item 封装
 *
 * 支持 items prop 声明式渲染和默认 slot 两种模式。
 */
import { ElTimeline, ElTimelineItem } from 'element-plus'

export interface TimelineItemData {
  content: string
  timestamp?: string
  type?: 'primary' | 'success' | 'warning' | 'danger' | 'info'
  placement?: 'top' | 'bottom'
  hollow?: boolean
}

withDefaults(
  defineProps<{
    /** 声明式时间线条目 */
    items?: TimelineItemData[]
    /** 是否倒序 */
    reverse?: boolean
  }>(),
  {
    reverse: false
  }
)
</script>

<template>
  <ElTimeline class="lq-timeline" :reverse="reverse">
    <!-- 声明式渲染 -->
    <template v-if="items && items.length">
      <ElTimelineItem
        v-for="(item, idx) in items"
        :key="idx"
        :timestamp="item.timestamp"
        :type="item.type"
        :placement="item.placement || 'top'"
        :hollow="item.hollow"
      >
        {{ item.content }}
      </ElTimelineItem>
    </template>
    <!-- 默认 slot -->
    <template v-else>
      <slot />
    </template>
  </ElTimeline>
</template>

<style scoped>
.lq-timeline {
  padding-left: var(--space-control-gap, 8px);
}
</style>
