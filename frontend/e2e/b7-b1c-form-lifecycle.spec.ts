import { expect, test, type Page, type Route } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'

/**
 * B7-B1c：表单生命周期与异步回填回归。
 *
 * 契约（本阶段要钉死的行为）：
 *   1. **异步回填不得覆盖更新鲜的用户输入**；
 *   2. **旧请求（已关闭 / 已切换到其它对象 / 组件已卸载）的响应必须被丢弃**；
 *   3. **弹窗再次打开时必须清除上一次的校验错误态**；
 *   4. **提交载荷只能包含明确的业务字段**，不得携带 id / 时间戳 / 派生统计等只读属性；
 *   5. 一次打开只应触发一次详情请求（不重复）。
 *
 * 为什么必须在**请求侧 / 竞态窗口内**断言：
 *   - 「回填覆盖输入」只在响应返回那一刻发生，等断言时界面已稳定，
 *     必须在响应落地前后各取一次快照才能区分；
 *   - 「校验态残留」不体现在任何 input 值上，只能断言 `.el-form-item__error` 的数量。
 *
 * 三个夹具均直接挂载真实业务组件（只替换父级 + 注入受控 props），
 * 全部 /api 请求由 page.route 拦截返回固定夹具数据，
 * 不连接后端、不产生任何写入副作用。
 */

// 夹具挂载真实组件 + Element Plus；延迟 mock 使单用例耗时较长，放宽超时
test.setTimeout(120_000)

const OUT = resolve(process.cwd(), 'playwright-report', 'b7-b1c')

const ME = {
  employeeId: 1,
  name: 'E2E 验收账号',
  permissions: ['employee.manage', 'packaging.manage', 'packagingMold.manage'],
  dataScope: { type: 'ALL' },
  compatibilityMode: false
}

interface ApiCall {
  method: string
  path: string
  body: string | null
}

type Dispatcher = (call: ApiCall, route: Route) => Promise<void>

/** 只拦后端接口（路径以 /api 开头）——更宽的写法会把 Vite 的 /src/api/*.ts 模块请求一起拦掉。 */
async function mockApi(page: Page, dispatch: Dispatcher): Promise<ApiCall[]> {
  const calls: ApiCall[] = []
  await page.route(
    url => new URL(url).pathname.startsWith('/api/'),
    async route => {
      const call: ApiCall = {
        method: route.request().method(),
        path: new URL(route.request().url()).pathname,
        body: route.request().postData()
      }
      calls.push(call)
      await dispatch(call, route)
    }
  )
  return calls
}

/** 注册在后，优先级高于上面的兜底拦截器 */
async function mockMe(page: Page) {
  await page.addInitScript(() => localStorage.setItem('lq.token', 'e2e-mock-token'))
  await page.route(url => new URL(url).pathname === '/api/auth/me', route => route.fulfill({ json: ME }))
}

async function gotoHarness(page: Page, fixture: string, selector: string) {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(`/e2e/fixtures/${fixture}.html`)
  await expect(page.locator(selector).first()).toBeVisible({ timeout: 30_000 })
}

async function shotSafe(page: Page, name: string) {
  mkdirSync(OUT, { recursive: true })
  await page.screenshot({ path: resolve(OUT, `${name}.png`), fullPage: true }).catch(() => undefined)
}

function emptyPage(content: unknown[] = [], total = content.length) {
  return { content, totalElements: total, totalPages: Math.max(1, Math.ceil(total / 20)), number: 0, size: 20 }
}

function parseBody(body: string | null): Record<string, unknown> {
  return body ? (JSON.parse(body) as Record<string, unknown>) : {}
}

// ============================================================
// C1 · MoldFormDialog（components/mold/MoldFormDialog.vue）
//
// 缺陷面：
//   - `onMounted`(807) 与 `watch(visible)`(793) 都会调 `loadForEdit()`；
//   - `loadForEdit`(628) 两次 await 之后**整对象** `Object.assign(form, …)`(635)，
//     既无请求身份校验，也无「用户是否已动过」的判断；
//   - 结束后无条件 `dirty.value = false`(686) —— 异步初始化会抹掉更晚的用户编辑；
//   - `LqDialog`(2) 未传 `destroy-on-close`，全组件无 `clearValidate`。
// ============================================================

