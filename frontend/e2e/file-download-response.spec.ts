import { expect, test } from '@playwright/test'

async function callDownload(page: import('@playwright/test').Page) {
  return page.evaluate(async () => {
    const api = await (0, eval)("import('/src/api/file/fileAssets.ts')")
    try {
      const response = await api.downloadFileAsset('document', 41)
      return { ok: true, size: response.data.size, type: response.data.type }
    } catch (error) {
      return { ok: false, message: error instanceof Error ? error.message : String(error) }
    }
  })
}

test('accepts a non-empty binary file response', async ({ page }) => {
  await page.route('**/api/file-assets/document/assets/41/download', route =>
    route.fulfill({ body: 'PDF-DATA', contentType: 'application/pdf' })
  )
  await page.goto('/')

  await expect(callDownload(page)).resolves.toEqual({ ok: true, size: 8, type: 'application/pdf' })
})

test('rejects a JSON error blob returned with HTTP 200', async ({ page }) => {
  await page.route('**/api/file-assets/document/assets/41/download', route =>
    route.fulfill({ json: { message: '文件已被永久删除' } })
  )
  await page.goto('/')

  const result = await callDownload(page)
  expect(result.ok).toBe(false)
  expect(result.message).toContain('文件已被永久删除')
})
