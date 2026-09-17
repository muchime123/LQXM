import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import LqSkeleton from '../LqSkeleton.vue'

const global = {
  stubs: {
    ElSkeletonItem: {
      props: ['variant'],
      template: '<div class="stub-skeleton-item" :class="`stub-${variant}`" />'
    },
    ElSkeleton: {
      props: ['rows', 'animated'],
      template: '<div class="stub-el-skeleton" />'
    }
  }
}

describe('LqSkeleton', () => {
  it('renders card variant with specified count', () => {
    const wrapper = mount(LqSkeleton, {
      props: { variant: 'card', count: 4, loading: true },
      global
    })
    const cards = wrapper.findAll('.lq-skeleton__card')
    expect(cards).toHaveLength(4)
  })

  it('renders list variant with specified count', () => {
    const wrapper = mount(LqSkeleton, {
      props: { variant: 'list', count: 5, loading: true },
      global
    })
    const rows = wrapper.findAll('.lq-skeleton__list-row')
    expect(rows).toHaveLength(5)
  })

  it('renders table variant with specified rows and columns', () => {
    const wrapper = mount(LqSkeleton, {
      props: { variant: 'table', rows: 6, columns: 3, loading: true },
      global
    })
    const tableRows = wrapper.findAll('.lq-skeleton__table-row')
    expect(tableRows).toHaveLength(6)
    const headerItems = wrapper.findAll('.lq-skeleton__table-header .stub-skeleton-item')
    expect(headerItems).toHaveLength(3)
  })

  it('renders default slot content when loading is false', () => {
    const wrapper = mount(LqSkeleton, {
      props: { loading: false },
      slots: { default: '<div class="my-content">Real Content</div>' },
      global
    })
    expect(wrapper.find('.my-content').exists()).toBe(true)
    expect(wrapper.find('.lq-skeleton').exists()).toBe(false)
  })

  it('renders skeleton when loading is true', () => {
    const wrapper = mount(LqSkeleton, {
      props: { loading: true },
      slots: { default: '<div class="my-content">Real Content</div>' },
      global
    })
    expect(wrapper.find('.lq-skeleton').exists()).toBe(true)
    expect(wrapper.find('.my-content').exists()).toBe(false)
  })

  it('has role=status and aria-live=polite for accessibility', () => {
    const wrapper = mount(LqSkeleton, {
      props: { variant: 'card', loading: true },
      global
    })
    const root = wrapper.find('.lq-skeleton')
    expect(root.attributes('role')).toBe('status')
    expect(root.attributes('aria-live')).toBe('polite')
  })
})
