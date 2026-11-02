import { expect, test, type Page } from '@playwright/test'

/**
 * B7-B2a：权限入口与批量选择行为审计。
 *
 * 本阶段**只读审计，不修改生产代码**。这里的用例不用于「通过」，而用于
 * **固定当前真实行为**：把「无权限用户能否看到批量入口 / 行级写按钮」
 * 「跨页选择是否保留」逐条记录成可复现的证据。
 *
 * 夹具直接挂载**真实业务页面**，所有 /api/ 请求由 page.route 拦截返回固定夹具数据，
 * 不连接后端、不产生任何写入副作用。
 *
 * 权限注入：`GET /api/auth/me` 返回指定 permissions 列表。
 * `useCurrentUser` 为模块级单例且 `can()` 读取响应式 ref，
 * 因此身份加载完成后所有权限分支自动重算。
 *
 * 注意：Playwright 的 route 按**后注册者优先**匹配，
 * 因此这里只注册**一个**通用处理器内部分发，避免通用规则覆盖专用规则。
 *
 * 范围说明：批量「删除成功 / 失败后选择变化」两条未纳入本文件。
 * 原因是页面的确认链路走全局 `askConfirm`，
 * 由 `App.vue` 挂载的 `LqConfirmDialog` 负责渲染并靠 props 逐个接收弹窗内容，
 * 隔离夹具下难以稳定驱动；该两条已按源码逐行核对，结论见审计报告 §5。
 */

test.setTimeout(60_000)

const MANAGE_PERMISSIONS = [
  'certificate.read',
  'certificate.manage',
  'customer.read',
  'customer.manage',
  'media.read',
  'media.manage',
  'website.manage'
]

/** 只读用户：只有 read，没有 manage */
const READ_ONLY_PERMISSIONS = ['certificate.read', 'customer.read', 'media.read']

const SIZE = 20
const TOTAL = 137

type User = {
  employeeId: number
  name: string
  permissions: string[]
  dataScope: { type: string }
  compatibilityMode: boolean
}

function me(permissions: string[]): User {
  return {
    employeeId: 1,
    name: 'B7-B2a 审计账号',
    permissions,
    dataScope: { type: 'ALL' },
    compatibilityMode: false
  }
}

function certRow(i: number) {
  return {
    id: i + 1,
    assetStatus: '有效',
    assetCategory: '专利',
    patentType: '发明',
    name: `示例知识产权 ${i + 1}`,
    patentNo: `ZL2026${String(i + 1).padStart(6, '0')}`,
    applicationNo: '202610000000.0',
    applicant: '浙江利强包装',
    applyDate: '2026-01-01',
    expireDate: '2036-01-01'
  }
}

function pageResult(pathname: string, url: string, build: (i: number) => Record<string, unknown>) {
  const p = Number(new URL(url).searchParams.get('page') ?? 0)
  return {
    content: Array.from({ length: SIZE }, (_, i) => build(p * SIZE + i)),
    totalElements: TOTAL,
    totalPages: Math.ceil(TOTAL / SIZE),
    number: p,
    path: pathname
  }
}

/** 统一拦截：只处理 /api 开头的请求，按 pathname 分发。 */
async function mockApi(page: Page, permissions: string[], opts: { failDelete?: boolean } = {}) {
  await page.addInitScript(() => localStorage.setItem('lq.token', 'e2e-mock-token'))
  await page.route(
    u => new URL(u).pathname.startsWith('/api/'),
    route => {
      const request = route.request()
      const url = request.url()
      const path = new URL(url).pathname

      if (path === '/api/auth/me') return route.fulfill({ json: me(permissions) })
      if (opts.failDelete && request.method() === 'DELETE') {
        return route.fulfill({ status: 500, json: { message: 'E2E 模拟删除失败' } })
      }
      if (path === '/api/certificates') {
        return route.fulfill({ json: pageResult(path, url, certRow) })
      }
      return route.fulfill({ json: {} })
    }
  )
}

async function gotoHarness(page: Page, fixture: string) {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(`/e2e/fixtures/${fixture}.html`)
  await page.waitForSelector('.search-toolbar__search input')
  if (fixture.startsWith('certificate')) {
    // 列表请求返回后分页信息才回写，用它作为「首屏就绪」信号
    await expect(page.locator('.el-pagination__total')).toContainText(String(TOTAL))
  } else {
    await expect(page.locator('.el-table__row').first()).toBeVisible()
  }
}

/** 勾选当前页前 n 行（点击行首复选框） */
async function selectRows(page: Page, count: number) {
  const boxes = page.locator('.el-table__row .el-checkbox')
  for (let i = 0; i < count; i++) await boxes.nth(i).click()
}

const batchBar = (page: Page) => page.locator('.search-toolbar-batch')

// ============================================================
// A · 批量入口权限门控（P0-3）
//
// 契约：`batch-enabled` 必须绑定 `canManageXxx`，不能写常量。
// 否则只读用户勾选行后仍会进入批量模式并看到「批量编辑 / 批量删除所选」。
// 后端 `PermissionInterceptor` 仍会拦截（职责分离未破坏），
// 但前端暴露了必然 403 的入口，属误导性 UI。
// ============================================================

test.describe('A · 批量入口权限门控', () => {
  test('A-1 只读用户（有 read 无 manage）勾选行后仍出现批量栏', async ({ page }) => {
    await mockApi(page, READ_ONLY_PERMISSIONS)
    await gotoHarness(page, 'certificate-batch')

    await selectRows(page, 1)
    await expect(batchBar(page)).toBeVisible()
    await expect(batchBar(page)).toContainText('批量编辑')
    await expect(batchBar(page)).toContainText('批量删除所选')
  })

  test('A-2 行内「编辑」「删除」对只读用户可见', async ({ page }) => {
    await mockApi(page, READ_ONLY_PERMISSIONS)
    await gotoHarness(page, 'certificate-batch')

    const firstRow = page.locator('.el-table__row').first()
    await expect(firstRow.getByText('编辑', { exact: true })).toBeVisible()
    await expect(firstRow.getByText('删除', { exact: true })).toBeVisible()
  })

  test('A-3 有 manage 权限时，顶部写按钮与批量栏均可见（对照）', async ({ page }) => {
    await mockApi(page, MANAGE_PERMISSIONS)
    await gotoHarness(page, 'certificate-batch')

    await expect(page.getByRole('button', { name: '新增知识产权' })).toBeVisible()
    await selectRows(page, 1)
    await expect(batchBar(page)).toBeVisible()
  })
})

// ============================================================
// B · 批量选择的跨页语义
//
// 契约：跨页选择是否保留**必须基于当前产品语义决定**，
// 不允许全站统一成清空或保留。这里只固定 CertificateView 的现状。
// ============================================================

test.describe('B · 批量选择的跨页语义', () => {
  test('B-1 CertificateView：翻页后已选记录保留（跨页累积）', async ({ page }) => {
    await mockApi(page, MANAGE_PERMISSIONS)
    await gotoHarness(page, 'certificate-batch')

    await selectRows(page, 2)
    await expect(batchBar(page)).toContainText('2')

    await page.locator('.el-pagination .btn-next').click()
    await expect(page.locator('.el-pager li.is-active')).toHaveText('2')

    await expect(batchBar(page)).toContainText('2')
  })
})
