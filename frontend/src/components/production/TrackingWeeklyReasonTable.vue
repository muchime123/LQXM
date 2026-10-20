<template>
  <el-table :data="items" stripe :empty-text="emptyText">
    <el-table-column label="原因类型" width="130">
      <template #default="{ row }"><button class="reason-link" @click="select(row)">{{ row.label }}</button></template>
    </el-table-column>
    <el-table-column label="订单数量" width="110">
      <template #default="{ row }"><button class="reason-link" @click="select(row)">{{ row.count }} 单</button></template>
    </el-table-column>
    <el-table-column label="占比" width="240">
      <template #default="{ row }">
        <div class="rate-cell">
          <div class="rate-track"><b :style="{ width: barWidth(row.count, total) }" /></div>
          <span>{{ percent(row.rate) }}</span>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="跟单员填写的原文" min-width="220">
      <template #default="{ row }">
        <span v-if="!row.samples.length" class="muted">—</span>
        <span v-else>{{ row.samples.join('；') }}</span>
      </template>
    </el-table-column>
  </el-table>
</template>

<script setup lang="ts">
import type { TrackingReasonCategory } from '@/api/productionTracking'

/**
 * 周复盘看板的原因分类表（逾期、延期两块共用，只有下钻的 bucket 不同）。
 *
 * 【为什么单独抽出】两张表结构完全一样，区别只是下钻时用的 bucket 与总量；
 * 抽成一个组件，改样式只改一处，也避免「逾期表能下钻、延期表忘了接」这类漏接。
 */
const props = defineProps<{
  items: TrackingReasonCategory[]
  /** 占比的分母（逾期总数 / 延期总数），与周报「合计」是同一个数。 */
  total: number
  bucket: string
  emptyText: string
}>()

const emit = defineEmits<{ select: [bucket: string, category: TrackingReasonCategory] }>()

const percent = (value: number | null) => value === null ? '样本不足' : `${value.toFixed(1)}%`
const barWidth = (count: number, total: number) => `${total ? count * 100 / total : 0}%`
const select = (row: TrackingReasonCategory) => emit('select', props.bucket, row)
</script>

<style scoped>
.reason-link { border: 0; background: none; padding: 0; color: #087c72; cursor: pointer; font: inherit; text-align: left; }
.reason-link:hover { text-decoration: underline; }
.rate-cell { display: flex; align-items: center; gap: 10px; }
.rate-track { flex: 1; min-width: 80px; height: 8px; border-radius: 6px; background: #e9eef1; overflow: hidden; }
.rate-track b { display: block; height: 100%; background: #db9869; }
.muted { color: #94a3b8; }
</style>
