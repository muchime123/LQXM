import { expect, test, type Page } from '@playwright/test'

/**
 * 「生产任务单审批」界面验证（决策 #35）。
 *
 * 后端审批关口已由 `ManufacturingApprovalContractMysqlTest`（隔离 schema）与 8082 真实 HTTP 活体
 * 验证钉死；本文件只管**前端这一层**，钉死四件事：
 *
 * 1. **建单后果与车间口径一致**：草稿 / 待审批**单独一列审批态**，不能被业务状态掩盖
 *    （驳回后 `status` 回到草稿，只看业务状态看不出被驳回过）；
 * 2. **提交与审批是两个独立权限**：只给 `manufacturing.manage` 时只出现「提交审批」，
 *    绝不能出现「审批通过 / 驳回」；只给 `manufacturing.approve` 时反过来；
 * 3. **写动作带幂等键**：提交 / 通过 / 驳回都必须带 `X-Request-Key`（决策 #12「无键不直通」）；
 * 4. **驳回原因必填**：空原因不能提交，且不得发出请求。
 *
 * 全 mock，确定性；不写任何真库数据。
 */

/** 本文件内可变的 mock 状态：每个用例只覆盖自己关心的那几个字段。 */
interface MockState {
  permissions: string[]
  status: string
  approvalStatus: string
  approvals: Array<Record<string, unknown>>
  /** 写动作证据：path + 幂等键 + 请求体 */
  calls: Array<{ path: string; key?: string; body: Record<string, any> }>
}

const ORDER_ID = 16971
const ORDER_NO = 'MO00016971'

const BASE_ORDER: Record<string, unknown> = {
  id: ORDER_ID,
  orderNo: ORDER_NO,
  customerNameSnapshot: '审批验证客户',
  productNameSnapshot: '活体验证产品',
  plannedQuantity: 10,
  completedQuantity: 0,
  unit: '台',
  dueDate: '2026-10-01',
  status: 'DRAFT',
  approvalStatus: 'NONE',
  taskType: 'SAMPLE',
  workshopType: '封口机车间',
  processTemplateNameSnapshot: '封口机车间',
  createdAt: '2026-09-27T10:32:00'
}

const APPROVAL_HISTORY: Array<Record<string, unknown>> = [
  { id: 1, docType: 'MFG_ORDER', docId: ORDER_ID, docNo: ORDER_NO, action: 'SUBMIT', fromStatus: 'DRAFT', toStatus: 'PENDING_APPROVAL', actorEmployeeId: 1, actorName: '于士强', comment: null, createdAt: '2026-09-27T10:32:10' },
  { id: 2, docType: 'MFG_ORDER', docId: ORDER_ID, docNo: ORDER_NO, action: 'REJECT', fromStatus: 'PENDING_APPROVAL', toStatus: 'DRAFT', actorEmployeeId: 23, actorName: '于洋洋', comment: '产线与实际承接车间不符，请确认后重提', createdAt: '2026-09-27T10:32:20' }
]

async function fixtures(page: Page, state: MockState) {
  await page.addInitScript(() => localStorage.setItem('lq.token', 'mfg-approval-test-only'))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const reply = (data: unknown) =>
      route.fulfill({ json: { code: 0, message: 'ok', timestamp: new Date().toISOString(), data } })

    if (path === '/api/auth/me') {
      return reply({
        employeeId: 1, name: '测试建单人', roles: [],
        permissions: state.permissions, dataScope: 'ALL', loginType: 'LOCAL'
      })
    }
    // 任务单总览列表：只回一张单，状态与审批态由用例决定
    if (path === '/api/manufacturing/orders' && request.method() === 'GET') {
      return reply({ items: [{ ...BASE_ORDER, status: state.status, approvalStatus: state.approvalStatus }], total: 1, page: 1, size: 20 })
    }
    // 详情：审批流水一并返回（抽屉里的「审批记录」靠它渲染）
    if (path === `/api/manufacturing/orders/${ORDER_ID}` && request.method() === 'GET') {
      return reply({
        order: { ...BASE_ORDER, status: state.status, approvalStatus: state.approvalStatus },
        outputs: [], receipts: [], lots: [], operations: [], workReports: [], inspections: [], issues: [], reworks: [],
        approvals: state.approvals
      })
    }
    // 写动作：一律记录证据（幂等键 + 请求体），不区分成功/失败
    if (request.method() === 'POST' && /^\/api\/manufacturing\/orders\/\d+\/(submit|approve|reject)$/.test(path)) {
      state.calls.push({ path, key: request.headers()['x-request-key'], body: (request.postDataJSON() ?? {}) as Record<string, any> })
      return reply(true)
    }
    if (path === '/api/manufacturing/execution-board') {
      return reply({ items: [], total: 0, page: 1, size: 20, summary: { total: 0, ready: 0, inProgress: 0, blocked: 0, overdue: 0, rework: 0 }, workshops: [] })
    }
    if (path === '/api/manufacturing/warehouses') return reply([])
    return reply({ items: [], total: 0, page: 1, size: 20 })
  })
}

function state(overrides: Partial<MockState> = {}): MockState {
  return { permissions: ['manufacturing.read.all', 'manufacturing.manage'], status: 'DRAFT', approvalStatus: 'NONE', approvals: [], calls: [], ...overrides }
}

/** 进入「任务单总览」并打开本单详情抽屉。 */
async function openDetail(page: Page) {
  await page.goto('/#/local-manufacturing-execution')
  await page.getByRole('tab', { name: '任务单总览' }).click()
  await page.getByRole('button', { name: ORDER_NO }).click()
  const drawer = page.getByRole('dialog', { name: '生产任务单详情' })
  await expect(drawer).toBeVisible()
  return drawer
}

