import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import SourceTrace from '../SourceTrace.vue'

describe('SourceTrace', () => {
  it('keeps the complete source available when the visible label is shortened', () => {
    const wrapper = mount(SourceTrace, {
      props: { sourceFile: 'products.xlsx', sourceSheet: '产品', sourceRowNumber: 18, importBatchId: 7 }
    })

    expect(wrapper.attributes('title')).toContain('products.xlsx')
    expect(wrapper.attributes('title')).toContain('18')
    expect(wrapper.attributes('aria-label')).toBe(wrapper.attributes('title'))
    expect(wrapper.get('svg').attributes('aria-hidden')).toBe('true')
  })

  it('uses the fallback as both visible and accessible source information', () => {
    const wrapper = mount(SourceTrace, { props: { fallback: '人工维护' } })

    expect(wrapper.text()).toBe('人工维护')
    expect(wrapper.attributes('aria-label')).toBe('人工维护')
  })
})
