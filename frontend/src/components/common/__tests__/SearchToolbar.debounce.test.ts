import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import SearchToolbar from '../SearchToolbar.vue'

/**
 * SearchToolbar 防抖与状态同步契约（B1 基线）
 *
 * 保护的现状行为（SearchToolbar.vue）：
 *   - debounce 默认 300ms，组件内 setTimeout 调度（:246-258）
 *   - 中文/日文输入法组词期间不触发搜索（:19-20 / :255 / :288-291 / :368-380）
 *   - 输入框自带清空 → 取消待发 + emit('search','')（:271-275）
 *   - 「清除筛选」按钮 → 取消待发 + emit('clear')，不重复 search（:277-281）
 *   - 回车立即提交（:265-269）
 *   - 外部回写 fieldValues 时文本镜像跟随，但不反向派发 filter-change（:414-428）
 */

const global = {
  stubs: {
    'el-input': { name: 'ElInput', props: ['modelValue'], template: '<div />' },
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

function mountToolbar(props: Record<string, unknown> = {}) {
  return mount(SearchToolbar, { props, global })
}

function searchInput(wrapper: ReturnType<typeof mountToolbar>) {
  return wrapper.findAllComponents({ name: 'ElInput' })[0]
}

describe('SearchToolbar 防抖契约', () => {
  it('默认防抖 300ms：到点才提交一次', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar()

    searchInput(wrapper).vm.$emit('update:modelValue', 'abc')
    await vi.advanceTimersByTimeAsync(299)
    expect(wrapper.emitted('search')).toBeUndefined()

    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.emitted('search')).toEqual([['abc']])
  })

  it('自定义 debounce 生效', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar({ debounce: 50 })

    searchInput(wrapper).vm.$emit('update:modelValue', 'abc')
    await vi.advanceTimersByTimeAsync(49)
    expect(wrapper.emitted('search')).toBeUndefined()

    await vi.advanceTimersByTimeAsync(1)
    expect(wrapper.emitted('search')).toEqual([['abc']])
  })

  it('连续输入合并为一次提交，并 trim 首尾空格', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar({ debounce: 300 })

    const input = searchInput(wrapper)
    input.vm.$emit('update:modelValue', 'a')
    input.vm.$emit('update:modelValue', 'ab')
    input.vm.$emit('update:modelValue', ' abc ')
    await vi.advanceTimersByTimeAsync(300)

    expect(wrapper.emitted('search')).toEqual([['abc']])
  })
})

describe('SearchToolbar 提交入口契约', () => {
  it('回车立即查询，并取消已排队的防抖提交', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar({ debounce: 300 })

    searchInput(wrapper).vm.$emit('update:modelValue', 'abc')
    await searchInput(wrapper).trigger('keyup.enter')

    expect(wrapper.emitted('search')).toEqual([['abc']])

    // 已排队的任务被取消，不会再补发一次
    await vi.advanceTimersByTimeAsync(300)
    expect(wrapper.emitted('search')).toEqual([['abc']])
  })

  it('输入框清空按钮：取消待发并以空串提交一次', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar({ debounce: 300 })

    searchInput(wrapper).vm.$emit('update:modelValue', 'abc')
    searchInput(wrapper).vm.$emit('clear')
    await vi.advanceTimersByTimeAsync(300)

    expect(wrapper.emitted('search')).toEqual([['']])
  })

  it('「清除筛选」按钮：取消待发，只派发 clear 不派发 search', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar({ keyword: '', clearVisible: true, debounce: 300 })

    searchInput(wrapper).vm.$emit('update:modelValue', 'stale')
    await wrapper.get('.search-toolbar__clear').trigger('click')
    await vi.advanceTimersByTimeAsync(300)

    expect(wrapper.emitted('clear')).toHaveLength(1)
    expect(wrapper.emitted('search')).toBeUndefined()
  })

  it('submit 模式：输入不触发，查询按钮才触发', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar({ searchMode: 'submit', queryButton: true, debounce: 10 })

    searchInput(wrapper).vm.$emit('update:modelValue', 'manual')
    await vi.advanceTimersByTimeAsync(10)
    expect(wrapper.emitted('search')).toBeUndefined()

    await wrapper.get('.search-toolbar__query').trigger('click')
    expect(wrapper.emitted('search')).toEqual([['manual']])
  })
})

describe('SearchToolbar 中文输入法契约', () => {
  it('组词期间不提交，compositionend 后补提交', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar({ debounce: 10 })
    const input = searchInput(wrapper)

    input.vm.$emit('compositionstart')
    input.vm.$emit('update:modelValue', '拼')
    input.vm.$emit('update:modelValue', '拼音')
    await vi.advanceTimersByTimeAsync(10)
    expect(wrapper.emitted('search')).toBeUndefined()

    input.vm.$emit('compositionend')
    await vi.advanceTimersByTimeAsync(10)
    expect(wrapper.emitted('search')).toEqual([['拼音']])
  })

  it('文本筛选框组词期间也不提交 filter-change', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar({
      debounce: 10,
      fields: [{ key: 'name', label: '名称', type: 'text' }],
      fieldValues: { name: '' }
    })
    const textInput = wrapper.findAllComponents({ name: 'ElInput' })[1]

    textInput.vm.$emit('compositionstart', 'name')
    textInput.vm.$emit('update:modelValue', '利')
    await vi.advanceTimersByTimeAsync(10)
    expect(wrapper.emitted('filter-change')).toBeUndefined()

    textInput.vm.$emit('compositionend', 'name')
    await vi.advanceTimersByTimeAsync(10)
    expect(wrapper.emitted('filter-change')).toEqual([['name', '利']])
  })
})

describe('SearchToolbar 生命周期与状态同步契约', () => {
  it('卸载后清理定时器，不再派发 search', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar({ debounce: 300 })

    searchInput(wrapper).vm.$emit('update:modelValue', 'abc')
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(300)

    expect(wrapper.emitted('search')).toBeUndefined()
  })

  it('外部回写 keyword 不触发 search（避免双向触发循环）', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar({ keyword: '', debounce: 10 })

    await wrapper.setProps({ keyword: '外部值' })
    await vi.advanceTimersByTimeAsync(10)

    expect(wrapper.emitted('search')).toBeUndefined()
    expect(searchInput(wrapper).props('modelValue')).toBe('外部值')
  })

  it('外部更新 fieldValues 时文本镜像跟随，且不反向派发 filter-change', async () => {
    const wrapper = mountToolbar({
      debounce: 10,
      fields: [{ key: 'name', label: '名称', type: 'text' }],
      fieldValues: { name: '' }
    })
    const textInput = wrapper.findAllComponents({ name: 'ElInput' })[1]
    expect(textInput.props('modelValue')).toBe('')

    await wrapper.setProps({ fieldValues: { name: '利强' } })
    await nextTick()

    expect(textInput.props('modelValue')).toBe('利强')
    expect(wrapper.emitted('filter-change')).toBeUndefined()
  })

  it('切换批量模式不产生意外搜索', async () => {
    vi.useFakeTimers()
    const wrapper = mountToolbar({ batchEnabled: true, batchMode: false, debounce: 10 })

    await wrapper.get('.search-toolbar__batch-entry').trigger('click')
    await vi.advanceTimersByTimeAsync(10)

    expect(wrapper.emitted('toggle-batch-mode')).toHaveLength(1)
    expect(wrapper.emitted('search')).toBeUndefined()
  })
})
