import { expect, test, type Page } from '@playwright/test'

async function fixtures(page: Page, writable: boolean) {
  const state = { plannedStartDate: null, expectedFinishDate: '2026-09-25', delayReason: '', completedDate: null, warehousedDate: null, shippedDate: null, remark: '', stages: { 备料: '2026-09-18' } }
  const order = { id: 1, workOrderNo: 'SCGD20260005858', productName: 'LQ750全自动气调机', requiredQuantity: 1, productionLine: '全自动气调机车间', moldLayout: '一出四', boxSize: '94×94', salespersonName: '测试业务员', requiredCompletionDate: '2026-09-20', sourceRequiredCompletionDate: '2026-09-20', version: 0, state, stageNames: ['画图', '备料', '钣金', '模具', '组装', '调试'] }
  const writes: Array<{ key: string | undefined; data: { version: number; state: typeof state; content: string } }> = []
  let fail = true
  const logs: unknown[] = []
  await page.addInitScript(() => localStorage.setItem('lq.token', 'followup-test-only'))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    const reply = (data: unknown) => route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })
    if (path === '/api/auth/me') return reply({ employeeId: 23, name: '测试跟单员', roles: [], permissions: writable ? ['manufacturing.read.all', 'manufacturing.followup'] : ['manufacturing.read.all'], dataScope: 'ALL', loginType: 'LOCAL' })
    if (path === '/api/production-followups/workshops') return reply(['全自动气调机车间'])
    if (path === '/api/production-followups') {
      const query = new URL(route.request().url()).searchParams
      expect(query.get('status')).toBe('all')
      expect(query.get('orderBy')).toBe('newest')
      return reply({ items: [order], total: 1 })
    }
    if (path === '/api/production-followups/1') {
      if (route.request().method() === 'PUT') {
        const data = route.request().postDataJSON()
        writes.push({ key: route.request().headers()['x-request-key'], data })
        if (fail) { fail = false; return route.fulfill({ status: 503, json: { code: 503, message: '测试网络故障', timestamp: new Date().toISOString(), data: null } }) }
        logs.unshift({ id: 1, version: 1, content: data.content, before: order.state, after: data.state, createdByName: '测试跟单员', createdAt: '2026-09-22T10:30:00' })
        order.state = data.state; order.version = 1
        return reply({ version: 1 })
      }
      return reply({ order, logs, logCount: logs.length, sourceComponents: [{ componentName: '来源模具加工', completedQuantity: 1, completionRatio: '100.00%', completed: true }] })
    }
    return reply([])
  })
  return writes
}

test('台账深链打开跟单，失败保留内容且重试复用请求键，成功显示历史', async ({ page }) => {
  const writes = await fixtures(page, true)
  await page.goto('/#/production-followups?sourceId=1')
  const drawer = page.getByRole('dialog', { name: '生产工单跟单' })
  await expect(drawer).toBeVisible()
  await expect(drawer.getByText('来源模具加工')).toBeVisible()
  await drawer.getByPlaceholder('记录当前进度、阻碍事项或修改原因').fill('配件已到，安排明日组装')
  await drawer.getByRole('button', { name: '保存本次跟单' }).click()
  await expect(drawer.getByText(/保存失败：测试网络故障/)).toBeVisible()
  await expect(drawer.getByPlaceholder('记录当前进度、阻碍事项或修改原因')).toHaveValue('配件已到，安排明日组装')
  await drawer.getByRole('button', { name: '保存本次跟单' }).click()
  await expect(drawer.locator('.preserve')).toHaveText('配件已到，安排明日组装')
  expect(writes).toHaveLength(2)
  expect(writes[0].key).toBeTruthy()
  expect(writes[1].key).toBe(writes[0].key)
  expect(writes[1].data).toEqual(writes[0].data)
  await page.screenshot({ path: 'test-results/production-followup.png', fullPage: true })
})

test('只读人员可以查看但没有保存按钮', async ({ page }) => {
  await fixtures(page, false)
  await page.goto('/#/production-followups?sourceId=1')
  const drawer = page.getByRole('dialog', { name: '生产工单跟单' })
  await expect(drawer).toBeVisible()
  await expect(drawer.getByRole('button', { name: '保存本次跟单' })).toHaveCount(0)
  await expect(drawer.locator('input').first()).toBeDisabled()
})
