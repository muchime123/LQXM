<!--
  LqConfirmDialog - 确认弹窗组件
  用于删除、提示、简单确认等轻量场景。
  支持双按钮（取消/确认）和三按钮（取消/次要/主要）模式。

  使用方式：
    <LqConfirmDialog
      v-model="visible"
      title="确认删除"
      type="danger"
      @confirm="handleDelete"
    >
      删除后无法恢复，是否继续？
    </LqConfirmDialog>

  三按钮模式：
    <LqConfirmDialog
      v-model="visible"
      title="删除文件"
      type="danger"
      secondary-text="只删记录"
      confirm-text="同时删除文件"
      @secondary="handleRecordOnly"
      @confirm="handleDeleteAll"
    >
      选择删除方式
    </LqConfirmDialog>
-->
<template>
  <LqDialog
    :model-value="modelValue"
    :title="title"
    :subtitle="subtitle"
    size="sm"
    mode="form"
    :close-on-click-modal="closeOnClickModal"
    :show-close="showClose"
    :show-footer="true"
    :confirm-text="confirmText"
    :cancel-text="cancelText"
    :confirm-disabled="confirmDisabled"
    :loading="loading"
    :z-index="UI_LAYER.popover"
    @update:model-value="handleUpdate"
    @close="handleClose"
    @cancel="handleCancel"
    @confirm="handleConfirm"
  >
    <div class="lq-confirm-body">
      <div v-if="type === 'danger'" class="lq-confirm-icon lq-confirm-icon--danger">
        <svg
          viewBox="0 0 24 24"
          width="24"
          height="24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
          <line
x1="12"
y1="9"
x2="12"
y2="13" />
          <line
x1="12"
y1="17"
x2="12.01"
y2="17" />
        </svg>
      </div>
      <div v-else-if="type === 'warning'" class="lq-confirm-icon lq-confirm-icon--warning">
        <svg
          viewBox="0 0 24 24"
          width="24"
          height="24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <circle cx="12" cy="12" r="10" />
          <line
x1="12"
y1="8"
x2="12"
y2="12" />
          <line
x1="12"
y1="16"
x2="12.01"
y2="16" />
        </svg>
      </div>
      <div v-else class="lq-confirm-icon lq-confirm-icon--info">
        <svg
          viewBox="0 0 24 24"
          width="24"
          height="24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <circle cx="12" cy="12" r="10" />
          <line
x1="12"
y1="16"
x2="12"
y2="12" />
          <line
x1="12"
y1="8"
x2="12.01"
y2="8" />
        </svg>
      </div>
      <div class="lq-confirm-content">
        <slot />
      </div>
    </div>

    <template #footer>
      <div class="lq-dialog__foot lq-confirm-footer" :class="{ 'has-secondary': secondaryText }">
        <button type="button" :disabled="loading" @click="handleCancel">{{ cancelText }}</button>
        <button
v-if="secondaryText"
type="button"
:disabled="loading"
class="secondary-btn"
@click="handleSecondary">
          {{ secondaryText }}
        </button>
        <button
          type="button"
          :class="['primary', type === 'danger' ? 'danger' : '']"
          :disabled="loading || confirmDisabled"
          @click="handleConfirm"
        >
          <span v-if="loading" class="lq-dialog__loading-dot"></span>
          {{ confirmText }}
        </button>
      </div>
    </template>
  </LqDialog>
</template>

<script setup lang="ts">
import LqDialog from './LqDialog.vue'
import { UI_LAYER } from '@/constants/uiLayers'

withDefaults(
  defineProps<{
    modelValue: boolean
    title?: string
    subtitle?: string
    /** 确认类型：danger 删除 / warning 警告 / info 普通 */
    type?: 'danger' | 'warning' | 'info'
    confirmText?: string
    cancelText?: string
    /** 次要按钮文字，传了则显示三按钮模式 */
    secondaryText?: string
    confirmDisabled?: boolean
    loading?: boolean
    closeOnClickModal?: boolean
    showClose?: boolean
  }>(),
  {
    title: '确认操作',
    subtitle: '',
    type: 'info',
    confirmText: '确认',
    cancelText: '取消',
    secondaryText: '',
    confirmDisabled: false,
    loading: false,
    closeOnClickModal: false,
    showClose: true
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  close: []
  cancel: []
  confirm: []
  secondary: []
}>()

function handleUpdate(value: boolean) {
  // 关闭事件由内层 LqDialog 的 @close 唯一派发（见 LqDialog.requestClose / handleClose），
  // 这里再补一次 close 会让消费者收到两次。
  emit('update:modelValue', value)
}

function handleClose() {
  emit('close')
}

function handleCancel() {
  emit('cancel')
  emit('update:modelValue', false)
}

function handleConfirm() {
  emit('confirm')
}

function handleSecondary() {
  emit('secondary')
}
</script>

<style scoped>
.lq-confirm-body {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-md);
  padding: 12px 8px 4px;
  text-align: center;
}

.lq-confirm-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 50%;
}

.lq-confirm-icon--danger {
  background: var(--color-danger-bg);
  color: var(--color-danger);
}

.lq-confirm-icon--warning {
  background: var(--color-warning-bg);
  color: var(--color-warning);
}

.lq-confirm-icon--info {
  background: var(--color-primary-bg);
  color: var(--color-primary);
}

.lq-confirm-content {
  color: var(--color-text-muted);
  font-size: var(--fs-sm);
  line-height: 1.6;
}

.lq-confirm-footer,
.lq-confirm-footer.has-secondary {
  justify-content: flex-end;
}

.lq-confirm-footer .secondary-btn {
  border-color: var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-strong);
}

.lq-confirm-footer .secondary-btn:hover:not(:disabled) {
  border-color: var(--color-text-muted);
  color: var(--color-text-strong);
}

/* danger 按钮样式由公共外壳统一提供（styles/modules/dialog-layout.css 的
   .lq-dialog__foot button.danger），此处不重复维护 */
</style>
