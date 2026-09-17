import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import BatchActionBar from '../BatchActionBar.vue'

const global = { stubs: { ElButton: { template: '<button><slot /></button>' } } }

describe('BatchActionBar', () => {
  it('stays absent until at least one item is selected', () => {
    const wrapper = mount(BatchActionBar, { props: { selectedCount: 0 }, global })
    expect(wrapper.find('.batch-action-bar').exists()).toBe(false)
  })

  it('announces the count, renders actions, and can clear selection', async () => {
    const wrapper = mount(BatchActionBar, {
      props: { selectedCount: 3, hint: '失败时保留选择' },
      slots: { default: '<button class="archive">归档</button>' },
      global
    })
    expect(wrapper.attributes('aria-live')).toBe('polite')
    expect(wrapper.text()).toContain('已选 3 项')
    expect(wrapper.text()).toContain('失败时保留选择')
    const buttons = wrapper.findAll('button')
    await buttons[buttons.length - 1].trigger('click')
    expect(wrapper.emitted('clear')).toHaveLength(1)
  })
})
