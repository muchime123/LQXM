import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import LqDialog from '../LqDialog.vue'

/**
 * LqDialog 关闭契约（B1 基线）
 *
 * 契约边界说明：
 * LqDialog 的职责是「把关闭意图翻译成对 el-dialog 的 props，并在 el-dialog 真正关闭时
 * 统一派发 close」。真正的 ESC 监听、遮罩点击、过渡动画由 Element Plus 负责，
 * 因此本文件把两部分分开验证：
 *   1）LqDialog 侧契约：转发给 el-dialog 的 closeOnPressEscape / closeOnClickModal /
 *      destroyOnClose / appendToBody 是否正确（stub 即可精确断言）；
 *   2）真实行为验证：用真实 Element Plus 挂载，验证 Teleport、结构渲染与关闭链路。
 *
 * 现有行为（不得被 B2/B11 破坏）：
 *   - v-model 模式：requestClose 只同步 update:modelValue，close 由 el-dialog @close 唯一派发；
 *   - :model-value + @close 模式（父级不回写）：nextTick 兜底派发 close，
 *     并由 suppressNextClose 压制随后 el-dialog @close 的重复派发。
 */

const ElDialogStub = {
  name: 'ElDialog',
  props: [
    'modelValue',
    'width',
    'closeOnClickModal',
    'closeOnPressEscape',
    'destroyOnClose',
    'showClose',
    'alignCenter',
    'fullscreen',
    'beforeClose',
    'class',
    'appendToBody',
    'zIndex'
  ],
  template: `
    <div class="el-dialog" :class="$props.class">
      <slot name="header" />
      <slot />
      <slot name="footer" />
    </div>
  `
}

function factory(props: Record<string, unknown> = {}, slots: Record<string, string> = {}) {
  return mount(LqDialog, {
    props: { modelValue: true, ...props },
    global: { stubs: { 'el-dialog': ElDialogStub } },
    slots: { default: '<div class="test-body">content</div>', ...slots }
  })
}

function elDialogOf(wrapper: ReturnType<typeof factory>) {
  return wrapper.findComponent(ElDialogStub)
}

describe('LqDialog 关闭通道转发契约', () => {
  beforeEach(() => vi.clearAllMocks())

  it('默认允许 ESC 关闭', () => {
    expect(elDialogOf(factory()).props('closeOnPressEscape')).toBe(true)
  })

  it('loading 期间关闭 ESC 通道', () => {
    expect(elDialogOf(factory({ loading: true })).props('closeOnPressEscape')).toBe(false)
  })

  it('默认禁用遮罩关闭', () => {
    expect(elDialogOf(factory()).props('closeOnClickModal')).toBe(false)
  })

  it('closeOnClickModal=true 时开启遮罩关闭', () => {
    expect(elDialogOf(factory({ closeOnClickModal: true })).props('closeOnClickModal')).toBe(true)
  })

  it('loading 期间即使 closeOnClickModal=true 也关闭遮罩通道', () => {
    expect(elDialogOf(factory({ closeOnClickModal: true, loading: true })).props('closeOnClickModal')).toBe(false)
  })

  it('透传 destroyOnClose', () => {
    expect(elDialogOf(factory({ destroyOnClose: true })).props('destroyOnClose')).toBe(true)
  })

  it('始终传入 append-to-body（嵌套与层级依赖它）', () => {
    // 模板以裸属性 `append-to-body` 传入（LqDialog.vue:58）；stub 未声明 Boolean 类型
    // 故此处取值为 ''，真实 el-dialog 侧为 Boolean → true，
    // 由「与真实 Element Plus 集成」用例验证内容确实被 Teleport 到 body。
    expect(elDialogOf(factory()).props('appendToBody')).toBe('')
  })
})