test.describe('C1 · MoldFormDialog 异步回填与校验态', () => {
  const MOLD_A = {
    id: 101,
    modelId: 7,
    groupId: 3,
    moldCode: 'M20910-3F',
    baseMoldCode: 'M20910',
    moldTypeCode: 'F',
    cavityCount: 3,
    moldRole: 'main',
    moldCategory: '成型模',
    compartmentCount: '1',
    surfaceStructure: '',
    bottomEngraving: '',
    engravingType: 'generic',
    rawAccessoriesText: '',
    rawCaliber: '',
    rawHeight: '',
    status: 'in_use',
    websiteEnabled: false,
    websiteSortOrder: 0,
    remark: 'A 备注'
  }

  const MOLD_B = { ...MOLD_A, id: 102, moldCode: 'P23128-6W', baseMoldCode: 'P23128', remark: 'B 备注' }

  /** 详情接口延迟可控：key 为 moldId */
  async function installMoldMocks(page: Page, delays: Record<number, number> = {}) {
    return mockApi(page, async (call, route) => {
      if (call.path === '/api/packaging/models' && call.method === 'GET') {
        return route.fulfill({ json: emptyPage([{ id: 7, name: '示例系列' }], 1) })
      }
      if (call.path === '/api/packaging-mold-groups' && call.method === 'GET') {
        return route.fulfill({ json: [{ id: 3, name: '1414方盒' }] })
      }
      const m = /^\/api\/packaging-molds\/(\d+)$/.exec(call.path)
      if (call.method === 'GET' && m) {
        const id = Number(m[1])
        await delay(delays[id] ?? 0)
        return route.fulfill({ json: { mold: id === 101 ? MOLD_A : MOLD_B, accessories: [] } })
      }
      if (call.method === 'POST' || call.method === 'PUT') {
        return route.fulfill({ json: { id: 201 } })
      }
      return route.fulfill({ json: {} })
    })
  }

  test('① 详情回填未返回时输入的模号不被旧响应覆盖', async ({ page }) => {
    await installMoldMocks(page, { 101: 1200 })
    await mockMe(page)
    await gotoHarness(page, 'mold-form-dialog', '.mold-form')

    // 弹窗已渲染，但详情还在路上 —— 此刻表单是默认值，用户开始输入
    const codeInput = page.locator('input[placeholder*="M20910-3F"]')
    await expect(codeInput).toBeVisible()
    await codeInput.fill('E2E-TYPED-CODE')

    // 等旧响应落地，并再留余量
    await delay(1200 + 800)

    await expect(codeInput).toHaveValue('E2E-TYPED-CODE')
    // 用户没动过的字段仍应正常回填 —— 证明不是整段跳过回填
    await expect(page.locator('.mold-form textarea[maxlength="1000"]')).toHaveValue('A 备注')
  })

  test('② 切换到另一个模具时，旧请求的响应不得覆盖新模具的回填', async ({ page }) => {
    await installMoldMocks(page, { 101: 1800, 102: 100 })
    await mockMe(page)
    await gotoHarness(page, 'mold-form-dialog', '.mold-form')

    const codeInput = page.locator('input[placeholder*="M20910-3F"]')
    await expect(codeInput).toBeVisible()

    // 关闭（A 的请求仍在飞行）→ 切到 B → 重新打开
    await page.evaluate(() => {
      const s = (window as unknown as { __moldDialog: { visible: boolean } }).__moldDialog
      s.visible = false
    })
    await page.evaluate(() => {
      const s = (window as unknown as { __moldDialog: { visible: boolean; moldId: number | null } }).__moldDialog
      s.moldId = 102
      s.visible = true
    })

    // 等到 A 的慢响应也已落地（1.8s + 余量）
    await delay(1800 + 800)

    await expect(codeInput).toHaveValue('P23128-6W')
    await expect(page.locator('.mold-form textarea[maxlength="1000"]')).toHaveValue('B 备注')
  })

  test('③ 关闭后返回的旧响应不得回填表单', async ({ page }) => {
    await installMoldMocks(page, { 101: 900 })
    await mockMe(page)
    await gotoHarness(page, 'mold-form-dialog', '.mold-form')

    const codeInput = page.locator('input[placeholder*="M20910-3F"]')
    await expect(codeInput).toBeVisible()

    // 关闭弹窗：此时详情请求仍在飞行
    await page.evaluate(() => {
      const s = (window as unknown as { __moldDialog: { visible: boolean } }).__moldDialog
      s.visible = false
    })

    // 等旧响应落地并留余量
    await delay(900 + 800)

    // 表单仍应是默认值 —— 已关闭的会话不接受回填
    await expect(codeInput).toHaveValue('')
    await expect(page.locator('.mold-form textarea[maxlength="1000"]')).toHaveValue('')
  })

  test('④ 一次打开只触发一次详情请求（watch 与 onMounted 不重复）', async ({ page }) => {
    const calls = await installMoldMocks(page, { 101: 150 })
    await mockMe(page)
    await gotoHarness(page, 'mold-form-dialog', '.mold-form')

    await expect(page.locator('input[placeholder*="M20910-3F"]')).toHaveValue('M20910-3F')
    await delay(900)

    expect(calls.filter(c => c.path === '/api/packaging-molds/101').length).toBe(1)
  })

  test('⑤ 校验失败后关闭重开，旧错误提示不残留', async ({ page }) => {
    await installMoldMocks(page, { 101: 100 })
    await mockMe(page)
    await gotoHarness(page, 'mold-form-dialog', '.mold-form')

    await expect(page.locator('input[placeholder*="M20910-3F"]')).toHaveValue('M20910-3F')

    // 清空必填「模号」→ 提交触发校验失败
    const codeInput = page.locator('input[placeholder*="M20910-3F"]')
    await codeInput.fill('')
    await codeInput.blur()
    await page.locator('.lq-dialog__foot button.primary').click()
    await expect(page.locator('.el-form-item__error').first()).toBeVisible()

    // 关闭 → 重新打开：不应残留上一次的红字
    await page.evaluate(() => {
      const s = (window as unknown as { __moldDialog: { visible: boolean } }).__moldDialog
      s.visible = false
    })
    await delay(600)
    await page.evaluate(() => {
      const s = (window as unknown as { __moldDialog: { visible: boolean } }).__moldDialog
      s.visible = true
    })
    await delay(900)

    expect(await page.locator('.el-form-item__error').count()).toBe(0)
  })
})

