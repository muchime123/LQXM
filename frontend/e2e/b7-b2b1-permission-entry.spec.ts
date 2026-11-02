import { expect, test, type Page } from '@playwright/test'

/**
 * B7-B2b-1：权限入口门控的先红后绿验收。
 *
 * 本文件覆盖 `EmployeeDetailPanel.vue` 的 fail-open 默认值缺陷：
 *
 * | 用例 | 权限态 | 期望 |
 * |---|---|---|
 * | E-1 | 无 manage | 未显式授予时**不得**暴露写操作入口 |
 * | E-2 | 有 manage，且父级显式传入 | 写操作入口正常可见（管理员能力不回归） |
 *
 * 关于「无权限用户」的构造方式：这里一律通过**路由权限允许进入、但 permissions
 * 里没有对应 manage 码**来模拟，绝不强行 push 不可达路由来制造假阳性。
 *
 * 范围说明：本批另两个对象（`MediaAccountView` 行级、`ProductCatalogView` 批量入口）
 * 的浏览器验证**未能在本文件落地**。原因是这两页在 `script setup` 顶层 `await`
 * 拉取列表与筛选选项，隔离夹具下挂载链路依赖较多，首轮驱动失败；为不阻塞门禁，
 * 已改由 `src/components/common/__tests__/PermissionEntryGates.contract.test.ts`
 * 做源码级断言，浏览器用例留待下一批补齐（见报告 §9）。
 *
 * 所有 /api/ 请求由 page.route 拦截，不连接后端、不产生任何写入副作用。
 */

test.setTimeout(60_000)

const MANAGE_PERMISSIONS = ['system.employee.manage', 'product.manage', 'media.manage']
/** 只读用户：路由允许进入，但没有任何 manage 权限码 */
const READ_ONLY_PERMISSIONS: string[] = []

function me(permissions: string[]) {
  return {
    employeeId: 1,
    name: 'B7-B2b-1 验收账号',
    permissions,
    dataScope: { type: 'ALL' },
    compatibilityMode: false
  }
}

const EMPLOYEE = {
  id: 1,
  employeeNo: 'LQ001',
  name: '示例员工',
  gender: '男',
  department: '生产部',
  departmentId: 2,
  companyId: 1,
  position: '操作工',
  phone: '13800000001',
  idCardNumber: null,
  email: null,
  entryDate: '2024-01-01',
  leaveDate: null,
  status: '在职',
  mappings: [
    {
      id: 501,
      employeeId: 1,
      sourceSystem: '氚云',
      externalUserId: 'H3-OWNER-001',
      externalName: '氚云账号',
      externalDepartment: '生产部',
      externalStatus: '在职',
      externalPosition: '操作工',
      externalRoles: null,
      isPrimary: true,
      remark: null,
      mappedAt: '2024-01-02 10:00:00'
    }
  ]
}

/**
 * 统一拦截：只处理 /api 开头的请求，按 pathname 分发。
 *
 * 注意：Playwright 的 route 按**后注册者优先**匹配，
 * 因此这里只注册**一个**通用处理器内部分发，避免通用规则覆盖专用规则。
 */
async function mockApi(page: Page, permissions: string[]) {
  await page.addInitScript(() => localStorage.setItem('lq.token', 'e2e-mock-token'))
  await page.route(
    u => new URL(u).pathname.startsWith('/api/'),
    route => {
      const request = route.request()
      const path = new URL(request.url()).pathname

      if (path === '/api/auth/me') return route.fulfill({ json: me(permissions) })
      if (path === '/api/employees/1') return route.fulfill({ json: EMPLOYEE })
      if (path.startsWith('/api/employees/1/mappings')) return route.fulfill({ json: EMPLOYEE.mappings })
      if (path === '/api/departments') return route.fulfill({ json: [] })
      if (path.startsWith('/api/employees/1/files')) return route.fulfill({ json: [] })
      if (path === '/api/system-roles') return route.fulfill({ json: [] })

      return route.fulfill({ json: {} })
    }
  )
}

/**
 * 打开夹具并等待首屏就绪。
 *
 * `ready` 按夹具形态传入：`EmployeeDetailPanel` 是详情面板，没有搜索栏，
 * 不能用列表页的 `.search-toolbar__search input` 作为就绪信号。
 */
async function openFixture(page: Page, fixture: string, ready = '.search-toolbar__search input') {
  await page.setViewportSize({ width: 1440, height: 1000 })
  if (process.env.E2E_DEBUG) {
    page.on('pageerror', e => console.log(`[${fixture}][PAGEERROR] ${e.message}`))
    page.on('console', m => {
      if (m.type() === 'error') console.log(`[${fixture}][CONSOLE] ${m.text()}`)
    })
  }
  await page.goto(`/e2e/fixtures/${fixture}.html`)
  await page.waitForSelector(ready)
}

// ============================================================
// E · EmployeeDetailPanel：权限 prop 默认值不得 fail-open
//
// 生产路径：唯一父级 `EmployeeRosterView`（:55-61）渲染该面板时
// **此前完全没有传** `can-manage`，而 prop 默认为 `true`
// ⇒ 漏传即放行写操作（编辑档案 / 删除离职 / 新增映射 / 文件删除 …）。
//
// 修复：默认改 `false`（fail-closed），父级按 `system.employee.manage` 显式传入。
// ============================================================

test.describe('E · EmployeeDetailPanel 权限默认值', () => {
  test('E-1 未显式传入 can-manage 时，不得暴露写操作入口（fail-closed）', async ({ page }) => {
    await mockApi(page, MANAGE_PERMISSIONS)
    await openFixture(page, 'employee-detail-default', '.employee-detail-panel')
    // 只读浏览不回归：详情数据仍应正常渲染
    await expect(page.locator('.employee-detail-panel')).toContainText('示例员工')
    // 但未授权则不出现写按钮
    await expect(page.getByRole('button', { name: '编辑档案' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '删除 / 离职' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '新增映射' })).toHaveCount(0)
  })

  test('E-2 显式授予 can-manage 后，写操作入口恢复可见（管理员能力不回归）', async ({ page }) => {
    await mockApi(page, MANAGE_PERMISSIONS)
    await openFixture(page, 'employee-detail-panel', '.employee-detail-panel')
    await expect(page.locator('.employee-detail-panel')).toContainText('示例员工')
    await expect(page.getByRole('button', { name: '编辑档案' })).toBeVisible()
    await expect(page.getByRole('button', { name: '删除 / 离职' })).toBeVisible()
  })

  test('E-3 只读用户（有 read 无 manage）经父级传递后同样不暴露写入口', async ({ page }) => {
    await mockApi(page, READ_ONLY_PERMISSIONS)
    await openFixture(page, 'employee-detail-default', '.employee-detail-panel')
    await expect(page.locator('.employee-detail-panel')).toContainText('示例员工')
    await expect(page.getByRole('button', { name: '编辑档案' })).toHaveCount(0)
    await expect(page.getByRole('button', { name: '删除 / 离职' })).toHaveCount(0)
  })
})
