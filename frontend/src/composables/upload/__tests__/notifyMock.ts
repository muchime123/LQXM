import { vi } from 'vitest'
import type { NotifyFn } from '../../ui/useToast'

/** Creates a fully-typed NotifyFn mock with all helper methods as spies. */
export function createNotifyMock(): NotifyFn {
  const fn = vi.fn()
  return Object.assign(fn, {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    errorWithDetail: vi.fn()
  }) as unknown as NotifyFn
}
