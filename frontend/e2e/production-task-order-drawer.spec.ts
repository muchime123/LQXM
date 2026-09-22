import { expect, test, type Locator, type Page } from '@playwright/test'

/**
 * 「生产任务单」抽屉验证（车间执行页 → 右上角「新建生产任务单」）。
 *
 * 钉死三件事：
 * 1. **形态**：下单是抽屉（role=dialog），不是独立侧边栏页面；旧路径只做跳转。
 * 2. **联动**：先选产线 → 该产线的「产品名称 / 可选配置」才可选（未选产线时两者禁用）。
 * 3. **契约**：产品名称即后端制程解析的 `processCategory`；产品配置随单落库；
 *    写动作必须带 `X-Request-Key`（决策 #12「无键不直通」）。
 *
 * 全 mock，确定性；不写任何真库数据。
 */

const WORKSHOP = { id: 7, workshopName: '封口机车间' }
const PRODUCT = { id: 101, productCode: 'H3-40438', productName: '气动片膜', unit: '台', specification: '500ml' }

async function fixtures(page: Page, created: Array<{ key?: string; body: Record<string, any> }>) {
  await page.addInitScript(() => localStorage.setItem('lq.token', 'task-order-drawer-test-only'))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const reply = (data: unknown) =>
      route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })
    if (path === '/api/auth/me') {
      return reply({
        employeeId: 23, name: '测试业务员', roles: [],
        permissions: ['manufacturing.manage', 'manufacturing.report', 'manufacturing.receive'],
        dataScope: 'ALL', loginType: 'LOCAL'
      })
    }
    if (path === '/api/manufacturing/workshop-options') return reply([WORKSHOP])
    // 产线联动的唯一数据源：与后端制程解析读同一张 manufacturing_task_config_items
    if (path === '/api/manufacturing/task-config-options') {
      expect(new URL(request.url()).searchParams.get('workshopId')).toBe(String(WORKSHOP.id))
      return reply({ productNames: ['气动片膜'], optionalConfigs: ['加长嘴', '双出'] })
    }
    if (path === '/api/manufacturing/product-options') return reply([PRODUCT])
    if (path === '/api/customers') return reply({ content: [{ id: 5, name: '测试客户' }], total: 1 })
    if (path === '/api/manufacturing/orders' && request.method() === 'POST') {
      created.push({ key: request.headers()['x-request-key'], body: request.postDataJSON() })
      return reply(8801)
    }
    if (path === '/api/manufacturing/execution-board') {
      return reply({
        items: [], total: 0, page: 1, size: 20,
        summary: { total: 0, ready: 0, inProgress: 0, blocked: 0, overdue: 0, rework: 0 },
        workshops: [WORKSHOP.workshopName]
      })
    }
    return reply({ items: [], total: 0, page: 1, size: 20 })
  })
}

/** 按表单项 label 定位，不依赖字段顺序 —— 以后加字段不会把用例带崩。 */
const item = (drawer: Locator, label: string) => drawer.locator('.el-form-item').filter({ hasText: label }).first()

/**
 * 选下拉项。
 *
 * ⚠️ EP 2.14 里 `.el-select` 内的 `<input>` 被占位文案遮住（`is-transparent` 的 span 会拦截点击），
 * 直接点 input 会一直重试到超时 —— 必须点 `.el-select__wrapper`。
 * ⚠️ 更坑的是选项定位：**页面上每个 el-select 的 popper 都常驻 DOM**（未打开的是零尺寸空壳），
 * 实测 38 个 item 里只有 1 个真可见 —— 不加 `:visible` 就会取到空壳，
 * 报「element is not visible」并把责任错推给页面。所以必须 `:visible` + hasText 双条件。
 * `keyword` 用于远程搜索型下拉（客户 / 可售产品）：必须先真的敲字触发 remote-method，
 * 否则下拉里没有候选，断言会假失败。
 */
async function pickSelect(page: Page, drawer: Locator, label: string, option: string, keyword?: string) {
  const select = item(drawer, label).locator('.el-select')
  await select.locator('.el-select__wrapper').click()
  if (keyword) {
    await select.locator('input').focus()
    await page.keyboard.type(keyword)
  }
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: option }).first().click()
}

async function pickRadio(drawer: Locator, label: string, value: string) {
  await item(drawer, label).getByText(value, { exact: true }).click()
}

