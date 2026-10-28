import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ListPageLayout from '../ListPageLayout.vue'

describe('ListPageLayout', () => {
  it('hides the page header by default', () => {
    const wrapper = mount(ListPageLayout, {
      props: { title: '文档管理', description: '维护文档资料' }
    })
    // 头部外观现由公共 .page-surface-header 统一承载（B5-B）
    expect(wrapper.find('.page-surface-header').exists()).toBe(false)
  })

  it('exposes loading state and an optional content label', () => {
    const wrapper = mount(ListPageLayout, {
      props: { busy: true, contentLabel: '文档列表' },
      slots: { default: '<div>内容</div>' }
    })
    expect(wrapper.get('.list-page-layout__body').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('.list-page-layout__body').attributes('aria-label')).toBe('文档列表')
  })

  it('allows exceptional pages to show it explicitly', () => {
    const wrapper = mount(ListPageLayout, {
      props: { title: '文档管理', description: '维护文档资料', showHeader: true }
    })
    expect(wrapper.get('h1').text()).toBe('文档管理')
    expect(wrapper.get('.page-surface-header p').text()).toBe('维护文档资料')
  })

  it('renders named layout regions only when provided', () => {
    const wrapper = mount(ListPageLayout, {
      slots: {
        summary: '<div>统计摘要</div>',
        toolbar: '<section aria-label="列表工具栏"><button>筛选</button><button>新增</button></section>',
        pagination: '<div>分页</div>',
        default: '<div>内容</div>'
      }
    })
    expect(wrapper.find('.list-page-layout__summary').exists()).toBe(true)
    expect(wrapper.find('.list-page-layout__toolbar').exists()).toBe(true)
    expect(wrapper.find('.list-page-layout__pagination-bar').exists()).toBe(true)
  })
})
