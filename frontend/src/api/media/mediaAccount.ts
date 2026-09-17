import { http } from '../http'

const CACHE_TTL = 5 * 60 * 1000
const cache = new Map<string, { data: any; time: number }>()

function generateCacheKey(url: string, params?: Record<string, any>) {
  return `${url}:${JSON.stringify(params || {})}`
}

async function fetchWithCache(url: string, config: Record<string, any> = {}) {
  const { params, useCache = true, ...rest } = config
  const cacheKey = generateCacheKey(url, params)

  if (useCache) {
    const cached = cache.get(cacheKey)
    if (cached && Date.now() - cached.time < CACHE_TTL) {
      return cached.data
    }
  }

  const response = await http.get(url, { params, ...rest })
  const data = response.data

  if (useCache) {
    cache.set(cacheKey, { data, time: Date.now() })
  }

  return data
}

export function fetchMediaAccounts(params?: Record<string, any>, options?: Record<string, any>) {
  return fetchWithCache('/media-accounts', { params, useCache: false, ...options })
}

export function fetchMediaAccount(id: number, options?: Record<string, any>) {
  return fetchWithCache(`/media-accounts/${id}`, { useCache: false, ...options })
}

export function createMediaAccount(data: Record<string, any>) {
  return http.post('/media-accounts', data).then(res => res.data)
}

export function updateMediaAccount(id: number, data: Record<string, any>) {
  return http.put(`/media-accounts/${id}`, data).then(res => res.data)
}

export function deleteMediaAccount(id: number) {
  return http.delete(`/media-accounts/${id}`).then(res => res.data)
}

export function batchUpdateMediaAccounts(ids: number[], fields: Record<string, unknown>) {
  return http.put('/media-accounts/batch', { ids, fields }).then(res => res.data as { affected: number })
}

export function batchDeleteMediaAccounts(ids: number[]) {
  return http.delete('/media-accounts/batch', { data: ids }).then(res => res.data as { affected: number })
}

export function fetchMediaAccountStats() {
  return fetchWithCache('/media-accounts/stats', { useCache: false })
}

export function fetchOperators() {
  return fetchWithCache('/media-accounts/operators', { useCache: false })
}

export function fetchPublishOwners() {
  return fetchWithCache('/media-accounts/publish-owners', { useCache: false })
}

export function fetchPlatformConfigs() {
  return fetchWithCache('/platform-configs')
}

export function fetchActivePlatformConfigs() {
  return fetchWithCache('/platform-configs/active')
}

export function createPlatformConfig(data: Record<string, any>) {
  return http.post('/platform-configs', data).then(res => res.data)
}

export function updatePlatformConfig(id: number, data: Record<string, any>) {
  return http.put(`/platform-configs/${id}`, data).then(res => res.data)
}

export function deletePlatformConfig(id: number) {
  return http.delete(`/platform-configs/${id}`).then(res => res.data)
}

export function importMediaAccounts(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return http
    .post('/media-accounts/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 600000
    })
    .then(res => res.data)
}

export function previewMediaUpdateImport(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return http
    .post('/media-accounts/updates/import-preview', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 600000
    })
    .then(res => res.data)
}

export function confirmMediaUpdateImport(data: Record<string, any>) {
  return http.post('/media-accounts/updates/import-confirm', data).then(res => res.data)
}

export function fetchRecentUpdates(days = 30, page = 0, size = 20, options?: Record<string, any>) {
  return fetchWithCache('/media-accounts/updates/recent', {
    params: { days, page, size },
    ...options
  })
}

export function addAccountUpdate(accountId: number, data: Record<string, any>) {
  return http.post(`/media-accounts/${accountId}/updates`, data).then(res => res.data)
}

export function editAccountUpdate(updateId: number, data: Record<string, any>) {
  return http.put(`/media-accounts/updates/${updateId}`, data).then(res => res.data)
}

export function deleteAccountUpdate(updateId: number) {
  return http.delete(`/media-accounts/updates/${updateId}`).then(res => res.data)
}

export function fetchUpdateFrequencies(platform?: string, operator?: string) {
  const params: Record<string, any> = {}
  if (platform) params.platform = platform
  if (operator) params.operator = operator
  return fetchWithCache('/media-accounts/update-frequency', { params })
}

export function fetchAnalytics(days = 30, options?: Record<string, any>) {
  return fetchWithCache('/media-accounts/analytics', { params: { days }, ...options })
}

export function fetchAccountAnalytics(accountId: number, days = 90, options?: Record<string, any>) {
  return fetchWithCache(`/media-accounts/${accountId}/analytics`, {
    params: { days },
    useCache: false,
    ...options
  })
}

export function batchTodayUpdate(data: Record<string, any>) {
  return http.post('/media-accounts/batch-update', data).then(res => res.data)
}

export function createPublishTask(data: Record<string, any>) {
  return http.post('/media-accounts/publish-tasks', data).then(res => res.data)
}

export function assignPublishOwner(data: Record<string, any>) {
  return http.post('/media-accounts/assign-publish-owner', data).then(res => res.data)
}

export function exportMediaAccounts(params?: Record<string, any>) {
  return http.get('/media-accounts/export', { params, responseType: 'blob' }).then(res => res.data)
}
