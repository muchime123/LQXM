import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, nextTick, reactive } from 'vue'
import ElementPlus from 'element-plus'
import LqPagination from '../LqPagination.vue'
import { useListPagination } from '@/composables/list/useListPagination'

/**
 * LqPagination 分页契约（B1 基线 + B2 扩展）
 *
 * 上半部分保护「模式 A · 分页对象模式」的既有行为：
 *   - 显示 1 基、存储 0 基，换算只发生在组件内
 *   - change 只挂在 current-change / size-change，不在 update:* 上（避免重复请求）
 *
 * 下半部分为 B2 新增的「模式 B · 独立状态模式」以及两种模式必须共同遵守的
 * 统一规则（0/1 基、请求触发、视觉一致）。
 *
 * 历史：B1 阶段模式 B 的 5 条用例以 it.fails 标记「期望失败」；B2 实现后
 * 已移除 .fails 转为必须通过，并补充了模式优先级与边界用例。
 */

const ElPaginationStub = {
  name: 'ElPagination',
  props: [
    'currentPage',
    'pageSize',
    'total',
    'layout',
    'pageSizes',
    'pagerCount',
    'disabled',
    'hideOnSinglePage'
  ],
  emits: ['update:currentPage', 'update:pageSize', 'current-change', 'size-change'],
  template: '<div class="pagination-stub" />'
}

const global = { stubs: { ElPagination: ElPaginationStub } }

function mountPagination(options: Record<string, unknown> = {}) {
  const pagination = useListPagination({ page: 0, size: 20, total: 120 })
  vi.spyOn(pagination, 'onPageChange')
  vi.spyOn(pagination, 'onPageSizeChange')
  const wrapper = mount(LqPagination, { props: { pagination, ...options }, global })
  return { wrapper, pagination, control: wrapper.findComponent({ name: 'ElPagination' }) }
}

describe('LqPagination 0/1 基换算契约', () => {
  it('第 0 页显示为第 1 页', () => {
    const { control } = mountPagination()
    expect(control.props('currentPage')).toBe(1)
  })

  it('第 2 页（0 基）显示为第 3 页', () => {
    const pagination = useListPagination({ page: 2, size: 20, total: 120 })
    const wrapper = mount(LqPagination, { props: { pagination }, global })
    expect(wrapper.findComponent({ name: 'ElPagination' }).props('currentPage')).toBe(3)
  })

  it('点击第 N 页换算回 0 基并写入 pageInfo', async () => {
    const { pagination, control } = mountPagination()

    await control.vm.$emit('update:currentPage', 3)

    expect(pagination.onPageChange).toHaveBeenCalledWith(3)
    expect(pagination.pageInfo.page).toBe(2)
    expect(pagination.getPageParams()).toEqual({ page: 2, size: 20 })
  })

  it('点击第 1 页换算为 0', async () => {
    const { pagination, control } = mountPagination()

    await control.vm.$emit('update:currentPage', 1)

    expect(pagination.pageInfo.page).toBe(0)
  })

  it('resetPage 后显示回到第 1 页', async () => {
    const { pagination, control } = mountPagination()

    await control.vm.$emit('update:currentPage', 4)
    expect(control.props('currentPage')).toBe(4)

    pagination.resetPage()
    await control.vm.$forceUpdate?.()
    expect(pagination.pageInfo.page).toBe(0)
    expect(pagination.pageInfo.size).toBe(20)
  })
})

