import { expect, test } from '@playwright/test'

const envelope = (data: unknown) => ({
  json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data }
})

test('氚云任务检验退回后按指定制程返工，完成后进入待复检', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('lq.currentEmployeeId', '23'))
  let phase = 0
  const writes: Array<{ path: string; key?: string }> = []
  await page.route(url => url.pathname.startsWith('/api/h3-production-'), async route => {
    const req = route.request()
    const path = new URL(req.url()).pathname
    if (req.method() === 'POST') {
      writes.push({ path, key: req.headers()['x-request-key'] })
      if (path.endsWith('/fail')) phase = 1
      else if (path.endsWith('/reports')) phase = 2
      await route.fulfill(envelope({ id: phase }))
      return
    }
    if (path === '/api/h3-production-execution') {
      await route.fulfill(envelope({ items: [{ id: 1, workOrderNo: 'H3-TEST-1', productName: '全自动气调机',
        componentCount: 1, completedComponentCount: phase === 1 ? 0 : 1,
        reworkStatus: phase === 1 ? 'REWORKING' : phase === 2 ? 'PENDING_REINSPECTION' : null }], total: 1 }))
      return
    }
    if (path === '/api/h3-production-execution/1') {
      await route.fulfill(envelope({ order: { id: 1, workOrderNo: 'H3-TEST-1', productName: '全自动气调机',
        requiredQuantity: 1, warehousedQuantity: 0, warehousingCompleted: false },
        components: [{ externalId: 'comp-1', sequenceNo: 1, componentName: '装配', requiredQuantity: 1,
          completedQuantity: 1, effectiveQuantity: 1, effectiveCompleted: phase === 1 ? 0 : 1,
          activeReworkId: phase ? 8 : null, reworkTargetQuantity: phase ? 1 : null,
          reworkReportedQuantity: phase === 2 ? 1 : 0 }],
        reports: [], reportCount: 0, localReports: [], componentCount: 1,
        completedComponentCount: phase === 1 ? 0 : 1 }))
      return
    }
    if (path === '/api/h3-production-completions/1') {
      await route.fulfill(envelope({ completions: [], items: [] }))
      return
    }
    if (path === '/api/h3-production-reworks/1') {
      const activeRework = phase ? { id: 8, componentExternalId: 'comp-1', componentName: '装配',
        targetQuantity: 1, reportedQuantity: phase === 2 ? 1 : 0,
        status: phase === 2 ? 'PENDING_REINSPECTION' : 'REWORKING', failureReason: '装配缺陷',
        inspectedByName: '质检员', inspectedAt: '2026-09-23 18:00:00' } : null
      await route.fulfill(envelope({ activeRework, reworks: activeRework ? [activeRework] : [], reworkReports: [] }))
      return
    }
    await route.fulfill(envelope([]))
  })

  await page.goto('/#/h3-production-execution')
  await page.getByRole('button', { name: 'H3-TEST-1' }).click()
  await expect(page.getByRole('button', { name: '检验不合格 · 退回返工' })).toBeEnabled()
  await page.getByRole('button', { name: '检验不合格 · 退回返工' }).click()
  await page.getByRole('dialog', { name: '检验不合格 · 指定返工' }).getByRole('textbox', { name: '不合格原因' }).fill('装配缺陷')
  await page.getByRole('button', { name: '确认退回返工' }).click()
  await expect(page.getByText('返工中').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '质检合格并入库' })).toBeDisabled()

  await page.getByRole('button', { name: '返工报工' }).click()
  await page.getByRole('button', { name: '提交返工报工' }).click()
  await expect(page.getByText('待复检').first()).toBeVisible()
  await expect(page.getByRole('button', { name: '复检合格并入库' })).toBeEnabled()
  expect(writes).toHaveLength(2)
  expect(writes.every(write => write.key)).toBe(true)
})
