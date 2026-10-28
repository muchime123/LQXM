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
 * <p>规格本身已由后端按「尺寸 / 形状 / 材质」拼好；**这里只保留尺寸段**（第一段），
 * 形状与材质不在列表展示（完整规格仍可在详情与导出看到），再把「模具出数（一出几）」连上。
 * 任一段缺失时不留孤零零的分隔符。
 */
const props = defineProps<{
  specification: string | null | undefined
  moldLayout: string | null | undefined
}>()

const line = computed(() => {
  // specification 形如「250 / 圆形 / 铝箔」，取第一段作为尺寸；模具出数单独显示。
  const size = props.specification?.split('/')[0]?.trim()
  return [size, props.moldLayout?.trim()].filter(Boolean).join(' / ')
})
</script>

<style scoped>
.empty { color: #a7b4c1; }
</style>
