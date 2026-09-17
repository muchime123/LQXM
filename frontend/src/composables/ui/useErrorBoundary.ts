import { ref } from 'vue'

export interface AppError {
  message: string
  stack?: string
  source: 'render' | 'global' | 'async'
  timestamp: number
}

const errorState = ref<AppError | null>(null)

interface ErrorNodeShape {
  /** 公开实例通过 $options / $parent 访问，内部实例通过 type / parent 访问，这里两者都兼容。 */
  $options?: { __name?: string; name?: string }
  $parent?: unknown
  type?: unknown
  parent?: unknown
}

function componentNameOf(node: ErrorNodeShape): string {
  const options = node.$options
  if (options) {
    const named = options.__name || options.name
    if (named) return named
  }
  const type = node.type
  if (typeof type === 'object' && type !== null) {
    const typed = type as { __name?: string; name?: string }
    return typed.__name || typed.name || ''
  }
  return typeof type === 'string' ? type : ''
}

function resolveComponentStack(instance?: unknown): string {
  const names: string[] = []
  let node = (instance as ErrorNodeShape | null | undefined) ?? null
  while (node) {
    const name = componentNameOf(node)
    if (name) names.unshift(name)
    const parent = node.$parent ?? node.parent
    node = (parent as ErrorNodeShape | null | undefined) ?? null
  }
  return names.length ? names.join(' > ') : 'unknown'
}

function captureError(
  err: unknown,
  source: AppError['source'],
  instance?: unknown,
  info?: string
): void {
  const componentChain = resolveComponentStack(instance)
  const errorObject = err && typeof err === 'object' ? (err as Record<string, unknown>) : null
  const objectMessage = errorObject?.message ?? errorObject?.error ?? errorObject?.detail ?? errorObject?.statusText
  const message =
    err instanceof Error
      ? err.message
      : typeof err === 'string'
        ? err
        : typeof objectMessage === 'string'
          ? objectMessage
          : errorObject
            ? safeStringify(errorObject)
            : '未知错误'
  const stack =
    err instanceof Error ? err.stack : typeof errorObject?.stack === 'string' ? errorObject.stack : undefined
  errorState.value = {
    message,
    stack,
    source,
    timestamp: Date.now()
  }
  console.error(`[ErrorBoundary:${source}]${componentChain ? ` <${componentChain}>` : ''}${info ? ` [${info}]` : ''}`, err)
}

function safeStringify(value: Record<string, unknown>): string {
  try {
    const serialized = JSON.stringify(value)
    return serialized && serialized !== '{}' ? serialized : '发生了未提供错误信息的对象异常'
  } catch {
    return '发生了无法序列化的对象异常'
  }
}

function clearError(): void {
  errorState.value = null
}

export { errorState, captureError, clearError }
