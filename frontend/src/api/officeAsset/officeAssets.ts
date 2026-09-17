import { http } from '../http'

export interface OfficeAsset {
  id: number
  assetCode: string
  name: string
  category: string
  brand?: string
  model?: string
  serialNumber?: string
  department?: string
  custodian?: string
  location?: string
  status: string
  purchaseDate?: string
  purchasePrice?: number
  supplier?: string
  warrantyUntil?: string
  notes?: string
  lastInventoryAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface OfficeAssetRecord {
  id: number
  assetId: number
  action: string
  operatorName?: string
  department?: string
  custodian?: string
  location?: string
  notes?: string
  createdAt: string
}
export interface OfficeAssetStats {
  total: number
  inStock: number
  inUse: number
  repairing: number
  retired: number
}
export interface AssetActionPayload {
  action: string
  operatorName?: string
  department?: string
  custodian?: string
  location?: string
  notes?: string
}

export const fetchOfficeAssets = (params?: Record<string, unknown>) =>
  http.get('/office-assets', { params }).then(res => res.data)
export const fetchOfficeAssetStats = () => http.get('/office-assets/stats').then(res => res.data as OfficeAssetStats)
export const scanOfficeAsset = (code: string) =>
  http.get('/office-assets/scan', { params: { code } }).then(res => res.data as OfficeAsset)
export const createOfficeAsset = (data: Partial<OfficeAsset>) =>
  http.post('/office-assets', data).then(res => res.data as OfficeAsset)
export const updateOfficeAsset = (id: number, data: Partial<OfficeAsset>) =>
  http.put(`/office-assets/${id}`, data).then(res => res.data as OfficeAsset)
export const deleteOfficeAsset = (id: number) => http.delete(`/office-assets/${id}`)
export const batchUpdateOfficeAssets = (ids: number[], fields: Record<string, unknown>) =>
  http.put('/office-assets/batch', { ids, fields }).then(res => res.data as { affected: number })
export const performOfficeAssetAction = (id: number, data: AssetActionPayload) =>
  http.post(`/office-assets/${id}/actions`, data).then(res => res.data as OfficeAsset)
export const fetchOfficeAssetRecords = (id: number) =>
  http.get(`/office-assets/${id}/records`).then(res => res.data as OfficeAssetRecord[])