test('列表把审批态单列一列：草稿单显示「未提交」', async ({ page }) => {
  await fixtures(page, state())
  await page.goto('/#/local-manufacturing-execution')
  await page.getByRole('tab', { name: '任务单总览' }).click()

  const row = page.getByRole('row').filter({ hasText: ORDER_NO })
  await expect(row).toBeVisible()
  // 业务状态与审批态必须各自可见：只有「草稿」看不出有没有被驳回过
  await expect(row.getByText('草稿')).toBeVisible()
  await expect(row.getByText('未提交')).toBeVisible()
})

test('只有提交权（manage）：草稿单出现「提交审批」，绝不出现审批按钮；提交带幂等键', async ({ page }) => {
  const s = state()
  await fixtures(page, s)
  const drawer = await openDetail(page)

  await expect(drawer.getByText('草稿：尚未提交审批，车间看不到这张单。')).toBeVisible()
  await expect(drawer.getByRole('button', { name: '提交审批' })).toBeVisible()
  // 有 manage ≠ 有 approve：这两个按钮在只有提交权的账号上必须一个都不出现
  await expect(drawer.getByRole('button', { name: '审批通过' })).toHaveCount(0)
  await expect(drawer.getByRole('button', { name: '驳回' })).toHaveCount(0)

  await drawer.getByRole('button', { name: '提交审批' }).click()
  // 提交是不可逆动作（审批期间车间看不到、也不能改），必须先确认
  await expect(page.getByText('提交后这张单进入待审批')).toBeVisible()
  await page.getByRole('button', { name: '确定' }).click()

  await expect.poll(() => s.calls.length).toBe(1)
  expect(s.calls[0].path).toBe(`/api/manufacturing/orders/${ORDER_ID}/submit`)
  expect(s.calls[0].key, '提交必须带 X-Request-Key（决策 #12 无键不直通）').toBeTruthy()

  // ⚠️ 草稿态下「审批通过 / 驳回」本来就被**状态**挡住（要求 PENDING_APPROVAL），
  // 光看上面那段证明不了权限判断 —— 必须换到待审批态再看一次：此时这两个按钮若出现，
  // 只可能是因为漏了 `canApprove` 这道**权限**闸门。
  s.status = 'PENDING_APPROVAL'
  s.approvalStatus = 'PENDING'
  // 关闭要精确匹配页脚那个「关闭」——抽屉右上角的 × 无障碍名也是「关闭此对话框」，
  // 不精确匹配会 strict mode 撞两个元素。
  await drawer.getByRole('button', { name: '关闭', exact: true }).click()
  await page.getByRole('button', { name: ORDER_NO }).click()
  await expect(drawer.getByRole('button', { name: '审批通过' })).toHaveCount(0)
  await expect(drawer.getByRole('button', { name: '驳回' })).toHaveCount(0)
  await expect(drawer.getByRole('button', { name: '提交审批' }), '待审批态下不再显示提交按钮').toHaveCount(0)
})

test('只有审批权（approve）：待审批单出现「审批通过 / 驳回」，无提交按钮；驳回原因必填', async ({ page }) => {
  const s = state({ permissions: ['manufacturing.read.all', 'manufacturing.approve'], status: 'PENDING_APPROVAL', approvalStatus: 'PENDING' })
  await fixtures(page, s)
  const drawer = await openDetail(page)

  await expect(drawer.getByText('已提交审批，等待老板审批；审批通过前车间看不到这张单，也不能排产。')).toBeVisible()
  await expect(drawer.getByRole('button', { name: '审批通过' })).toBeVisible()
  await expect(drawer.getByRole('button', { name: '驳回' })).toBeVisible()
  await expect(drawer.getByRole('button', { name: '提交审批' })).toHaveCount(0)

  // 空原因必须被拦下，且**不得发请求**（否则会白跑一趟 400，还留下一次审批动作的痕迹）
  await drawer.getByRole('button', { name: '驳回' }).click()
  await expect(page.getByText('请填写驳回原因，建单人会看到。')).toBeVisible()
  await page.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('驳回原因必填')).toBeVisible()
  expect(s.calls.length, '空原因不得发出驳回请求').toBe(0)

  await page.locator('.el-message-box textarea').fill('产线与实际承接车间不符，请确认后重提')
  await page.getByRole('button', { name: '确定' }).click()
  await expect.poll(() => s.calls.length).toBe(1)
  expect(s.calls[0].path).toBe(`/api/manufacturing/orders/${ORDER_ID}/reject`)
  expect(s.calls[0].key, '驳回必须带 X-Request-Key').toBeTruthy()
  expect(s.calls[0].body.comment).toContain('产线与实际承接车间不符')
})

test('审批记录表渲染历史流水（含驳回原因）', async ({ page }) => {
  const s = state({ status: 'DRAFT', approvalStatus: 'REJECTED', approvals: APPROVAL_HISTORY })
  await fixtures(page, s)
  const drawer = await openDetail(page)

  // 被驳回的单：业务状态是草稿，但提示与标签都要说清「是被驳回」
  await expect(drawer.getByText('上次提交被驳回，请按审批意见修改后重新提交。')).toBeVisible()
  await expect(drawer.getByText('已驳回').first()).toBeVisible()

  const table = drawer.locator('table').filter({ hasText: '产线与实际承接车间不符' })
  await expect(table).toBeVisible()
  await expect(table.getByText('提交审批')).toBeVisible()
  await expect(table.getByText('驳回')).toBeVisible()
  await expect(table.getByText('于士强')).toBeVisible()
  await expect(table.getByText('于洋洋')).toBeVisible()
})