// ============================================================
// C2 · PackagingEditorModal（components/packaging/PackagingEditorModal.vue）
//
// 缺陷面：
//   - `onMounted`(324) 内 `await fetchPackagingModel(props.modelId)` 后
//     **整对象** `Object.assign(form, detail.model)`(327)，无任何守卫；
//   - `cleanPayload()`(289) 直接展开整个 `form`，会把详情带回的
//     `id` / `variantCount` / `assetCount` / `defaultImage*` 一并提交。
// ============================================================

test.describe('C2 · PackagingEditorModal 异步回填与载荷', () => {
  const MODEL_A = {
    id: 201,
    name: '示例包材 A',
    nameEn: 'Sample A',
    modelCode: 'LQ-PKG-A',
    packagingKind: 'box',
    filmForm: '',
    shapeType: 'square',
    material: 'PP',
    colorStyle: '',
    capacityMl: '500',
    applicable: '通用',
    moq: '1000',
    unitPrice: '1.20',
    leadTime: '7-10 天',
    customizable: false,
    status: '在售',
    tags: '耐高温',
    defaultImage: 'a.jpg',
    defaultImageAssetId: 11,
    advantages: '优势 A',
    remark: 'A 备注',
    sortOrder: 0,
    websiteEnabled: false,
    websiteSortOrder: 0,
    variantCount: 2,
    assetCount: 3
  }

  const MODEL_B = { ...MODEL_A, id: 202, name: '示例包材 B', remark: 'B 备注' }

  async function installPackagingMocks(page: Page, delays: Record<number, number> = {}) {
    return mockApi(page, async (call, route) => {
      const m = /^\/api\/packaging\/models\/(\d+)$/.exec(call.path)
      if (call.method === 'GET' && m) {
        const id = Number(m[1])
        await delay(delays[id] ?? 0)
        return route.fulfill({
          json: {
            model: id === 201 ? MODEL_A : MODEL_B,
            variants: [],
            assets: [],
            variantCount: 0,
            assetCount: 0
          }
        })
      }
      if (call.method === 'POST' || call.method === 'PUT') {
        return route.fulfill({ json: { id: 201 } })
      }
      return route.fulfill({ json: {} })
    })
  }

  test('① 详情请求未返回时输入的名称不被响应覆盖', async ({ page }) => {
    await installPackagingMocks(page, { 201: 1200 })
    await mockMe(page)
    await gotoHarness(page, 'packaging-editor-modal', '.packaging-editor')

    const nameInput = page.locator('input[placeholder*="500ml PP盒"]')
    await expect(nameInput).toBeVisible()
    await nameInput.fill('E2E-PKG-NAME')

    await delay(1200 + 800)

    await expect(nameInput).toHaveValue('E2E-PKG-NAME')
  })

  test('② 保存载荷不含 id / 时间戳 / 派生统计等非业务字段', async ({ page }) => {
    const calls = await installPackagingMocks(page, { 201: 60 })
    await mockMe(page)
    await gotoHarness(page, 'packaging-editor-modal', '.packaging-editor')

    // 等详情回填完成
    await expect(page.locator('input[placeholder*="500ml PP盒"]')).toHaveValue('示例包材 A')

    await page.locator('.lq-dialog__foot button.primary').click()
    await expect(page.locator('.lq-dialog__foot button.primary')).toBeVisible()
    await delay(600)

    const put = calls.filter(c => c.method === 'PUT' && c.path === '/api/packaging/models/201')
    expect(put.length).toBeGreaterThan(0)

    const keys = Object.keys(parseBody(put[put.length - 1].body)).sort()
    const forbidden = ['id', 'createdAt', 'updatedAt', 'deletedAt', 'variantCount', 'assetCount']
    expect(keys.filter(k => forbidden.includes(k))).toEqual([])
    // 白名单字段必须仍然存在，避免误删合法业务字段
    for (const key of ['name', 'nameEn', 'modelCode', 'packagingKind', 'shapeType', 'material', 'status']) {
      expect(keys).toContain(key)
    }
  })

  test('③ 组件卸载后返回的旧响应不得报错', async ({ page }) => {
    await mockApi(page, async (call, route) => {
      const m = /^\/api\/packaging\/models\/(\d+)$/.exec(call.path)
      if (call.method === 'GET' && m) {
        await delay(200)
        return route.fulfill({ status: 500, json: { message: '模拟详情加载失败' } })
      }
      if (call.method === 'POST' || call.method === 'PUT') return route.fulfill({ json: { id: 201 } })
      return route.fulfill({ json: {} })
    })
    await mockMe(page)
    await gotoHarness(page, 'packaging-editor-modal', '.packaging-editor')

    // 详情接口 500：当前实现 onMounted 内 await 无 catch，
    // 错误被静默吞掉，用户只看到一个空表单且毫无提示。
    await delay(900)

    await expect(page.locator('.el-message--error')).toHaveCount(1)
  })
})

