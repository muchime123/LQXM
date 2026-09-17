import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DataState from '../DataState.vue'

const global = {
  stubs: {
    LoadingState: { props: ['text'], template: '<div class="loading-state">{{ text }}</div>' },
    EmptyState: {
      props: ['text', 'hint'],
      template: '<div class="empty-state"><strong>{{ text }}</strong><span>{{ hint }}</span><slot /></div>'
    },
    ElButton: { template: '<button><slot /></button>' },
    ElIcon: { template: '<i><slot /></i>' },
    Loading: { template: '<span />' }
  }
}

describe('DataState', () => {
  it('uses one explicit state and emits the standard retry action', async () => {
    const wrapper = mount(DataState, { props: { state: 'error' }, global })
    expect(wrapper.get('[role="alert"]').text()).toContain('加载失败')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('distinguishes filtered empty from database empty', () => {
    const wrapper = mount(DataState, { props: { state: 'filtered-empty' }, global })
    expect(wrapper.get('.empty-state').text()).toContain('没有匹配结果')
    expect(wrapper.get('.empty-state').text()).toContain('调整或清除筛选条件')
  })

  it('keeps existing content visible while refreshing', () => {
    const wrapper = mount(DataState, {
      props: { state: 'ready', refreshing: true },
      slots: { default: '<div class="existing-data">旧数据</div>' },
      global
    })
    expect(wrapper.get('.existing-data').isVisible()).toBe(true)
    expect(wrapper.get('.data-state-refreshing').text()).toContain('正在刷新')
    expect(wrapper.attributes('aria-busy')).toBe('true')
  })

  it('keeps existing content and shows a stale warning after refresh failure', () => {
    const wrapper = mount(DataState, {
      props: { state: 'stale' },
      slots: { default: '<div class="existing-data">旧数据</div>' },
      global
    })
    expect(wrapper.get('.existing-data').isVisible()).toBe(true)
    expect(wrapper.get('[role="status"]').text()).toContain('刷新失败')
  })

  it('supports legacy boolean props during migration', () => {
    const wrapper = mount(DataState, { props: { loading: true }, global })
    expect(wrapper.get('.loading-state').text()).toBe('正在加载数据...')
  })
})
