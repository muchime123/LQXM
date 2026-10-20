import { http } from './http'
import type { BatchProgress, ImportResult, StageAccepted, StagingPreview } from '@/types/h3Import'
import { waitH3ImportUntil } from '@/utils/h3Import'

const base = '/manufacturing/followups/production-task-import'

export async function stageProductionTask(file: File): Promise<StageAccepted> {
  const data = new FormData()
  data.append('file', file)
  return (await http.post<StageAccepted>(`${base}/stage`, data, { timeout: 30000 })).data
}
export async function productionTaskImportProgress(id: number): Promise<BatchProgress> {
  return (await http.get<BatchProgress>(`${base}/${id}/progress`)).data
}
export async function productionTaskImportPreview(id: number): Promise<StagingPreview> {
  return (await http.get<StagingPreview>(`${base}/${id}/preview`)).data
}
export async function confirmProductionTaskImport(id: number): Promise<ImportResult> {
  return (await http.post<ImportResult>(`${base}/${id}/confirm`, {}, { timeout: 600000 })).data
}
export function waitProductionTaskImport(id: number, onProgress: (progress: BatchProgress) => void) {
  return waitH3ImportUntil(productionTaskImportProgress, id, ['PREVIEW_READY', 'COMPLETED'], { onProgress })
}