// ============================================================
// C3 · EmployeeDetailPanel（components/employee/EmployeeDetailPanel.vue）
//
// 缺陷面：
//   - `LqDialog`(93) 未传 `destroy-on-close`，内部 `el-form`(101) 带 `:rules`；
//   - `resetMappingForm()`(432) 只重置值，**不清除校验态**；
//   - 父级 `EmployeeRosterView.vue:56` 只在「切换员工」时重建面板，
//     同一员工内反复开关弹窗会复用同一个 `el-form` 实例 → 红字残留。
// ============================================================

test.describe('C3 · EmployeeDetailPanel 校验态', () => {
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
    isSalesperson: true,
    remark: null,
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

  async function installEmployeeMocks(page: Page, delayMs = 0) {
    return mockApi(page, async (call, route) => {
      if (call.path === '/api/employees/1' && call.method === 'GET') {
        await delay(delayMs)
        return route.fulfill({ json: EMPLOYEE })
      }
      if (call.path.startsWith('/api/employees/1/mappings') && call.method === 'GET') {
        return route.fulfill({ json: EMPLOYEE.mappings })
      }
      if (call.path === '/api/departments' && call.method === 'GET') {
        return route.fulfill({ json: [] })
      }
      if (call.path === '/api/access-control/roles' && call.method === 'GET') {
        return route.fulfill({ json: [] })
      }
      if (call.path === '/api/access-control/employees/1/roles' && call.method === 'GET') {
        return route.fulfill({ json: { employee: {}, roleCodes: [] } })
      }
      if (call.path.includes('/file-assets') && call.method === 'GET') {
        return route.fulfill({ json: [] })
      }
      if (call.method === 'POST' || call.method === 'PUT') {
        return route.fulfill({ json: { id: 1 } })
      }
      return route.fulfill({ json: {} })
    })
  }

  test('① 校验失败后关闭重开，旧错误提示消失', async ({ page }) => {
    await installEmployeeMocks(page)
    await mockMe(page)
    await gotoHarness(page, 'employee-detail-panel', '.employee-detail-panel')

    // 切到「身份映射」→ 新增 → 不填必填项直接保存 → 触发校验失败
    await page.locator('.drawer-tabs button', { hasText: '身份映射' }).first().click()
    await page.getByRole('button', { name: '新增映射' }).click()
    await expect(page.locator('.lq-dialog__foot button.primary')).toBeVisible()
    await page.locator('.lq-dialog__foot button.primary').click()
    await expect(page.locator('.el-form-item__error').first()).toBeVisible()
    const before = await page.locator('.el-form-item__error').count()
    expect(before).toBeGreaterThan(0)

    // 关闭 → 重新打开：红字必须清掉
    await page.locator('.lq-dialog__foot button:not(.primary)').first().click()
    await delay(700)
    await page.getByRole('button', { name: '新增映射' }).click()
    await delay(600)

    expect(await page.locator('.el-form-item__error').count()).toBe(0)
  })

  test('② 新增表单为默认值，编辑表单取当前行值', async ({ page }) => {
    await installEmployeeMocks(page)
    await mockMe(page)
    await gotoHarness(page, 'employee-detail-panel', '.employee-detail-panel')

    await page.locator('.drawer-tabs button', { hasText: '身份映射' }).first().click()

    // 新增：外部用户 ID 为空
    await page.getByRole('button', { name: '新增映射' }).click()
    await delay(300)
    await expect(page.locator('input[placeholder*="OwnerId"]')).toHaveValue('')
    await page.locator('.lq-dialog__foot button:not(.primary)').first().click()
    await delay(600)

    // 编辑：应取当前行值
    await page.getByRole('button', { name: '编辑', exact: true }).first().click()
    await delay(400)
    await expect(page.locator('input[placeholder*="OwnerId"]')).toHaveValue('H3-OWNER-001')
  })

  test('③ 清除校验不得错误清空合法输入（编辑值仍保留）', async ({ page }) => {
    await installEmployeeMocks(page)
    await mockMe(page)
    await gotoHarness(page, 'employee-detail-panel', '.employee-detail-panel')

    await page.locator('.drawer-tabs button', { hasText: '身份映射' }).first().click()

    // 打开编辑 → 改成别的值 → 关闭 → 再打开编辑：应回到行值（未被清空）
    await page.getByRole('button', { name: '编辑', exact: true }).first().click()
    await delay(300)
    await page.locator('input[placeholder*="OwnerId"]').fill('TEMP-EDITED')
    await page.locator('.lq-dialog__foot button:not(.primary)').first().click()
    await delay(700)
    await page.getByRole('button', { name: '编辑', exact: true }).first().click()
    await delay(400)

    await expect(page.locator('input[placeholder*="OwnerId"]')).toHaveValue('H3-OWNER-001')
    expect(await page.locator('.el-form-item__error').count()).toBe(0)
  })
})
