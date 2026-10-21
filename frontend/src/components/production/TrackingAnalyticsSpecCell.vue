<template>
  <span v-if="line">{{ line }}</span>
  <span v-else class="empty">—</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 下钻清单里的「产品规格 / 模具出数」单元格 —— **一行展示**（LQ 2026-10-21：不分两行，列宽给够）。
 *
 * <p>【为什么单独成组件】「生产交期达成分析」与「生产跟单周复盘」共用同一个下钻接口，
 * 两边都挂这一列。拼装规则若各写一份，就会一边带分隔符、一边不带。
 *
 * <p>规格本身已由后端按「尺寸 / 形状 / 材质」拼好（与生产跟单列表同一口径），前端不再拆段；
 * 这里只负责把「规格」和「模具出数」用「 / 」连起来，任一段缺失时不留孤零零的分隔符。
 */
const props = defineProps<{
  specification: string | null | undefined
  moldLayout: string | null | undefined
}>()

const line = computed(() =>
  [props.specification?.trim(), props.moldLayout?.trim()].filter(Boolean).join(' / ')
)
</script>

<style scoped>
.empty { color: #a7b4c1; }
</style>