describe('LqPagination 请求触发契约（不产生重复请求）', () => {
  it('单次翻页只触发一次 change', async () => {
    const { wrapper, control } = mountPagination()

    // 真实 el-pagination 点击时会同时下发 update:currentPage 与 current-change
    await control.vm.$emit('update:currentPage', 2)
    await control.vm.$emit('current-change', 2)

    expect(wrapper.emitted('change')).toHaveLength(1)
  })

  it('仅 update:currentPage（无 current-change）不触发 change', async () => {
    const { wrapper, control } = mountPagination()

    await control.vm.$emit('update:currentPage', 2)

    expect(wrapper.emitted('change')).toBeUndefined()
  })

  it('单次修改页大小只触发一次 change 且页码归零', async () => {
    const { wrapper, pagination, control } = mountPagination()

    await control.vm.$emit('update:currentPage', 3)
    await control.vm.$emit('update:pageSize', 50)
    await control.vm.$emit('size-change', 50)

    expect(pagination.onPageSizeChange).toHaveBeenCalledWith(50)
    expect(pagination.pageInfo.page).toBe(0)
    expect(pagination.pageInfo.size).toBe(50)
    expect(wrapper.emitted('change')).toHaveLength(1)
  })

  it('两次独立交互触发两次 change（不合并、不丢失）', async () => {
    const { wrapper, control } = mountPagination()

    await control.vm.$emit('current-change', 2)
    await control.vm.$emit('size-change', 50)

    expect(wrapper.emitted('change')).toHaveLength(2)
  })
})

describe('LqPagination 属性透传契约', () => {
  it('full 模式 layout 含 total 与 sizes', () => {
    const { control } = mountPagination({ mode: 'full' })
    expect(control.props('layout')).toBe('total, sizes, prev, pager, next')
  })

  it('compact 模式 layout 只保留翻页', () => {
    const { control } = mountPagination({ mode: 'compact' })
    expect(control.props('layout')).toBe('prev, pager, next')
  })

  it('透传 pageSizes / pagerCount / disabled / hideOnSinglePage', () => {
    const { control } = mountPagination({
      pageSizes: [10, 30],
      pagerCount: 5,
      disabled: true,
      hideOnSinglePage: true
    })

    expect(control.props('pageSizes')).toEqual([10, 30])
    expect(control.props('pagerCount')).toBe(5)
    expect(control.props('disabled')).toBe(true)
    expect(control.props('hideOnSinglePage')).toBe(true)
  })

  it('total 跟随 pageInfo.total', async () => {
    const { pagination, control } = mountPagination()
    expect(control.props('total')).toBe(120)

    pagination.pageInfo.total = 240
    await nextTick()

    expect(control.props('total')).toBe(240)
  })

  it('挂载时不因 aria 兜底（onMounted querySelector）抛错', () => {
    expect(() => mountPagination()).not.toThrow()
  })
})

describe('LqPagination 与真实 Element Plus 集成', () => {
  it('点击下一页真实触发 0 基换算与 change', async () => {
    const pagination = useListPagination({ page: 0, size: 20, total: 120 })
    const wrapper = mount(LqPagination, {
      props: { pagination },
      global: { plugins: [ElementPlus] },
      attachTo: document.body
    })
    await wrapper.vm.$nextTick()

    const next = wrapper.find('.btn-next')
    expect(next.exists()).toBe(true)

    await next.trigger('click')

    expect(pagination.pageInfo.page).toBe(1)
    expect(wrapper.emitted('change')).toHaveLength(1)

    wrapper.unmount()
  })
})

