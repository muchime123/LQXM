/**
 * 统一错误处理工具
 * 从各组件中重复的 errorMessage() 提取而来
 */

import type { ApiError } from '@/types'

type ErrorLike = {
  response?: {
    data?: ApiError | string
  }
  message?: string
}

/**
 * 从 axios 错误或普通错误对象中提取可读的错误消息
 * @param err - 错误对象（通常是 axios 错误）
 * @returns 可读的错误消息
 */
export function errorMessage(err: unknown): string {
  const e = err as ErrorLike
  const responseData = e?.response?.data
  if (typeof responseData === 'object' && responseData !== null && 'message' in responseData) {
    return (responseData as ApiError).message
  }
  if (typeof responseData === 'string') {
    return responseData
  }
  return e?.message || String(err)
}
