import { reportFileAssetDiagnostic } from '@/utils/fileAssetDiagnostics'
export function normalizeFileAssetIds(assetIds: number[]) {
  return Array.from(
    new Set(assetIds.filter((assetId): assetId is number => Number.isSafeInteger(assetId) && assetId !== 0))
  )
}

export class FileAssetBatchError extends Error {
  constructor(
    public readonly assetId: number,
    public readonly completedCount: number,
    public readonly cause: unknown
  ) {
    super(`文件 ${assetId} 操作失败（已完成 ${completedCount} 项）：${batchErrorMessage(cause)}`)
    this.name = 'FileAssetBatchError'
    reportFileAssetDiagnostic('batch_partial_failure', {
      severity: 'error',
      operation: 'batch',
      assetId,
      completedCount,
      cause
    })
  }
}

function batchErrorMessage(error: unknown) {
  if (error instanceof Error && error.message) return error.message
  if (typeof error === 'string' && error) return error
  return '未知错误'
}

export async function runFileAssetBatch<T>(assetIds: number[], action: (assetId: number) => Promise<T>) {
  const results: T[] = []
  for (const assetId of normalizeFileAssetIds(assetIds)) {
    try {
      results.push(await action(assetId))
    } catch (error) {
      throw new FileAssetBatchError(assetId, results.length, error)
    }
  }
  return results
}
