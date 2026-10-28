<template>
  <div class="tracking-drill-empty">
    <div class="tracking-drill-empty-icon">◈</div>
    <p>{{ text }}</p>
    <small v-if="subtext">{{ subtext }}</small>
  </div>
</template>

<script setup lang="ts">
/**
 * 分析/复盘右栏下钻面板的空态占位 —— 三个抽屉（生产分析 / 周复盘 / 节点分析）共用。
 *
 * 【为什么单独一个组件】三处空态原本各自写 `<div class="xxx-drill-empty">◈ + 提示</div>`，
 * 结构与样式同构、只有文案不同；抽成一个组件后，改图标/样式只改一处，文案仍可按抽屉覆盖。
 */
withDefaults(defineProps<{ text?: string; subtext?: string }>(), {
  text: '点击左侧指标查看对应工单'
})
</script>

<style scoped>
.tracking-drill-empty {
  border: 1px dashed #cdd7e2;
  border-radius: 12px;
  background: #fbfcfe;
  color: #94a3b8;
  text-align: center;
  padding: 44px 16px;
}
.tracking-drill-empty-icon {
  font-size: 28px;
  color: #c3d0dc;
  margin-bottom: 8px;
}
.tracking-drill-empty p {
  margin: 0;
  line-height: 1.8;
}
.tracking-drill-empty small {
  display: block;
  margin-top: 6px;
  color: #b2bcc8;
  line-height: 1.6;
}
</style>
