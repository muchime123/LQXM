import { describe, expect, it } from 'vitest'
import {
  catalogSpecificationKey,
  classifyCatalogTags,
  isMoldSpecificationTag,
  isOutputSpecificationTag
} from '../specificationTags'

describe('catalog specification tags', () => {
  it('separates specification dimensions from content tags', () => {
    expect(classifyCatalogTags('设备正面，方模具，一出一，细节图')).toEqual({
      contentTags: ['设备正面', '细节图'],
      outputCount: '一出一',
      moldShape: '方模具'
    })
  })

  it('recognizes common output and mold labels', () => {
    expect(isOutputSpecificationTag('一出四')).toBe(true)
    expect(isMoldSpecificationTag('圆模具')).toBe(true)
    expect(isMoldSpecificationTag('设备正面')).toBe(false)
  })

  it('builds a stable key for automatic matching', () => {
    expect(catalogSpecificationKey(' 一出一 ', ' 方模具 ')).toBe('一出一|方模具')
  })
})
