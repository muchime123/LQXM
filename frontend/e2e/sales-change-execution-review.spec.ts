import { expect, test } from '@playwright/test'

test('执行中的交期变更必须确认关联单据，提交复评携带所见版本', async ({ page }) => {
  let submitted: Record<string, unknown> | undefined
  await page.addInitScript(() => localStorage.setItem('lq.token', 'execution-review-test-only'))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    const reply = (data: unknown) => route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })
    if (path === '/api/auth/me') return reply({ employeeId: 23, name: '车间审核员', roles: [],
      permissions: ['sales_order.read.all', 'manufacturing.manage'], dataScope: 'ALL', loginType: 'LOCAL' })
    if (path === '/api/erp-sales-orders/change-requests/production') return reply(submitted ? [] : [{
      changeId: 9, orderId: 1, itemId: 2, exceptionId: 3, orderNo: 'SO-TEST-1', customerName: '测试客户',
      productName: '全自动气调机', supplyMode: 'MAKE', originalQuantity: 1, proposedQuantity: 1,
      originalDeliveryDate: '2026-10-01', proposedDeliveryDate: '2026-10-20', exceptionReason: '客户交期调整',
      customerConfirmationRef: '测试客户确认记录', impactToken: 'displayed-version-1',
      downstreamImpact: [{ id: 5, documentType: 'PRODUCTION', documentNo: 'MO-TEST-1', status: 'IN_PRODUCTION',
        quantity: 1, reportedQuantity: 2, executedQuantity: 0, deliveryDate: '2026-10-01' }],
    }])
    if (path === '/api/erp-sales-orders/change-requests/9/review-production') {
      submitted = route.request().postDataJSON()
      return reply(null)
    }
    return reply([])
  })
  await page.goto('/#/sales-fulfillment-reviews')
  await page.getByRole('button', { name: '复评变更', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '复评已确认订单变更' })
  await expect(dialog.getByText('MO-TEST-1')).toBeVisible()
  await dialog.getByRole('button', { name: '复评通过', exact: true }).click()
  await expect(page.getByText('请核对全部关联单据、勾选确认并填写执行协调安排')).toBeVisible()
  expect(submitted).toBeUndefined()
  await dialog.locator('textarea').fill('已与车间确认排产，客户交期可履行')
  await dialog.getByText('已核对全部关联单据，并与相关执行人员确认交期处理安排', { exact: true }).click()
  await expect(dialog.getByRole('checkbox')).toBeChecked()
  await dialog.getByRole('button', { name: '复评通过', exact: true }).click()
  await expect.poll(() => submitted).toMatchObject({
    comment: '已与车间确认排产，客户交期可履行', downstreamConfirmed: true, impactToken: 'displayed-version-1',
  })
  await expect(dialog).not.toBeVisible()
})
