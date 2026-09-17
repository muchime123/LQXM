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
})
