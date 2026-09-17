<template>
  <el-dropdown :trigger="trigger" :placement="placement" :disabled="disabled" :teleported="teleported"
    @command="handleCommand" @visible-change="onVisibleChange">
    <span class="lq-dropdown-trigger" :class="{ 'lq-dropdown-trigger--disabled': disabled }">
      <slot name="trigger">
        <button class="lq-dropdown-btn" type="button" :disabled="disabled">
          {{ triggerText }}
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"
            stroke-linejoin="round" width="14" height="14" class="lq-dropdown-arrow" :class="{ 'is-open': visible }">
            <polyline points="6 9 12 15 18 9" />
          </svg>
        </button>
      </slot>
    </span>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="item in items" :key="item.command" :command="item.command" :disabled="item.disabled"
          :divided="item.divided" :class="item.type === 'danger' ? 'lq-dropdown-item--danger' : undefined">
          <span v-if="item.icon" class="lq-dropdown-item-icon" v-html="item.icon"></span>
          {{ item.label }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElDropdown, ElDropdownMenu, ElDropdownItem } from 'element-plus'

export interface DropdownItem {
  command: string | number
  label: string
  icon?: string
  disabled?: boolean
  divided?: boolean
  /** 视觉类型：default 默认 / danger 危险操作（红色文字） */
  type?: 'default' | 'danger'
}

withDefaults(
  defineProps<{
    items: DropdownItem[]
    trigger?: 'hover' | 'click' | 'contextmenu'
    placement?:
    | 'auto'
    | 'auto-start'
    | 'auto-end'
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
    disabled?: boolean
    teleported?: boolean
    triggerText?: string
  }>(),
  {
    trigger: 'click',
    placement: 'bottom-end',
    disabled: false,
    teleported: true,
    triggerText: '更多操作'
  }
)

const emit = defineEmits<{
  command: [command: string | number]
  visibleChange: [visible: boolean]
}>()

const visible = ref(false)

function handleCommand(command: string | number) {
  emit('command', command)
}

function onVisibleChange(val: boolean) {
  visible.value = val
  emit('visibleChange', val)
}
</script>

<style scoped>
.lq-dropdown-trigger {
  display: inline-flex;
  cursor: pointer;
}

.lq-dropdown-trigger--disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

.lq-dropdown-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: var(--fs-sm);
  color: var(--color-text-secondary);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: var(--space-xs) var(--space-sm-md);
  border-radius: var(--radius-md);
  transition: var(--transition-fast);
}

.lq-dropdown-btn:hover {
  background: var(--color-surface-hover);
  color: var(--color-text-primary);
}

.lq-dropdown-arrow {
  transition: transform var(--transition-fast);
}

.lq-dropdown-arrow.is-open {
  transform: rotate(180deg);
}

.lq-dropdown-item-icon {
  display: inline-flex;
  margin-right: var(--space-sm-md);
}

.lq-dropdown-item-icon :deep(svg) {
  width: 14px;
  height: 14px;
}
</style>

<!-- 全局样式：el-dropdown-menu 被 teleported 到 body 外部，scoped :deep() 无法触达 -->
<style>
.lq-dropdown-item--danger {
  color: var(--color-danger);
}

.lq-dropdown-item--danger:not(.is-disabled):hover {
  background: var(--color-danger-bg);
  color: var(--color-danger-text);
}
</style>
