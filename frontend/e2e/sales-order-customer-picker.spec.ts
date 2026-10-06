import { expect, test } from '@playwright/test'
import { ORDER_CUSTOMER, customerDirectoryPage, pickOrderCustomer } from './fixtures/salesOrderCustomer'

/**
 * 销售订单的客户必须来自**客户档案**：档案里没有的客户当场建档，但不允许手填一个游离的名称。
 * 这条用例钉住四件事：
 * 1) 没选客户档案 → 保存被拦（订单往下游靠 customerId 认客户，手填名称挂不上钩）；
 * 2) 档案里有 → 搜到、选中、payload 带 customerId；
 * 3) 档案里没有 → 就地建档（POST /customers/quick-create）并选中；
 * 4) 同名建档 → 不重复建，直接复用既有档案（后端 created=false）。
 *
 * ⚠️ 保存后抽屉标题会从「新建销售订单」变成「销售订单 XSDD…」→ 必须用函数式 locator 重新解析，
 * 否则后续步骤会一直等一个已经不存在的标题（实测踩过）。
 */
test('销售订单客户只能从客户档案选择，档案里没有则就地建档', async ({ page }) => {
  const created: Array<Record<string, any>> = []
  const updated: Array<Record<string, any>> = []
  const quickCreatePosts: Array<Record<string, any>> = []
  await page.addInitScript(() => localStorage.setItem('lq.token', 'order-customer-test-only'))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const reply = (data: unknown) => route.fulfill({ json: {
      code: 0, message: 'ok', timestamp: new Date().toISOString(), data
    } })
    if (path === '/api/auth/me') return reply({ employeeId: 23, name: '销售员', roles: [],
      permissions: ['sales_order.read.all', 'sales_order.manage'], dataScope: 'ALL', loginType: 'LOCAL' })
    if (path === '/api/customers') return reply(customerDirectoryPage)
    if (path === '/api/customers/quick-create' && request.method() === 'POST') {
      const body = request.postDataJSON()
      quickCreatePosts.push(body)
      // 后端按同名幂等：档案里已有这个名字 → created=false，直接复用既有档案
      const duplicated = body.customerName === ORDER_CUSTOMER.customerName
      return reply({ created: !duplicated,
        customer: duplicated ? ORDER_CUSTOMER
          : { id: 9, customerCode: 'CUS000009', customerName: body.customerName } })
    }
    if (path === '/api/erp-sales-orders' && request.method() === 'GET') return reply({ items: [], total: 0, page: 1, size: 20 })
    if (path === '/api/erp-sales-orders' && request.method() === 'POST') {
      created.push(request.postDataJSON())
      return reply(42)
    }
    if (path === '/api/erp-sales-orders/42' && request.method() === 'PUT') {
      updated.push(request.postDataJSON())
      return reply(42)
    }
    if (path === '/api/erp-sales-orders/42') {
      const saved = [...created, ...updated].at(-1)!
      return reply({ head: { id: 42, orderNo: 'XSDD00000042', status: 'DRAFT', customerId: saved.customerId,
        customerName: saved.customerName, orderDate: saved.orderDate, salespersonName: '销售员',
        createdAt: new Date().toISOString() },
      items: saved.lines.map((line: Record<string, unknown>, index: number) => ({ ...line, id: index + 1 })) })
    }
    return reply([])
  })

  // 保存后标题会变，因此每次都用函数式 locator 重新解析抽屉
  const orderDrawer = () => page.getByRole('dialog', { name: /销售订单/ })

  await page.goto('/#/sales-orders')
  await page.getByRole('button', { name: '新建订单' }).click()
  await orderDrawer().locator('.el-table__row').first().locator('td').first().locator('input').first().fill('外采电机')

  // 1) 客户没锚定档案：保存被拦，且不发请求
  await orderDrawer().getByRole('button', { name: '保存草稿' }).click()
  await expect(page.getByText('请从客户档案选择客户')).toBeVisible()
  expect(created).toHaveLength(0)

  // 2) 从客户档案搜到并选中 → payload 带 customerId
  await pickOrderCustomer(page, orderDrawer())
  await orderDrawer().getByRole('button', { name: '保存草稿' }).click()
  await expect.poll(() => created.length).toBe(1)
  expect(created[0]).toMatchObject({ customerId: ORDER_CUSTOMER.id, customerName: ORDER_CUSTOMER.customerName })

  // 3) 档案里没有的客户 → 就地建档并选中（此时订单已存在，保存走更新）
  await orderDrawer().getByRole('button', { name: '新增客户' }).click()
  const createDialog = page.getByRole('dialog', { name: '新增客户档案' })
  await createDialog.locator('input').fill('新客户甲')
  await createDialog.getByRole('button', { name: '建档并选中' }).click()
  await expect(page.getByText('已新建客户档案并选中：新客户甲')).toBeVisible()
  expect(quickCreatePosts[0]).toEqual({ customerName: '新客户甲' })
  await orderDrawer().getByRole('button', { name: '保存草稿' }).click()
  await expect.poll(() => updated.length).toBe(1)
  expect(updated[0]).toMatchObject({ customerId: 9, customerName: '新客户甲' })

  // 4) 同名建档 → 不重复建，直接复用既有档案（后端 created=false）
  await orderDrawer().getByRole('button', { name: '新增客户' }).click()
  await createDialog.locator('input').fill(ORDER_CUSTOMER.customerName)
  await createDialog.getByRole('button', { name: '建档并选中' }).click()
  await expect(page.getByText('档案里已有同名客户，已直接选中')).toBeVisible()
  await orderDrawer().getByRole('button', { name: '保存草稿' }).click()
  await expect.poll(() => updated.length).toBe(2)
  expect(updated[1]).toMatchObject({ customerId: ORDER_CUSTOMER.id, customerName: ORDER_CUSTOMER.customerName })
})
