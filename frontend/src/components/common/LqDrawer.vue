<!--
  LqDrawer - 统一抽屉组件
  基于 Element Plus el-drawer 封装，统一后台抽屉的标题、尺寸、内容区、底部操作与关闭交互。
  框架负责功能（遮罩、ESC、滚动锁定、动画、焦点陷阱），组件负责视觉与关闭语义一致性。

  使用方式：
    <LqDrawer
      v-model="visible"
      title="生产跟单"
      subtitle="按当前有效车间预计完成日期归集任务"
      size="lg"
      mode="detail"
      :loading="saving"
      :dirty="dirty"
      :before-close="handleBeforeClose"
    >
      内容区域
    </LqDrawer>

  尺寸规范（size）：取自 styles/tokens.css 的 --drawer-width-* 令牌
    sm  min(420px, 100vw)   简单详情、轻量确认
    md  min(720px, 94vw)    普通详情、基础资料（默认）
    lg  min(1000px, 94vw)   复杂业务、表格、多区域
    xl  min(1280px, 96vw)   大型工作台

  也可通过 width 自定义覆盖 size 默认值，直接透传给 el-drawer 的 size
  （支持百分比与函数表达式，如 width="90%" 或 width="min(1120px, 98vw)"）。
  注意：direction 为 ttb / btt（纵向）时，该值作用的是**高度**而非宽度。

  模式（mode）：
    detail     查看详情，默认无 footer，内容区域滚动
    form       编辑表单，默认有 footer，含保存/取消
    workspace  大型操作，减少 padding，接近全屏

  关闭一致性约定：
    1. 关闭按钮 / ESC / 点击遮罩 / 取消按钮 四条路径语义完全一致：
       全部经过 beforeClose 拦截，全部先派发 update:modelValue(false)，
       close 由 el-drawer 的 @close 唯一派发（或在父级不回写时兜底派发一次）。
       业务语义事件（cancel）仅在关闭被允许后派发，不会绕过脏数据拦截。
    2. loading 期间禁止所有关闭方式（关闭按钮隐藏，ESC 与遮罩同步失效）。
    3. confirm 只派发意图，不自动关闭抽屉。

  ⚠️ 与 el-dialog 的差异（已核实 Element Plus 2.14.2 源码，不要照搬 LqDialog 假设）：
    - el-drawer 的 appendToBody 默认 **false**（不 Teleport），el-dialog 默认行为不同；
      本组件恒定传 append-to-body，与 LqDialog 保持一致。
    - el-drawer 的 update:modelValue 在**过渡动画结束后**（afterLeave）才由框架派发，
      而 close 在过渡开始前（beforeLeave）派发，顺序与 LqDialog 的 stub 契约相反。
    - el-drawer 的 before-close 只接收 hide(shouldCancel) 回调，**不认返回值**；
      本组件与 LqDialog 一样自建拦截层，统一支持 false / true / Promise / 回调式。

  命名注意：自定义类名使用 lq-drawer__head / lq-drawer__foot（不是 __header/__footer），
  以避免与 Element Plus 的 .el-drawer__header / .el-drawer__footer 命名混淆。
-->
<template>
  <el-drawer
    :model-value="modelValue"
    :title="title"
    :size="resolvedSize"
    :direction="direction"
    :with-header="showHeader"
    :show-close="false"
    :close-on-click-modal="closeOnClickModal && !loading"
    :close-on-press-escape="!loading"
    :destroy-on-close="destroyOnClose"
    :before-close="elBeforeClose"
    :z-index="zIndex"
    class="lq-drawer"
    :class="drawerClasses"
    append-to-body
    @update:model-value="handleModelUpdate"
    @close="handleClose"
  >
    <template v-if="showHeader" #header="{ titleId }">
      <div :id="titleId" class="lq-drawer__head">
        <slot name="title">
          <div class="lq-drawer__title">
            <div class="lq-drawer__title-main">
              <span v-if="eyebrow" class="lq-drawer__eyebrow" :class="{ dirty }">{{ eyebrow }}</span>
              <span v-if="title" class="lq-drawer__title-text">{{ title }}</span>
              <span v-if="dirty && !eyebrow" class="lq-drawer__eyebrow dirty">已修改</span>
            </div>
            <p v-if="subtitle" class="lq-drawer__subtitle">{{ subtitle }}</p>
          </div>
        </slot>
        <div class="lq-drawer__actions">
          <slot name="actions" />
          <button
            v-if="showClose && !loading"
            type="button"
            class="lq-drawer__close"
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

    <section class="lq-drawer__body" :class="{ 'is-scrollable': scrollable }">
      <slot />
    </section>

    <template v-if="hasFooter" #footer>
      <div class="lq-drawer__foot">
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
            <span v-if="loading" class="lq-drawer__loading-dot"></span>
            {{ confirmText }}
          </button>
        </slot>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, nextTick, watch } from 'vue'

