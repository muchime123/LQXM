import { expect, test, type Page } from '@playwright/test'

async function mock(page: Page, permissions: string[], options: { failOutbound?: boolean; failStock?: boolean } = {}) {
  const requests: string[] = []
  await page.addInitScript(() => localStorage.setItem('lq.token', 'erp-test-only'))
  await page.route(
    url => url.pathname.startsWith('/api/'),
    async route => {
      const path = new URL(route.request().url()).pathname
      requests.push(path)
      const reply = (data: unknown) =>
        route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })
      if (path === '/api/auth/me')
        return reply({ employeeId: 23, name: '测试销售', roles: [], permissions, dataScope: 'ALL', loginType: 'LOCAL' })
      if (path === '/api/erp-sales-orders')
        return reply({
          items: [
            { id: 42, orderNo: 'XSDD00000042', customerName: '测试客户', salespersonName: '测试销售', status: 'DRAFT' }
          ],
          total: 9,
          page: 1,
          size: 5
        })
      if (path === '/api/erp-sales-orders/42')
        return reply({
          head: { id: 42, orderNo: 'XSDD00000042', customerName: '测试客户', status: 'CONFIRMED' },
          items: [{ id: 77, productName: '气调机', supplyMode: 'MAKE', quantity: 3, shippedQuantity: 1, unit: '台' }],
          approvals: [],
          shipments: [{ outboundId: 55, outboundNo: 'XSCK55', status: 'DRAFT', quantity: 1, productName: '气调机' }]
        })
      if (path === '/api/sales-outbounds/available-stock') {
        if (options.failStock)
          return route.fulfill({
            status: 500,
            json: { code: 500, timestamp: new Date().toISOString(), message: '正式库存暂不可用' }
          })
        return reply([
          {
            balanceId: 1,
            warehouseId: 3,
            warehouseName: '温州仓',
            productName: '气调机',
            productCode: 'CP001',
            available: 2,
            unit: '台'
          }
        ])
      }
      if (path === '/api/sales-outbounds') {
        if (options.failOutbound)
          return route.fulfill({
            status: 500,
            json: { code: 500, timestamp: new Date().toISOString(), message: '出库队列暂不可用' }
          })
        return reply({ items: [], total: 0, page: 1, size: 5 })
      }
      return reply([])
    }
  )
  return requests
}

test('ERP 共用侧栏，真实订单待办进入全页协作，正式库存可查询', async ({ page }) => {
  const requests = await mock(page, ['sales_order.read.all'])
  await page.goto('/#/erp-workbench')
  await expect(page.getByRole('heading', { name: '业务工作台', exact: true })).toBeVisible()
  await expect(page.locator('[data-queue="orders"] .el-tag')).toHaveText('9')
  await expect(page.locator('[data-queue="production"]')).toHaveCount(0)
  await page.getByRole('button', { name: 'XSDD00000042 测试客户 · 测试销售' }).click()
  await expect(page).toHaveURL(/sales-orders\/42/)
  await expect(page.getByRole('heading', { name: '每项需求的交付准备' })).toBeVisible()
  await expect(page.locator('[data-line="77"]')).toContainText('已发 1 · 待发 2')
  await expect(page.getByText('XSCK55', { exact: true })).toBeVisible()
  expect(requests.some(path => path.includes('/ledger/'))).toBeFalsy()
  await page.goto('/#/erp-inventory')
  await expect(page.getByRole('heading', { name: '可售库存', exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '温州仓', exact: true })).toBeVisible()
  expect(requests.some(path => path.includes('production-demands'))).toBeFalsy()
  expect(requests.some(path => path.includes('erp-purchase-orders'))).toBeFalsy()
})

test('失败队列与零待办区分，其他队列保持可用', async ({ page }) => {
  await mock(page, ['sales_order.read.all'], { failOutbound: true })
  await page.goto('/#/erp-workbench')
  await expect(page.locator('[data-queue="outbounds"]')).toContainText('未获取')
  await expect(page.locator('[data-queue="outbounds"]')).toContainText('出库队列暂不可用')
  await expect(page.locator('[data-queue="outbounds"]')).not.toContainText('当前没有待处理事项')
  await expect(page.locator('[data-queue="orders"]')).toContainText('XSDD00000042')
})

test('无业务权限不请求队列，库存访问受路由权限控制', async ({ page }) => {
  const requests = await mock(page, [])
  await page.goto('/#/erp-workbench')
  await expect(page.getByText('当前账号暂无 ERP 工作队列权限，请联系管理员配置业务授权。')).toBeVisible()
  expect(requests.some(path => path.includes('erp-sales-orders') || path.includes('sales-outbounds'))).toBeFalsy()
  await page.goto('/#/erp-inventory')
  await expect(page).toHaveURL(/403/)
  expect(requests).not.toContain('/api/sales-outbounds/available-stock')
})

test('库存请求失败不展示零库存结论', async ({ page }) => {
  await mock(page, ['sales_order.read.all'], { failStock: true })
  await page.goto('/#/erp-inventory')
  await expect(page.getByText('正式库存暂不可用', { exact: true })).toBeVisible()
  await expect(page.getByText('当前返回 0 行', { exact: false })).toHaveCount(0)
})


test('移动端工作台可操作且不产生页面横向溢出', async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mock(page, ['sales_order.read.all'])
  await page.goto('/#/erp-workbench')
  await expect(page.getByRole('heading', { name: '业务工作台', exact: true })).toBeVisible()
  await expect(page.locator('[data-queue="orders"]')).toContainText('XSDD00000042')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
  await page.screenshot({ path: testInfo.outputPath('erp-mobile.png'), fullPage: true })
})
