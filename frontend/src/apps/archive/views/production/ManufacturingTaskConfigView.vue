<template>
  <div class="task-config-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">MANUFACTURING MASTER</p>
        <h1>任务单产品与配置</h1>
        <p>维护各产线可下单的产品名称与可选配置。这里是新建生产任务单时的联动数据源，也是后续产品身份（可售库存口径）的锚点。</p>
      </div>
      <div class="head-actions">
        <el-button :icon="Refresh" @click="reload">刷新</el-button>
      </div>
    </header>

    <!-- 配置项维护内容自成一个组件：页面只提供外壳，能力扩展在组件内进行。
         组件挂载时取数，故用 key 重建来实现「刷新」。 -->
    <ManufacturingTaskConfigPanel :key="reloadKey" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import ManufacturingTaskConfigPanel from '@/components/production/ManufacturingTaskConfigPanel.vue'

/** 组件内为顶层 await 取数，无法从外部触发重新加载，故用重建的方式刷新。 */
const reloadKey = ref(0)
const reload = () => { reloadKey.value += 1 }
</script>

<style scoped>
.task-config-page { display: grid; gap: var(--space-section-gap-wide, 16px); padding: var(--space-2xl, 24px); min-width: 0; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-lg-xl, 16px); flex-wrap: wrap; }
.page-head h1 { margin: 2px 0 6px; font-size: 26px; line-height: 1.2; }
.page-head p { margin: 0; color: var(--color-text-secondary, #5f6f82); }
.eyebrow { font-size: 11px; letter-spacing: .16em; color: var(--color-primary, #0f766e) !important; font-weight: 700; margin: 0; }
.head-actions { display: flex; gap: var(--space-md-lg, 12px); flex-wrap: wrap; }

@media (max-width: 760px) {
  .task-config-page { padding: 14px; }
  .head-actions { width: 100%; }
}
</style>
