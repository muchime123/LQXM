<!--
  LqDrawer - 统一抽屉组件
  基于 Element Plus el-drawer 封装，统一全局视觉与布局。

  使用方式：
    <LqDrawer
      v-model="visible"
      title="员工档案"
      size="lg"
      :show-footer="false"
    >
      内容区域
    </LqDrawer>

  尺寸规范（size）：
    sm  420px   简单详情
    md  720px   普通详情
    lg  1100px  复杂详情、多标签页
    xl  min(1400px, 90vw)  大型面板
-->
<template>
  <el-drawer
    :model-value="modelValue"
    :size="resolvedSize"
    :title="title"
    :with-header="!!title"
    :close-on-click-modal="closeOnClickModal"
    :destroy-on-close="destroyOnClose"
    :append-to-body="appendToBody"
    class="lq-drawer"
    @update:model-value="handleModelUpdate"
    @close="handleClose"
  >
    <template v-if="title" #header>
      <div class="lq-drawer__head">
        <span class="lq-drawer__title-text">{{ title }}</span>
        <button
          v-if="showClose"
          type="button"
          class="lq-drawer__close"
          aria-label="关闭"
          @click="requestClose"
        >
          <svg viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">
            <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
          </svg>
        </button>
      </div>
    </template>

    <section class="lq-drawer__body">
      <slot />
    </section>

    <template v-if="showFooter" #footer>
      <div class="lq-drawer__foot">
        <slot name="footer">
          <button type="button" @click="handleCancel">{{ cancelText }}</button>
          <button type="button" class="primary" @click="handleConfirm">{{ confirmText }}</button>
        </slot>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'

type DrawerSize = 'sm' | 'md' | 'lg' | 'xl'

const props = withDefaults(
  defineProps<{
    /** v-model 绑定值 */
    modelValue: boolean
    /** 主标题 */
    title?: string
    /** 预设尺寸 */
    size?: DrawerSize
    /** 自定义宽度，覆盖 size 默认值 */
    width?: string
    /** 是否显示 footer */
    showFooter?: boolean
    /** 是否显示右上角关闭按钮 */
    showClose?: boolean
    /** 点击遮罩是否关闭 */
    closeOnClickModal?: boolean
    /** 关闭时是否销毁内容 */
    destroyOnClose?: boolean
    /** 是否挂载到 body */
    appendToBody?: boolean
    /** 确认按钮文案 */
    confirmText?: string
    /** 取消按钮文案 */
    cancelText?: string
  }>(),
  {
    title: '',
    size: 'md',
    width: '',
    showFooter: false,
    showClose: true,
    closeOnClickModal: false,
    destroyOnClose: false,
    appendToBody: true,
    confirmText: '确认',
    cancelText: '取消'
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  close: []
  cancel: []
  confirm: []
}>()

const sizeMap: Record<DrawerSize, string> = {
  sm: '420px',
  md: '720px',
  lg: '1100px',
  xl: 'min(1400px, 90vw)'
}

const resolvedSize = computed(() => props.width || sizeMap[props.size] || sizeMap.md)

function handleModelUpdate(value: boolean) {
  emit('update:modelValue', value)
}

function handleClose() {
  emit('close')
}

function requestClose() {
  emit('update:modelValue', false)
}

function handleCancel() {
  emit('cancel')
  requestClose()
}

function handleConfirm() {
  emit('confirm')
}
</script>
