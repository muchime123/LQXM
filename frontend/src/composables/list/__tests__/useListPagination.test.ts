import { describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
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

  // ------------------------------------------------------------------
  // B1 补充：响应映射优先级、边界条件与实例隔离
  // ------------------------------------------------------------------
  describe('B1 补充：响应字段优先级', () => {
    it('number 与 page 同时存在时以 number 为准', () => {
      const { pageInfo, updateFromResponse } = useListPagination()
      updateFromResponse({ number: 1, page: 5, size: 20 })
      expect(pageInfo.page).toBe(1)
    })

    it('totalElements 为 0 时取 0，不回退到 total', () => {
      const { pageInfo, updateFromResponse } = useListPagination()
      updateFromResponse({ totalElements: 0, total: 80, size: 20 })
      expect(pageInfo.total).toBe(0)
    })

    it('totalPages 为 0 时 pages 取 0', () => {
      const { pageInfo, updateFromResponse } = useListPagination()
      updateFromResponse({ totalPages: 0, pages: 3, size: 20 })
      expect(pageInfo.pages).toBe(0)
    })

    it('size 为 0 时保留 0（不静默回退）', () => {
      const { pageInfo, updateFromResponse } = useListPagination({ size: 20 })
      updateFromResponse({ size: 0, totalElements: 100 })
      expect(pageInfo.size).toBe(0)
    })
  })

  describe('B1 补充：边界条件', () => {
    it('onPageChange(0) 与负值同样 clamp 到 0', () => {
      const { pageInfo, onPageChange } = useListPagination({ page: 4 })
      onPageChange(0)
      expect(pageInfo.page).toBe(0)
      onPageChange(-3)
      expect(pageInfo.page).toBe(0)
    })

    it('resetPage 只重置 page，不改变 size 与 total', () => {
      const { pageInfo, resetPage } = useListPagination({ page: 5, size: 50, total: 300 })
      resetPage()
      expect(pageInfo.page).toBe(0)
      expect(pageInfo.size).toBe(50)
      expect(pageInfo.total).toBe(300)
    })

    it('resetPage 后 indexMethod 回到从 1 开始', () => {
      const { pageInfo, indexMethod, resetPage } = useListPagination({ page: 3, size: 20 })
      expect(indexMethod(0)).toBe(61)
      resetPage()
      expect(pageInfo.page).toBe(0)
      expect(indexMethod(0)).toBe(1)
    })

    it('getPageParams 返回拷贝，修改返回值不污染内部状态', () => {
      const { getPageParams, pageInfo } = useListPagination({ page: 2, size: 20 })
      const params = getPageParams()
      params.page = 99
      expect(pageInfo.page).toBe(2)
    })

    it('queryParams 随 page / size 变化保持响应式', async () => {
      const { queryParams, onPageChange, onPageSizeChange } = useListPagination()
      expect(queryParams.value).toEqual({ page: 0, size: 20 })

      onPageChange(3)
      await nextTick()
      expect(queryParams.value).toEqual({ page: 2, size: 20 })

      onPageSizeChange(50)
      await nextTick()
      expect(queryParams.value).toEqual({ page: 0, size: 50 })
    })

    it('pages 已由响应给出时 totalPages 不受 total 影响', () => {
      const { totalPages, updateFromResponse } = useListPagination({ size: 20 })
      updateFromResponse({ totalPages: 2, totalElements: 1000 })
      expect(totalPages.value).toBe(2)
    })
  })

  describe('B1 补充：实例隔离', () => {
    it('多个实例之间不共享分页状态', () => {
      const first = useListPagination({ page: 0, size: 20 })
      const second = useListPagination({ page: 0, size: 20 })

      first.onPageChange(4)

      expect(first.pageInfo.page).toBe(3)
      expect(second.pageInfo.page).toBe(0)
      expect(first.pageInfo).not.toBe(second.pageInfo)
    })
  })
})
