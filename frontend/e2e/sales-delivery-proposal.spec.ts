import { expect, test } from '@playwright/test'
import { customerDirectoryPage } from './fixtures/salesOrderCustomer'

for (const scenario of ['原方案', '复评后方案'] as const) {
  test(`销售无审批权限也可登记客户接受${scenario}，不重复商业审批`, async ({ page }) => {
    let submitted: Record<string, unknown> | undefined
    let requestPath = ''
    const head = { id: 42, orderNo: 'XSDD00000042', status: 'CONFIRMED', customerId: 5, customerName: '测试客户', orderDate: '2026-09-28' }
    await page.addInitScript(() => localStorage.setItem('lq.token', 'delivery-proposal-test-only'))
    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const path = new URL(route.request().url()).pathname
      const reply = (data: unknown) => route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })
      if (path === '/api/auth/me') return reply({ employeeId: 23, name: '销售员', roles: [], permissions: ['sales_order.read.all', 'sales_order.manage'], dataScope: 'ALL', loginType: 'LOCAL' })
      if (path === '/api/customers') return reply(customerDirectoryPage)
      if (path === '/api/erp-sales-orders') return reply({ items: [head], total: 1, page: 1, size: 20 })
      if (path === '/api/erp-sales-orders/42') return reply({ head, items: [{ id: 77, lineNo: 1, supplyMode: 'MAKE', productName: '全自动气调机', quantity: 1, unit: '台' }], approvals: [] })
      if (path.endsWith('/execution-exceptions')) return reply(submitted ? [] : [{ id: 3, orderId: 42, itemId: 77, status: 'OPEN', pauseFollowup: false,
        reason: '产能发生变化', proposedAction: '新交期执行安排已核对', proposedDeliveryDate: scenario === '原方案' ? '2026-11-05' : null, reportedByName: '车间主任' }])
      if (path === '/api/erp-sales-orders/42/change-requests') return reply(scenario === '复评后方案' && !submitted ? [{
        id: 9, exceptionId: 3, itemId: 77, status: 'PENDING_CUSTOMER', originalQuantity: 1, proposedQuantity: 1,
        proposedDeliveryDate: '2026-11-05', reviewedByName: '车间主任', reviewComment: '客户新日期可执行', reviewImpactCurrent: true,
      }] : [])
      if (path.endsWith('/accept-delivery-proposal') || path.endsWith('/confirm-delivery')) {
        submitted = route.request().postDataJSON(); requestPath = path; return reply(9)
      }
      return reply([])
    })
    await page.goto('/#/sales-orders?orderId=42')
    const order = page.getByRole('dialog', { name: '销售订单 XSDD00000042' })
    await expect(order.getByText('风险提醒', { exact: true })).toBeVisible()
    await expect(order.getByRole('button', { name: '批准商业变更' })).toHaveCount(0)
    await order.getByRole('button', { name: scenario === '原方案' ? '客户接受原交期方案' : '登记客户接受并生效' }).click()
    const evidenceDialog = page.getByRole('dialog', { name: /客户接受/ })
    await evidenceDialog.locator('textarea').fill('客户邮件明确接受 11 月 5 日交付')
    await evidenceDialog.getByRole('button', { name: '确定', exact: true }).click()
    await expect.poll(() => submitted).toEqual({ customerConfirmationRef: '客户邮件明确接受 11 月 5 日交付' })
    expect(requestPath).toContain(scenario === '原方案' ? 'accept-delivery-proposal' : 'confirm-delivery')
  })
}

test('待下达需求只生成任务，条件变化可登记非阻断风险和执行方交期', async ({ page }) => {
  let submitted: Record<string, unknown> | undefined
  await page.addInitScript(() => localStorage.setItem('lq.token', 'production-release-test-only'))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    const reply = (data: unknown) => route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })
    if (path === '/api/auth/me') return reply({ employeeId: 23, name: '车间主任', roles: [], permissions: ['manufacturing.manage'], dataScope: 'ALL', loginType: 'LOCAL' })
    if (path === '/api/erp-sales-orders/production-demands') return reply([{ itemId: 77, orderId: 42, orderNo: 'XSDD42', productName: '全自动气调机', quantity: 1, unit: '台', productionConfigJson: '{}' }])
    if (path.endsWith('/exception-context')) return reply({ downstreamImpact: [], impactToken: 'displayed-facts' })
    if (path.endsWith('/execution-exceptions')) { submitted = route.request().postDataJSON(); return reply(3) }
    return reply([])
  })
  await page.goto('/#/production-demands')
  await expect(page.getByRole('heading', { name: '待下达生产需求' })).toBeVisible()
  await expect(page.getByRole('button', { name: '评估接单' })).toHaveCount(0)
  await page.getByRole('button', { name: '查看并下达' }).click()
  await expect(page.getByRole('button', { name: '生成生产任务', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '登记条件变化' }).click()
  const dialog = page.getByRole('dialog', { name: '登记履约风险与处理方案' })
  await dialog.locator('textarea').nth(0).fill('设备维护影响交期')
  await dialog.locator('textarea').nth(1).fill('已安排替代产线，可在新日期交付')
  await dialog.locator('.el-date-editor input').fill('2026-11-05')
  await dialog.locator('.el-date-editor input').press('Enter')
  await dialog.getByText('已核对关联执行情况，此日期是执行方确认可行的客户交期').click()
  await dialog.getByRole('button', { name: '登记风险与方案' }).click()
  await expect.poll(() => submitted).toMatchObject({ pauseFollowup: false, proposedDeliveryDate: '2026-11-05', impactToken: 'displayed-facts' })
})
