import type { FileOwnerType } from '@/types/fileAsset'

export type FileAssetDiagnosticEvent =
  'invalid_request' | 'invalid_response' | 'invalid_download' | 'batch_partial_failure' | 'request_canceled'

export interface FileAssetDiagnostic {
  timestamp: string
  event: FileAssetDiagnosticEvent
  severity: 'info' | 'warning' | 'error'
  operation?: string
  ownerType?: FileOwnerType
  ownerId?: number | null
  assetId?: number
  completedCount?: number
  message: string
}

export type FileAssetDiagnosticSink = (diagnostic: Readonly<FileAssetDiagnostic>) => void

let configuredSink: FileAssetDiagnosticSink | null = null

function safeMessage(value: unknown) {
  if (value instanceof Error) return value.message
  if (typeof value === 'string') return value
  return '未知错误'
}

function defaultSink(diagnostic: Readonly<FileAssetDiagnostic>) {
  if (import.meta.env.MODE === 'test') return
  const method = diagnostic.severity === 'error' ? 'error' : diagnostic.severity === 'warning' ? 'warn' : 'info'
  console[method]('[file-asset]', diagnostic)
}

export function reportFileAssetDiagnostic(
  event: FileAssetDiagnosticEvent,
  context: Omit<FileAssetDiagnostic, 'timestamp' | 'event' | 'message'> & { message?: string; cause?: unknown }
) {
  const { cause, ...safeContext } = context
  const diagnostic: FileAssetDiagnostic = {
    timestamp: new Date().toISOString(),
    event,
    ...safeContext,
    message: context.message || safeMessage(cause)
  }
  ;(configuredSink || defaultSink)(Object.freeze(diagnostic))
  return diagnostic
}

export function setFileAssetDiagnosticSink(sink: FileAssetDiagnosticSink | null) {
  const previous = configuredSink
  configuredSink = sink
  return () => {
    configuredSink = previous
  }
}
