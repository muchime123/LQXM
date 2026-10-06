import { expect, test, type Locator, type Page } from '@playwright/test'
import { ORDER_CUSTOMER, customerDirectoryPage, pickOrderCustomer } from './fixtures/salesOrderCustomer'

const formItem = (drawer: Locator, label: string) =>
  drawer.locator('.el-form-item').filter({ hasText: label }).first()

async function select(page: Page, drawer: Locator, label: string, option: string) {
  await formItem(drawer, label).locator('.el-select__wrapper').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: option }).first().click()
}

test('销售订单复用生产任务单配置表单，保存配置时不提前创建工单', async ({ page }) => {
  const manufacturingPosts: string[] = []
  const orderPayloads: Array<Record<string, any>> = []
  await page.addInitScript(() => localStorage.setItem('lq.token', 'sales-config-test-only'))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const reply = (data: unknown) => route.fulfill({ json: {
      code: 0, message: 'ok', timestamp: new Date().toISOString(), data
    } })
    if (path === '/api/product-configurations/products') return reply({ products: [], options: [] })
    if (path === '/api/auth/me') return reply({ employeeId: 23, name: '销售员', roles: [],
      permissions: ['sales_order.read.all', 'sales_order.manage'], dataScope: 'ALL', loginType: 'LOCAL' })
    if (path === '/api/customers') return reply(customerDirectoryPage)
    if (path === '/api/erp-sales-orders/production-workshops')
      return reply([{ id: 7, workshopName: '气调机车间' }])
    if (path === '/api/erp-sales-orders/production-options')
      return reply({ productNames: ['全自动气调机'], productTemplates: { 全自动气调机: 8 },
        productTemplateNames: { 全自动气调机: '气调机标准制程' }, optionalConfigs: ['加长嘴'] })
    if (path === '/api/erp-sales-orders' && request.method() === 'POST') {
      orderPayloads.push(request.postDataJSON())
      return reply(42)
    }
    if (path === '/api/erp-sales-orders/42') {
      const saved = orderPayloads[0]
      return reply({ head: { id: 42, orderNo: 'XSDD00000042', status: 'DRAFT', customerId: ORDER_CUSTOMER.id, customerName: saved.customerName,
        orderDate: saved.orderDate, salespersonName: '销售员', createdAt: new Date().toISOString() },
      items: saved.lines.map((line: Record<string, unknown>, index: number) => ({ ...line, id: index + 1,
        productionConfigJson: JSON.stringify(line.productionConfig) })) })
    }
    if (path === '/api/erp-sales-orders' && request.method() === 'GET')
      return reply({ items: [], total: 0, page: 1, size: 20 })
    if (path === '/api/manufacturing/orders' && request.method() === 'POST') {
      manufacturingPosts.push(path)
      return reply(99)
    }
    return reply([])
  })

  await page.goto('/#/sales-orders')
  await page.getByRole('button', { name: '新建订单' }).click()
  const order = page.getByRole('dialog', { name: '新建销售订单' })
  await pickOrderCustomer(page, order)
  const line = order.locator('.el-table__row').first()
  await line.locator('td').nth(1).locator('.el-select__wrapper').click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: '自产' }).first().click()
  await line.getByRole('button', { name: '配置自产产品' }).click()

  const config = page.getByRole('dialog', { name: '生产任务单 · 配置销售需求' })
  await expect(config).toBeVisible()
  await expect(config.getByText('制程选择')).toHaveCount(0)
  await select(page, config, '产线选择', '气调机车间')
  await select(page, config, '产品名称', '全自动气调机')
  await expect(formItem(config, '对应制程').locator('input')).toHaveValue('气调机标准制程')
  await formItem(config, '盒子形状').getByText('方形', { exact: true }).click()
  await formItem(config, '盒子长').locator('input').fill('200')
  await formItem(config, '盒子宽').locator('input').fill('150')
  await formItem(config, '盒子高度').locator('input').fill('40')
  await select(page, config, '模具排列', '1出1')
  await formItem(config, '盒子材质').locator('input').fill('PP')
  await formItem(config, '电压').getByText('单相220V', { exact: true }).click()
  await formItem(config, '插头').getByText('国标', { exact: true }).click()
  await formItem(config, '品牌').getByText('利强品牌', { exact: true }).click()
  await config.getByRole('button', { name: '保存配置到销售订单' }).click()
  await expect(config).toBeHidden()
  expect(manufacturingPosts).toHaveLength(0)

  await order.getByRole('button', { name: '保存草稿' }).click()
  await expect.poll(() => orderPayloads.length).toBe(1)
  expect(orderPayloads[0].lines[0].productionConfig).toMatchObject({ workshopId: 7,
    productName: '全自动气调机', boxShape: 'RECT', boxSize: '200x150', boxLengthMm: '200', boxWidthMm: '150' })
  // 订单头必须带上客户档案 id（只是名称锚不上档案，下游就对不上客户）
  expect(orderPayloads[0]).toMatchObject({ customerId: ORDER_CUSTOMER.id, customerName: ORDER_CUSTOMER.customerName })
  expect(manufacturingPosts).toHaveLength(0)
})
