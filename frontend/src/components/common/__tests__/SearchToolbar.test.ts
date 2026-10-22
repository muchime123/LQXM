import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import SearchToolbar from '../SearchToolbar.vue'

const global = {
  stubs: {
    'el-input': { name: 'ElInput', template: '<div />' },
    'el-button': {
      name: 'ElButton',
      emits: ['click'],
      template: '<button @click="$emit(\'click\')"><slot /></button>'
    },
    'el-select': { name: 'ElSelect', template: '<div><slot /></div>' },
    'el-option': { name: 'ElOption', template: '<span />' },
    'el-segmented': { name: 'ElSegmented', template: '<div />' },
    'el-icon': { name: 'ElIcon', template: '<i><slot /></i>' }
  }
}

afterEach(() => vi.useRealTimers())

describe('SearchToolbar', () => {
  it('debounces input searches', async () => {
    vi.useFakeTimers()
    const wrapper = mount(SearchToolbar, { props: { debounce: 300 }, global })

    wrapper.findComponent({ name: 'ElInput' }).vm.$emit('update:modelValue', ' first ')
    wrapper.findComponent({ name: 'ElInput' }).vm.$emit('update:modelValue', ' second ')
    await vi.advanceTimersByTimeAsync(300)

    expect(wrapper.emitted('search')).toEqual([['second']])
  })

  it('cancels a pending search when filters are reset', async () => {
    vi.useFakeTimers()
    const wrapper = mount(SearchToolbar, {
      props: { keyword: '', clearVisible: true, debounce: 300 },
      global
    })

    wrapper.findComponent({ name: 'ElInput' }).vm.$emit('update:modelValue', 'stale')
    await wrapper.get('.search-toolbar__clear').trigger('click')
    await vi.advanceTimersByTimeAsync(300)

    expect(wrapper.emitted('clear')).toHaveLength(1)
    expect(wrapper.emitted('update:keyword')).toEqual([['stale'], ['']])
    expect(wrapper.emitted('search')).toBeUndefined()
  })

  it('supports submit-only search mode', async () => {
    vi.useFakeTimers()
    const wrapper = mount(SearchToolbar, {
      props: { searchMode: 'submit', queryButton: true, debounce: 10 },
      global
    })

    wrapper.findComponent({ name: 'ElInput' }).vm.$emit('update:modelValue', 'manual')
    await vi.advanceTimersByTimeAsync(10)
    expect(wrapper.emitted('search')).toBeUndefined()

    await wrapper.get('.search-toolbar__query').trigger('click')
    expect(wrapper.emitted('search')).toEqual([['manual']])
  })

  it('does not search while an IME composition is active', async () => {
    vi.useFakeTimers()
    const wrapper = mount(SearchToolbar, { props: { debounce: 10 }, global })
    const input = wrapper.findComponent({ name: 'ElInput' })

    input.vm.$emit('compositionstart')
    input.vm.$emit('update:modelValue', '拼')
    await vi.advanceTimersByTimeAsync(10)
    expect(wrapper.emitted('search')).toBeUndefined()

    input.vm.$emit('compositionend')
    await vi.advanceTimersByTimeAsync(10)
    expect(wrapper.emitted('search')).toEqual([['拼']])
  })

  it('forwards batch actions and disables destructive actions without a selection', async () => {
    // 用宿主组件挂载：batch-edit / batch-remove 已在 defineEmits 中声明，
    // 只有真实模板监听才会进入 vnode.props，onXxx 属性写法无法验证探测逻辑。
    const Host = defineComponent({
      components: { SearchToolbar },
      setup: () => ({ onBatchEdit: vi.fn(), onBatchRemove: vi.fn() }),
      template: `
        <SearchToolbar
          batch-enabled
          batch-mode
          :selected-count="0"
          :models-count="3"
          @batch-edit="onBatchEdit"
          @batch-remove="onBatchRemove"
        />
      `
    })
    const wrapper = mount(Host, { global })
    const batch = wrapper.findComponent({ name: 'SearchToolbarBatchBar' })

    expect(batch.attributes('aria-label')).toBe('批量管理操作')
    expect(wrapper.find('.search-toolbar__row').exists()).toBe(false)
    expect(batch.text()).toContain('0 / 3')
    expect(batch.findAll('button').filter(button => button.attributes('disabled') !== undefined)).toHaveLength(2)

    await batch.findAll('button')[0].trigger('click')
    expect(wrapper.findComponent(SearchToolbar).emitted('toggle-select-all')).toHaveLength(1)
  })

  it('hides built-in batch actions when the owner does not listen to them', () => {
    // 未监听 batch-edit / batch-remove 时不应渲染对应按钮（否则点击静默无响应）
    const wrapper = mount(SearchToolbar, {
      props: { batchEnabled: true, batchMode: true, selectedCount: 2, modelsCount: 3 },
      global
    })

    const labels = wrapper.findAll('button').map(button => button.text())

    expect(labels.join('|')).not.toContain('批量编辑')
    expect(labels.join('|')).not.toContain('批量删除所选')
    expect(labels.join('|')).toContain('退出批量')
  })

  it('can hide the batch entry when a table keeps its selection column visible', () => {
    const wrapper = mount(SearchToolbar, {
      props: { batchEnabled: true, batchEntryVisible: false, batchMode: false },
      global
    })

    expect(wrapper.find('.search-toolbar__row').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('批量管理')
  })
})
