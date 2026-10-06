import { expect, test } from '@playwright/test'
import { customerDirectoryPage } from './fixtures/salesOrderCustomer'

for (const mode of ['MAKE', 'BUY'] as const) {
  test(`${mode} 从原订单明细生成关联出库，沿用实际入库产品与仓库`, async ({ page }) => {
    let submitted: Record<string, unknown> | undefined
    await page.addInitScript(() => localStorage.setItem('lq.token', 'order-delivery-test-only'))
    const head = { id: 42, orderNo: 'XSDD00000042', status: 'CONFIRMED', customerId: 5, customerName: '测试客户', orderDate: '2026-09-28' }
    await page.route(url => url.pathname.startsWith('/api/'), async route => {
      const path = new URL(route.request().url()).pathname
      const reply = (data: unknown) => route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })
      if (path === '/api/auth/me') return reply({ employeeId: 23, name: '销售员', roles: [],
        permissions: ['sales_order.read.all', 'sales_order.manage'], dataScope: 'ALL', loginType: 'LOCAL' })
      if (path === '/api/customers') return reply(customerDirectoryPage)
      if (path === '/api/erp-sales-orders') return reply({ items: [head], total: 1, page: 1, size: 20 })
      if (path === '/api/erp-sales-orders/42') return reply({ head, items: [{ id: 77, lineNo: 1, supplyMode: mode,
        productName: '全自动气调机', quantity: 3, unit: '台', allocatedQuantity: 1, shippedQuantity: 0 }], approvals: [], shipments: [] })
      if (path === '/api/erp-sales-orders/42/items/77/delivery-options') return reply([{
        sourceLotId: mode === 'MAKE' ? 88 : undefined, sourcePurchaseItemId: mode === 'BUY' ? 99 : undefined,
        sourceNo: mode === 'MAKE' ? 'FG00000088' : 'CGDD00000099', warehouseId: 3, warehouseName: '温州仓',
        salesProductId: 111, productName: '气调机实际入库产品', unit: '台', stockQuantity: 5, sourceQuantity: 2, availableQuantity: 2,
      }])
      if (path === '/api/erp-sales-orders/42/items/77/outbound-draft') { submitted = route.request().postDataJSON(); return reply(55) }
      return reply([])
    })
    await page.goto('/#/sales-orders?orderId=42')
    const order = page.getByRole('dialog', { name: '销售订单 XSDD00000042' })
    await order.getByRole('button', { name: '办理发货' }).click()
    const delivery = page.getByRole('dialog', { name: '从原订单明细办理发货' })
    await expect(delivery.getByText('当前总库存 5 台', { exact: false })).toBeVisible()
    await delivery.getByRole('button', { name: '生成草稿' }).click()
    await expect.poll(() => submitted).toMatchObject({ warehouseId: 3, quantity: 2 })
    expect(submitted?.sourceLotId).toBe(mode === 'MAKE' ? 88 : undefined)
    await expect(page).toHaveURL(/sales-outbounds.*outboundId=55.*sourceOrderId=42/)
  })
}
