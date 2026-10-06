import { expect, test } from '@playwright/test'

test('出库单关联订单直达订单全流程', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('lq.token', 'erp-navigation-fixture'))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    const reply = (data: unknown) => route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })
    if (path === '/api/auth/me') return reply({ employeeId: 1, name: '测试', roles: [], permissions: ['sales_order.read.all'], dataScope: 'ALL', loginType: 'LOCAL' })
    if (path === '/api/sales-outbounds') return reply({ items: [], total: 0, page: 1, size: 20 })
    if (path === '/api/sales-outbounds/55') return reply({ head: { id: 55, outboundNo: 'XSCK55', status: 'POSTED', salesOrderId: 42, salesOrderRef: 'SO42', customerName: '星禾食品' }, items: [] })
    if (path === '/api/erp-sales-orders/42') return reply({ head: { id: 42, orderNo: 'SO42', status: 'CONFIRMED', customerName: '星禾食品' }, items: [], approvals: [], shipments: [], preparation: { production: [], purchases: [] } })
    return reply([])
  })
  await page.goto('/#/sales-outbounds?outboundId=55&sourceOrderId=42')
  const outbound = page.getByRole('dialog').filter({ hasText: 'XSCK55' })
  await expect(outbound.getByRole('button', { name: 'SO42' })).toBeVisible()
  await outbound.getByRole('button', { name: 'SO42' }).click()
  await expect(page).toHaveURL(/sales-orders\/42/)
  await expect(page.getByRole('heading', { name: '星禾食品 · 订单协作' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '分批交付记录' })).toBeVisible()
})
