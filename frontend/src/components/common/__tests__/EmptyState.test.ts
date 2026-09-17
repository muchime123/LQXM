import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import EmptyState from '../EmptyState.vue'

describe('EmptyState', () => {
  it('announces the empty result and exposes recovery actions', () => {
    const wrapper = mount(EmptyState, {
      props: { text: '暂无匹配结果', hint: '请清除筛选条件', size: 'sm' },
      slots: { default: '<button type="button">清除筛选</button>' }
    })

    expect(wrapper.attributes()).toMatchObject({ role: 'status', 'aria-live': 'polite' })
    expect(wrapper.classes()).toContain('empty-state--sm')
    expect(wrapper.text()).toContain('暂无匹配结果')
    expect(wrapper.get('button').text()).toBe('清除筛选')
  })

  it('does not render empty optional regions', () => {
    const wrapper = mount(EmptyState, { props: { text: '暂无数据' } })

    expect(wrapper.find('.empty-icon').exists()).toBe(false)
    expect(wrapper.find('.empty-hint').exists()).toBe(false)
    expect(wrapper.find('.empty-actions').exists()).toBe(false)
  })
})