// ---------------------------------------------------------------------------
// 模式 B · 独立状态模式（B2 新增能力）
// 让拥有独立分页状态的页面可以直接受控接入，无需先引入 useListPagination。
// 这 5 条在 B1 阶段以 it.fails 标记「期望失败」，B2 实现后移除 .fails 转为必须通过。
// ---------------------------------------------------------------------------
describe('模式 B · 独立状态模式（B2 新增能力）', () => {
  function mountIndependent(template: string, setup?: () => Record<string, unknown>) {
    const Host = defineComponent({
      components: { LqPagination },
      setup: setup ?? (() => ({})),
      template
    })
    return mount(Host, { global })
  }

  it('支持 :total / :page / :page-size 独立接入（不依赖 useListPagination）', () => {
    const wrapper = mountIndependent(`<LqPagination :total="120" :page="2" :page-size="20" />`)
    const control = wrapper.findComponent({ name: 'ElPagination' })

    expect(control.props('total')).toBe(120)
    expect(control.props('pageSize')).toBe(20)
    // 0 基的 2 → 显示第 3 页
    expect(control.props('currentPage')).toBe(3)
  })

  it('切页派发 update:page，值为 0 基', async () => {
    const wrapper = mountIndependent(`<LqPagination :total="120" :page="0" :page-size="20" />`)
    const control = wrapper.findComponent({ name: 'ElPagination' })

    await control.vm.$emit('update:currentPage', 3)

    expect(wrapper.findComponent(LqPagination).emitted('update:page')).toEqual([[2]])
  })

  it('修改页大小派发 update:pageSize 且页码归零', async () => {
    const wrapper = mountIndependent(`<LqPagination :total="120" :page="2" :page-size="20" />`)
    const control = wrapper.findComponent({ name: 'ElPagination' })

    await control.vm.$emit('update:pageSize', 50)

    expect(wrapper.findComponent(LqPagination).emitted('update:pageSize')).toEqual([[50]])
    expect(wrapper.findComponent(LqPagination).emitted('update:page')).toEqual([[0]])
  })

  it('外部回写 page 时显示同步，且不额外触发 change', async () => {
    // 用宿主模板承载外部状态，模拟调用方的受控回写
    const state = reactive({ page: 0 })
    const wrapper = mountIndependent(`<LqPagination :total="120" :page="state.page" :page-size="20" />`, () => ({
      state
    }))
    const control = wrapper.findComponent({ name: 'ElPagination' })
    expect(control.props('currentPage')).toBe(1)

    state.page = 4
    await nextTick()

    expect(control.props('currentPage')).toBe(5)
    expect(wrapper.findComponent(LqPagination).emitted('change')).toBeUndefined()
  })

  it('细分事件 page-change / size-change 与兼容事件 change 并存', async () => {
    const wrapper = mountIndependent(`<LqPagination :total="120" :page="0" :page-size="20" />`)
    const lq = wrapper.findComponent(LqPagination)
    const control = wrapper.findComponent({ name: 'ElPagination' })

    await control.vm.$emit('update:currentPage', 2)
    await control.vm.$emit('current-change', 2)

    expect(lq.emitted('change')).toHaveLength(1)
    expect(lq.emitted('page-change')).toEqual([[1]])
  })
})

