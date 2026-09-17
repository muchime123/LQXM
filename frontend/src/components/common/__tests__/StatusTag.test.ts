import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StatusTag from '../StatusTag.vue'

describe('StatusTag', () => {
  it('always exposes a textual status', () => {
    const wrapper = mount(StatusTag, { props: { type: 'tag-ok', label: '已完成' } })
    expect(wrapper.text()).toBe('已完成')
  })

  it('gives dot-only status an accessible name', () => {
    const wrapper = mount(StatusTag, { props: { type: 'tag-danger', label: '异常', dot: true } })
    expect(wrapper.get('.status-dot').attributes('aria-label')).toBe('异常')
  })
})
