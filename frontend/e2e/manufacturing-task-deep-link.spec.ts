import { expect, test } from '@playwright/test'

/**
 * 从销售订单明细点「生产任务单编号」→ 跳 `/local-manufacturing-execution?taskId=42` 深链直达。
 *
 * 修复前（2026-09-28 LQ 实测）：详情抽屉纯空白、无报错——
 * 父组件在 setup 阶段就把 visible/orderId 设成终值，子组件的 watch 没有 immediate，
 * 挂载时值已不变 → load() 一次都不跑，detail 恒为 null。
 * 从列表页点「详情」按钮正常（false→true 有变化），所以只有深链路径踩中。
 *
 * 本用例钉住：深链直达时抽屉必须自己完成首次加载并渲染出单号。
 */
test('生产任务单详情支持 ?taskId= 深链直达，抽屉不空白', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('lq.token', 'task-deep-link-test-only'))
  await page.route(
    url => url.pathname.startsWith('/api/'),
    async route => {
      const request = route.request()
      const path = new URL(request.url()).pathname
      const reply = (data: unknown) =>
        route.fulfill({
          json: {
            code: 0,
            message: 'ok',
            timestamp: new Date().toISOString(),
            data
          }
        })
      if (path === '/api/auth/me')
        return reply({
          employeeId: 23,
          name: '计划员',
          roles: [],
          permissions: ['manufacturing.read.all', 'manufacturing.manage'],
          dataScope: 'ALL',
          loginType: 'LOCAL'
        })
      if (path === '/api/manufacturing/orders/42')
        return reply({
          order: {
            id: 42,
            orderNo: 'MO00000042',
            status: 'RELEASED',
            approvalStatus: 'APPROVED',
            productNameSnapshot: '深链测试产品',
            plannedQuantity: 3,
            unit: '台'
          },
          operations: [],
          workReports: [],
          inspections: [],
          issues: [],
          receipts: [],
          approvals: []
        })
      return reply([])
    }
  )

  await page.goto('/#/local-manufacturing-execution?taskId=42')
  const drawer = page.getByRole('dialog', { name: '生产任务单详情' })
  await expect(drawer.getByText('MO00000042')).toBeVisible()
  await expect(drawer.getByText('深链测试产品', { exact: true })).toBeVisible()
})
