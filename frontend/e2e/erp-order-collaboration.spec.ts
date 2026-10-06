import { expect, test, type Page } from '@playwright/test'

async function fixture(
  page: Page,
  opts: { readonly?: boolean; held?: boolean; failSources?: boolean; draft?: boolean; blocked?: boolean; noDocuments?: boolean } = {}
) {
  const writes: { path: string; key: string; body: unknown }[] = []
  let status = opts.draft ? 'DRAFT' : 'CONFIRMED'
  await page.addInitScript(() => localStorage.setItem('lq.token', 'erp-collaboration-test'))
  await page.route(
    url => url.pathname.startsWith('/api/'),
    async route => {
      const request = route.request(),
        path = new URL(request.url()).pathname
      const reply = (data: unknown) =>
        route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })
      const fail = (message: string) =>
        route.fulfill({ status: 503, json: { code: 503, message, timestamp: new Date().toISOString() } })
      if (path === '/api/auth/me')
        return reply({
          employeeId: 23,
          name: '测试销售',
          roles: [],
          dataScope: 'ALL',
          loginType: 'LOCAL',
          permissions: [
            'sales_order.read.all',
            ...(!opts.readonly ? ['sales_order.manage'] : []),
            'purchase_order.read.all', 'purchase_order.manage', 'manufacturing.manage'
          ]
        })
      if (request.method() === 'POST') {
        writes.push({ path, key: request.headers()['x-request-key'], body: request.postDataJSON() })
        if (path.endsWith('/outbound-draft')) return fail('模拟提交响应失败，请重试')
        if (path.endsWith('/submit')) status = 'PENDING_APPROVAL'
        return reply(null)
      }
      if (path === '/api/erp-sales-orders/42')
        return reply({
          head: {
            id: 42,
            orderNo: 'SO42',
            customerName: '星禾食品',
            salespersonName: '测试销售',
            status,
            requestedDeliveryDate: '2026-10-08',
            totalAmount: 38000
          },
          items: [
            {
              id: 77,
              productName: '封口设备',
              specification: '200×138 · 一出四 · 220V',
              supplyMode: 'MAKE',
              quantity: 3,
              unit: '台',
              allocatedQuantity: 1,
              shippedQuantity: 1,
              manufacturingOrderId: opts.noDocuments ? null : 7
            },
            { id: 78, productName: '包装盒', supplyMode: 'BUY', quantity: 2000, unit: '个', shippedQuantity: 0 }
          ],
          approvals: [],
          shipments: [
            { outboundId: 55, outboundNo: 'XSCK55', itemId: 77, quantity: 1, productName: '封口设备', status: 'POSTED' }
          ],
          preparation: {
            production: opts.noDocuments ? [] : [
              { id: 7, itemId: 77, documentNo: 'MO7', status: 'PENDING_INSPECTION', workshopName: '封口机车间' }
            ],
            purchases: opts.noDocuments ? [] : [
              { id: 8, itemId: 78, documentNo: 'PO8', status: 'CLOSED', fulfillmentMode: 'DROP_SHIP' },
              { id: 9, itemId: 78, documentNo: 'PO9', status: 'CANCELLED', fulfillmentMode: 'WAREHOUSE' }
            ]
          }
        })
      if (path.endsWith('/delivery-options')) {
        if (opts.failSources) return fail('仓储来源核对暂不可用')
        return reply(
          path.includes('/77/')
            ? [
                {
                  sourceLotId: 88,
                  sourceNo: 'FG88',
                  warehouseId: 3,
                  warehouseName: '温州仓',
                  salesProductId: 111,
                  productName: '封口设备实际入库产品',
                  unit: '台',
                  availableQuantity: 2,
                  stockQuantity: 5,
                  sourceQuantity: 2
                }
              ]
            : []
        )
      }
      if (path.endsWith('/readiness'))
        return reply({
          reviewReady: true,
          submitReady: !opts.blocked,
          configBlockers: [],
          reviewBlockers: opts.blocked ? [{ code: 'FULFILLMENT_REVIEW', message: '采购意见尚未确认' }] : [],
          hints: []
        })
      if (path.endsWith('/execution-exceptions'))
        return reply(
          opts.held
            ? [
                {
                  id: 1,
                  itemId: 77,
                  status: 'OPEN',
                  pauseFollowup: true,
                  reason: '客户确认前暂停',
                  reportedByName: '跟单',
                  reportedAt: '2026-09-29'
                }
              ]
            : []
        )
      if (path === '/api/erp-purchase-orders') return reply({ items: [], total: 0, page: 1, size: 20 })
      if (path === '/api/erp-purchase-orders/8')
        return reply({
          head: {
            id: 8,
            poNo: 'PO8',
            status: 'CLOSED',
            supplierName: '测试供应商',
            fulfillmentMode: 'DROP_SHIP',
            salesOrderId: 42
          },
          items: [],
          receipts: [],
          approvals: [],
          dropShipOutbound: null,
          warehouses: []
        })
      return reply([])
    }
  )
  return writes
}

