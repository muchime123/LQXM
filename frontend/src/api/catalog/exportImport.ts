import { http } from '../http'

interface ExportCatalogParams {
  keyword?: string
  category?: string
  status?: string
}

export function exportCatalogExcel(params: ExportCatalogParams = {}) {
  const query = new URLSearchParams()
  if (params.keyword) query.append('keyword', params.keyword)
  if (params.category) query.append('category', params.category)
  if (params.status) query.append('status', params.status)
  const qs = query.toString()
  return http.get(`/catalog/export${qs ? '?' + qs : ''}`, { responseType: 'blob' }).then(res => res.data)
}

export function exportProductAssetsZip(modelId: number) {
  return http.get(`/catalog/models/${modelId}/export-zip`, { responseType: 'blob' }).then(res => res.data)
}

export function importCatalog(file: File, reset = true) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('reset', String(reset))
  return http
    .post('/catalog/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 600000
    })
    .then(res => res.data)
}

