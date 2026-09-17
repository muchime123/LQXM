import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useCatalogFilters } from '../useCatalogFilters'

const STORAGE_KEY = 'catalog-list-sort'

describe('useCatalogFilters', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  describe('初始状态', () => {
    it('默认使用空字符串作为各筛选字段', () => {
      const { query, listFilter } = useCatalogFilters()
      expect(query.keyword).toBe('')
      expect(query.category).toBe('')
      expect(query.status).toBe('')
      expect(listFilter.value).toBe('')
    })

    it('listSort 默认 name-asc（localStorage 无值时）', () => {
      const { listSort } = useCatalogFilters()
      expect(listSort.value).toBe('name-asc')
    })

    it('listSort 从 localStorage 读取已保存的合法值', () => {
      localStorage.setItem(STORAGE_KEY, 'manual')
      const { listSort } = useCatalogFilters()
      expect(listSort.value).toBe('manual')
    })

    it('listSort localStorage 值非法时回退到 name-asc', () => {
      localStorage.setItem(STORAGE_KEY, 'invalid-sort')
      const { listSort } = useCatalogFilters()
      expect(listSort.value).toBe('name-asc')
    })

    it('listSort localStorage 抛错时回退到 name-asc', () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new Error('storage disabled')
      })
      const { listSort } = useCatalogFilters()
      expect(listSort.value).toBe('name-asc')
    })

    it('接受初始 query 注入', () => {
      const { query } = useCatalogFilters({
        keyword: 'foo',
        category: 'bar',
        status: 's1'
      })
      expect(query.keyword).toBe('foo')
      expect(query.category).toBe('bar')
      expect(query.status).toBe('s1')
    })

    it('filterOptions 初始为空数组', () => {
      const { filterOptions } = useCatalogFilters()
      expect(filterOptions.categories).toEqual([])
      expect(filterOptions.statuses).toEqual([])
    })
  })

  describe('updateFilters', () => {
    it('更新指定字段', () => {
      const { query, updateFilters } = useCatalogFilters()
      updateFilters({ category: 'CAT-1', status: 'S-1' })
      expect(query.category).toBe('CAT-1')
      expect(query.status).toBe('S-1')
    })

    it('未提供的字段保持不变', () => {
      const { query, updateFilters } = useCatalogFilters({ status: 'S-1' })
      updateFilters({ category: 'CAT-1' })
      expect(query.status).toBe('S-1')
    })

    it('支持空字符串清空字段', () => {
      const { query, updateFilters } = useCatalogFilters({ status: 'S-1' })
      updateFilters({ status: '' })
      expect(query.status).toBe('')
    })

    it('undefined 值不覆盖字段', () => {
      const { query, updateFilters } = useCatalogFilters({ status: 'S-1' })
      updateFilters({ status: undefined })
      expect(query.status).toBe('S-1')
    })
  })

  describe('clearFilters', () => {
    it('清空所有筛选字段', () => {
      const { query, listFilter, clearFilters } = useCatalogFilters({
        keyword: 'foo',
        category: 'bar',
        status: 's1'
      })
      listFilter.value = 'health-issue'
      clearFilters()
      expect(query.keyword).toBe('')
      expect(query.category).toBe('')
      expect(query.status).toBe('')
      expect(listFilter.value).toBe('')
    })
  })

  describe('setListFilter', () => {
    it('设置列表筛选标签', () => {
      const { listFilter, setListFilter } = useCatalogFilters()
      setListFilter('missing-assets')
      expect(listFilter.value).toBe('missing-assets')
    })
  })

  describe('setFilterOptions', () => {
    it('更新筛选选项', () => {
      const { filterOptions, setFilterOptions } = useCatalogFilters()
      setFilterOptions({
        categories: ['CAT-1', 'CAT-2'],
        statuses: ['ACTIVE', 'DISCONTINUED']
      })
      expect(filterOptions.categories).toEqual(['CAT-1', 'CAT-2'])
      expect(filterOptions.statuses).toEqual(['ACTIVE', 'DISCONTINUED'])
    })

    it('部分更新不影响未提供的选项', () => {
      const { filterOptions, setFilterOptions } = useCatalogFilters()
      setFilterOptions({ statuses: ['S-1'] })
      setFilterOptions({ categories: ['CAT-1'] })
      expect(filterOptions.statuses).toEqual(['S-1'])
      expect(filterOptions.categories).toEqual(['CAT-1'])
    })
  })

  describe('getQueryParams', () => {
    it('只返回非空字段', () => {
      const { query, getQueryParams, setListFilter } = useCatalogFilters()
      query.keyword = 'foo'
      query.category = 'CAT-1'
      setListFilter('missing-docs')
      const params = getQueryParams()
      expect(params).toEqual({
        keyword: 'foo',
        category: 'CAT-1',
        listFilter: 'missing-docs'
      })
    })

    it('所有字段为空时返回空对象', () => {
      const { getQueryParams } = useCatalogFilters()
      expect(getQueryParams()).toEqual({})
    })

    it('包含全部 4 个字段', () => {
      const { query, getQueryParams, setListFilter } = useCatalogFilters()
      query.keyword = 'k'
      query.category = 'c'
      query.status = 's'
      setListFilter('f')
      const params = getQueryParams()
      expect(Object.keys(params).sort()).toEqual([
        'category',
        'keyword',
        'listFilter',
        'status'
      ])
    })
  })

  describe('getSortParams', () => {
    it('manual 排序映射到 sortOrder asc', () => {
      const { listSort, getSortParams } = useCatalogFilters()
      listSort.value = 'manual'
      expect(getSortParams()).toEqual({ sort: 'sortOrder', dir: 'asc' })
    })

    it('name-asc 映射到 name asc', () => {
      const { listSort, getSortParams } = useCatalogFilters()
      listSort.value = 'name-asc'
      expect(getSortParams()).toEqual({ sort: 'name', dir: 'asc' })
    })

    it('name-desc 映射到 name desc', () => {
      const { listSort, getSortParams } = useCatalogFilters()
      listSort.value = 'name-desc'
      expect(getSortParams()).toEqual({ sort: 'name', dir: 'desc' })
    })

    it('assets-desc 映射到 assetCount desc', () => {
      const { listSort, getSortParams } = useCatalogFilters()
      listSort.value = 'assets-desc'
      expect(getSortParams()).toEqual({ sort: 'assetCount', dir: 'desc' })
    })

    it('非法值回退到 name-asc', () => {
      const { listSort, getSortParams } = useCatalogFilters()
      listSort.value = 'invalid' as unknown as typeof listSort.value
      expect(getSortParams()).toEqual({ sort: 'name', dir: 'asc' })
    })
  })

  describe('search', () => {
    it('立即触发回调并传入 keyword', () => {
      const { query, search } = useCatalogFilters()
      query.keyword = 'apple'
      const onSearch = vi.fn()
      search(onSearch)
      expect(onSearch).toHaveBeenCalledWith('apple')
      expect(onSearch).toHaveBeenCalledTimes(1)
    })

    it('清除 pending 的 debouncedSearch 定时器', () => {
      const { query, search, debouncedSearch } = useCatalogFilters()
      query.keyword = 'apple'
      const onSearch = vi.fn()
      debouncedSearch(onSearch, 500)
      search(onSearch)
      vi.advanceTimersByTime(500)
      expect(onSearch).toHaveBeenCalledTimes(1)
    })
  })

  describe('debouncedSearch', () => {
    it('延迟触发回调', () => {
      const { query, debouncedSearch } = useCatalogFilters()
      query.keyword = 'banana'
      const onSearch = vi.fn()
      debouncedSearch(onSearch, 300)
      expect(onSearch).not.toHaveBeenCalled()
      vi.advanceTimersByTime(300)
      expect(onSearch).toHaveBeenCalledWith('banana')
    })

    it('重复调用只保留最后一次', () => {
      const { query, debouncedSearch } = useCatalogFilters()
      const onSearch = vi.fn()
      query.keyword = 'first'
      debouncedSearch(onSearch, 300)
      query.keyword = 'second'
      debouncedSearch(onSearch, 300)
      vi.advanceTimersByTime(300)
      expect(onSearch).toHaveBeenCalledTimes(1)
      expect(onSearch).toHaveBeenCalledWith('second')
    })

    it('默认延迟 300ms', () => {
      const { query, debouncedSearch } = useCatalogFilters()
      query.keyword = 'x'
      const onSearch = vi.fn()
      debouncedSearch(onSearch)
      vi.advanceTimersByTime(299)
      expect(onSearch).not.toHaveBeenCalled()
      vi.advanceTimersByTime(1)
      expect(onSearch).toHaveBeenCalledTimes(1)
    })
  })

  describe('saveListSort', () => {
    it('防抖写入 localStorage', () => {
      const { listSort, saveListSort } = useCatalogFilters()
      listSort.value = 'manual'
      saveListSort()
      expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
      vi.advanceTimersByTime(300)
      expect(localStorage.getItem(STORAGE_KEY)).toBe('manual')
    })

    it('重复调用只保留最后一次', () => {
      const { listSort, saveListSort } = useCatalogFilters()
      listSort.value = 'manual'
      saveListSort()
      listSort.value = 'name-desc'
      saveListSort()
      vi.advanceTimersByTime(300)
      expect(localStorage.getItem(STORAGE_KEY)).toBe('name-desc')
    })

    it('非法值在保存前归一化为 name-asc', () => {
      const { listSort, saveListSort } = useCatalogFilters()
      listSort.value = 'invalid' as unknown as typeof listSort.value
      saveListSort()
      vi.advanceTimersByTime(300)
      expect(localStorage.getItem(STORAGE_KEY)).toBe('name-asc')
      expect(listSort.value).toBe('name-asc')
    })

    it('localStorage 抛错时不抛出异常', () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('quota exceeded')
      })
      const { listSort, saveListSort } = useCatalogFilters()
      listSort.value = 'manual'
      expect(() => {
        saveListSort()
        vi.advanceTimersByTime(300)
      }).not.toThrow()
    })
  })
})
