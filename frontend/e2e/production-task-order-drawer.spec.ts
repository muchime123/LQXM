import { expect, test, type Locator, type Page } from '@playwright/test'

/**
 * 「生产任务单」抽屉验证（车间执行页 → 右上角「新建生产任务单」）。
 *
 * 钉死六件事：
 * 1. **形态**：下单是抽屉（role=dialog），不是独立侧边栏页面；旧路径只做跳转。
 * 2. **制程先行**（09-23 LQ 定）：先选制程，再定产线 —— 制程唯一对应产线时产线自动带出并锁定；
 *    通用制程（无需报工）产线任选。提交体必须显式带 `processTemplateId`。
 * 3. **产线联动**：定了产线后，该产线的「产品名称 / 可选配置」才可选（未定产线时两者禁用）。
 * 4. **契约**：产品名称即后端的 `processCategory`；产品配置随单落库；
 *    写动作必须带 `X-Request-Key`（决策 #12「无键不直通」）。
 * 5. **不带 SKU**（09-22 拍板）：抽屉里不存在「可售产品（SKU）」这一项，
 *    提交体也不含 `salesProductId` —— SKU 是完工入库的库存身份键，不是下单字段。
 * 6. **免报工当场提示**（09-23 拍板）：选了 0 道工序的制程（无需报工）
 *    要**当场提示**「本单不展开工序」，而不是让用户提交后吃 400。
 *
 * 全 mock，确定性；不写任何真库数据。
 */

const WORKSHOP = { id: 7, workshopName: '封口机车间', defaultProcessTemplateId: 8 }
const WORKSHOP_NO_PROCESS = { id: 1, workshopName: '称重机车间', defaultProcessTemplateId: null }
/** 制程下拉候选（与 V333 后的真库一致：4 条在用里的代表性 2 条）。 */
const TEMPLATE_SEALER = { id: 8, templateCode: 'H3_WORKSHOP_SEALER', templateName: '封口机车间', workshopType: '封口机车间', operationCount: 1 }
const TEMPLATE_NO_REPORT = { id: 12, templateCode: 'H3_NO_REPORT', templateName: '无需报工', workshopType: null, operationCount: 0 }
const TEMPLATES = [TEMPLATE_SEALER, TEMPLATE_NO_REPORT]

/**
 * mock 侧采集到的调用证据（供用例断言"请求真的发了、带了什么参数"）。
 *
 * ⚠️ `configWorkshopIds` 必须**记录下来**而不是在路由里直接断言等于某一条产线 id：
 * 「无制程产线」用例选的是另一条产线，写死 `toBe(String(WORKSHOP.id))` 会让路由抛错、
 * 请求静默失败，用例变成查一个跟被测逻辑无关的假问题（2026-09-23 自检发现）。
 */
interface Probes {
  productOptionCalls: number
  configWorkshopIds: string[]
}

