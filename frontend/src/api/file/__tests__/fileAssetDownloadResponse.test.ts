import { describe, expect, it } from 'vitest'
import type { AxiosResponse } from 'axios'
import { FileAssetDownloadError, validateFileAssetDownloadResponse } from '../fileAssetErrors'

function response(data: unknown, contentType = 'application/octet-stream') {
  return { data, headers: { 'content-type': contentType } } as AxiosResponse<unknown>
}

describe('file asset download response validation', () => {
  it('accepts a non-empty file blob without changing the response contract', async () => {
    const value = response(new Blob(['pdf'], { type: 'application/pdf' }), 'application/pdf')
    await expect(validateFileAssetDownloadResponse(value)).resolves.toBe(value)
  })

  it('rejects non-blob and empty responses', async () => {
    await expect(validateFileAssetDownloadResponse(response('not a blob'))).rejects.toBeInstanceOf(
      FileAssetDownloadError
    )
    await expect(validateFileAssetDownloadResponse(response(new Blob([])))).rejects.toThrow(/文件内容为空/)
  })

  it('extracts a readable error from a JSON blob', async () => {
    const blob = new Blob([JSON.stringify({ message: '文件已被删除' })], { type: 'application/json' })
    await expect(validateFileAssetDownloadResponse(response(blob, 'application/json;charset=UTF-8'))).rejects.toThrow(
      /文件已被删除/
    )
  })
})
