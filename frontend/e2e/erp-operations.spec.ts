import { expect, test, type Page } from '@playwright/test'

test('ERP 子菜单连续切换更新队列，业务标签返回保留采购筛选', async ({ page }) => {
  await mock(page, ['purchase_order.read.all', 'manufacturing.read.all', 'sales_order.read.all'])
  await page.goto('/#/erp-purchase')
  await page.locator('[data-operation-queue="drop-ship"]').getByRole('button', { name: '查看完整队列 →' }).click()
  await expect(page.locator('.multi-tab__item.is-active')).toContainText('采购订单')
  const sidebar = page.getByRole('navigation', { name: '主导航' })
  await sidebar.getByRole('link', { name: '生产管理', exact: true }).click()
  await expect(page.getByRole('heading', { name: '生产协同工作台' })).toBeVisible()
  await expect(page.locator('[data-operation-queue="tasks"]')).toContainText('MO7')
  await sidebar.getByRole('link', { name: '库存与交付', exact: true }).click()
  await expect(page.getByRole('heading', { name: '仓储与交付工作台' })).toBeVisible()
  await expect(page.locator('[data-operation-queue="outbounds"]')).toContainText('XSCK55')
  await page.locator('.multi-tab__item').filter({ has: page.locator('.multi-tab__label', { hasText: /^采购订单$/ }) }).click()
  await expect(page).toHaveURL(/status=CONFIRMED.*fulfillmentMode=DROP_SHIP/)
  await expect(sidebar.getByRole('link', { name: '采购管理', exact: true })).toHaveClass(/active/)
})

test('车间任务入口打开任务总览，返回工作台后仓储检验入口打开检验队列', async ({ page }) => {
  await mock(page, ['manufacturing.read.all', 'manufacturing.inspect'])
  await page.goto('/#/erp-production')
  await page.getByRole('navigation', { name: '业务工作区导航' }).getByRole('button', { name: '生产任务', exact: true }).click()
  await expect(page).toHaveURL(/tab=tasks/)
  await expect(page.getByRole('tab', { name: '任务单总览' })).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('.multi-tab__item.is-active')).toContainText('车间执行')
  await page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '库存与交付', exact: true }).click()
  await page.getByRole('navigation', { name: '业务工作区导航' }).getByRole('button', { name: '检验与交仓', exact: true }).click()
  await expect(page).toHaveURL(/tab=quality/)
  await expect(page.getByRole('tab', { name: '待完工检验' })).toHaveAttribute('aria-selected', 'true')
  await page.getByRole('navigation', { name: '业务工作区导航' }).getByRole('button', { name: '业务工作台', exact: true }).click()
  await expect(page).toHaveURL(/erp-workbench/)
})

