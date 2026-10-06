<template>
  <nav class="fulfillment-steps" aria-label="订单与生产交付流程">
    <template v-for="(step, index) in visibleSteps" :key="step.key">
      <span v-if="index" class="separator" aria-hidden="true">→</span>
      <RouterLink :to="step.to" class="step" :class="{ active: current === step.key }" :aria-current="current === step.key ? 'step' : undefined">
        <span class="number">{{ step.number }}</span>{{ step.label }}
      </RouterLink>
    </template>
    <small class="hint">确认前完成履约评审；确认后自产下达生产、外采办理采购，两条路径并行。现货直接出库；展会、展厅等任务可独立新建。</small>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useCurrentUser } from '@/composables/auth/useCurrentUser'

type Stage = 'order' | 'review' | 'demand' | 'task' | 'purchase' | 'outbound'
defineProps<{ current: Stage }>()

const auth = useCurrentUser()
const steps: Array<{ key: Stage; number: number; label: string; to: string; permissions: string[] }> = [
  { key: 'order', number: 1, label: '销售订单', to: '/sales-orders', permissions: ['sales_order.read.self', 'sales_order.read.department', 'sales_order.read.all'] },
  { key: 'review', number: 2, label: '履约评审', to: '/sales-fulfillment-reviews', permissions: ['manufacturing.manage', 'purchase_order.manage'] },
  { key: 'demand', number: 3, label: '待下达生产需求', to: '/production-demands', permissions: ['manufacturing.manage'] },
  { key: 'task', number: 4, label: '本地生产任务', to: '/local-manufacturing-execution', permissions: ['manufacturing.manage', 'manufacturing.report', 'manufacturing.inspect', 'manufacturing.report.override', 'manufacturing.receive', 'manufacturing.read.self', 'manufacturing.read.department', 'manufacturing.read.all'] },
  // 外采（BUY）与自产并行：自产走「待下达生产需求 → 本地生产任务」，外采走「采购订单 → 到货入库/代发」。
  { key: 'purchase', number: 5, label: '采购订单', to: '/purchase-orders', permissions: ['purchase_order.read.self', 'purchase_order.read.department', 'purchase_order.read.all'] },
  { key: 'outbound', number: 6, label: '销售出库', to: '/sales-outbounds', permissions: ['sales_order.read.self', 'sales_order.read.department', 'sales_order.read.all'] }
]
const visibleSteps = computed(() => steps.filter(step => step.permissions.some(permission => auth.can(permission))))
</script>

<style scoped>
.fulfillment-steps { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 18px; color: var(--el-text-color-secondary); }
.step { display: inline-flex; align-items: center; gap: 6px; padding: 7px 11px; border: 1px solid var(--el-border-color); border-radius: 8px; color: inherit; text-decoration: none; background: var(--el-bg-color); }
.step:hover { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); }
.step.active { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); font-weight: 600; }
.number { display: inline-grid; place-items: center; width: 18px; height: 18px; border-radius: 50%; background: var(--el-fill-color-dark); font-size: 11px; }
.active .number { color: #fff; background: var(--el-color-primary); }
.separator { color: var(--el-text-color-placeholder); }
.hint { flex-basis: 100%; color: var(--el-text-color-secondary); font-size: 12px; }
</style>