type DrawerSize = 'sm' | 'md' | 'lg' | 'xl'
type DrawerMode = 'detail' | 'form' | 'workspace'
type DrawerDirection = 'ltr' | 'rtl' | 'ttb' | 'btt'

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
    /** 预设尺寸，映射到 --drawer-width-* 令牌 */
    size?: DrawerSize
    /** 自定义尺寸，覆盖 size 默认值，直接作为 el-drawer 的 size 透传。
     *  支持百分比与函数表达式（如 '90%'、'min(1120px, 98vw)'）；
     *  纵向方向（ttb / btt）下该值作用于高度。 */
    width?: string
    /** 弹出方向，默认 rtl（右侧滑出） */
    direction?: DrawerDirection
    /** 模式：detail 查看 / form 编辑 / workspace 大型操作 */
    mode?: DrawerMode
    /** 是否显示头部（false 时整块头部不渲染，业务可完全自绘） */
    showHeader?: boolean
    /** 是否显示右上角关闭按钮 */
    showClose?: boolean
    /** 点击遮罩是否关闭 */
    closeOnClickModal?: boolean
    /** 关闭时是否销毁内容 */
    destroyOnClose?: boolean
    /** 是否处于脏状态 */
    dirty?: boolean
    /** 加载中状态（footer 按钮会禁用并显示 loading，同时封锁所有关闭入口） */
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
    /** 显式层级；不传时使用 Element Plus 的栈式分配 */
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
    direction: 'rtl',
    mode: 'form',
    showHeader: true,
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
    confirmDisabled: false
  }
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  close: []
  cancel: []
  confirm: []
}>()

// 尺寸唯一来源：styles/tokens.css 的 --drawer-width-* 令牌
const sizeWidthMap: Record<DrawerSize, string> = {
  sm: 'var(--drawer-width-sm)',
  md: 'var(--drawer-width-md)',
  lg: 'var(--drawer-width-lg)',
  xl: 'var(--drawer-width-xl)'
}

// 与 LqDialog 一致：width 优先，其次 size 预设。
// 刻意不让 mode 反向覆盖尺寸：workspace 只减少内边距，
// 需要接近全屏时显式传 size="xl" 或 width="92vw"，避免隐式行为。
const resolvedSize = computed(() => props.width || sizeWidthMap[props.size] || sizeWidthMap.md)

const hasFooter = computed(() => {
  if (props.showFooter !== undefined) return props.showFooter
  return props.mode !== 'detail'
})

const drawerClasses = computed(() => ({
  [`lq-drawer--${props.mode}`]: true,
  'lq-drawer--loading': props.loading
}))

/**
 * 本轮关闭流程是否已经派发过 close。
 * 用于压制「兜底 close」与「el-drawer 真正关闭时的 close」二者之一，保证恰好一次。
 * 在每次发起关闭意图时重置，并在抽屉被打开时重置。
 */
let closeEmitted = false

watch(
  () => props.modelValue,
  value => {
    if (value) closeEmitted = false
  }
)

/**
 * 统一的关闭意图出口：先同步 modelValue，父级不回写时兜底派发 close。
 *
 * 与 LqDialog 的差异（有意为之，不回改 LqDialog）：
 * LqDialog 的兜底用 nextTick 判断父级是否回写；但 el-drawer 的 @close 在过渡开始前
 * （beforeLeave）就由框架派发，早于 nextTick 回调，会导致 :model-value 模式下
 * close 被派发两次。这里改用 closeEmitted 标记，让两条路径互相压制。
 */
function emitCloseIntent() {
  closeEmitted = false
  emit('update:modelValue', false)
  nextTick(() => {
    if (props.modelValue && !closeEmitted) {
      closeEmitted = true
      emit('close')
    }
  })
}

/**
 * 关闭按钮 / 遮罩 / ESC / 取消按钮的公共入口。
 * @param onAllowed 关闭被允许（未被 beforeClose 拦截）时才执行的回调。
 *   业务侧语义事件（如 cancel）必须走这里，否则会绕过脏数据拦截。
 */
function requestClose(onAllowed?: () => void) {
  runBeforeClose(() => {
    onAllowed?.()
    emitCloseIntent()
  })
}

/**
 * 执行用户 beforeClose 的拦截层，被允许时调用 done。
 *
 * 与 LqDialog 同一套语义：
 *   false 阻止 / true 立即关闭 / void 由钩子持 done / Promise<false> 阻止、
 *   Promise<true> 允许、Promise reject 视为不关闭且不抛出。
 */
function runBeforeClose(done: () => void) {
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

  // void 返回表示钩子持有回调（Element Plus 约定）；显式返回 true 表示立即关闭。
  if (result === true) completeOnce()
}

/**
 * 转发给 el-drawer 的 :before-close（ESC / 点击遮罩由框架调用这里）。
 *
 * 刻意**不调用**框架传入的 hide()：本组件把 ESC / 遮罩的关闭意图也转成
 * update:modelValue，这样四条关闭路径（关闭按钮 / ESC / 遮罩 / 取消）
 * 的事件顺序完全一致（先 update:modelValue，后 close）。
 */
function elBeforeClose(_epHide?: () => void) {
  runBeforeClose(emitCloseIntent)
}

/** el-drawer 关闭流程启动后（过渡前）派发，是 close 的唯一出口 */
function handleClose() {
  if (closeEmitted) {
    closeEmitted = false
    return
  }
  closeEmitted = true
  emit('close')
}

/**
 * 转发 el-drawer 的 update:modelValue。
 * 框架会在过渡结束后（afterLeave）补发一次 false，此时父级通常早已回写，
 * 与原值相同则丢弃，避免 v-model 模式下收到重复的 update:modelValue。
 */
function handleModelUpdate(value: boolean) {
  if (value === props.modelValue) return
  emit('update:modelValue', value)
}

function handleCancel() {
  // cancel 与关闭按钮同样受 beforeClose 拦截：仅在确认允许关闭后才派发
  requestClose(() => emit('cancel'))
}

function handleConfirm() {
  emit('confirm')
}
</script>