async function fixtures(
  page: Page,
  created: Array<{ key?: string; body: Record<string, any> }>,
  probes: Probes,
  workshops: Array<Record<string, unknown>> = [WORKSHOP],
  templates: Array<Record<string, unknown>> = TEMPLATES
) {
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
    if (path === '/api/manufacturing/workshop-options') return reply(workshops)
    if (path === '/api/manufacturing/process-templates') return reply(templates)
    // 产线联动的唯一数据源：与后端制程解析读同一张 manufacturing_task_config_items
    if (path === '/api/manufacturing/task-config-options') {
      const workshopId = new URL(request.url()).searchParams.get('workshopId')
      expect(workshopId, '产品名称/可选配置必须按 workshopId 拉取').toBeTruthy()
      probes.configWorkshopIds.push(workshopId as string)
      return reply({ productNames: ['气动片膜'], optionalConfigs: ['加长嘴', '双出'] })
    }
    // 09-22 起抽屉不应再拉 SKU 候选 —— 这里计数，由用例断言恒为 0（防「悄悄加回来」）
    if (path === '/api/manufacturing/product-options') {
      probes.productOptionCalls += 1
      return reply([])
    }
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
 * `keyword` 用于远程搜索型下拉（客户）：必须先真的敲字触发 remote-method，
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

test('生产任务单是抽屉：制程先行带出产线，提交带显式制程与产品配置', async ({ page }) => {
  const created: Array<{ key?: string; body: Record<string, any> }> = []
  const probes: Probes = { productOptionCalls: 0, configWorkshopIds: [] }
  await fixtures(page, created, probes)
  await page.goto('/#/manufacturing-execution')

  await expect(page.getByRole('heading', { level: 1, name: '车间执行' })).toBeVisible()
  // 抽屉在点击之前不存在 —— 它确实是「按钮调出来的」，不是页面常驻区块
  await expect(page.getByRole('dialog', { name: '生产任务单' })).toHaveCount(0)

  await page.getByRole('button', { name: '新建生产任务单' }).click()
  const drawer = page.getByRole('dialog', { name: '生产任务单' })
  await expect(drawer).toBeVisible()

  // ---- 09-22：抽屉里不存在「可售产品（SKU）」这一项 ----
  await expect(drawer.getByText('可售产品', { exact: false })).toHaveCount(0)

  // ---- 联动纪律：未定产线时，产品名称与可选配置必须禁用 ----
  const productNameSelect = drawer.getByRole('combobox', { name: /产品名称/ })
  const configSelect = drawer.getByRole('combobox', { name: /可选配置/ })
  await expect(productNameSelect).toBeDisabled()
  await expect(configSelect).toBeDisabled()

  // ---- 制程先行（09-23）：先选制程，产线自动带出并锁定 ----
  await pickSelect(page, drawer, '制程选择', TEMPLATE_SEALER.templateName)
  const workshopSelect = drawer.getByRole('combobox', { name: /产线选择/ })
  await expect(workshopSelect).toBeDisabled()
  // ⚠️ **判"选中了什么"只能断言渲染文案，不能断言 `.el-select__input` 的 value**：
  // EP 2.x 里非 filterable 的 el-select 把选中项渲染进 `<span class="el-select__selected-item">`，
  // 内层 input 的值**恒为空字符串** → `toHaveValue('封口机车间')` 必然假失败（09-23 实测：
  // 制程先行改版去掉了产线下拉的 `filterable`，同一处断言立刻由通过变失败，看着像"产线没带出来"）。
  await expect(item(drawer, '产线选择')).toContainText(TEMPLATE_SEALER.workshopType!)

  // 封口机车间有制程 → 不得出现免报工提示
  await expect(drawer.getByText('不展开任何工序')).toHaveCount(0)
  // 产线定下后产品名称/可选配置解禁（且按该产线拉取，见 configWorkshopIds 断言）
  await expect(productNameSelect).toBeEnabled()
  await expect(configSelect).toBeEnabled()

  // ---- 填单 ----
  const dueDate = item(drawer, '要求生产完成日期').locator('input')
  await dueDate.fill('2026-09-30')
  await dueDate.press('Enter')

  await pickSelect(page, drawer, '客户选择', '测试客户', '测试')
  await item(drawer, '需求数量').locator('input').fill('12')

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
  // 产品名称 → processCategory：后端按它建单，缺了就建不出工序
  expect(body.processCategory).toBe('气动片膜')
  // 制程先行（09-23）：显式制程必须随单提交，产线由制程带出
  expect(body.processTemplateId).toBe(TEMPLATE_SEALER.id)
  expect(body.workshopId).toBe(WORKSHOP.id)
  // 09-22：提交体里不能再有 salesProductId（SKU 已退出建单），也不是前端硬塞单位
  expect(body).not.toHaveProperty('salesProductId')
  expect(body).not.toHaveProperty('unit')
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

  // 防「悄悄加回来」：整个下单过程一次都没拉过 SKU 候选
  expect(probes.productOptionCalls, '建单不应再请求 SKU 候选').toBe(0)
  // 产品名称/可选配置确实是按产线自动带出后拉的（不是全量拉回来前端自己筛）
  expect(probes.configWorkshopIds).toContain(String(WORKSHOP.id))

  // 成功后抽屉自行关闭
  await expect(drawer).toBeHidden()
})

test('通用制程（无需报工）：产线任选且当场提示「不展开工序」', async ({ page }) => {
  const created: Array<{ key?: string; body: Record<string, any> }> = []
  const probes: Probes = { productOptionCalls: 0, configWorkshopIds: [] }
  await fixtures(page, created, probes, [WORKSHOP_NO_PROCESS])
  await page.goto('/#/manufacturing-execution')

  await page.getByRole('button', { name: '新建生产任务单' }).click()
  const drawer = page.getByRole('dialog', { name: '生产任务单' })
  await expect(drawer).toBeVisible()

  // 未选制程时不该有任何免报工提示
  await expect(drawer.getByText('不展开任何工序')).toHaveCount(0)

  await pickSelect(page, drawer, '制程选择', TEMPLATE_NO_REPORT.templateName)

  // 通用制程不锁产线
  await expect(drawer.getByRole('combobox', { name: /产线选择/ })).toBeEnabled()
  // 选中的是 0 道工序制程 → 必须当场说清楚这张单不会有工序
  await expect(drawer.getByText(/不展开任何工序/)).toBeVisible()
  await expect(drawer.getByText(/免报工/)).toBeVisible()

  // 定了产线后产品名称照常可选，提交路径不变
  await pickSelect(page, drawer, '产线选择', WORKSHOP_NO_PROCESS.workshopName)
  await expect(drawer.getByRole('combobox', { name: /产品名称/ })).toBeEnabled()
  expect(created.length, '仅选制程/产线不应触发建单').toBe(0)
})

test('下单没有独立侧边栏页面：旧路径跳转到车间执行，入口只有那个按钮', async ({ page }) => {
  const created: Array<{ key?: string; body: Record<string, any> }> = []
  const probes: Probes = { productOptionCalls: 0, configWorkshopIds: [] }
  await fixtures(page, created, probes)

  await page.goto('/#/production-task-orders')
  await expect(page).toHaveURL(/#\/manufacturing-execution/)
  await expect(page.getByRole('heading', { level: 1, name: '车间执行' })).toBeVisible()
  // 下单入口收敛成一个按钮；页面上不存在「生产任务单」的独立导航项
  await expect(page.getByRole('button', { name: '新建生产任务单' })).toBeVisible()
  await expect(page.getByRole('link', { name: '生产任务单', exact: true })).toHaveCount(0)
})
