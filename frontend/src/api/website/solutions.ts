import { http } from '../http'
import type {
  WebsiteSolution,
  WebsiteSolutionPayload,
  SolutionSectionGroup
} from '../../types/website'

export async function fetchSolutions(): Promise<WebsiteSolution[]> {
  const res = await http.get('/website/solutions/admin/list')
  return res.data
}

export async function fetchSolutionsGrouped(): Promise<SolutionSectionGroup[]> {
  const res = await http.get('/website/solutions/admin/grouped')
  return res.data
}

export async function fetchSolutionById(id: number): Promise<WebsiteSolution> {
  const res = await http.get(`/website/solutions/admin/${id}`)
  return res.data
}

export async function createSolution(payload: WebsiteSolutionPayload): Promise<WebsiteSolution> {
  const res = await http.post('/website/solutions/admin', payload)
  return res.data
}

export async function updateSolution(id: number, payload: WebsiteSolutionPayload): Promise<WebsiteSolution> {
  const res = await http.put(`/website/solutions/admin/${id}`, payload)
  return res.data
}

export async function deleteSolution(id: number): Promise<{ success: boolean; id: number }> {
  const res = await http.delete(`/website/solutions/admin/${id}`)
  return res.data
}

export async function batchUpdateSolutionVisible(ids: number[], websiteEnabled: boolean): Promise<WebsiteSolution[]> {
  const res = await http.put('/website/solutions/admin/batch-visible', { ids, websiteEnabled })
  return res.data
}
