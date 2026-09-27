import { expect, test, type Page } from '@playwright/test'

/**
 * 「车间执行」页验证（工单 → 报工 → 人工检验并入库）。
 *
 * 两个层次：
 * 1. 页面与写动作契约（**全 mock，确定性**）：三个页签各自加载、空态正确；
 *    每个写动作都带 `X-Request-Key`，且「同一次提交的重试复用同一个键」——
 *    这是决策 #12「无键不直通」在前端的落点，退化成不带键就会静默丢掉幂等保护。
 * 2. 真后端只读冒烟（后端不可达时自动跳过）：确认真实数据形状能渲染，不做任何写入。
 */

const EMPLOYEE_INIT = () => {
  localStorage.setItem('lq.currentEmployeeId', '23')
}

const envelope = (data: unknown) => ({
  json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data }
})

const WORKSHOPS = ['封口机车间']

/** 把制造域接口全部 mock 掉：页面渲染与写动作请求都可确定性断言。 */
async function mockManufacturingApis(page: Page, writes: Array<{ path: string; key?: string }>) {
  // ⚠️ 必须按 pathname 前缀匹配，不能用 `**/api/manufacturing/**`：
  // 源码模块地址 `/src/api/manufacturing/index.ts` 也含 `/api/manufacturing/`，
  // 用 glob 会把页面自己要 import 的模块替成 JSON，页面直接白屏（本用例踩过）。
  await page.route(url => url.pathname.startsWith('/api/manufacturing/'), async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' || request.method() === 'PUT') {
      writes.push({ path, key: request.headers()['x-request-key'] })
      await route.fulfill(envelope(1))
      return
    }
    if (path.endsWith('/execution-board')) {
      await route.fulfill(envelope({
        items: [], total: 0, page: 1, size: 20,
        summary: { total: 0, ready: 0, inProgress: 0, blocked: 0, overdue: 0, rework: 0 },
        workshops: WORKSHOPS
      }))
      return
    }
    if (path.endsWith('/work-reports')) {
      await route.fulfill(envelope({ items: [], total: 0, page: 1, size: 20, workshops: WORKSHOPS }))
      return
    }
    if (path.endsWith('/completion-receipts')) {
      await route.fulfill(envelope({
        items: [], total: 0, page: 1, size: 20,
        warehouses: [{ id: 1, warehouseCode: 'WH', warehouseName: '温州仓' }]
      }))
      return
    }
    if (path.endsWith('/manufacturing/warehouses')) {
      await route.fulfill(envelope([{ id: 1, warehouseCode: 'WH', warehouseName: '温州仓' }]))
      return
    }
    await route.fulfill(envelope({ items: [], total: 0, page: 1, size: 20 }))
  })
}

