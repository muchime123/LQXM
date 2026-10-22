<!--
  LqDialog - 统一弹窗组件
  基于 Element Plus el-dialog 封装，统一全局视觉与三段式布局（Header / Body / Footer）。
  框架负责功能（遮罩、ESC、滚动锁定、动画、可拖拽等），组件负责视觉一致性。

  使用方式：
    <LqDialog
      v-model="visible"
      title="编辑产品"
      subtitle="维护产品基础资料"
      size="lg"
      mode="form"
      :dirty="dirty"
      :loading="saving"
      :before-close="handleBeforeClose"
      confirm-text="保存"
      cancel-text="取消"
    >
      内容区域
    </LqDialog>

  尺寸规范（size）：取自 styles/tokens.css 的 --dialog-width-* 令牌
    sm  --dialog-width-sm (420px)    简单确认、删除、提示
    md  --dialog-width-md (720px)    普通编辑、表单、基础资料
    lg  --dialog-width-lg (1100px)   复杂业务、表格、多区域编辑
    xl  --dialog-width-xl (min(1400px,90vw))  大型工作台

  模式（mode）：
    detail     查看详情，默认无 footer，内容区域滚动
    form       编辑表单，默认有 footer，含保存/取消
    workspace  大型操作，减少 padding，接近全屏

  也可通过 width 自定义覆盖 size 默认值。

  关闭一致性约定：
    1. 关闭按钮 / ESC / 点击遮罩 / 取消按钮 全部经过 beforeClose 拦截，
       业务语义事件（cancel）仅在关闭被允许后派发，不会绕过脏数据拦截。
    2. loading 期间禁止所有关闭方式（关闭按钮隐藏，ESC 与遮罩同步失效）。

  命名注意：自定义类名使用 lq-dialog__head / lq-dialog__foot（不是 __header/__footer），
  以避免与 Element Plus 的 .el-dialog__header / .el-dialog__footer 命名混淆。
-->
<template>
  <el-dialog
    :model-value="modelValue"
    :width="resolvedWidth"
    :close-on-click-modal="closeOnClickModal && !loading"
    :close-on-press-escape="!loading"
    :destroy-on-close="destroyOnClose"
    :show-close="false"
    :align-center="!fullscreen"
    :fullscreen="fullscreen"
    :before-close="elBeforeClose"
    :aria-label="title || '对话框'"
    :z-index="zIndex"
    class="lq-dialog"
    :class="dialogClasses"
    append-to-body
    @update:model-value="handleModelUpdate"
    @close="handleClose"
  >
    <template #header="{ titleId }">
      <div :id="titleId" class="lq-dialog__head">
        <slot name="title">
          <div class="lq-dialog__title">
            <div class="lq-dialog__title-main">
              <span v-if="eyebrow" class="lq-dialog__eyebrow" :class="{ dirty }">{{ eyebrow }}</span>
              <span v-if="title" class="lq-dialog__title-text">{{ title }}</span>
              <span v-if="dirty && !eyebrow" class="lq-dialog__eyebrow dirty">已修改</span>
            </div>
            <p v-if="subtitle" class="lq-dialog__subtitle">{{ subtitle }}</p>
          </div>
        </slot>
        <div class="lq-dialog__actions">
          <slot name="actions" />
          <button
            v-if="showClose && !loading"
            type="button"
            class="lq-dialog__close"
            aria-label="关闭"
            @click="() => requestClose()"
          >
            <svg
              viewBox="0 0 16 16"
              width="14"
              height="14"
              fill="none"
              aria-hidden="true"
            >
              <path
                d="M4 4l8 8M12 4l-8 8"
                stroke="currentColor"
                stroke-width="1.6"
                stroke-linecap="round"
              />
            </svg>
          </button>
        </div>
      </div>
    </template>

    <section class="lq-dialog__body" :class="{ 'is-scrollable': scrollable }">
      <slot />
    </section>

    <template v-if="hasFooter" #footer>
      <div class="lq-dialog__foot">
        <slot name="footer">
          <span v-if="mode === 'form' && dirty" class="footer-status">有未保存的修改</span>
          <span v-else-if="mode === 'form'" class="footer-status">当前没有未保存修改</span>
          <button
            v-if="showCancel"
            type="button"
            :disabled="loading"
            @click="handleCancel"
          >
            {{ cancelText }}
          </button>
          <button
            v-if="showConfirm"
            type="button"
            class="primary"
            :disabled="loading || confirmDisabled"
            @click="handleConfirm"
          >
            <span v-if="loading" class="lq-dialog__loading-dot"></span>
            {{ confirmText }}
          </button>
        </slot>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, nextTick } from 'vue'

type DialogSize = 'sm' | 'md' | 'lg' | 'xl'
type DialogMode = 'detail' | 'form' | 'workspace'

