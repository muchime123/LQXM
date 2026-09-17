import { http } from '../http'

/** 机型档位：夹在产品与规格型号之间的中间层（如 330 / 390 / 500 机型） */
export interface CatalogMachineTier {
  id: number
  modelId: number
  modelName?: string
  tierCode: string
  tierLabel?: string | null
  roundDiameterMin?: number | null
  roundDiameterMax?: number | null
  rectLengthMin?: number | null
  rectLengthMax?: number | null
  rectWidthMin?: number | null
  rectWidthMax?: number | null
  /** 机器尺寸，如 "420×420×700mm"。跟随机型档位走。 */
  machineSize?: string | null
  /** 净重(kg)。跟随机型档位走。 */
  netWeightKg?: string | null
  /** 纸箱包装尺寸。跟随机型档位走。 */
  cartonSize?: string | null
  /** 木箱包装尺寸。跟随机型档位走。 */
  woodBoxSize?: string | null
  /** 毛重(kg)。跟随机型档位走。 */
  grossWeightKg?: string | null
  sortOrder: number
  remark?: string | null
  /** 该机型下已关联的规格数量 */
  variantCount?: number | null
}

export interface MachineTierPayload {
  tierCode: string
  tierLabel?: string | null
  roundDiameterMin?: number | null
  roundDiameterMax?: number | null
  rectLengthMin?: number | null
  rectLengthMax?: number | null
  rectWidthMin?: number | null
  rectWidthMax?: number | null
  machineSize?: string | null
  netWeightKg?: string | null
  cartonSize?: string | null
  woodBoxSize?: string | null
  grossWeightKg?: string | null
  sortOrder?: number
  remark?: string | null
}

export function fetchMachineTiers(modelId: number) {
  return http.get(`/catalog/models/${modelId}/machine-tiers`).then(res => res.data as CatalogMachineTier[])
}

export function createMachineTier(modelId: number, data: MachineTierPayload) {
  return http.post(`/catalog/models/${modelId}/machine-tiers`, data).then(res => res.data as CatalogMachineTier)
}

export function updateMachineTier(id: number, data: MachineTierPayload) {
  return http.put(`/catalog/machine-tiers/${id}`, data).then(res => res.data as CatalogMachineTier)
}

export function deleteMachineTier(id: number) {
  return http.delete(`/catalog/machine-tiers/${id}`).then(res => res.data)
}

export interface PackagingCompatibilityOption {
  id: number
  modelId: number
  modelName: string
  variantName?: string
  apertureKey: string
  apertureLabel: string
  heightMm?: string
  capacityMl?: string
  dimensionLabel: string
}

export interface ProductPackagingCompatibilityMatrix {
  options: PackagingCompatibilityOption[]
  productPackagingVariantIds: number[]
  machineTierAssignments: Array<{ machineTierId: number; packagingVariantIds: number[] }>
}

export function fetchProductPackagingCompatibility(modelId: number) {
  return http.get(`/catalog/models/${modelId}/packaging-compatibility`)
    .then(res => res.data as ProductPackagingCompatibilityMatrix)
}

export function replaceProductPackagingCompatibility(
  modelId: number,
  machineTierId: number | null,
  packagingVariantIds: number[]
) {
  return http.put(`/catalog/models/${modelId}/packaging-compatibility`, { machineTierId, packagingVariantIds })
    .then(res => res.data as ProductPackagingCompatibilityMatrix)
}
