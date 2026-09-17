import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { effectScope } from 'vue'
import { useOfficePreviewPolling } from '../useOfficePreviewPolling'
import type { OfficePreviewStatusData } from '../useOfficePreviewPolling'

/** 构造 ApiResponse 包装的状态响应。 */
function statusResponse(data: Partial<OfficePreviewStatusData>): Response {
  return {
    ok: true,
    status: 200,
    json: () =>
      Promise.resolve({
        code: 200,
        message: 'ok',
        data: {
          status: 'pending',
          documentId: null,
          startedAt: null,
          updatedAt: null,
          errorMessage: null,
          pdfReady: false,
          ...data
        },
        timestamp: new Date().toISOString()
      })
  } as Response
}

function networkError(): TypeError {
  return new TypeError('Failed to fetch')
}

const STATUS_URL = '/api/documents/1/preview/pdf/status'
const PDF_URL = '/api/documents/1/preview/pdf'

describe('useOfficePreviewPolling', () => {
  let fetchSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.useFakeTimers()
    fetchSpy = vi.spyOn(globalThis, 'fetch')
  })

  afterEach(() => {
    vi.useRealTimers()
    fetchSpy.mockRestore()
  })

  it('polls pending then transitions to done with pdfUrl', async () => {
    fetchSpy
      .mockResolvedValueOnce(statusResponse({ status: 'pending' }))
      .mockResolvedValueOnce(statusResponse({ status: 'done', pdfReady: true }))

    const scope = effectScope()
    const polling = scope.run(() => useOfficePreviewPolling())!

    polling.start({ statusUrl: STATUS_URL, pdfUrl: PDF_URL })
    expect(polling.state.value).toBe('pending')
    expect(polling.pdfUrl.value).toBe(PDF_URL)

    // flush first fetch (pending) → schedules next at +1500ms
    await vi.advanceTimersByTimeAsync(0)
    expect(fetchSpy).toHaveBeenCalledTimes(1)

    // advance to trigger second poll (done)
    await vi.advanceTimersByTimeAsync(1500)
    expect(fetchSpy).toHaveBeenCalledTimes(2)
    expect(polling.state.value).toBe('done')
    expect(polling.pdfUrl.value).toBe(PDF_URL)

    scope.stop()
  })

  it('transitions to error when backend reports error status', async () => {
    fetchSpy
      .mockResolvedValueOnce(statusResponse({ status: 'pending' }))
      .mockResolvedValueOnce(
        statusResponse({ status: 'error', errorMessage: '转换失败：soffice 未安装' })
      )

    const scope = effectScope()
    const polling = scope.run(() => useOfficePreviewPolling())!

    polling.start({ statusUrl: STATUS_URL, pdfUrl: PDF_URL })
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(1500)

    expect(polling.state.value).toBe('error')
    expect(polling.errorMessage.value).toBe('转换失败：soffice 未安装')

    scope.stop()
  })

  it('transitions to error on timeout when conversion never completes', async () => {
    fetchSpy.mockResolvedValue(statusResponse({ status: 'pending' }))

    const scope = effectScope()
    const polling = scope.run(() => useOfficePreviewPolling())!

    polling.start({ statusUrl: STATUS_URL, pdfUrl: PDF_URL, intervalMs: 1000, timeoutMs: 5000 })

    // advance well past the timeout
    await vi.advanceTimersByTimeAsync(10_000)

    expect(polling.state.value).toBe('error')
    expect(polling.errorMessage.value).toContain('超时')

    scope.stop()
  })

  it('retries on network error then succeeds', async () => {
    fetchSpy
      .mockResolvedValueOnce(statusResponse({ status: 'pending' }))
      .mockRejectedValueOnce(networkError())
      .mockRejectedValueOnce(networkError())
      .mockResolvedValueOnce(statusResponse({ status: 'done', pdfReady: true }))

    const scope = effectScope()
    const polling = scope.run(() => useOfficePreviewPolling())!

    polling.start({ statusUrl: STATUS_URL, pdfUrl: PDF_URL, maxRetries: 2 })

    // first poll: pending → schedules next
    await vi.advanceTimersByTimeAsync(0)

    // second poll: network error → retry 1
    await vi.advanceTimersByTimeAsync(1500)
    // third poll: network error → retry 2
    await vi.advanceTimersByTimeAsync(1500)
    // fourth poll: done
    await vi.advanceTimersByTimeAsync(1500)

    expect(polling.state.value).toBe('done')
    expect(fetchSpy).toHaveBeenCalledTimes(4)

    scope.stop()
  })

  it('transitions to error after exhausting network retries', async () => {
    fetchSpy.mockRejectedValue(networkError())

    const scope = effectScope()
    const polling = scope.run(() => useOfficePreviewPolling())!

    polling.start({ statusUrl: STATUS_URL, pdfUrl: PDF_URL, maxRetries: 2 })

    // initial fetch fails → retry 1
    await vi.advanceTimersByTimeAsync(0)
    // retry 1 fails → retry 2
    await vi.advanceTimersByTimeAsync(1500)
    // retry 2 fails → error
    await vi.advanceTimersByTimeAsync(1500)

    expect(polling.state.value).toBe('error')
    expect(polling.errorMessage.value).toContain('Failed to fetch')
    expect(fetchSpy).toHaveBeenCalledTimes(3)

    scope.stop()
  })

  it('stop() aborts in-flight fetch and resets state', async () => {
    fetchSpy.mockResolvedValue(statusResponse({ status: 'pending' }))

    const scope = effectScope()
    const polling = scope.run(() => useOfficePreviewPolling())!

    polling.start({ statusUrl: STATUS_URL, pdfUrl: PDF_URL })
    await vi.advanceTimersByTimeAsync(0)

    expect(polling.state.value).toBe('pending')
    polling.stop()
    expect(polling.state.value).toBe('idle')
    expect(polling.pdfUrl.value).toBe('')
    expect(polling.elapsedMs.value).toBe(0)

    // no further timers should fire
    await vi.advanceTimersByTimeAsync(10_000)
    expect(fetchSpy).toHaveBeenCalledTimes(1)

    scope.stop()
  })

  it('tracks elapsed time, progress percent, and phase text during polling', async () => {
    fetchSpy.mockResolvedValue(statusResponse({ status: 'pending' }))

    const scope = effectScope()
    const polling = scope.run(() => useOfficePreviewPolling())!

    polling.start({ statusUrl: STATUS_URL, pdfUrl: PDF_URL, timeoutMs: 90_000 })

    // 初始：elapsedMs=0, 进度=0%, 阶段="正在启动转换器..."
    expect(polling.elapsedMs.value).toBe(0)
    expect(polling.progressPercent.value).toBe(0)
    expect(polling.phaseText.value).toBe('正在启动转换器...')

    // 推进 5s：interval 触发 5 次
    await vi.advanceTimersByTimeAsync(5_000)
    expect(polling.elapsedMs.value).toBe(5_000)
    expect(polling.progressPercent.value).toBe(6) // round(5000/90000*100)=6
    expect(polling.phaseText.value).toBe('正在转换文档...')

    // 推进到 35s：阶段变化
    await vi.advanceTimersByTimeAsync(30_000)
    expect(polling.elapsedMs.value).toBe(35_000)
    expect(polling.progressPercent.value).toBe(39) // round(35000/90000*100)=39
    expect(polling.phaseText.value).toBe('转换即将完成...')

    // 推进到 65s：阶段变化为"转换耗时较长"
    await vi.advanceTimersByTimeAsync(30_000)
    expect(polling.elapsedMs.value).toBe(65_000)
    expect(polling.progressPercent.value).toBe(72) // round(65000/90000*100)=72
    expect(polling.phaseText.value).toBe('转换耗时较长，请耐心等待...')

    scope.stop()
  })

  it('auto-cleans on scope dispose (unmount)', async () => {
    fetchSpy.mockResolvedValue(statusResponse({ status: 'pending' }))

    const scope = effectScope()
    const polling = scope.run(() => useOfficePreviewPolling())!

    polling.start({ statusUrl: STATUS_URL, pdfUrl: PDF_URL })
    await vi.advanceTimersByTimeAsync(0)
    expect(polling.state.value).toBe('pending')

    scope.stop()

    expect(polling.state.value).toBe('idle')
    // no further polling after dispose
    const callsBefore = fetchSpy.mock.calls.length
    await vi.advanceTimersByTimeAsync(10_000)
    expect(fetchSpy.mock.calls.length).toBe(callsBefore)
  })

  it('start() switches target file and resets previous polling', async () => {
    fetchSpy
      .mockResolvedValueOnce(statusResponse({ status: 'pending' }))
      .mockResolvedValueOnce(statusResponse({ status: 'done', pdfReady: true }))

    const scope = effectScope()
    const polling = scope.run(() => useOfficePreviewPolling())!

    // first file
    polling.start({ statusUrl: STATUS_URL, pdfUrl: PDF_URL })
    await vi.advanceTimersByTimeAsync(0)
    expect(polling.pdfUrl.value).toBe(PDF_URL)

    // switch to second file before first completes
    const secondPdfUrl = '/api/documents/2/preview/pdf'
    const secondStatusUrl = '/api/documents/2/preview/pdf/status'
    polling.start({ statusUrl: secondStatusUrl, pdfUrl: secondPdfUrl })

    expect(polling.state.value).toBe('pending')
    expect(polling.pdfUrl.value).toBe(secondPdfUrl)

    scope.stop()
  })
})
