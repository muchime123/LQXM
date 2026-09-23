import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { http } from './http'

/**
 * 拦截器契约：**拒绝值必须是真正的 Error 实例**，且 message 是服务端给的业务原因。
 *
 * 起因（2026-09-23）：建单 400 时前端只弹「创建失败，请重试」，服务端明明回了
 * 「产品未关联制程，且车间未设置默认制程」。根因是拦截器 reject 的是纯对象
 * `{message,timestamp}`，而各页面取消息普遍写 `err instanceof Error ? err.message : 兜底`，
 * 对象过不了 instanceof，真实原因被吞。本文件把该契约钉死，防止回归。
 */

/** 真实 axios 适配器契约的最小替身：直接把错误当拒绝值抛出。 */
function useRejectingAdapter(error: unknown): void {
  http.defaults.adapter = () => Promise.reject(error)
}

/** axios 实际抛出的就是 Error 实例（CanceledError / AxiosError 均继承 Error），替身照此构造。 */
function axiosLikeError(status: number, data: unknown, name = 'AxiosError'): Error {
  const error = new Error(`Request failed with status code ${status}`)
  error.name = name
  return Object.assign(error, {
    isAxiosError: true,
    config: {},
    response: { status, statusText: '', data, headers: {}, config: {} }
  })
}

const serverBody = {
  code: 400,
  message: '产品未关联制程，且车间未设置默认制程',
  timestamp: '2026-09-23T10:13:57.352'
}

const originalAdapter = http.defaults.adapter

beforeEach(() => {
  localStorage.clear()
  window.location.hash = '#/'
})

afterEach(() => {
  http.defaults.adapter = originalAdapter
})

describe('http 拦截器错误契约', () => {
  it('HTTP 400 + 业务响应体：拒绝值是 Error 实例且 message 为服务端原因', async () => {
    useRejectingAdapter(axiosLikeError(400, serverBody))

    const caught = await http.post('/manufacturing/orders', {}).then(
      () => null,
      (e: unknown) => e
    )

    expect(caught).toBeInstanceOf(Error)
    expect((caught as Error).message).toBe(serverBody.message)
    // 页面普遍按 `err instanceof Error ? err.message : '兜底'` 取文案 —— 必须走到真原因分支
    expect((caught as Error & { timestamp: string }).timestamp).toBe(serverBody.timestamp)
  })

  it('HTTP 200 但业务码非 0：同样拒绝为 Error 实例', async () => {
    useRejectingAdapter(
      axiosLikeError(200, {
        code: 500,
        message: '所选制程模板没有工序',
        timestamp: '2026-09-23T10:20:00.000'
      })
    )

    const caught = await http.post('/manufacturing/orders', {}).then(
      () => null,
      (e: unknown) => e
    )

    expect(caught).toBeInstanceOf(Error)
    expect((caught as Error).message).toBe('所选制程模板没有工序')
  })

  it('取消请求：仍保留 AbortError 名称，不打断静默取消判断', async () => {
    useRejectingAdapter(axiosLikeError(0, undefined, 'AbortError'))

    const caught = await http.get('/manufacturing/orders').then(
      () => null,
      (e: unknown) => e
    )

    expect(caught).toBeInstanceOf(Error)
    expect((caught as Error).name).toBe('AbortError')
  })

  it('HTTP 401：清 Token 并跳登录页（原行为不变）', async () => {
    localStorage.setItem('lq.token', 'stale-token')
    useRejectingAdapter(axiosLikeError(401, { code: 401, message: '会话已失效', timestamp: 't' }))

    await http.get('/manufacturing/orders').catch(() => undefined)

    expect(localStorage.getItem('lq.token')).toBeNull()
    expect(window.location.hash.startsWith('#/login')).toBe(true)
  })
})