const props = withDefaults(
  defineProps<{
    /** v-model 绑定值 */
    modelValue: boolean
    /** 顶部标签（小型主色徽章，非默认结构，特殊场景使用） */
    eyebrow?: string
    /** 主标题 */
    title?: string
    /** 副标题/说明 */
    subtitle?: string
    /** 预设尺寸 */
    size?: DialogSize
    /** 自定义宽度，覆盖 size 默认值 */
    width?: string
    /** 弹窗模式：detail 查看 / form 编辑 / workspace 大型操作 */
    mode?: DialogMode
    /** 是否显示右上角关闭按钮 */
    showClose?: boolean
    /** 点击遮罩是否关闭 */
    closeOnClickModal?: boolean
    /** 关闭时是否销毁内容 */
    destroyOnClose?: boolean
    /** 是否处于脏状态 */
    dirty?: boolean
    /** 加载中状态（footer 按钮会禁用并显示 loading） */
    loading?: boolean
    /** body 区域是否可滚动（默认 true） */
    scrollable?: boolean
    /** 是否显示 footer（默认 true，mode=detail 时默认 false） */
    showFooter?: boolean
    /** 是否显示取消按钮（默认 true） */
    showCancel?: boolean
    /** 是否显示确认按钮（默认 true） */
    showConfirm?: boolean
    /** 确认按钮文案（默认"保存"） */
    confirmText?: string
    /** 取消按钮文案（默认"取消"） */
    cancelText?: string
    /** 是否禁用确认按钮 */
    confirmDisabled?: boolean
    /** 全屏模式 */
    fullscreen?: boolean
    /** Explicit layer for nested business dialogs. Omit to use Element Plus stack allocation. */
    zIndex?: number
    /** 关闭前拦截钩子，返回 false 或 Promise<false> 阻止关闭 */
    beforeClose?: (done: () => void) => void | boolean | Promise<void | boolean>
  }>(),
  {
    eyebrow: '',
    title: '',
    subtitle: '',
    size: 'md',
    width: '',
    mode: 'form',
    showClose: true,
    closeOnClickModal: false,
    destroyOnClose: false,
    dirty: false,
    loading: false,
    scrollable: true,
    showFooter: undefined,
    showCancel: true,
    showConfirm: true,
    confirmText: '保存',
    cancelText: '取消',
    confirmDisabled: false,
    fullscreen: false
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  close: []
  cancel: []
  confirm: []
}>()

// 尺寸唯一来源：styles/tokens.css 的 --dialog-width-* 令牌
// （el-dialog 会把 width 透传为 --el-dialog-width，故此处可直接引用 CSS 变量）
const sizeWidthMap: Record<DialogSize, string> = {
  sm: 'var(--dialog-width-sm)',
  md: 'var(--dialog-width-md)',
  lg: 'var(--dialog-width-lg)',
  xl: 'var(--dialog-width-xl)'
}

const resolvedWidth = computed(() => {
  if (props.width) return props.width
  return sizeWidthMap[props.size] ?? sizeWidthMap.md
})

const hasFooter = computed(() => {
  if (props.showFooter !== undefined) return props.showFooter
  return props.mode !== 'detail'
})

const dialogClasses = computed(() => ({
  [`lq-dialog--${props.mode}`]: true,
  'lq-dialog--loading': props.loading
}))

function handleModelUpdate(value: boolean) {
  emit('update:modelValue', value)
}

/**
 * 关闭请求后的兜底标记：requestClose 已通过 close 事件兜底关闭，
 * 防止 el-dialog 真正关闭时 handleClose 重复派发 close。
 */
let suppressNextClose = false

/**
 * 按钮/遮罩请求关闭：只同步状态，由 el-dialog @close 统一派发 close。
 * @param onAllowed 关闭被允许（未被 beforeClose 拦截）时才执行的回调。
 *   业务侧语义事件（如 cancel）必须走这里，否则会绕过脏数据拦截，
 *   造成「点取消直接关、点 X 才提示」的不一致。
 */
function requestClose(onAllowed?: () => void) {
  elBeforeClose(() => {
    onAllowed?.()
    emit('update:modelValue', false)
    // 兜底：部分消费者仅监听 @close（:model-value + @close 模式），
    // 不会响应 update:modelValue。若下一帧仍未关闭，则改派 close 让其自行关闭。
    nextTick(() => {
      if (props.modelValue) {
        suppressNextClose = true
        emit('close')
      }
    })
  })
}

/** el-dialog 关闭完成后的唯一出口 */
function handleClose() {
  if (suppressNextClose) {
    suppressNextClose = false
    return
  }
  emit('close')
}

function handleCancel() {
  // cancel 与关闭按钮同样受 beforeClose 拦截：仅在确认允许关闭后才派发
  requestClose(() => emit('cancel'))
}

function handleConfirm() {
  emit('confirm')
}

function elBeforeClose(done: () => void) {
  if (!props.beforeClose) {
    done()
    return
  }

  let completed = false
  const completeOnce = () => {
    if (completed) return
    completed = true
    done()
  }
  const result = props.beforeClose(completeOnce)

  if (result === false) return
  if (result instanceof Promise) {
    result
      .then(allowed => {
        if (allowed !== false) completeOnce()
      })
      .catch(() => {})
    return
  }

  // A void return means the hook owns the callback (Element Plus convention).
  // Returning true explicitly opts into immediate close.
  if (result === true) completeOnce()
}
</script>
