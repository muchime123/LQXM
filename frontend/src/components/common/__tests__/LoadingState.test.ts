import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LoadingState from '../LoadingState.vue'

describe('LoadingState', () => {
  it('announces an active loading operation without exposing decorative animation', () => {
    const wrapper = mount(LoadingState, { props: { text: '正在加载列表', size: 'lg' } })

    expect(wrapper.attributes()).toMatchObject({ role: 'status', 'aria-live': 'polite', 'aria-busy': 'true' })
    expect(wrapper.classes()).toContain('loading-state--lg')
    expect(wrapper.get('.loading-spinner').attributes('aria-hidden')).toBe('true')
    expect(wrapper.text()).toBe('正在加载列表')
  })

  it('allows visual-only loading without an empty paragraph', () => {
    const wrapper = mount(LoadingState, { props: { text: '' } })
    expect(wrapper.find('p').exists()).toBe(false)
  })
})
