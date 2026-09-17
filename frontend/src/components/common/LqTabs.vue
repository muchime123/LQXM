<script setup lang="ts">
/**
 * C14 LqTabs — el-tabs + el-tab-pane 封装
 *
 * 将 CompanyInfoEditorModal 中 5 处 :deep 样式穿透统一收口到组件内部 scoped 样式。
 * 支持 items prop 声明式渲染和默认 slot 自定义两种模式。
 */
import { ElTabs, ElTabPane } from 'element-plus'

export interface TabItem {
  label: string
  name: string
  disabled?: boolean
}

const props = withDefaults(
  defineProps<{
    /** v-model 绑定的 tab name */
    modelValue?: string
    /** 声明式 tab 配置 */
    items?: TabItem[]
    /** tab 位置 */
    position?: 'top' | 'left' | 'right' | 'bottom'
    /** 是否可关闭 */
    closable?: boolean
    /** 是否加卡片边框 */
    type?: '' | 'card' | 'border-card'
  }>(),
  {
    position: 'top',
    closable: false,
    type: ''
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  tabChange: [value: string]
}>()

function onTabChange(name: string | number) {
  const nameStr = String(name)
  emit('update:modelValue', nameStr)
  emit('tabChange', nameStr)
}
</script>

<template>
  <ElTabs
    :model-value="props.modelValue"
    :type="props.type || undefined"
    :tab-position="props.position"
    :closable="props.closable"
    class="lq-tabs"
    @tab-change="onTabChange"
  >
    <!-- 声明式渲染 -->
    <template v-if="items && items.length">
      <ElTabPane
        v-for="item in items"
        :key="item.name"
        :label="item.label"
        :name="item.name"
        :disabled="item.disabled"
      >
        <slot :name="item.name" />
      </ElTabPane>
    </template>
    <!-- 默认 slot 自定义 -->
    <template v-else>
      <slot />
    </template>
  </ElTabs>
</template>

<style scoped>
.lq-tabs {
  --el-tabs-header-height: 36px;
}

.lq-tabs :deep(.el-tabs__nav-wrap::after) {
  background-color: var(--color-border-light);
}

.lq-tabs :deep(.el-tabs__item) {
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-primary);
  transition: color 0.2s;
}

.lq-tabs :deep(.el-tabs__item.is-active) {
  color: var(--color-primary);
  font-weight: 600;
}

.lq-tabs :deep(.el-tabs__active-bar) {
  background-color: var(--color-primary);
}

.lq-tabs :deep(.el-tabs__content) {
  padding-top: var(--space-control-gap, 8px);
}
</style>
