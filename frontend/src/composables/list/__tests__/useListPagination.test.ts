import { describe, expect, it, vi } from 'vitest'
import { useListPagination } from '../useListPagination'

describe('useListPagination', () => {
  describe('初始状态', () => {
    it('使用默认值（page=0, size=20, total=0）', () => {
      const { pageInfo, totalPages } = useListPagination()
      expect(pageInfo.page).toBe(0)
      expect(pageInfo.size).toBe(20)
      expect(pageInfo.total).toBe(0)
      expect(totalPages.value).toBe(0)
    })

    it('支持自定义初始值', () => {
      const { pageInfo } = useListPagination({ page: 2, size: 50, total: 100 })
      expect(pageInfo.page).toBe(2)
      expect(pageInfo.size).toBe(50)
      expect(pageInfo.total).toBe(100)
    })
  })

  describe('updateFromResponse', () => {
    it('优先使用 totalElements / totalPages / number', () => {
      const { pageInfo, updateFromResponse } = useListPagination()
      updateFromResponse({
        totalElements: 120,
        totalPages: 6,
        number: 3,
        size: 20
      })
      expect(pageInfo.total).toBe(120)
      expect(pageInfo.pages).toBe(6)
      expect(pageInfo.page).toBe(3)
      expect(pageInfo.size).toBe(20)
    })

    it('回退到 total / pages / page', () => {
      const { pageInfo, updateFromResponse } = useListPagination()
      updateFromResponse({
        total: 80,
        pages: 4,
        page: 2,
        size: 20
      })
      expect(pageInfo.total).toBe(80)
      expect(pageInfo.pages).toBe(4)
      expect(pageInfo.page).toBe(2)
    })

    it('responsePage 为负数时 clamp 到 0', () => {
      const { pageInfo, updateFromResponse } = useListPagination({ page: 5 })
      updateFromResponse({ number: -1, size: 20 })
      expect(pageInfo.page).toBe(0)
    })

    it('未提供 size 时保留当前 size', () => {
      const { pageInfo, updateFromResponse } = useListPagination({ size: 30 })
      updateFromResponse({ totalElements: 100 })
      expect(pageInfo.size).toBe(30)
    })

    it('全空响应时 total/pages 归零', () => {
      const { pageInfo, updateFromResponse } = useListPagination({ total: 100 })
      updateFromResponse({})
      expect(pageInfo.total).toBe(0)
      expect(pageInfo.pages).toBe(0)
    })
  })

  describe('onPageChange', () => {
    it('将 1-based 页码转为 0-based', () => {
      const { pageInfo, onPageChange } = useListPagination()
      onPageChange(3)
      expect(pageInfo.page).toBe(2)
    })

    it('触发 reload 回调', () => {
      const reloadFn = { fn: () => {} }
      const spy = vi.spyOn(reloadFn, 'fn')
      const { onPageChange } = useListPagination()
      onPageChange(2, reloadFn.fn)
      expect(spy).toHaveBeenCalledTimes(1)
    })

    it('页码为 1 时转为 0', () => {
      const { pageInfo, onPageChange } = useListPagination()
      onPageChange(1)
      expect(pageInfo.page).toBe(0)
    })

    it('负数页码 clamp 到 0', () => {
      const { pageInfo, onPageChange } = useListPagination()
      onPageChange(-5)
      expect(pageInfo.page).toBe(0)
    })
  })

  describe('onPageSizeChange', () => {
    it('更新 size 并重置 page 为 0', () => {
      const { pageInfo, onPageSizeChange } = useListPagination({ page: 3 })
      onPageSizeChange(50)
      expect(pageInfo.size).toBe(50)
      expect(pageInfo.page).toBe(0)
    })

    it('触发 reload 回调', () => {
      const reloadFn = { fn: () => {} }
      const spy = vi.spyOn(reloadFn, 'fn')
      const { onPageSizeChange } = useListPagination()
      onPageSizeChange(50, reloadFn.fn)
      expect(spy).toHaveBeenCalledTimes(1)
    })
  })

  describe('resetPage', () => {
    it('重置 page 为 0', () => {
      const { pageInfo, resetPage } = useListPagination({ page: 5 })
      resetPage()
      expect(pageInfo.page).toBe(0)
    })
  })

  describe('getPageParams / queryParams', () => {
    it('返回当前 page 与 size', () => {
      const { getPageParams, queryParams } = useListPagination({ page: 2, size: 30 })
      expect(getPageParams()).toEqual({ page: 2, size: 30 })
      expect(queryParams.value).toEqual({ page: 2, size: 30 })
    })
  })

  describe('totalPages 计算属性', () => {
    it('优先使用 pages 字段', () => {
      const { totalPages, updateFromResponse } = useListPagination()
      updateFromResponse({ totalPages: 7 })
      expect(totalPages.value).toBe(7)
    })

    it('pages 为 0 时用 total/size 向上取整', () => {
      const { totalPages } = useListPagination({ total: 105, size: 20 })
      expect(totalPages.value).toBe(6)
    })

    it('total 与 size 均为 0 时为 0', () => {
      const { totalPages } = useListPagination()
      expect(totalPages.value).toBe(0)
    })
  })

  describe('indexMethod', () => {
    it('第一页序号从 1 开始', () => {
      const { indexMethod } = useListPagination({ page: 0, size: 20 })
      expect(indexMethod(0)).toBe(1)
      expect(indexMethod(4)).toBe(5)
    })

    it('第二页序号接续', () => {
      const { indexMethod } = useListPagination({ page: 1, size: 20 })
      expect(indexMethod(0)).toBe(21)
      expect(indexMethod(9)).toBe(30)
    })

    it('跨页计算正确', () => {
      const { indexMethod } = useListPagination({ page: 3, size: 50 })
      expect(indexMethod(0)).toBe(151)
    })
  })
})
