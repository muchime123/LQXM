import { describe, expect, it } from 'vitest'
import { fileNameFromContentDisposition, safeDownloadFileName, validateDownloadBlob } from '../downloadResponse'

describe('download response helpers', () => {
  it('parses UTF-8 and quoted response filenames', () => {
    expect(
      fileNameFromContentDisposition("attachment; filename*=UTF-8''%E4%BD%BF%E7%94%A8%E6%89%8B%E5%86%8C.pdf")
    ).toBe('使用手册.pdf')
    expect(fileNameFromContentDisposition('attachment; filename="report.xlsx"')).toBe('report.xlsx')
  })

  it('removes path segments and unsafe filename characters', () => {
    expect(safeDownloadFileName('../folder/report:2026?.xlsx')).toBe('report_2026_.xlsx')
    expect(safeDownloadFileName('..')).toBe('下载文件')
  })

  it('rejects empty and JSON error blobs', async () => {
    await expect(validateDownloadBlob(new Blob([]))).rejects.toThrow(/为空/)
    await expect(
      validateDownloadBlob(new Blob([JSON.stringify({ error: '导出权限不足' })]), 'application/json')
    ).rejects.toThrow(/导出权限不足/)
  })
})
