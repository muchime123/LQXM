import { http } from '../http'

export interface WebsiteCompanyProfileFields {
  companyName: string
  companyNameEn: string | null
  brandName: string | null
  brandNameEn: string | null
  brandSlogan: string | null
  mission: string | null
  vision: string | null
  values: string | null
  corePositioning: string | null
  mainBusiness: string | null
  targetIndustries: string | null
  advantages: string | null
  manufacturingStrengths: string | null
  enterpriseScale: string | null
  establishmentDate: string | null
  officeAddress: string | null
  factoryAddress: string | null
  phone: string | null
  email: string | null
  website: string | null
  logoMediaId: number | null
}

export interface WebsiteCompanyProfile extends WebsiteCompanyProfileFields {
  id: number
  status: 'DRAFT' | 'PUBLISHED'
}

export interface WebsiteCompanyProfileReadiness {
  ready: boolean
  blockers: string[]
  warnings: string[]
}

export interface WebsiteCompanyProfileParity {
  legacyExists: boolean
  draftExists: boolean
  differentCount: number
  fields: Array<{ key: string; legacy: string | null; draft: string | null; different: boolean }>
}

export async function fetchWebsiteCompanyProfileParity(): Promise<WebsiteCompanyProfileParity> {
  const response = await http.get<WebsiteCompanyProfileParity>('/website/admin/company-profile/parity')
  return response.data
}

export async function fetchWebsiteCompanyProfileReadiness(): Promise<WebsiteCompanyProfileReadiness> {
  const response = await http.get<WebsiteCompanyProfileReadiness>('/website/admin/company-profile/readiness')
  return response.data
}

export async function fetchWebsiteCompanyProfile(): Promise<WebsiteCompanyProfile | null> {
  const response = await http.get<WebsiteCompanyProfile | null>('/website/admin/company-profile')
  return response.data
}

export async function saveWebsiteCompanyProfileDraft(fields: WebsiteCompanyProfileFields): Promise<WebsiteCompanyProfile> {
  const response = await http.put<WebsiteCompanyProfile>('/website/admin/company-profile', fields)
  return response.data
}
