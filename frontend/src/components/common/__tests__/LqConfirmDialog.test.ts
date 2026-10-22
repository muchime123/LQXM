import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LqConfirmDialog from '../LqConfirmDialog.vue'

const global = {
  stubs: {
    LqDialog: {
      name: 'LqDialog',
      props: ['modelValue', 'title', 'confirmText', 'cancelText', 'loading'],
      template: '<section><slot /><footer><slot name="footer" /></footer></section>'
    }
  }
}

describe('LqConfirmDialog', () => {
  it('uses explicit destructive wording and exposes the affected content', () => {
    const wrapper = mount(LqConfirmDialog, {
      props: {
        modelValue: true,
        title: '确认删除 3 条记录',
        type: 'danger',
        confirmText: '确认删除'
      },
      slots: { default: '删除后无法恢复。' },
      global
    })
    expect(wrapper.findComponent({ name: 'LqDialog' }).props('title')).toBe('确认删除 3 条记录')
    expect(wrapper.text()).toContain('删除后无法恢复。')
    expect(wrapper.get('button.primary.danger').text()).toContain('确认删除')
  })

  it('supports secondary action and keeps the dialog open until its owner resolves it', async () => {
    const wrapper = mount(LqConfirmDialog, {
      props: { modelValue: true, secondaryText: '只删记录', confirmText: '同时删除文件' },
      global
    })
    await wrapper.get('button.secondary-btn').trigger('click')
    expect(wrapper.emitted('secondary')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
  })

  it('disables every decision while an operation is running', () => {
    const wrapper = mount(LqConfirmDialog, {
      props: { modelValue: true, loading: true, secondaryText: '次要操作' },
      global
    })
    expect(wrapper.findAll('button').every(button => button.attributes('disabled') !== undefined)).toBe(true)
  })

  it('emits close exactly once when the inner dialog syncs modelValue and then closes', async () => {
    // 内层 LqDialog 的真实关闭序列：先 update:modelValue=false，再派发 @close
    const wrapper = mount(LqConfirmDialog, { props: { modelValue: true }, global })
    const inner = wrapper.findComponent({ name: 'LqDialog' })

    await inner.vm.$emit('update:modelValue', false)
    await inner.vm.$emit('close')

    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it('closes on cancel but only emits confirm for the primary action', async () => {
    const wrapper = mount(LqConfirmDialog, { props: { modelValue: true }, global })
    const buttons = wrapper.findAll('button')
    await buttons[0].trigger('click')
    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])

    await buttons[buttons.length - 1].trigger('click')
    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })
})
