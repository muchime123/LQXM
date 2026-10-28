<template>
  <el-drawer :model-value="modelValue" size="90%" :with-header="false" append-to-body @update:model-value="emit('update:modelValue', $event)">
  <main class="tracking-node-analytics">
    <header class="node-head">
      <div><h1>节点完成率 · 排产等待分析</h1><p>按跟单员跟单覆盖的任务统计各节点完成情况与排产等待。节点动态取自制程表，各车间各自展示，不写死序列。</p></div>
      <div class="node-head-actions"><el-button @click="refresh">刷新数据</el-button><el-button aria-label="关闭节点分析" @click="emit('update:modelValue', false)">关闭</el-button></div>
    </header>
    <TrackingNodeAnalytics ref="contentRef" @open-order="id => emit('open-order', id)" />
  </main>
  </el-drawer>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import TrackingNodeAnalytics from '@/components/production/TrackingNodeAnalytics.vue'

/**
 * 节点完成率 · 排产等待分析抽屉：与「生产分析」「周复盘」同一种抽屉形态（size 90%、无默认头部、
 * 自绘 h1 头部 + 刷新/关闭），交互也同构——先看聚合，点节点/等待天数才下钻工单清单，点任务行打开跟单详情。
 */
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; 'open-order': [id: number] }>()

const contentRef = ref<InstanceType<typeof TrackingNodeAnalytics> | null>(null)
function refresh() { void contentRef.value?.load() }

// 与生产分析 / 周复盘抽屉一致：每次打开都重新取数，避免拿旧数据当当前结论。
watch(() => props.modelValue, open => { if (open) void refresh() }, { immediate: true })
</script>

<style scoped>
.tracking-node-analytics { padding: 8px 12px 28px; color: #263448; }
.node-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.node-head h1 { margin: 8px 0 6px; font-size: 24px; }
.node-head p { margin: 0; color: #64748b; }
.node-head-actions { display: flex; gap: 8px; }
</style>
