import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import LqPagination from '../LqPagination.vue'
import { useListPagination } from '@/composables/list/useListPagination'

function createPagination() {
  const pagination = useListPagination({ page: 1, size: 20, total: 120 })
  vi.spyOn(pagination, 'onPageChange')
  vi.spyOn(pagination, 'onPageSizeChange')
  return pagination
}

const global = {
  stubs: {
    ElPagination: {
      name: 'ElPagination',
      props: ['currentPage', 'pageSize', 'layout'],
      emits: ['update:currentPage', 'update:pageSize', 'current-change', 'size-change'],
      template: '<div class="pagination-stub" />'
    }
  }
}

describe('LqPagination', () => {
  it('converts the zero-based page to the displayed page', () => {
    const pagination = createPagination()
    const wrapper = mount(LqPagination, { props: { pagination }, global })
    expect(wrapper.findComponent({ name: 'ElPagination' }).props('currentPage')).toBe(2)
  })

  it('delegates page and size updates and emits one change notification', () => {
    const pagination = createPagination()
    const wrapper = mount(LqPagination, { props: { pagination }, global })
    const control = wrapper.findComponent({ name: 'ElPagination' })
    control.vm.$emit('update:currentPage', 3)
    control.vm.$emit('current-change', 3)
    control.vm.$emit('update:pageSize', 50)
    control.vm.$emit('size-change', 50)
    expect(pagination.onPageChange).toHaveBeenCalledWith(3)
    expect(pagination.onPageSizeChange).toHaveBeenCalledWith(50)
    expect(wrapper.emitted('change')).toHaveLength(2)
  })

  it('removes totals and page-size controls in compact mode', () => {
    const wrapper = mount(LqPagination, { props: { pagination: createPagination(), mode: 'compact' }, global })
    expect(wrapper.findComponent({ name: 'ElPagination' }).props('layout')).toBe('prev, pager, next')
  })

  // 请求页码契约：页面侧（如 WebsiteBannerView）把 pageInfo 直接作为 0 基请求参数下发，
  // 组件显示 1 基、存储 0 基，这条用例钉住「点击 → 请求」的换算，防止再出现两套基准混用。
  it('round-trips page clicks and size changes into zero-based request params', async () => {
    const pagination = useListPagination({ size: 20, total: 120 })
    const wrapper = mount(LqPagination, { props: { pagination }, global })
    const control = wrapper.findComponent({ name: 'ElPagination' })

    // 初始：显示第 1 页 → 请求 page=0
    expect(control.props('currentPage')).toBe(1)
    expect(pagination.getPageParams()).toEqual({ page: 0, size: 20 })

    // 点击第 2 页：el-pagination 下发 1 基的 2 → 请求 page=1
    await control.vm.$emit('update:currentPage', 2)
    expect(pagination.getPageParams()).toEqual({ page: 1, size: 20 })

    // 回到第 1 页 → 请求 page=0
    await control.vm.$emit('update:currentPage', 1)
    expect(pagination.getPageParams()).toEqual({ page: 0, size: 20 })

    // 每页条数变更：页码归零，size 生效
    await control.vm.$emit('update:currentPage', 3)
    await control.vm.$emit('update:pageSize', 50)
    expect(pagination.getPageParams()).toEqual({ page: 0, size: 50 })

    // 每次交互都只通知一次刷新
    expect(wrapper.emitted('change')).toBeUndefined()
  })
})
