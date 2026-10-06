import { expect, test, type Locator, type Page } from '@playwright/test'
import { customerDirectoryPage, pickOrderCustomer } from './fixtures/salesOrderCustomer'

/**
 * 新建销售订单的「期望交期」默认为**下单日 + 7 天**（LQ 2026-09-28 定），且仍可在页面上改。
 *
 * 三条用例钉三件事：
 * 1) 新建时两字段差 7 天 —— 用「订单日期 → 期望交期」的差值断言，不写死今天的日期，
 *    因此不随时间漂移、也不受运行时时区影响；
 * 2) 默认值真的会提交：不改就存 → payload 是默认值；改了再存 → payload 是改后的值；
 * 3) 打开**既有订单**时不被默认值污染：库里是空就显示空、存了值就显示原值
 *    （否则会把客户承诺的交期静默改写，等于篡改承诺）。
 *
 * ⚠️ 替身必须按真实后端形状返回 `{ head, items }`：保存后前端会 `openDetail(id)` 回读，
 * 少了 `head.status` 会让「保存草稿」按钮直接消失、少了 `items` 会抛错导致抽屉打不开——
 * 两种表现都像"应用坏了"，实为替身不真。
 */

const pad = (n: number) => String(n).padStart(2, '0')
const toIso = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
function shift(isoDate: string, days: number) {
  const d = new Date(`${isoDate}T00:00:00`)
  d.setDate(d.getDate() + days)
  return toIso(d)
}

/** 「订单日期 / 期望交期」都在头部表单里，按 label 定位它的日期输入框。 */
const dateInput = (drawer: Locator, label: string) =>
  drawer.locator('.order-head-form .el-form-item').filter({ hasText: label }).first().locator('input').first()

/** 保存后抽屉标题从「新建销售订单」变成「销售订单 XSDD…」→ 必须函数式重新解析，不能缓存 locator。 */
const orderDrawer = (page: Page) => page.getByRole('dialog', { name: /销售订单/ })

type OrderDoc = { head: Record<string, any>; items?: Array<Record<string, any>> }

/** 销售订单页的最小后端替身：记录写入 payload，便于断言"到底提交了什么"。 */
async function stubOrderApi(page: Page, orders: OrderDoc[]) {
  const created: Array<Record<string, any>> = []
  const updated: Array<Record<string, any>> = []
  const detail: Record<number, { head: Record<string, any>; items: Array<Record<string, any>> }> = {}
  for (const o of orders) detail[o.head.id] = { ...o, items: o.items ?? [] }

  await page.addInitScript(() => localStorage.setItem('lq.token', 'delivery-default-test-only'))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const reply = (data: unknown) =>
      route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })

    if (path === '/api/auth/me')
      return reply({ employeeId: 23, name: '销售员', roles: [],
        permissions: ['sales_order.read.all', 'sales_order.manage'], dataScope: 'ALL', loginType: 'LOCAL' })
    if (path === '/api/customers') return reply(customerDirectoryPage)
    if (path === '/api/erp-sales-orders' && request.method() === 'GET')
      return reply({ items: orders.map(o => o.head), total: orders.length, page: 1, size: 20 })
    if (path === '/api/erp-sales-orders' && request.method() === 'POST') {
      const body = request.postDataJSON()
      created.push(body)
      // 保存后前端会 openDetail(id) 回读：把提交的内容当作已落库的草稿，抽屉才停在正常的编辑态
      detail[42] = {
        head: { id: 42, orderNo: 'XSDD00000042', status: 'DRAFT', approvalStatus: 'NONE',
          customerId: body.customerId, customerName: body.customerName, orderDate: body.orderDate,
          requestedDeliveryDate: body.requestedDeliveryDate, remark: body.remark },
        items: body.lines.map((line: Record<string, any>, index: number) => ({ ...line, id: index + 1 }))
      }
      return reply(42)
    }
    const idMatch = /^\/api\/erp-sales-orders\/(\d+)$/.exec(path)
    if (idMatch) {
      const id = Number(idMatch[1])
      const doc = detail[id] ?? { head: {}, items: [] }
      if (request.method() === 'PUT') {
        const body = request.postDataJSON()
        updated.push(body)
        doc.head = { ...doc.head, ...body, id }
        return reply(id)
      }
      return reply({ ...doc, shipments: [], approvals: [] })
    }
    return reply([])
  })
  return { created, updated }
}

test('新建订单的期望交期默认是订单日期 + 7 天', async ({ page }) => {
  await stubOrderApi(page, [])
  await page.goto('/#/sales-orders')
  await page.getByRole('button', { name: '新建订单' }).click()

  const orderDate = await dateInput(orderDrawer(page), '订单日期').inputValue()
  const delivery = await dateInput(orderDrawer(page), '期望交期').inputValue()

  expect(orderDate).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  expect(delivery).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  expect(delivery).toBe(shift(orderDate, 7))
})

test('默认值随保存进入 payload，且允许改成别的日期', async ({ page }) => {
  const { created, updated } = await stubOrderApi(page, [])
  await page.goto('/#/sales-orders')
  await page.getByRole('button', { name: '新建订单' }).click()
  await orderDrawer(page).locator('.el-table__row').first().locator('td').first().locator('input').first().fill('外采电机')

  const orderDate = await dateInput(orderDrawer(page), '订单日期').inputValue()
  const defaultDelivery = shift(orderDate, 7)

  // 1) 不动交期直接存 → 提交的正是默认值
  await pickOrderCustomer(page, orderDrawer(page))
  await orderDrawer(page).getByRole('button', { name: '保存草稿' }).click()
  await expect.poll(() => created.length).toBe(1)
  expect(created[0].requestedDeliveryDate).toBe(defaultDelivery)
  expect(created[0].orderDate).toBe(orderDate)

  // 2) 改成下单日 + 3 天 → 提交按改后的值（"需要更改时可以在页面上改"）
  const changed = shift(orderDate, 3)
  const input = dateInput(orderDrawer(page), '期望交期')
  await input.fill(changed)
  await input.press('Enter')
  await orderDrawer(page).getByRole('button', { name: '保存草稿' }).click()
  await expect.poll(() => updated.length).toBe(1)
  expect(updated[0].requestedDeliveryDate).toBe(changed)
})

test('打开既有订单时不被默认交期污染：空仍是空、有值显示原值', async ({ page }) => {
  const head = { status: 'DRAFT', customerId: 5, customerName: '测试客户', orderDate: '2026-09-20',
    salespersonName: '销售员', createdAt: '2026-09-20T10:00:00' }
  await stubOrderApi(page, [
    { head: { ...head, id: 7, orderNo: 'XSDD00000007', requestedDeliveryDate: null } },
    { head: { ...head, id: 8, orderNo: 'XSDD00000008', requestedDeliveryDate: '2026-12-31' } }
  ])
  await page.goto('/#/sales-orders')
  const row = (no: string) => page.locator('.el-table__row').filter({ hasText: no })

  // 库里交期为空 → 页面保持为空，不能被灌成"下单日 + 7 天"
  await row('XSDD00000007').getByRole('button', { name: '详情' }).click()
  await expect(dateInput(orderDrawer(page), '期望交期')).toHaveValue('')
  await orderDrawer(page).getByRole('button', { name: '关闭此对话框' }).click()
  await expect(orderDrawer(page)).toBeHidden()

  // 库里存了交期 → 显示原值（不是原值 + 7 天）
  await row('XSDD00000008').getByRole('button', { name: '详情' }).click()
  await expect(dateInput(orderDrawer(page), '期望交期')).toHaveValue('2026-12-31')
})
