import { http } from '../http'
import type {
  CompanyAccount,
  CompanyAsset,
  CompanyCertification,
  CompanyCertificationPayload,
  CompanyInfo,
  CompanyMilestone,
  CompanyOverviewStats,
  KbArticle,
  KbCategory
} from '@/types/company'

const unwrap = <T>(response: { data: T }): T => response.data

export function getMilestones(): Promise<CompanyMilestone[]> {
  return http.get<CompanyMilestone[]>('/company/milestones').then(unwrap)
}

export function createMilestone(data: Partial<CompanyMilestone>): Promise<CompanyMilestone> {
  return http.post<CompanyMilestone>('/company/milestones', data).then(unwrap)
}

export function updateMilestone(id: number, data: Partial<CompanyMilestone>): Promise<CompanyMilestone> {
  return http.put<CompanyMilestone>(`/company/milestones/${id}`, data).then(unwrap)
}

export function deleteMilestone(id: number): Promise<void> {
  return http.delete<void>(`/company/milestones/${id}`).then(unwrap)
}

export function getCompanyStats(): Promise<CompanyOverviewStats> {
  return http.get<CompanyOverviewStats>('/company/stats').then(unwrap)
}

export function getCompanyInfo(): Promise<CompanyInfo> {
  return http.get<CompanyInfo>('/company/info').then(unwrap)
}

export function updateCompanyInfo(data: Partial<CompanyInfo>): Promise<CompanyInfo> {
  return http.put<CompanyInfo>('/company/info', data).then(unwrap)
}

export function getAccounts(): Promise<CompanyAccount[]> {
  return http.get<CompanyAccount[]>('/company/accounts').then(unwrap)
}

export function createAccount(data: Partial<CompanyAccount>): Promise<CompanyAccount> {
  return http.post<CompanyAccount>('/company/accounts', data).then(unwrap)
}

export function updateAccount(id: number, data: Partial<CompanyAccount>): Promise<CompanyAccount> {
  return http.put<CompanyAccount>(`/company/accounts/${id}`, data).then(unwrap)
}

export function deleteAccount(id: number): Promise<void> {
  return http.delete<void>(`/company/accounts/${id}`).then(unwrap)
}

export function getKbCategories(): Promise<KbCategory[]> {
  return http.get<KbCategory[]>('/company/kb/categories').then(unwrap)
}

export function createKbCategory(data: Partial<KbCategory>): Promise<KbCategory> {
  return http.post<KbCategory>('/company/kb/categories', data).then(unwrap)
}

export function updateKbCategory(id: number, data: Partial<KbCategory>): Promise<KbCategory> {
  return http.put<KbCategory>(`/company/kb/categories/${id}`, data).then(unwrap)
}

export function deleteKbCategory(id: number): Promise<void> {
  return http.delete<void>(`/company/kb/categories/${id}`).then(unwrap)
}

export type KbArticleQuery = Record<string, string | number | undefined>

export function getKbArticles(params?: KbArticleQuery): Promise<KbArticle[]> {
  return http.get<KbArticle[]>('/company/kb/articles', { params }).then(unwrap)
}

export function createKbArticle(data: Partial<KbArticle>): Promise<KbArticle> {
  return http.post<KbArticle>('/company/kb/articles', data).then(unwrap)
}

export function updateKbArticle(id: number, data: Partial<KbArticle>): Promise<KbArticle> {
  return http.put<KbArticle>(`/company/kb/articles/${id}`, data).then(unwrap)
}

export function deleteKbArticle(id: number): Promise<void> {
  return http.delete<void>(`/company/kb/articles/${id}`).then(unwrap)
}

export function incrementKbArticleView(id: number): Promise<void> {
  return http.post<void>(`/company/kb/articles/${id}/view`).then(unwrap)
}

export function getAssets(category?: string): Promise<CompanyAsset[]> {
  return http.get<CompanyAsset[]>('/company/assets', { params: category ? { category } : {} }).then(unwrap)
}

export function createAsset(data: Partial<CompanyAsset>): Promise<CompanyAsset> {
  return http.post<CompanyAsset>('/company/assets', data).then(unwrap)
}

export function getTrashedAssets(): Promise<CompanyAsset[]> {
  return http.get<CompanyAsset[]>('/company/assets/trash').then(unwrap)
}

// ===== 公司资质与合规文件（检测报告、营业执照、ISO 认证等） =====

export function getCertifications(category?: string, keyword?: string): Promise<CompanyCertification[]> {
  const params: Record<string, string> = {}
  if (category) params.category = category
  if (keyword) params.keyword = keyword
  return http.get<CompanyCertification[]>('/company/certifications', { params }).then(unwrap)
}

export function createCertification(data: CompanyCertificationPayload): Promise<CompanyCertification> {
  return http.post<CompanyCertification>('/company/certifications', data).then(unwrap)
}

export function updateCertification(id: number, data: CompanyCertificationPayload): Promise<CompanyCertification> {
  return http.put<CompanyCertification>(`/company/certifications/${id}`, data).then(unwrap)
}

export function deleteCertification(id: number): Promise<void> {
  return http.delete<void>(`/company/certifications/${id}`).then(unwrap)
}

export function batchDeleteCertifications(ids: number[]): Promise<{ updated: number }> {
  return http.delete<{ updated: number }>('/company/certifications/batch', { data: ids }).then(unwrap)
}

export function batchUpdateCertifications(
  ids: number[],
  fields: Record<string, unknown>
): Promise<{ updated: number }> {
  return http.put<{ updated: number }>('/company/certifications/batch', { ids, fields }).then(unwrap)
}
