<template>
  <ElTooltip class="lq-tooltip" :content="content" :raw-content="rawContent" :placement="placement" :disabled="disabled"
    :visible="visible" :offset="offset" :show-arrow="showArrow" :transition="transition" :popper-class="popperClass"
    :show-after="showAfter" :hide-after="hideAfter" :auto-close="autoClose" :tabindex="tabindex" :effect="effect"
    :enterable="enterable" :hide-on-click="hideOnClick" :popper-options="popperOptions"
    @before-show="emit('before-show')" @before-hide="emit('before-hide')" @show="emit('show')" @hide="emit('hide')">
    <template v-if="$slots.content" #content>
      <slot name="content" />
    </template>
    <slot />
  </ElTooltip>
</template>

<script setup lang="ts">
import { ElTooltip } from 'element-plus'

export type TooltipPlacement =
  | 'top'
  | 'top-start'
  | 'top-end'
  | 'bottom'
  | 'bottom-start'
  | 'bottom-end'
  | 'left'
  | 'left-start'
  | 'left-end'
  | 'right'
  | 'right-start'
  | 'right-end'

export type TooltipEffect = 'dark' | 'light'

const props = withDefaults(
  defineProps<{
    /** 提示内容 */
    content?: string
    /** 是否渲染 HTML */
    rawContent?: boolean
    /** 出现位置 */
    placement?: TooltipPlacement
    /** 是否禁用 */
    disabled?: boolean
    /** 手动控制可见性（受控模式） */
    visible?: boolean
    /** 出现位置偏移量 */
    offset?: number
    /** 是否显示箭头 */
    showArrow?: boolean
    /** 过渡动画名 */
    transition?: string
    /** popper 自定义类 */
    popperClass?: string
    /** 延迟显示（毫秒） */
    showAfter?: number
    /** 延迟隐藏（毫秒） */
    hideAfter?: number
    /** 自动关闭延时（毫秒，0 表示不自动关闭） */
    autoClose?: number
    /** tabindex */
    tabindex?: number
    /** 主题：dark/light */
    effect?: TooltipEffect
    /** 鼠标是否可进入 tooltip */
    enterable?: boolean
    /** 点击是否隐藏 */
    hideOnClick?: boolean | 'toggle'
    /** popper.js 配置 */
    popperOptions?: Record<string, unknown>
  }>(),
  {
    content: '',
    rawContent: false,
    placement: 'top',
    disabled: false,
    visible: undefined,
    offset: 8,
    showArrow: true,
    transition: undefined,
    popperClass: '',
    showAfter: 150,
    hideAfter: 0,
    autoClose: 0,
    tabindex: 0,
    effect: 'dark',
    enterable: true,
    hideOnClick: true,
    popperOptions: undefined
  }
)

const emit = defineEmits<{
  (e: 'before-show'): void
  (e: 'before-hide'): void
  (e: 'show'): void
  (e: 'hide'): void
}>()

// props 在模板中使用，无需在 script 中显式引用
void props
</script>

<style scoped>
/* 收口 el-tooltip 主题色到设计令牌：暗色背景跟随 --color-tooltip-bg，文字跟随 --color-tooltip-text */
.lq-tooltip:deep(.el-popper) {
  background: var(--color-tooltip-bg);
  color: var(--color-tooltip-text);
  border: 1px solid var(--color-tooltip-border, transparent);
}

.lq-tooltip:deep(.el-popper.is-light) {
  background: var(--color-surface);
  color: var(--color-text-primary);
  border-color: var(--color-border-light);
}
</style>
