/**
 * Office 文件预览异步转换轮询 composable。
 *
 * <p>收口两条预览路径的轮询逻辑：
 * <ul>
 *   <li>Path A（文档弹窗）：useDocumentPreview</li>
 *   <li>Path B（资产/文件预览）：FilePreviewBody office 分支</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>setTimeout 递归（非 setInterval）：保证前一次 fetch 完成后才调度下一次，不重叠。</li>
 *   <li>AbortController：组件卸载/切换文件时中止在途 fetch，避免泄漏与竞态。</li>
 *   <li>onScopeDispose(stop)：effect scope 销毁时自动收尾。</li>
 *   <li>状态机：idle → pending → done | error。done 设 pdfUrl 供 iframe 加载，error 设 errorMessage。</li>
 *   <li>超时 90s（后端转换 60s + 余量），超时转 error 走下载兜底。</li>
 *   <li>网络错误重试 maxRetries 次后转 error。</li>
 * </ul>
 */

import { computed, onScopeDispose, ref } from 'vue'
import type { ComputedRef, Ref } from 'vue'

/** 前端轮询状态机：idle → pending → done | error。 */
export type OfficePreviewState = 'idle' | 'pending' | 'done' | 'error'

/** 与后端 OfficePreviewStatus record 对应的响应数据。 */
export interface OfficePreviewStatusData {
  /** pending | done | error | none */
  status: string
  documentId: number | null
  startedAt: string | null
  updatedAt: string | null
  errorMessage: string | null
  pdfReady: boolean
}

export interface OfficePreviewPollingOptions {
  /** 状态查询 URL（完整路径，含 /api 前缀）。GET 即触发后端异步转换。 */
  statusUrl: string
  /** 转换完成后 iframe 加载的 PDF URL。 */
  pdfUrl: string
  /** 轮询间隔，默认 1500ms。 */
  intervalMs?: number
  /** 总超时，默认 90000ms（后端 60s + 余量）。 */
  timeoutMs?: number
  /** 网络错误最大重试次数，默认 2。 */
  maxRetries?: number
}

export interface UseOfficePreviewPolling {
  state: Ref<OfficePreviewState>
  pdfUrl: Ref<string>
  errorMessage: Ref<string>
  /** 已用时间（ms），1s 更新一次。 */
  elapsedMs: Ref<number>
  /** 进度百分比（0–95），基于 elapsedMs / timeoutMs 线性推算，done 前永不达 100。 */
  progressPercent: ComputedRef<number>
  /** 阶段提示文案，随 elapsedMs 变化。 */
  phaseText: ComputedRef<string>
  start: (options: OfficePreviewPollingOptions) => void
  stop: () => void
}

const DEFAULT_INTERVAL_MS = 1500
const DEFAULT_TIMEOUT_MS = 90_000
const DEFAULT_MAX_RETRIES = 2

const TIMEOUT_MESSAGE = '预览转换超时，请下载后查看'

export function useOfficePreviewPolling(): UseOfficePreviewPolling {
  const state = ref<OfficePreviewState>('idle')
  const pdfUrl = ref('')
  const errorMessage = ref('')
  const elapsedMs = ref(0)

  let timer: ReturnType<typeof setTimeout> | null = null
  let progressTimer: ReturnType<typeof setInterval> | null = null
  let abortController: AbortController | null = null
  let retryCount = 0
  let startedAt = 0
  let currentTimeoutMs = DEFAULT_TIMEOUT_MS

  const progressPercent = computed(() =>
    Math.min(95, Math.round((elapsedMs.value / currentTimeoutMs) * 100))
  )

  const phaseText = computed(() => {
    const seconds = Math.floor(elapsedMs.value / 1000)
    if (seconds < 3) return '正在启动转换器...'
    if (seconds < 10) return '正在转换文档...'
    if (seconds < 30) return '文档较大，正在转换...'
    if (seconds < 60) return '转换即将完成...'
    return '转换耗时较长，请耐心等待...'
  })

  function start(options: OfficePreviewPollingOptions): void {
    stop()
    const {
      statusUrl,
      pdfUrl: targetPdfUrl,
      intervalMs = DEFAULT_INTERVAL_MS,
      timeoutMs = DEFAULT_TIMEOUT_MS,
      maxRetries = DEFAULT_MAX_RETRIES
    } = options

    state.value = 'pending'
    pdfUrl.value = targetPdfUrl
    errorMessage.value = ''
    elapsedMs.value = 0
    retryCount = 0
    startedAt = Date.now()
    currentTimeoutMs = timeoutMs

    // 1s 定时器更新已用时间（独立于轮询定时器，UI 平滑刷新）
    progressTimer = setInterval(() => {
      elapsedMs.value = Date.now() - startedAt
    }, 1000)

    void poll(statusUrl, intervalMs, timeoutMs, maxRetries)
  }

  async function poll(
    statusUrl: string,
    intervalMs: number,
    timeoutMs: number,
    maxRetries: number
  ): Promise<void> {
    abortController = new AbortController()
    try {
      const response = await fetch(statusUrl, { signal: abortController.signal })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      const json = await response.json()
      const data = extractStatusData(json)
      if (!data) throw new Error('状态响应格式错误')

      // 后端一直 pending 时由前端兜底超时
      if (Date.now() - startedAt > timeoutMs) {
        transitionToError(TIMEOUT_MESSAGE)
        return
      }

      if (data.status === 'done' && data.pdfReady) {
        state.value = 'done'
        return
      }
      if (data.status === 'error') {
        transitionToError(data.errorMessage || '预览转换失败')
        return
      }
      // pending / none → 继续轮询
      scheduleNext(statusUrl, intervalMs, timeoutMs, maxRetries)
    } catch (err: unknown) {
      // stop() 主动中止，忽略（state 已由 stop 重置）
      if (isAbortError(err)) return
      if (retryCount < maxRetries) {
        retryCount++
        scheduleNext(statusUrl, intervalMs, timeoutMs, maxRetries)
      } else {
        transitionToError(err instanceof Error ? err.message : '网络错误，请稍后重试')
      }
    }
  }

  function scheduleNext(
    statusUrl: string,
    intervalMs: number,
    timeoutMs: number,
    maxRetries: number
  ): void {
    if (Date.now() - startedAt > timeoutMs) {
      transitionToError(TIMEOUT_MESSAGE)
      return
    }
    timer = setTimeout(
      () => void poll(statusUrl, intervalMs, timeoutMs, maxRetries),
      intervalMs
    )
  }

  function transitionToError(message: string): void {
    state.value = 'error'
    errorMessage.value = message
  }

  function stop(): void {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
    if (progressTimer) {
      clearInterval(progressTimer)
      progressTimer = null
    }
    if (abortController) {
      abortController.abort()
      abortController = null
    }
    state.value = 'idle'
    pdfUrl.value = ''
    errorMessage.value = ''
    elapsedMs.value = 0
    retryCount = 0
  }

  onScopeDispose(stop)

  return { state, pdfUrl, errorMessage, elapsedMs, progressPercent, phaseText, start, stop }
}

/** 从 ApiResponse 包装中提取状态数据，兼容无包装裸响应。 */
function extractStatusData(json: unknown): OfficePreviewStatusData | null {
  if (!json || typeof json !== 'object') return null
  const obj = json as Record<string, unknown>
  const data = obj.data
  if (data && typeof data === 'object') {
    return data as OfficePreviewStatusData
  }
  if ('status' in obj) {
    return obj as unknown as OfficePreviewStatusData
  }
  return null
}

function isAbortError(err: unknown): boolean {
  return err instanceof Error && err.name === 'AbortError'
}
