import { ElMessage } from 'element-plus'
import { errorMessage } from '../../utils/ui/errorHandler'

const DEFAULT_DURATION = 2800

interface NotifyOptions {
  message?: string
  msg?: string
  type?: 'success' | 'error' | 'warning' | 'info'
  duration?: number
}

export type NotifyFn = ((
  message: string | NotifyOptions,
  type?: 'success' | 'error' | 'warning' | 'info',
  duration?: number
) => void) & {
  success: (msg: string | NotifyOptions, dur?: number) => void
  error: (msg: string | NotifyOptions, dur?: number) => void
  warning: (msg: string | NotifyOptions, dur?: number) => void
  errorWithDetail: (prefix: string, err: unknown, duration?: number) => void
}

export const notify: NotifyFn = function (
  message: string | NotifyOptions,
  type: 'success' | 'error' | 'warning' | 'info' = 'success',
  duration = DEFAULT_DURATION
): void {
  if (typeof message === 'object' && message !== null) {
    const opts = message
    ElMessage({
      message: opts.message || opts.msg || '',
      type: opts.type || 'success',
      duration: opts.duration || DEFAULT_DURATION
    })
    return
  }

  ElMessage({ message, type, duration })
} as NotifyFn

notify.success = (msg: string | NotifyOptions, dur?: number) => notify(msg, 'success', dur)
notify.error = (msg: string | NotifyOptions, dur?: number) => notify(msg, 'error', dur)
notify.warning = (msg: string | NotifyOptions, dur?: number) => notify(msg, 'warning', dur)

notify.errorWithDetail = (prefix: string, err: unknown, duration = DEFAULT_DURATION): void => {
  const detail = errorMessage(err)
  notify(prefix + (detail ? '：' + detail : ''), 'error', duration)
}
