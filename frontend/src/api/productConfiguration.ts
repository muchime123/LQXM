import { http } from '@/api/http'
export interface ConfigOption { optionId: number; groupCode: string; technical: boolean; exclusiveGroup: string }
export interface ConfigDefinition { productType: string; requiredFields: string[]; options: ConfigOption[]; includeElectrical: boolean }
export interface ConfigRule { id: number; baseProductId: number; version: number; revision: number; status: string; definition: ConfigDefinition; labels: { productName: string; productCode: string; optionNames: Record<string, string> } }
export interface NamePreview { namePreview: string; specificationPreview: string; completionState: string; fieldErrors: Record<string, string>; confirmationItems: string[] }
export interface DirectoryProduct { id: number; name: string; code: string; ruleId: number | null }
export const configurationDirectory = async (workshopId: number) => (await http.get<{products: DirectoryProduct[]}>('/product-configurations/products', { params: { workshopId } })).data
export const configurationRule = async (id: number, ruleId?: number) => (await http.get<ConfigRule>(`/product-configurations/products/${id}/rule`, { params: { ruleId } })).data
export const previewConfiguration = async (baseProductId: number, ruleId: number, configuration: unknown) => (await http.post<NamePreview>('/product-configurations/preview', { baseProductId, ruleId, configuration })).data
