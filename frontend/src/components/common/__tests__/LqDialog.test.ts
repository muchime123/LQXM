import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import LqDialog from '../LqDialog.vue'

const ElDialogStub = {
  name: 'ElDialog',
  props: [
    'modelValue',
    'width',
    'closeOnClickModal',
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
    slots: {
      default: '<div class="test-body">content</div>',
      ...slots
    }
  })
}

describe('LqDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('rendering', () => {
    it('uses the visible title as the accessible dialog name', () => {
      const wrapper = factory({ title: '编辑资料' })
      expect(wrapper.find('.el-dialog').attributes('aria-label')).toBe('编辑资料')
    })

    it('renders slot content', () => {
      const wrapper = factory()
      expect(wrapper.find('.test-body').exists()).toBe(true)
    })

    it('applies mode class', () => {
      const wrapper = factory({ mode: 'detail' })
      expect(wrapper.find('.lq-dialog--detail').exists()).toBe(true)
    })

    it('applies loading class', () => {
      const wrapper = factory({ loading: true })
      expect(wrapper.find('.lq-dialog--loading').exists()).toBe(true)
    })

    it('shows dirty badge in header', () => {
      const wrapper = factory({ dirty: true, title: '编辑' })
      expect(wrapper.find('.lq-dialog__eyebrow.dirty').exists()).toBe(true)
    })

    it('shows custom eyebrow when provided', () => {
      const wrapper = factory({ eyebrow: '草稿', dirty: true })
      const eyebrow = wrapper.find('.lq-dialog__eyebrow')
      expect(eyebrow.exists()).toBe(true)
      expect(eyebrow.text()).toBe('草稿')
    })

    it('shows subtitle when provided', () => {
      const wrapper = factory({ subtitle: '请填写以下信息' })
      expect(wrapper.find('.lq-dialog__subtitle').text()).toBe('请填写以下信息')
    })
  })

  describe('close button', () => {
    it('reacting parent: close emits only update:modelValue, close comes from el-dialog', async () => {
      const wrapper = factory({ modelValue: true, showClose: true })
      const elDialog = wrapper.findComponent(ElDialogStub)
      const click = wrapper.find('.lq-dialog__close').trigger('click')
      // parent reacts synchronously to update:modelValue before the next flush
      await wrapper.setProps({ modelValue: false })
      await click

      expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
      expect(wrapper.emitted('update:modelValue')![0]).toEqual([false])
      // close is NOT emitted here - it comes from el-dialog @close
      expect(wrapper.emitted('close')).toBeUndefined()

      await elDialog.vm.$emit('close')
      expect(wrapper.emitted('close')).toHaveLength(1)
    })

    it('ignoring parent: close falls back to close event when update:modelValue is not handled', async () => {
      const wrapper = factory({ modelValue: true, showClose: true })
      const elDialog = wrapper.findComponent(ElDialogStub)
      await wrapper.find('.lq-dialog__close').trigger('click')

      // 兜底：parent 未响应 update:modelValue，下一帧改派 close
      await nextTick()
      expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
      expect(wrapper.emitted('close')).toHaveLength(1)

      // el-dialog 随后真实关闭时不再重复派发 close
      await wrapper.setProps({ modelValue: false })
      await elDialog.vm.$emit('close')
      expect(wrapper.emitted('close')).toHaveLength(1)
    })

    it('hides close button when loading', () => {
      const wrapper = factory({ loading: true, showClose: true })
      expect(wrapper.find('.lq-dialog__close').exists()).toBe(false)
    })
  })

  describe('event sequence', () => {
    it('handleModelUpdate only syncs modelValue, never emits close', async () => {
      const wrapper = factory({ modelValue: true })
      const elDialog = wrapper.findComponent(ElDialogStub)

      await elDialog.vm.$emit('update:modelValue', false)

      expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
      expect(wrapper.emitted('update:modelValue')![0]).toEqual([false])
      expect(wrapper.emitted('close')).toBeUndefined()
    })

    it('emits close exactly once from el-dialog @close', async () => {
      const wrapper = factory({ modelValue: true })
      const elDialog = wrapper.findComponent(ElDialogStub)

      await elDialog.vm.$emit('close')

      expect(wrapper.emitted('close')).toHaveLength(1)
    })

    it('full close-button sequence: click → update:modelValue → el-dialog @close → close once', async () => {
      const wrapper = factory({ modelValue: true, showClose: true })
      const elDialog = wrapper.findComponent(ElDialogStub)

      // 1. User clicks close button → only requests close
      const click = wrapper.find('.lq-dialog__close').trigger('click')
      await wrapper.setProps({ modelValue: false })
      await click
      expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
      expect(wrapper.emitted('close')).toBeUndefined()

      // 2. el-dialog closes and fires @close
      await elDialog.vm.$emit('close')

      // close emitted exactly once from the single source of truth
      expect(wrapper.emitted('close')).toHaveLength(1)
      expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
    })

    it('full el-dialog sequence: update:modelValue → close → close once', async () => {
      const wrapper = factory({ modelValue: true })
      const elDialog = wrapper.findComponent(ElDialogStub)

      // Simulate Element Plus: first update:modelValue=false, then @close
      await elDialog.vm.$emit('update:modelValue', false)
      await elDialog.vm.$emit('close')

      expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
      expect(wrapper.emitted('close')).toHaveLength(1)
    })

    it('cancel button sequence: cancel → update:modelValue → el-dialog @close → close once', async () => {
      const wrapper = factory({ mode: 'form', showCancel: true })
      const elDialog = wrapper.findComponent(ElDialogStub)
      const buttons = wrapper.findAll('.lq-dialog__foot button')

      // 1. Click cancel
      const click = buttons[0].trigger('click')
      await wrapper.setProps({ modelValue: false })
      await click
      expect(wrapper.emitted('cancel')).toHaveLength(1)
      expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
      expect(wrapper.emitted('update:modelValue')![0]).toEqual([false])
      expect(wrapper.emitted('close')).toBeUndefined()

      // 2. Parent reacts, el-dialog fires @close
      await elDialog.vm.$emit('close')
      expect(wrapper.emitted('close')).toHaveLength(1)
    })
  })

  describe('footer', () => {
    it('shows footer in form mode by default', () => {
      const wrapper = factory({ mode: 'form' })
      expect(wrapper.find('.lq-dialog__foot').exists()).toBe(true)
    })

    it('hides footer in detail mode by default', () => {
      const wrapper = factory({ mode: 'detail' })
      expect(wrapper.find('.lq-dialog__foot').exists()).toBe(false)
    })

    it('shows footer in detail mode when showFooter is true', () => {
      const wrapper = factory({ mode: 'detail', showFooter: true })
      expect(wrapper.find('.lq-dialog__foot').exists()).toBe(true)
    })

    it('shows dirty status text in form mode', () => {
      const wrapper = factory({ mode: 'form', dirty: true })
      expect(wrapper.find('.footer-status').text()).toBe('有未保存的修改')
    })

    it('shows clean status text in form mode', () => {
      const wrapper = factory({ mode: 'form', dirty: false })
      expect(wrapper.find('.footer-status').text()).toBe('当前没有未保存修改')
    })

    it('emits cancel and update:modelValue when cancel clicked, not close', async () => {
      const wrapper = factory({ mode: 'form', showCancel: true })
      const buttons = wrapper.findAll('.lq-dialog__foot button')
      const click = buttons[0].trigger('click')
      await wrapper.setProps({ modelValue: false })
      await click

      expect(wrapper.emitted('cancel')).toHaveLength(1)
      expect(wrapper.emitted('update:modelValue')).toHaveLength(1)
      // close NOT emitted here - comes from el-dialog @close
      expect(wrapper.emitted('close')).toBeUndefined()
    })

    it('emits confirm when confirm button clicked', async () => {
      const wrapper = factory({ mode: 'form', showConfirm: true })
      const buttons = wrapper.findAll('.lq-dialog__foot button')
      // confirm is the last button (or second if cancel exists)
      const confirmButton = buttons[buttons.length - 1]
      await confirmButton.trigger('click')

      expect(wrapper.emitted('confirm')).toHaveLength(1)
    })

    it('disables confirm button when confirmDisabled', () => {
      const wrapper = factory({ mode: 'form', confirmDisabled: true })
      const confirmBtn = wrapper.findAll('.lq-dialog__foot button').pop()
      expect((confirmBtn!.element as HTMLButtonElement).disabled).toBe(true)
    })

    it('shows loading dot when loading', () => {
      const wrapper = factory({ mode: 'form', loading: true })
      expect(wrapper.find('.lq-dialog__loading-dot').exists()).toBe(true)
    })
  })

  describe('beforeClose', () => {
    it('passes beforeClose to el-dialog', () => {
      const beforeClose = vi.fn()
      const wrapper = factory({ beforeClose })
      const elDialog = wrapper.findComponent(ElDialogStub)
      expect(elDialog.props('beforeClose')).toBeDefined()
    })

    it('runs beforeClose when the custom close button is clicked', async () => {
      const beforeClose = vi.fn(() => false)
      const wrapper = factory({ beforeClose })

      await wrapper.find('.lq-dialog__close').trigger('click')

      expect(beforeClose).toHaveBeenCalledOnce()
      expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    })

    it('waits for callback-style beforeClose hooks', async () => {
      let finishClose: (() => void) | undefined
      const beforeClose = vi.fn((done: () => void) => {
        finishClose = done
      })
      const wrapper = factory({ beforeClose })

      await wrapper.find('.lq-dialog__close').trigger('click')
      expect(wrapper.emitted('update:modelValue')).toBeUndefined()

      finishClose?.()
      expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    })

    it('closes once when a hook both calls done and resolves true', async () => {
      const beforeClose = vi.fn(async (done: () => void) => {
        done()
        return true
      })
      const wrapper = factory({ beforeClose })

      await wrapper.find('.lq-dialog__close').trigger('click')
      await Promise.resolve()

      expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    })
  })

  describe('width resolution', () => {
    it.each([
      ['sm', '420px'],
      ['md', '720px'],
      ['lg', '1100px'],
      ['xl', 'min(1400px, 90vw)']
    ] as const)('resolves size %s to %s', (size, expected) => {
      const wrapper = factory({ size })
      const elDialog = wrapper.findComponent(ElDialogStub)
      expect(elDialog.props('width')).toBe(expected)
    })

    it('custom width overrides size', () => {
      const wrapper = factory({ size: 'sm', width: '900px' })
      const elDialog = wrapper.findComponent(ElDialogStub)
      expect(elDialog.props('width')).toBe('900px')
    })
  })

  describe('layering', () => {
    it('forwards an explicit z-index to el-dialog', () => {
      const wrapper = factory({ zIndex: 2200 })
      expect(wrapper.findComponent(ElDialogStub).props('zIndex')).toBe(2200)
    })
  })

  describe('custom slots', () => {
    it('renders custom title slot', () => {
      const wrapper = factory({}, { title: '<span class="custom-title">Custom</span>' })
      expect(wrapper.find('.custom-title').exists()).toBe(true)
    })

    it('renders custom footer slot', () => {
      const wrapper = factory({ showFooter: true }, { footer: '<span class="custom-footer">Footer</span>' })
      expect(wrapper.find('.custom-footer').exists()).toBe(true)
    })
  })
})
