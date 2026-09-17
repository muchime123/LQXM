/**
 * 创建 AbortController 实例，用于取消正在进行的 HTTP 请求
 * @returns AbortController
 */
export function createAbortController(): AbortController {
  return new AbortController()
}
