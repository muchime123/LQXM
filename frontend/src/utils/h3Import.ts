/**
 * 氚云导入中心 · 批次进度轮询工具（R5 收敛：原 waitH3BatchUntil / waitLedgerBatchUntil
 * 函数体逐字重复，现统一实现，A/B 两域 API 模块通过薄包装复用）。
 */
import type { BatchProgress } from '@/types/h3Import'

export interface H3ImportWaitOptions {
  intervalMs?: number
  timeoutMs?: number
  onProgress?: (p: BatchProgress) => void
}

/** 直到批次进入 doneSet 或 FAILED（超时抛错）；每步回调 onProgress。 */
export async function waitH3ImportUntil(
  fetchProgress: (batchId: number) => Promise<BatchProgress>,
  batchId: number,
  doneStatuses: string[],
  opts?: H3ImportWaitOptions
): Promise<BatchProgress> {
  const interval = opts?.intervalMs ?? 500
  const timeout = opts?.timeoutMs ?? 600000
  const start = Date.now()
  while (Date.now() - start < timeout) {
    const p = await fetchProgress(batchId)
    opts?.onProgress?.(p)
    if (doneStatuses.includes(p.status)) return p
    if (p.status === 'FAILED') return p
    await new Promise(resolve => setTimeout(resolve, interval))
  }
  throw new Error(`批次 ${batchId} 等待超时（${Math.round(timeout / 1000)}s）`)
}