async function mock(page: Page, permissions: string[], failArrival = false) {
  const requests: { path: string; query: string; method: string }[] = []
  await page.addInitScript(() => localStorage.setItem('lq.token', 'erp-operations-test'))
  await page.route(
    url => url.pathname.startsWith('/api/'),
    async route => {
      const request = route.request(),
        url = new URL(request.url()),
        path = url.pathname
      requests.push({ path, query: url.search, method: request.method() })
      const reply = (data: unknown) =>
        route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })
      const pageResult = (items: unknown[], total = items.length) => reply({ items, total, page: 1, size: 8 })
      if (path === '/api/auth/me')
        return reply({ employeeId: 23, name: '协同人员', roles: [], permissions, dataScope: 'ALL', loginType: 'LOCAL' })
      if (path === '/api/erp-sales-orders/production-demands')
        return reply([
          {
            itemId: 77,
            orderId: 42,
            orderNo: 'SO42',
            customerName: '星禾食品',
            productName: '封口机',
            quantity: 2,
            unit: '台',
            workshopId: 1,
            workshopName: '封口机车间',
            productionConfigJson: '{}'
          }
        ])
      if (path === '/api/manufacturing/orders')
        return pageResult(
          [
            {
              id: 7,
              orderNo: 'MO7',
              productNameSnapshot: '封口机',
              plannedQuantity: 2,
              unit: '台',
              status: 'IN_PRODUCTION'
            }
          ],
          20
        )
      if (path === '/api/manufacturing/execution-board')
        return reply({ items: [], total: 0, summary: { total: 0 }, workshops: [] })
      if (path === '/api/manufacturing/inspections/pending')
        return pageResult([
          {
            id: 7,
            orderNo: 'MO7',
            productNameSnapshot: '封口机',
            plannedQuantity: 2,
            unit: '台',
            status: 'PENDING_INSPECTION'
          }
        ])
      if (path === '/api/manufacturing/orders/7')
        return reply({
          order: {
            id: 7,
            orderNo: 'MO7',
            status: 'PENDING_INSPECTION',
            productNameSnapshot: '封口机',
            plannedQuantity: 2,
            unit: '台'
          },
          outputs: [],
          operations: [],
          receipts: [],
          inspections: [],
          issues: [],
          approvals: [],
          workReports: [],
          lots: []
        })
      if (path === '/api/manufacturing/warehouses') return reply([{ id: 3, warehouseName: '温州仓' }])
      if (path === '/api/erp-purchase-orders/demands')
        return reply([
          {
            itemId: 78,
            orderId: 42,
            orderNo: 'SO42',
            customerName: '星禾食品',
            productName: '包装盒',
            quantity: 2000,
            unit: '个'
          }
        ])
      if (path === '/api/erp-purchase-orders') {
        const mode = url.searchParams.get('fulfillmentMode'),
          status = url.searchParams.get('status') || 'CONFIRMED'
        if (failArrival && mode === 'WAREHOUSE' && status === 'CONFIRMED')
          return route.fulfill({
            status: 503,
            json: { code: 503, timestamp: new Date().toISOString(), message: '到货队列暂不可用' }
          })
        return pageResult(
          [
            {
              id: mode === 'DROP_SHIP' ? 9 : 8,
              poNo: mode === 'DROP_SHIP' ? 'PO9' : 'PO8',
              supplierName: '包材供应商',
              status,
              fulfillmentMode: mode || 'WAREHOUSE',
              salesOrderId: 42,
              salesOrderNo: 'SO42'
            }
          ],
          mode === 'DROP_SHIP' ? 4 : 6
        )
      }
      if (path === '/api/erp-purchase-orders/8' || path === '/api/erp-purchase-orders/9')
        return reply({
          head: {
            id: path.endsWith('/9') ? 9 : 8,
            poNo: path.endsWith('/9') ? 'PO9' : 'PO8',
            supplierName: '包材供应商',
            status: 'RECEIVING',
            fulfillmentMode: path.endsWith('/9') ? 'DROP_SHIP' : 'WAREHOUSE',
            warehouseId: 3,
            salesOrderId: 42
          },
          items: [{ id: 101, productName: '包装盒', quantity: 3, receivedQuantity: 2, pendingQuantity: 1, unit: '个' }],
          receipts: [],
          approvals: [],
          dropShipOutbound: null,
          warehouses: [{ id: 3, warehouseName: '温州仓' }]
        })
      if (path === '/api/sales-outbounds')
        return pageResult(
          [
            {
              id: 55,
              outboundNo: 'XSCK55',
              customerName: '星禾食品',
              status: 'DRAFT',
              warehouseName: '温州仓',
              salesOrderId: 42
            }
          ],
          12
        )
      if (path === '/api/sales-outbounds/warehouses') return reply([{ id: 3, warehouseName: '温州仓' }])
      if (path === '/api/sales-outbounds/55')
        return reply({
          head: { id: 55, outboundNo: 'XSCK55', customerName: '星禾食品', status: 'DRAFT', warehouseId: 3 },
          items: []
        })
      return reply([])
    }
  )
  return requests
}

test('采购工作台分开到仓、部分到货与代发，按来源直接核对本次收货', async ({ page }, testInfo) => {
  const requests = await mock(page, ['purchase_order.read.all', 'purchase_order.manage', 'sales_order.read.all'])
  await page.goto('/#/erp-purchase')
  await expect(page.getByRole('heading', { name: '采购协同工作台' })).toBeVisible()
  await expect(page.locator('[data-operation-queue="drop-ship"] .el-tag').first()).toHaveText('4')
  await expect(page.locator('[data-operation-queue="arrival-RECEIVING"]')).toContainText('部分到货')
  await page.screenshot({ path: testInfo.outputPath('erp-purchase.png'), fullPage: true })
  await page
    .locator('[data-operation-queue="arrival-RECEIVING"]')
    .getByRole('button', { name: '核对本次到货 →' })
    .click()
  await expect(page).toHaveURL(/purchaseId=8.*action=receive.*sourceOrderId=42/)
  await expect(page.getByRole('dialog', { name: '到货入库', exact: true })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '业务工作区导航' }).getByRole('button', { name: '返回来源订单' })).toBeVisible()
  await expect(page.getByRole('dialog', { name: '到货入库', exact: true }).getByRole('spinbutton')).toHaveValue('1.000')
  expect(requests.some(row => row.method !== 'GET')).toBeFalsy()
  await expect(
    page.getByRole('navigation', { name: '主导航' }).getByRole('link', { name: '采购管理', exact: true })
  ).toHaveClass(/active/)
})