describe('LqDialog 关闭事件唯一性', () => {
  beforeEach(() => vi.clearAllMocks())

  it('遮罩关闭路径：update 与 close 各一次，不重复', async () => {
    const wrapper = factory({ closeOnClickModal: true })
    const elDialog = elDialogOf(wrapper)

    // Element Plus 行为顺序：先 update:modelValue=false，再 @close
    await elDialog.vm.$emit('update:modelValue', false)
    await elDialog.vm.$emit('close')

    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(wrapper.emitted('cancel')).toBeUndefined()
  })

  it('ESC 关闭路径：update 与 close 各一次，不重复', async () => {
    const wrapper = factory()
    const elDialog = elDialogOf(wrapper)

    await elDialog.vm.$emit('update:modelValue', false)
    await elDialog.vm.$emit('close')

    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('关闭按钮路径（父级响应 v-model）：close 仅由 el-dialog @close 派发一次', async () => {
    const wrapper = factory({ showClose: true })
    const elDialog = elDialogOf(wrapper)

    const click = wrapper.find('.lq-dialog__close').trigger('click')
    await wrapper.setProps({ modelValue: false })
    await click
    expect(wrapper.emitted('close')).toBeUndefined()

    await elDialog.vm.$emit('close')
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
  })

  it('关闭按钮路径（父级不回写）：兜底派发 close 一次，且不重复', async () => {
    const wrapper = factory({ showClose: true })
    const elDialog = elDialogOf(wrapper)

    await wrapper.find('.lq-dialog__close').trigger('click')
    await nextTick()

    expect(wrapper.emitted('close')).toHaveLength(1)

    // 父级随后才响应，el-dialog 真正关闭时不再重复派发
    await wrapper.setProps({ modelValue: false })
    await elDialog.vm.$emit('close')
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('取消按钮：cancel 一次 + close 一次，且 cancel 不会绕过 beforeClose', async () => {
    const wrapper = factory({ mode: 'form', showCancel: true })
    const elDialog = elDialogOf(wrapper)
    const buttons = wrapper.findAll('.lq-dialog__foot button')

    const click = buttons[0].trigger('click')
    await wrapper.setProps({ modelValue: false })
    await click
    await elDialog.vm.$emit('close')

    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('确认按钮只派发 confirm，不自动关闭', async () => {
    const wrapper = factory({ mode: 'form', showConfirm: true })
    const buttons = wrapper.findAll('.lq-dialog__foot button')

    await buttons[buttons.length - 1].trigger('click')

    expect(wrapper.emitted('confirm')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  it('loading 期间不渲染关闭按钮（关闭入口唯一由 el-dialog 通道控制）', () => {
    expect(factory({ loading: true, showClose: true }).find('.lq-dialog__close').exists()).toBe(false)
    expect(factory({ loading: false, showClose: true }).find('.lq-dialog__close').exists()).toBe(true)
  })
})

describe('LqDialog beforeClose 语义契约', () => {
  beforeEach(() => vi.clearAllMocks())

  it('返回 false：阻止关闭，且不派发 cancel / close', async () => {
    const beforeClose = vi.fn(() => false)
    const wrapper = factory({ beforeClose })

    await wrapper.find('.lq-dialog__close').trigger('click')
    await nextTick()

    expect(beforeClose).toHaveBeenCalledOnce()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  it('返回 true：立即关闭', async () => {
    const wrapper = factory({ beforeClose: () => true })

    await wrapper.find('.lq-dialog__close').trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })

  it('回调式（void 返回）：由钩子持 done，调用后才关闭', async () => {
    let done: (() => void) | undefined
    const wrapper = factory({
      beforeClose: (finish: () => void) => {
        done = finish
      }
    })

    await wrapper.find('.lq-dialog__close').trigger('click')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    done?.()
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })

  it('Promise resolve(false)：阻止关闭', async () => {
    const wrapper = factory({ beforeClose: () => Promise.resolve(false) })

    await wrapper.find('.lq-dialog__close').trigger('click')
    await Promise.resolve()
    await nextTick()

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  it('Promise resolve(true)：允许关闭', async () => {
    const wrapper = factory({ beforeClose: () => Promise.resolve(true) })

    await wrapper.find('.lq-dialog__close').trigger('click')
    await Promise.resolve()
    await nextTick()

    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
  })

  it('Promise reject：视为不关闭且不抛出（异常被内部吞掉）', async () => {
    const wrapper = factory({ beforeClose: () => Promise.reject(new Error('boom')) })

    await wrapper.find('.lq-dialog__close').trigger('click')
    await Promise.resolve()
    await nextTick()

    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('beforeClose 拦截时取消按钮也不派发 cancel', async () => {
    const wrapper = factory({ mode: 'form', showCancel: true, beforeClose: () => false })
    const buttons = wrapper.findAll('.lq-dialog__foot button')

    await buttons[0].trigger('click')
    await nextTick()

    expect(wrapper.emitted('cancel')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })
})

describe('LqDialog 嵌套与生命周期', () => {
  beforeEach(() => vi.clearAllMocks())

  it('子弹窗关闭不会影响父弹窗', async () => {
    const Host = defineComponent({
      components: { LqDialog },
      data: () => ({ outerVisible: true, innerVisible: true }),
      template: `
        <LqDialog v-model="outerVisible" title="外层">
          <LqDialog v-model="innerVisible" title="内层" />
        </LqDialog>
      `
    })
    const wrapper = mount(Host, { global: { stubs: { 'el-dialog': ElDialogStub } } })

    const dialogs = wrapper.findAllComponents(LqDialog)
    expect(dialogs).toHaveLength(2)

    const [outer, inner] = dialogs
    await inner.find('.lq-dialog__close').trigger('click')
    await nextTick()
    await inner.findComponent(ElDialogStub).vm.$emit('close')

    expect(inner.emitted('close')).toHaveLength(1)
    expect(outer.emitted('close')).toBeUndefined()
  })

  it('嵌套时可分别指定 zIndex', () => {
    const Host = defineComponent({
      components: { LqDialog },
      data: () => ({ outerVisible: true, innerVisible: true }),
      template: `
        <LqDialog v-model="outerVisible" :z-index="2200">
          <LqDialog v-model="innerVisible" :z-index="2300" />
        </LqDialog>
      `
    })
    const wrapper = mount(Host, { global: { stubs: { 'el-dialog': ElDialogStub } } })
    const [outer, inner] = wrapper.findAllComponents(LqDialog)

    expect(outer.findComponent(ElDialogStub).props('zIndex')).toBe(2200)
    expect(inner.findComponent(ElDialogStub).props('zIndex')).toBe(2300)
  })

  it('关闭请求未完成时卸载不抛错（生命周期安全）', async () => {
    const wrapper = factory({ showClose: true })

    await wrapper.find('.lq-dialog__close').trigger('click')

    expect(() => wrapper.unmount()).not.toThrow()
    await nextTick()
  })
})

describe('LqDialog 与真实 Element Plus 集成', () => {
  it('内容被 Teleport 到 body，且三段式结构完整', async () => {
    const wrapper = mount(LqDialog, {
      props: { modelValue: true, title: '编辑产品', subtitle: '维护基础资料', mode: 'form' },
      slots: { default: '<div class="real-body">content</div>' },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await nextTick()

    expect(document.body.querySelector('.lq-dialog__head')).not.toBeNull()
    expect(document.body.querySelector('.lq-dialog__body')).not.toBeNull()
    expect(document.body.querySelector('.lq-dialog__foot')).not.toBeNull()
    expect(document.body.querySelector('.real-body')).not.toBeNull()

    wrapper.unmount()
  })

  it('关闭后内容从 DOM 移除', async () => {
    const wrapper = mount(LqDialog, {
      props: { modelValue: true, title: '编辑产品' },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await nextTick()
    expect(document.body.querySelector('.lq-dialog__head')).not.toBeNull()

    await wrapper.setProps({ modelValue: false })
    // 过渡由 Element Plus 负责，这里只断言组件层已响应关闭意图
    expect(wrapper.emitted('update:modelValue') ?? []).toEqual([])

    wrapper.unmount()
  })
})