test('生产任务单是抽屉：产线联动产品名称/可选配置，提交带制程品类与产品配置', async ({ page }) => {
  const created: Array<{ key?: string; body: Record<string, any> }> = []
  await fixtures(page, created)
  await page.goto('/#/manufacturing-execution')

  await expect(page.getByRole('heading', { level: 1, name: '车间执行' })).toBeVisible()
  // 抽屉在点击之前不存在 —— 它确实是「按钮调出来的」，不是页面常驻区块
  await expect(page.getByRole('dialog', { name: '生产任务单' })).toHaveCount(0)

  await page.getByRole('button', { name: '新建生产任务单' }).click()
  const drawer = page.getByRole('dialog', { name: '生产任务单' })
  await expect(drawer).toBeVisible()

  // ---- 联动纪律：未选产线时，产品名称与可选配置必须禁用 ----
  const productNameSelect = drawer.getByRole('combobox', { name: /产品名称/ })
  const configSelect = drawer.getByRole('combobox', { name: /可选配置/ })
  await expect(productNameSelect).toBeDisabled()
  await expect(configSelect).toBeDisabled()

  await pickSelect(page, drawer, '产线选择', WORKSHOP.workshopName)

  // 选完产线后解禁，且选项来自该产线的配置（mock 里断言 workshopId 已随请求带上）
  await expect(productNameSelect).toBeEnabled()
  await expect(configSelect).toBeEnabled()

  // ---- 填单 ----
  const dueDate = item(drawer, '要求生产完成日期').locator('input')
  await dueDate.fill('2026-09-30')
  await dueDate.press('Enter')

  await pickSelect(page, drawer, '客户选择', '测试客户', '测试')
  await item(drawer, '需求数量').locator('input').fill('12')
  await pickSelect(page, drawer, '可售产品', PRODUCT.productCode, 'H3')

  await pickSelect(page, drawer, '产品名称', '气动片膜')
  await item(drawer, '盒子尺寸').locator('input').fill('94×94')
  await item(drawer, '盒子高度').locator('input').fill('60')
  await pickSelect(page, drawer, '模具排列', '1出2')
  await item(drawer, '盒子材质').locator('input').fill('PET')
  await pickRadio(drawer, '盒子形状', '方形')
  await pickRadio(drawer, '电压', '单相220V')
  await pickRadio(drawer, '插头', '国标')
  await pickRadio(drawer, '品牌', '利强品牌')
  await pickSelect(page, drawer, '可选配置', '加长嘴')
  await item(drawer, '配置说明').locator('textarea').fill('客户要求加长出料嘴')

  await drawer.screenshot({ path: 'e2e/__screenshots__/production-task-order-drawer.png' })

  await drawer.getByRole('button', { name: '提交', exact: true }).click()

  await expect.poll(() => created.length).toBe(1)
  const { key, body } = created[0]
  expect(key, '建单必须携带 X-Request-Key').toBeTruthy()
  // 产品名称 → processCategory：后端按它解析制程、展开工序，缺了就建不出工序
  expect(body.processCategory).toBe('气动片膜')
  expect(body.salesProductId).toBe(PRODUCT.id)
  expect(body.workshopId).toBe(WORKSHOP.id)
  expect(body.plannedQuantity).toBe(12)
  expect(body.customerName).toBe('测试客户')
  expect(body.dueDate).toBe('2026-09-30')
  expect(body.productConfig).toMatchObject({
    productName: '气动片膜',
    boxSize: '94×94',
    boxHeight: '60',
    moldArrangement: '1出2',
    boxShape: '方形',
    boxMaterial: 'PET',
    voltage: '单相220V',
    plug: '国标',
    brand: '利强品牌',
    optionalConfigs: ['加长嘴'],
    configNote: '客户要求加长出料嘴',
    extraMold: false
  })

  // 成功后抽屉自行关闭
  await expect(drawer).toBeHidden()
})

test('下单没有独立侧边栏页面：旧路径跳转到车间执行，入口只有那个按钮', async ({ page }) => {
  const created: Array<{ key?: string; body: Record<string, any> }> = []
  await fixtures(page, created)

  await page.goto('/#/production-task-orders')
  await expect(page).toHaveURL(/#\/manufacturing-execution/)
  await expect(page.getByRole('heading', { level: 1, name: '车间执行' })).toBeVisible()
  // 下单入口收敛成一个按钮；页面上不存在「生产任务单」的独立导航项
  await expect(page.getByRole('button', { name: '新建生产任务单' })).toBeVisible()
  await expect(page.getByRole('link', { name: '生产任务单', exact: true })).toHaveCount(0)
})