// ---------------------------------------------------------------------------
// 统一规则：模式判定、边界值、请求触发、视觉一致
// ---------------------------------------------------------------------------
describe('两种模式的统一规则（B2）', () => {
  function mountIndependent(template: string, setup?: () => Record<string, unknown>) {
    const Host = defineComponent({
      components: { LqPagination },
      setup: setup ?? (() => ({})),
      template
    })
    return mount(Host, { global })
  }

  it('同时传入 pagination 与独立 props 时以模式 A 为准（模式判定唯一）', () => {
    const pagination = useListPagination({ page: 0, size: 20, total: 120 })
    const wrapper = mountIndependent(
      `<LqPagination :pagination="pagination" :total="999" :page="3" :page-size="99" />`,
      () => ({ pagination })
    )
    const control = wrapper.findComponent({ name: 'ElPagination' })

    expect(control.props('total')).toBe(120)
    expect(control.props('pageSize')).toBe(20)
    expect(control.props('currentPage')).toBe(1)
  })

  it('模式 B 不传 page / page-size 时使用默认值，且挂载不抛错', () => {
    let wrapper!: ReturnType<typeof mountIndependent>
    expect(() => {
      wrapper = mountIndependent(`<LqPagination :total="0" />`)
    }).not.toThrow()

    const control = wrapper.findComponent({ name: 'ElPagination' })
    expect(control.props('total')).toBe(0)
    expect(control.props('currentPage')).toBe(1)
    expect(control.props('pageSize')).toBe(20)
  })

  it('page 为负数时 clamp 到第 1 页（不产生 0 或负的显示页码）', () => {
    const wrapper = mountIndependent(`<LqPagination :total="120" :page="-5" :page-size="20" />`)
    expect(wrapper.findComponent({ name: 'ElPagination' }).props('currentPage')).toBe(1)
  })

  it('模式 B 单次翻页不产生重复 change', async () => {
    const wrapper = mountIndependent(`<LqPagination :total="120" :page="0" :page-size="20" />`)
    const lq = wrapper.findComponent(LqPagination)
    const control = wrapper.findComponent({ name: 'ElPagination' })

    // 真实 el-pagination 点击时同时下发 update:currentPage 与 current-change
    await control.vm.$emit('update:currentPage', 2)
    await control.vm.$emit('current-change', 2)

    expect(lq.emitted('change')).toHaveLength(1)
  })

  it('模式 B 仅 update:* 同步状态时不触发 change（避免重复请求）', async () => {
    const wrapper = mountIndependent(`<LqPagination :total="120" :page="0" :page-size="20" />`)
    const lq = wrapper.findComponent(LqPagination)
    const control = wrapper.findComponent({ name: 'ElPagination' })

    await control.vm.$emit('update:currentPage', 2)

    expect(lq.emitted('update:page')).toEqual([[1]])
    expect(lq.emitted('change')).toBeUndefined()
  })

  it('模式 A 不派发模式 B 的细分事件（旧调用方行为不变）', async () => {
    const { wrapper, control } = mountPagination()

    await control.vm.$emit('update:currentPage', 2)
    await control.vm.$emit('current-change', 2)
    await control.vm.$emit('update:pageSize', 50)
    await control.vm.$emit('size-change', 50)

    expect(wrapper.emitted('update:page')).toBeUndefined()
    expect(wrapper.emitted('update:pageSize')).toBeUndefined()
    expect(wrapper.emitted('page-change')).toBeUndefined()
    expect(wrapper.emitted('size-change')).toBeUndefined()
    expect(wrapper.emitted('change')).toHaveLength(2)
  })

  it('两种模式共用同一套视觉 class、layout 与分页选项（视觉一致）', () => {
    const modeAControl = mountPagination({ mode: 'full', pageSizes: [20, 50], pagerCount: 7 }).control
    const modeBWrapper = mountIndependent(
      `<LqPagination :total="120" :page="0" :page-size="20" mode="full" :page-sizes="[20,50]" :pager-count="7" />`
    )
    const modeBControl = modeBWrapper.findComponent({ name: 'ElPagination' })

    expect(modeAControl.props('layout')).toBe(modeBControl.props('layout'))
    expect(modeAControl.props('pageSizes')).toEqual(modeBControl.props('pageSizes'))
    expect(modeAControl.props('pagerCount')).toBe(modeBControl.props('pagerCount'))
    expect(modeAControl.classes()).toEqual(modeBControl.classes())
    expect(modeBControl.classes()).toContain('lq-pagination')
    expect(modeBControl.classes()).toContain('lq-pagination--full')
  })
})

describe('模式 B 与真实 Element Plus 集成', () => {
  it('点击下一页真实下发 0 基 update:page 与一次 change', async () => {
    const state = reactive({ page: 0, size: 20 })
    const Host = defineComponent({
      components: { LqPagination },
      setup: () => ({ state }),
      template: `<LqPagination :total="120" :page="state.page" :page-size="state.size" @update:page="p => state.page = p" @update:page-size="s => state.size = s" />`
    })
    const wrapper = mount(Host, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    const lq = wrapper.findComponent(LqPagination)
    await nextTick()

    const next = wrapper.find('.btn-next')
    expect(next.exists()).toBe(true)

    await next.trigger('click')

    expect(lq.emitted('update:page')).toEqual([[1]])
    expect(state.page).toBe(1)
    expect(lq.emitted('change')).toHaveLength(1)

    wrapper.unmount()
  })
})
