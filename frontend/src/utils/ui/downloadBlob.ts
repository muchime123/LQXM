import { fileNameFromContentDisposition, safeDownloadFileName, validateDownloadBlob } from './downloadResponse'

/** 从 fetch 响应提取并下载文件。 */
export async function downloadFromResponse(response: Response): Promise<void> {
  if (!response.ok) {
    const detail = await response.text()
    throw new Error(`请求失败: ${response.status} - ${detail}`)
  }
  const blob = await response.blob()
  await validateDownloadBlob(blob, response.headers.get('Content-Type') || blob.type)
  const filename = fileNameFromContentDisposition(response.headers.get('Content-Disposition'))
  downloadBlob(blob, filename)
}

/** 使用 Blob 对象触发浏览器下载。 */
export function downloadBlob(blob: Blob, filename: string): void {
  if (!(blob instanceof Blob) || blob.size === 0) throw new Error('下载文件为空')
  const safeFilename = safeDownloadFileName(filename)
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = safeFilename
  document.body.appendChild(anchor)
  anchor.click()
  document.body.removeChild(anchor)
  URL.revokeObjectURL(url)
}

/** 通过 GET 请求下载文件。 */
export async function downloadByUrl(url: string, filename: string): Promise<void> {
  const response = await fetch(url)
  if (!response.ok) throw new Error(`下载失败: ${response.status}`)
  const blob = await response.blob()
  await validateDownloadBlob(blob, response.headers.get('Content-Type') || blob.type)
  downloadBlob(blob, filename)
}
