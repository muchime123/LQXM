import { http } from '@/api/http'

export type ProductGroup = 'BOX' | 'FILM' | 'MACHINE' | 'UNASSIGNED'
export const productGroupLabels: Record<ProductGroup,string> = {
  BOX: '盒子', FILM: '膜', MACHINE: '机器', UNASSIGNED: '待归类'
}
export interface H3CategoryGroupRow {
  sourceCategory: string
  productGroup: ProductGroup
  catalogRows: number
  stockRows: number
  salesLines: number
}
export const fetchH3CategoryGroups = async () =>
  (await http.get<H3CategoryGroupRow[]>('/h3-product-category-groups')).data
export const assignH3CategoryGroup = async (sourceCategory: string, productGroup: ProductGroup) =>
  (await http.put('/h3-product-category-groups', { sourceCategory, productGroup })).data
export const assignH3CategoryGroupsBatch = async (sourceCategories: string[], productGroup: ProductGroup) =>
  (await http.put<{updated:number}>('/h3-product-category-groups/batch', { sourceCategories, productGroup })).data