test('按原型展示同一订单准备路径、来源与分批交付，不把完成采购认作已交付', async ({ page }, testInfo) => {
  await fixture(page)
  await page.goto('/#/sales-orders/42')
  await expect(page.getByRole('heading', { name: '星禾食品 · 订单协作' })).toBeVisible()
  await expect(page.locator('[data-line="77"]')).toContainText('已发 1 · 待发 2')
  await expect(page.locator('[data-line="78"]')).toContainText('供应商直发跟进中')
  await expect(page.getByText('MO7', { exact: true })).toBeVisible()
  await expect(page.getByText('PO8', { exact: true })).toBeVisible()
  await expect(page.getByText('PO9', { exact: true })).toBeVisible()
  await expect(page.getByText('FG88', { exact: false })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('erp-preparation.png'), fullPage: true })
  await page.screenshot({ path: testInfo.outputPath('erp-order-overview.png'), fullPage: true })
  await page.locator('[data-line="78"]').getByRole('button', { name: '查看采购单 →' }).click()
  await expect(page).toHaveURL(/purchase-orders\?purchaseId=8/)
  await expect(page.getByRole('dialog', { name: '采购订单 PO8' })).toBeVisible()
})

test('已确认订单尚无下游单据时，从原明细直达生产和采购需求', async ({ page }) => {
  await fixture(page, { noDocuments: true })
  await page.goto('/#/sales-orders/42')
  await page.locator('[data-line="77"]').getByRole('button', { name: '下达生产任务 →' }).click()
  await expect(page).toHaveURL(/production-demands\?itemId=77.*sourceOrderId=42/)
  await page.goto('/#/sales-orders/42')
  await page.locator('[data-line="78"]').getByRole('button', { name: '办理采购 →' }).click()
  await expect(page).toHaveURL(/purchase-orders\?demandItemId=78.*sourceOrderId=42/)
})

test('发货失败原样重试复用键，修改数量后换键，保持来源与上限', async ({ page }) => {
  const writes = await fixture(page)
  await page.goto('/#/sales-orders/42')
  await page.locator('[data-line="77"]').getByRole('button', { name: '核对并安排发货' }).click()
  const dialog = page.getByRole('dialog', { name: '从原订单明细办理发货' })
  await expect(dialog.getByText('当前总库存 5 台', { exact: false })).toBeVisible()
  await dialog.getByRole('button', { name: '生成草稿' }).click()
  await expect(dialog.getByText('模拟提交响应失败，请重试')).toBeVisible()
  await dialog.getByRole('button', { name: '生成草稿' }).click()
  await expect.poll(() => writes.length).toBe(2)
  expect(writes[0].key).toBeTruthy()
  expect(writes[1].key).toBe(writes[0].key)
  expect(writes[0].body).toMatchObject({ warehouseId: 3, sourceLotId: 88, quantity: 2 })
  await dialog.getByRole('spinbutton').fill('1')
  await dialog.getByRole('spinbutton').blur()
  await dialog.getByRole('button', { name: '生成草稿' }).click()
  await expect.poll(() => writes.length).toBe(3)
  expect(writes[2].key).not.toBe(writes[0].key)
  expect(writes[2].body).toMatchObject({ quantity: 1, sourceLotId: 88 })
})

test('暂停明细禁止发货，只读用户没有写入口', async ({ page }) => {
  await fixture(page, { held: true })
  await page.goto('/#/sales-orders/42')
  await expect(page.locator('[data-line="77"]').getByRole('button', { name: '核对并安排发货' })).toBeDisabled()
  await expect(page.getByText('客户确认前暂停', { exact: false })).toBeVisible()
})

test('可发来源失败不显示就绪结论，也不能生成草稿', async ({ page }) => {
  const writes = await fixture(page, { failSources: true })
  await page.goto('/#/sales-orders/42')
  await expect(page.locator('[data-line="77"]')).toContainText('可发来源未获取')
  await page.locator('[data-line="77"]').getByRole('button', { name: '核对并安排发货' }).click()
  const dialog = page.getByRole('dialog', { name: '从原订单明细办理发货' })
  await expect(dialog.getByText('仓储来源核对暂不可用')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '生成草稿' })).toBeDisabled()
  expect(writes).toHaveLength(0)
})

test('就绪度阻塞可见，满足条件后在全页直接提交审批并刷新状态', async ({ page }) => {
  const writes = await fixture(page, { draft: true })
  await page.goto('/#/sales-orders/42')
  await page.getByRole('button', { name: '提交订单审批', exact: true }).click()
  await page.getByRole('dialog', { name: '提交订单审批' }).getByRole('button', { name: '确认提交订单审批' }).click()
  await expect(page.getByRole('button', { name: '提交订单审批', exact: true })).toHaveCount(0)
  expect(writes[0].path).toBe('/api/erp-sales-orders/42/submit')
  expect(writes[0].key).toBeTruthy()
})

test('未通过评审不能提交，权限不足没有发货与提交按钮', async ({ page }) => {
  await fixture(page, { draft: true, blocked: true, readonly: true })
  await page.goto('/#/sales-orders/42')
  await expect(page.getByRole('button', { name: '核对并安排发货' })).toHaveCount(0)
  await expect(page.getByText('采购意见尚未确认')).toBeVisible()
  await expect(page.getByRole('button', { name: '提交订单审批', exact: true })).toHaveCount(0)
})
