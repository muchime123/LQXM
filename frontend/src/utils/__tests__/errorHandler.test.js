import { describe, it, expect } from 'vitest'
import { errorMessage } from '../ui/errorHandler'

describe('errorMessage', () => {
  it('extracts message from axios-like error', () => {
    const err = { response: { data: { message: '网络错误' } } }
    expect(errorMessage(err)).toBe('网络错误')
  })

  it('falls back to response data', () => {
    const err = { response: { data: '服务器异常' } }
    expect(errorMessage(err)).toBe('服务器异常')
  })

  it('falls back to error message', () => {
    const err = new Error('超时')
    expect(errorMessage(err)).toBe('超时')
  })

  it('returns stringified value for unknown input', () => {
    expect(errorMessage('oops')).toBe('oops')
  })
})
