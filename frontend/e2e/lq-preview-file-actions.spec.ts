import { expect, test } from '@playwright/test'

async function mountHarness(page: import('@playwright/test').Page, assetId: number | null = 41) {
  await page.goto('/#/products')
  await page.waitForURL('**/#/products')
  await page.evaluate(async id => {
    document.body.innerHTML = '<div id="e2e-app"></div>'
    const vue = await (0, eval)("import('/node_modules/.vite/deps/vue.js')")
    const harness = await (0, eval)("import('/e2e/fixtures/LqPreviewHarness.vue')")
    vue.createApp(harness.default, { assetId: id }).mount('#e2e-app')
  }, assetId)
}

test('opens a registered file folder and shows success feedback', async ({ page }) => {
  const requests: string[] = []
  await page.route('**/api/file-assets/document/assets/41/open-folder', async route => {
    requests.push(route.request().url())
    await route.fulfill({ json: { success: true, localPath: 'D:/archive/manual.pdf' } })
  })

  await mountHarness(page)
  await expect(page.getByTestId('file-asset-preview-modal')).toBeVisible()
  await expect(page.getByRole('link', { name: '新窗口' })).toHaveAttribute('target', '_blank')
  await expect(page.getByRole('button', { name: '下载' })).toBeVisible()
  await page.getByTestId('file-asset-open-folder').click()

  await expect(page.getByText('文件所在文件夹已打开')).toBeVisible()
  expect(requests).toHaveLength(1)
})

test('downloads through the unified asset endpoint without navigating the page', async ({ page }) => {
  const requests: string[] = []
  await page.route('**/api/file-assets/document/assets/41/download', async route => {
    requests.push(route.request().url())
    await route.fulfill({
      headers: {
        'Content-Type': 'application/pdf',
        'Content-Disposition': 'attachment; filename="manual.pdf"'
      },
      body: Buffer.from('%PDF-1.4 test')
    })
  })

  await mountHarness(page)
  const urlBefore = page.url()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载' }).click()
  const download = await downloadPromise

  expect(download.suggestedFilename()).toBe('manual.pdf')
  expect(requests).toHaveLength(1)
  expect(page.url()).toBe(urlBefore)
})

test('shows the backend failure without bypassing unified policy', async ({ page }) => {
  await page.route('**/api/file-assets/document/assets/41/open-folder', route =>
    route.fulfill({ json: { success: false, error: '当前环境禁止打开文件夹', errorCode: 'OPEN_FOLDER_DISABLED' } })
  )

  await mountHarness(page)
  await page.getByTestId('file-asset-open-folder').click()

  await expect(page.getByText('当前环境禁止打开文件夹')).toBeVisible()
})

test('hides the open-folder action when no file identity is available', async ({ page }) => {
  await mountHarness(page, null)
  await expect(page.getByTestId('file-asset-preview-modal')).toBeVisible()
  await expect(page.getByTestId('file-asset-open-folder')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '下载' })).toHaveCount(0)
})
