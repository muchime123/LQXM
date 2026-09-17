import { expect, test } from '@playwright/test'

test('runs the unified file lifecycle through the browser HTTP stack', async ({ page }) => {
  const requests: Array<{ method: string; url: string }> = []
  const handle = {
    assetId: 41,
    ownerType: 'document',
    ownerId: 3,
    category: 'document',
    storageCategory: 'document',
    fileName: 'manual.pdf',
    originalFileName: '使用手册.pdf',
    mimeType: 'application/pdf',
    fileSize: 1024,
    tags: null,
    defaultFile: false,
    sortOrder: 0,
    deleted: false,
    createdAt: null,
    updatedAt: null,
    capabilities: ['PREVIEW', 'DOWNLOAD', 'DELETE', 'RESTORE', 'PERMANENT_DELETE', 'OPEN_FOLDER']
  }

  await page.route('**/api/file-assets/**', async route => {
    const request = route.request()
    requests.push({ method: request.method(), url: request.url() })
    const url = new URL(request.url())
    if (request.method() === 'GET' && url.pathname.endsWith('/file-assets/document/3')) {
      await route.fulfill({ json: [handle] })
      return
    }
    if (request.method() === 'GET' && url.pathname.endsWith('/assets/41/open-folder')) {
      await route.fulfill({ json: { success: true, localPath: 'D:/archive/manual.pdf' } })
      return
    }
    await route.fulfill({ status: 204 })
  })

  await page.goto('/')
  const result = await page.evaluate(async () => {
    const api = await (0, eval)("import('/src/api/file/fileAssets.ts')")
    const urls = await (0, eval)("import('/src/api/file/fileAssetUrls.ts')")
    const files = await api.fetchFileAssets('document', 3)
    await api.deleteFileAsset('document', 41)
    await api.restoreFileAsset('document', 41)
    await api.permanentDeleteFileAsset('document', 41, false)
    const folder = await api.openFileAssetFolder('document', 41)
    return {
      files,
      folder,
      previewUrl: urls.fileAssetPreviewUrlById('document', 41),
      downloadUrl: urls.fileAssetDownloadUrlById('document', 41)
    }
  })

  expect(result.files).toHaveLength(1)
  expect(result.files[0].capabilities).toContain('PERMANENT_DELETE')
  expect(result.folder).toEqual({ success: true, localPath: 'D:/archive/manual.pdf' })
  expect(result.previewUrl).toBe('/api/file-assets/document/assets/41/preview')
  expect(result.downloadUrl).toBe('/api/file-assets/document/assets/41/download')
  expect(requests).toEqual(expect.arrayContaining([
    expect.objectContaining({ method: 'DELETE', url: expect.stringContaining('permanent=false') }),
    expect.objectContaining({ method: 'POST', url: expect.stringContaining('/assets/41/restore') }),
    expect.objectContaining({ method: 'DELETE', url: expect.stringContaining('permanent=true&deletePhysical=false') })
  ]))
})
