<template>
  <span v-if="salesperson?.trim()">{{ salesperson.trim() }}</span>
  <span v-else class="empty">—</span>
</template>

<script setup lang="ts">
/**
 * 下钻清单里的「业务员」单元格。
 *
 * <p>【为什么单独成组件】「生产交期达成分析」与「生产跟单周复盘」共用同一个下钻接口
 * （`/manufacturing/followups/analytics/orders`），两个抽屉各有一张表。
 * 单元格文案若各写一份，就会出现「一边显示 `—`、一边显示空白」这种同源不同显的问题。
 */

defineProps<{
  salesperson: string | null | undefined
}>()
</script>

<style scoped>
/* 空值与有值的文字要能一眼区分，否则「—」会被误读成实际内容。 */
.empty { color: #a7b4c1; }
</style>
