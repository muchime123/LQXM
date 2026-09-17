import { http } from '../http'

interface FetchModelsParams {
  keyword?: string
  category?: string
  status?: string
  listFilter?: string
}

interface ModelData {
  [key: string]: unknown
}

export function fetchCatalogModels(params?: FetchModelsParams) {
  return http.get('/catalog/models', { params }).then(res => res.data)
}

export function fetchCatalogFilterOptions() {
  return http.get('/catalog/models/filters').then(res => res.data)
}

export function fetchCatalogModelsSimple() {
  return http.get('/catalog/models/simple').then(res => res.data)
}

export function fetchCatalogModel(id: number) {
  return http.get(`/catalog/models/${id}`).then(res => res.data)
}

export function createCatalogModel(data: ModelData) {
  return http.post('/catalog/models', data).then(res => res.data)
}

export function updateCatalogModel(id: number, data: ModelData) {
  return http.put(`/catalog/models/${id}`, data).then(res => res.data)
}

export function deleteCatalogModel(id: number) {
  return http.delete(`/catalog/models/${id}`).then(res => res.data)
}

export function batchUpdateModels(ids: number[], updates: ModelData) {
  return http.put('/catalog/models/batch', { ids, updates }).then(res => res.data)
}

/**
 * 官网发布字段批量更新。专用端点，仅接受 websiteEnabled / websiteFeatured /
 * websiteSlug / websiteSummary。Boolean 字段传字符串 'true'/'false'。
 */
export function batchUpdateWebsiteFields(ids: number[], fields: Record<string, string>) {
  return http.put('/catalog/models/website-batch', { ids, fields }).then(res => res.data)
}

export function batchDeleteModels(ids: number[]) {
  return http.post('/catalog/models/batch-delete', { ids }).then(res => res.data)
}

export function reorderCatalogModels(items: { id: number; sortOrder: number }[]) {
  return http.put('/catalog/models/reorder', { items }).then(res => res.data)
}