test('旧车间执行地址进入氚云任务，保留本地任务的独立地址', async ({ page }) => {
  await page.addInitScript(EMPLOYEE_INIT)
  await page.route(url => url.pathname === '/api/h3-production-execution', route =>
    route.fulfill(envelope({ items: [], total: 0 })))
  await page.goto('/#/manufacturing-execution')
  await expect(page).toHaveURL(/#\/h3-production-execution/)
  await expect(page.getByRole('heading', { level: 1, name: '车间执行 · 氚云生产任务' })).toBeVisible()
  await expect(page.getByRole('button', { name: '新建生产任务单' })).toHaveCount(0)
})

test('车间执行页：报工与完工检验分开，且没有独立收货入口', async ({ page }) => {
  const writes: Array<{ path: string; key?: string }> = []
  await mockManufacturingApis(page, writes)
  await page.addInitScript(EMPLOYEE_INIT)
  await page.goto('/#/local-manufacturing-execution')

  await expect(page.getByRole('heading', { level: 1, name: '本地生产任务 · 车间执行' })).toBeVisible()
  for (const tab of ['执行看板', '报工记录', '待完工检验', '完工入库记录']) {
    await expect(page.getByRole('tab', { name: tab })).toBeVisible()
  }
  // 执行看板：统计卡与空态
  await expect(page.getByText('待执行工序')).toBeVisible()
  await expect(page.getByText('没有待执行的工序')).toBeVisible()
  // 决策 #17：车间自治，领料/BOM 不在本页 —— 页面文案会提到「领料」，但绝不能有入口
  await expect(page.getByRole('button', { name: /领料/ })).toHaveCount(0)
  await expect(page.getByRole('tab', { name: /领料/ })).toHaveCount(0)

  await page.getByRole('tab', { name: '报工记录' }).click()
  await expect(page.getByText('没有报工记录')).toBeVisible()

  await page.getByRole('tab', { name: '完工入库记录' }).click()
  await expect(page.getByText('没有完工入库记录')).toBeVisible()
  await expect(page.getByRole('button', { name: '办理收货' })).toHaveCount(0)

  await page.screenshot({ path: 'e2e/__screenshots__/manufacturing-execution-receipts.png', fullPage: true })
})

test('车间执行页：每个写动作都带请求键，同一次提交的重试复用同一个键', async ({ page }) => {
  const writes: Array<{ path: string; key?: string }> = []
  await mockManufacturingApis(page, writes)
  await page.addInitScript(EMPLOYEE_INIT)
  await page.goto('/')

  await page.evaluate(async () => {
    const api = await (0, eval)("import('/src/api/manufacturing/index.ts')")
    const retryKey = api.newRequestKey()
    // 同一次提交：首次 + 重试，必须复用同一个键（后端据此识别为重放，不重复落账）
    await api.submitWorkReport(11, 21, { quantity: 1 }, retryKey)
    await api.submitWorkReport(11, 21, { quantity: 1 }, retryKey)
    // 新的一次提交：换新键
    await api.submitWorkReport(11, 22, { quantity: 1 }, api.newRequestKey())
    await api.reverseWorkReport(31, '报错冲正', api.newRequestKey())
    await api.createManufacturingOrder({ productName: 'x' }, api.newRequestKey())
    await api.generateManufacturingOperations(11, 5, api.newRequestKey())
    await api.submitManufacturingInspection(11, { result: 'FAILED' }, api.newRequestKey())
    await api.submitQualityCompletion(11, { inspection: { result: 'PASSED' }, inventory: { warehouseId: 1 } }, api.newRequestKey())
    await api.reverseCompletionReceipt(41, '反入库', api.newRequestKey())
    // 未显式给键的调用也不能漏带（兜底新生成一个）
    await api.reverseWorkReport(32, '兜底')
  })

  expect(writes.length).toBe(10)
  for (const write of writes) {
    expect(write.key, `${write.path} 必须携带 X-Request-Key`).toBeTruthy()
  }
  const reportKeys = writes.filter(w => w.path.endsWith('/work-reports') || w.path.endsWith('/work-reports/')).map(w => w.key)
  expect(reportKeys[0]).toBe(reportKeys[1])
  // 十个动作里除「重试复用」那一组外，不应出现重复键
  expect(new Set(writes.map(w => w.key)).size).toBe(9)
})

test('车间执行页：对真实后端只读冒烟（后端不可达则跳过）', async ({ page, request }) => {
  const reachable = await request
    .get('http://127.0.0.1:8081/actuator/health')
    .then(r => r.ok())
    .catch(() => false)
  test.skip(!reachable, '本机 8081 后端未启动，跳过真后端冒烟')

  await page.addInitScript(EMPLOYEE_INIT)
  await page.goto('/#/local-manufacturing-execution')
  await expect(page.getByRole('heading', { level: 1, name: '本地生产任务 · 车间执行' })).toBeVisible()
  // 真实数据可能为空：只断言页面没有报错横幅、统计卡渲染出数字
  await expect(page.locator('.stat-card__value').first()).toBeVisible()
  await expect(page.getByText('请求失败')).toHaveCount(0)
  await page.screenshot({ path: 'e2e/__screenshots__/manufacturing-execution-real.png', fullPage: true })
})
