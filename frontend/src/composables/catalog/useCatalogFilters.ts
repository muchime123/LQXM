import { ref, reactive } from 'vue'

const LIST_SORT_STORAGE_KEY = 'catalog-list-sort'
const LIST_SORT_OPTIONS = ['manual', 'name-asc', 'name-desc', 'assets-desc']
type ListSort = (typeof LIST_SORT_OPTIONS)[number]
type CatalogFilterQuery = {
  keyword: string
  category: string
  status: string
  listFilter: string
}
type CatalogFilterOptions = {
  categories: string[]
  statuses: string[]
}

function normalizeListSort(value: unknown): ListSort {
  return typeof value === 'string' && LIST_SORT_OPTIONS.includes(value) ? value : 'name-asc'
}

function loadListSort() {
  try {
    return normalizeListSort(localStorage.getItem(LIST_SORT_STORAGE_KEY))
  } catch {
    return 'name-asc'
  }
}

/**
 * 产品目录筛选和搜索状态管理
 * @returns {Object} 筛选相关的状态和方法
 */
export function useCatalogFilters(initialQuery: Partial<CatalogFilterQuery> = {}) {
  // 查询参数
  const query = reactive({
    keyword: initialQuery.keyword || '',
    category: initialQuery.category || '',
    status: initialQuery.status || ''
  })

  // 筛选选项
  const filterOptions = reactive<CatalogFilterOptions>({
    categories: [],
    statuses: []
  })

  // 列表排序
  const listSort = ref(loadListSort())

  // 列表筛选标签（按健康问题类型）
  const listFilter = ref('')

  // 防抖定时器
  let searchDebounceTimer: ReturnType<typeof setTimeout> | null = null
  let localStorageDebounceTimer: ReturnType<typeof setTimeout> | null = null

  /**
   * 防抖搜索
   * @param {Function} onSearch 搜索回调
   * @param {number} delay 延迟时间（毫秒）
   */
  function debouncedSearch(onSearch: (keyword: string) => void, delay = 300) {
    if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
    searchDebounceTimer = setTimeout(() => {
      onSearch(query.keyword)
    }, delay)
  }

  /**
   * 立即搜索
   * @param {Function} onSearch 搜索回调
   */
  function search(onSearch: (keyword: string) => void) {
    if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
    onSearch(query.keyword)
  }

  /**
   * 更新筛选条件
   * @param {Object} filters 筛选条件
   */
  function updateFilters(filters: Partial<CatalogFilterQuery>) {
    if (filters.category !== undefined) query.category = filters.category
    if (filters.status !== undefined) query.status = filters.status
  }

  /**
   * 清除所有筛选
   */
  function clearFilters() {
    query.keyword = ''
    query.category = ''
    query.status = ''
    listFilter.value = ''
  }

  /**
   * 设置列表筛选标签
   */
  function setListFilter(filterKey: string) {
    listFilter.value = filterKey
  }

  /**
   * 保存排序设置到 localStorage
   */
  function saveListSort() {
    if (localStorageDebounceTimer) clearTimeout(localStorageDebounceTimer)
    localStorageDebounceTimer = setTimeout(() => {
      listSort.value = normalizeListSort(listSort.value)
      try {
        localStorage.setItem(LIST_SORT_STORAGE_KEY, listSort.value)
      } catch {
        /* ignore */
      }
    }, 300)
  }

  /**
   * 设置列表排序（外部传入字符串，内部归一化为合法 ListSort）
   */
  function setListSort(sort: string) {
    listSort.value = normalizeListSort(sort)
  }

  /**
   * 设置筛选选项
   * @param {Object} options 筛选选项
   */
  function setFilterOptions(options: Partial<CatalogFilterOptions>) {
    if (options.categories) filterOptions.categories = options.categories
    if (options.statuses) filterOptions.statuses = options.statuses
  }

  /**
   * 获取查询参数（用于 API 调用）
   * @returns {Object} 查询参数
   */
  function getQueryParams() {
    const params: Partial<CatalogFilterQuery> = {}
    if (query.keyword) params.keyword = query.keyword
    if (query.category) params.category = query.category
    if (query.status) params.status = query.status
    if (listFilter.value) params.listFilter = listFilter.value
    return params
  }

  /**
   * 获取排序参数
   * @returns {Object} 排序参数
   */
  function getSortParams() {
    const sortMap: Record<ListSort, { sort: string; dir: 'asc' | 'desc' }> = {
      manual: { sort: 'sortOrder', dir: 'asc' },
      'name-asc': { sort: 'name', dir: 'asc' },
      'name-desc': { sort: 'name', dir: 'desc' },
      'assets-desc': { sort: 'assetCount', dir: 'desc' }
    }
    return sortMap[listSort.value] || sortMap['name-asc']
  }

  return {
    // 状态
    query,
    filterOptions,
    listSort,
    listFilter,

    // 方法
    debouncedSearch,
    search,
    updateFilters,
    clearFilters,
    saveListSort,
    setListSort,
    setFilterOptions,
    getQueryParams,
    getSortParams,
    setListFilter
  }
}
