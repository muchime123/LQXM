export class DownloadValidationError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DownloadValidationError'
  }
}

export function safeDownloadFileName(value: string | null | undefined, fallback = '下载文件') {
  const basename = (value || '').split(/[\\/]/).pop()?.trim() || ''
  const cleaned = basename
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, '_')
    .replace(/[. ]+$/g, '')
    .trim()
  return cleaned && cleaned !== '..' ? cleaned : fallback
}

export function fileNameFromContentDisposition(value: string | null | undefined, fallback = '下载文件') {
  if (!value) return fallback
  const encoded = value.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)?.[1]
  const plain = value.match(/filename\s*=\s*(?:"([^"]+)"|([^;]+))/i)
  let candidate = encoded || plain?.[1] || plain?.[2] || ''
  try {
    candidate = decodeURIComponent(candidate.trim())
  } catch {
    candidate = candidate.trim()
  }
  return safeDownloadFileName(candidate, fallback)
}

export async function validateDownloadBlob(blob: Blob, contentType = blob.type) {
  if (!(blob instanceof Blob)) throw new DownloadValidationError('下载响应不是文件')
  if (blob.size === 0) throw new DownloadValidationError('下载文件为空')
  const normalizedType = contentType.toLowerCase()
  if (normalizedType.includes('application/json') || normalizedType.includes('application/problem+json')) {
    const text = await blob.text()
    try {
      const parsed = JSON.parse(text) as Record<string, unknown>
      const detail = parsed.message ?? parsed.error ?? parsed.detail
      throw new DownloadValidationError(typeof detail === 'string' ? detail : '服务端返回了错误信息')
    } catch (error) {
      if (error instanceof DownloadValidationError) throw error
      throw new DownloadValidationError(text.trim() || '服务端返回了错误信息')
    }
  }
  return blob
}
