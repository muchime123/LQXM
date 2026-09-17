import { reactive, computed } from 'vue'

export function useListFilters<T extends Record<string, unknown>>(initialFilters: T, debounceMs = 300) {
  const filters = reactive({ ...initialFilters }) as T

  let debounceTimer: ReturnType<typeof setTimeout> | null = null

  const hasActiveFilters = computed(() => {
    return Object.entries(filters).some(([key, val]) => {
      const defaultVal = initialFilters[key as keyof T]
      if (defaultVal === undefined) return val !== '' && val !== undefined && val !== null
      return val !== defaultVal
    })
  })

  function debouncedSearch(onSearch: (filters: T) => void): void {
    if (debounceTimer) clearTimeout(debounceTimer)
    debounceTimer = setTimeout(() => {
      onSearch(filters)
    }, debounceMs)
  }

  function immediateSearch(onSearch: (filters: T) => void): void {
    if (debounceTimer) clearTimeout(debounceTimer)
    onSearch(filters)
  }

  function resetFilters(onSearch?: (filters: T) => void): void {
    Object.keys(filters).forEach(key => {
      const k = key as keyof T
      filters[k] = initialFilters[k] !== undefined ? initialFilters[k] : ('' as unknown as T[keyof T])
    })
    if (onSearch) onSearch(filters)
  }

  function getQueryParams(): Record<string, unknown> {
    const params: Record<string, unknown> = {}
    Object.entries(filters).forEach(([key, val]) => {
      if (val !== '' && val !== undefined && val !== null) {
        params[key] = val
      }
    })
    return params
  }

  function setFilter<K extends keyof T>(key: K, value: T[K]): void {
    filters[key] = value
  }

  return {
    filters,
    hasActiveFilters,
    debouncedSearch,
    immediateSearch,
    resetFilters,
    getQueryParams,
    setFilter
  }
}
