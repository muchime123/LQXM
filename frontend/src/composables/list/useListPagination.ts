import { reactive, computed } from 'vue'

export interface PageInfo {
  page: number
  size: number
  total: number
  pages: number
}

export interface PageResponse {
  number?: number
  totalPages?: number
  totalElements?: number
  total?: number
  pages?: number
  page?: number
  size?: number
}

export interface PaginationOptions {
  size?: number
  page?: number
  total?: number
}

export function useListPagination(options: PaginationOptions = {}) {
  const pageInfo = reactive<PageInfo>({
    page: options.page ?? 0,
    size: options.size ?? 20,
    total: options.total ?? 0,
    pages: 0
  })

  const totalPages = computed(() => pageInfo.pages || Math.ceil(pageInfo.total / pageInfo.size))
  const queryParams = computed(() => ({ page: pageInfo.page, size: pageInfo.size }))

  function updateFromResponse(result: PageResponse): void {
    pageInfo.total = result.totalElements ?? result.total ?? 0
    pageInfo.pages = result.totalPages ?? result.pages ?? 0
    const responsePage = result.number ?? result.page
    if (responsePage !== undefined) pageInfo.page = Math.max(0, responsePage)
    pageInfo.size = result.size ?? pageInfo.size
  }

  function onPageChange(p: number, reloadFn?: () => void): void {
    pageInfo.page = Math.max(0, p - 1)
    if (reloadFn) reloadFn()
  }

  function onPageSizeChange(size: number, reloadFn?: () => void): void {
    pageInfo.size = size
    pageInfo.page = 0
    if (reloadFn) reloadFn()
  }

  function resetPage(): void {
    pageInfo.page = 0
  }

  function getPageParams(): { page: number; size: number } {
    return { ...queryParams.value }
  }

  function indexMethod(index: number): number {
    return pageInfo.page * pageInfo.size + index + 1
  }

  return {
    pageInfo,
    totalPages,
    queryParams,
    updateFromResponse,
    onPageChange,
    onPageSizeChange,
    resetPage,
    getPageParams,
    indexMethod
  }
}

export type ListPagination = ReturnType<typeof useListPagination>