test('跨采购页保留来源订单并能回到订单协作页', async ({ page }) => {
  await mock(page, ['purchase_order.read.all', 'purchase_order.manage', 'sales_order.read.all'])
  await page.goto('/#/erp-purchase')
  await page.locator('[data-operation-queue="arrival-RECEIVING"]').getByRole('button', { name: '核对本次到货 →' }).click()
  await page.getByRole('dialog', { name: '到货入库', exact: true }).getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('dialog', { name: '采购订单 PO8' }).getByRole('button', { name: '关闭', exact: true }).click()
  const nav = page.getByRole('navigation', { name: '业务工作区导航' })
  await nav.getByRole('button', { name: '采购工作台', exact: true }).click()
  await expect(page).toHaveURL(/erp-purchase\?sourceOrderId=42/)
  await nav.getByRole('button', { name: '返回来源订单' }).click()
  await expect(page).toHaveURL(/sales-orders\/42/)
  await expect(page.getByRole('heading', { name: '每项需求的交付准备' })).toBeVisible()
})

test('完整采购队列保留状态和履约方式过滤，供应商直发不会自动入库', async ({ page }) => {
  const requests = await mock(page, ['purchase_order.read.all', 'purchase_order.manage'])
  await page.goto('/#/erp-purchase')
  await page.locator('[data-operation-queue="drop-ship"]').getByRole('button', { name: '查看完整队列 →' }).click()
  await expect(page).toHaveURL(/status=CONFIRMED.*fulfillmentMode=DROP_SHIP/)
  await expect
    .poll(() =>
      requests.some(
        row =>
          row.path === '/api/erp-purchase-orders' &&
          row.query.includes('status=CONFIRMED') &&
          row.query.includes('fulfillmentMode=DROP_SHIP')
      )
    )
    .toBeTruthy()
  await expect(page.getByRole('dialog', { name: '到货入库', exact: true })).toHaveCount(0)
  expect(requests.some(row => row.method !== 'GET')).toBeFalsy()
})

test('检验账号只请求已授权队列，仓储直达原任务检验，不自动提交入库', async ({ page }) => {
  const requests = await mock(page, ['manufacturing.inspect'])
  await page.goto('/#/erp-warehouse')
  await expect(page.locator('[data-operation-queue="quality"]')).toContainText('MO7')
  expect(
    requests.some(row => row.path.includes('erp-purchase-orders') || row.path.includes('sales-outbounds'))
  ).toBeFalsy()
  await page.getByRole('button', { name: '核对检验与收货 →' }).click()
  await expect(page).toHaveURL(/taskId=7.*action=quality/)
  await expect(page.getByRole('dialog', { name: '完工检验并入库' }).getByText('MO7')).toBeVisible()
  expect(requests.some(row => row.method !== 'GET')).toBeFalsy()
})

test('生产待接需求直达指定明细，未授权时不请求任务列表', async ({ page }) => {
  const requests = await mock(page, ['manufacturing.manage'])
  await page.goto('/#/erp-production')
  await expect(page.locator('[data-operation-queue="demands"]')).toContainText('SO42')
  expect(requests.some(row => row.path === '/api/manufacturing/orders')).toBeFalsy()
  await page.getByRole('button', { name: '核对并接单 →' }).click()
  await expect(page.getByRole('dialog', { name: '下达生产任务' }).getByText('SO42')).toBeVisible()
  expect(requests.some(row => row.method !== 'GET')).toBeFalsy()
})

test('单个到货队列失败不伪装成零待办，代发仍可查询', async ({ page }) => {
  await mock(page, ['purchase_order.read.all'], true)
  await page.goto('/#/erp-purchase')
  await expect(page.locator('[data-operation-queue="arrival-CONFIRMED"]')).toContainText('到货队列暂不可用')
  await expect(page.locator('[data-operation-queue="arrival-CONFIRMED"]')).toContainText('未获取')
  await expect(page.locator('[data-operation-queue="arrival-CONFIRMED"]')).not.toContainText(
    '当前查询范围没有待处理记录'
  )
  await expect(page.locator('[data-operation-queue="drop-ship"]')).toContainText('PO9')
})

test('仓储按待发草稿直达原出库单，移动端无页面横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mock(page, ['sales_order.read.all'])
  await page.goto('/#/erp-warehouse')
  await expect(page.locator('[data-operation-queue="outbounds"] .el-tag').first()).toHaveText('12')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
  await page.getByRole('button', { name: '核对并办理出库 →' }).click()
  await expect(page.getByRole('dialog', { name: 'XSCK55' })).toBeVisible()
})
