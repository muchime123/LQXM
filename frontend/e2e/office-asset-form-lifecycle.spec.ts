import { expect, test, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * B7-B1a：OfficeAssetView 表单生命周期与数据正确性回归。
 *
 * 与前序视觉验收（e2e/office-asset-standard-page.spec.ts）的分工：
 *   - 那份只断言**界面 input 值**，而 id / lastInventoryAt / createdAt / updatedAt
 *     在表单里根本没有对应控件，**界面断言永远抓不到它们的残留**；
 *   - 本份直接断言 **POST /api/office-assets 的请求载荷**，覆盖真正的持久化风险面。
 *
 * 夹具挂载真实业务页面 OfficeAssetView，后端请求全部由 page.route 拦截，
 * 不连接生产数据库、不产生任何写入副作用。
 */

// 夹具挂载真实业务页面 + Element Plus，单页首屏较重；放宽超时避免并行下的偶发抖动
test.setTimeout(60_000)

const HARNESS = '/e2e/fixtures/office-asset.html'
const OUT = resolve(process.cwd(), 'playwright-report', 'b7-b1a')

const STATS = { total: 137, inStock: 62, inUse: 48, repairing: 12, retired: 15 }

/**
 * 资产夹具：刻意带上 id / lastInventoryAt / createdAt / updatedAt。
 * 前三项（除 id）由后端负责维护，前端新建时**不应**出现在载荷里。
 */
function makeRow(i: number) {
  const idx = String(i + 1).padStart(3, '0')
  return {
    id: 1000 + i,
    assetCode: `AS-${idx}`,
    name: `示例资产 ${idx}`,
    category: ['电脑设备', '办公家具', '网络设备', '打印设备'][i % 4],
    brand: `示例品牌 ${idx}`,
    model: `M-${idx}`,
    serialNumber: `SN-${idx}`,
    department: ['行政部', '生产部', '财务部', '技术部'][i % 4],
    custodian: ['张伟', '李娜', '王强', '赵敏'][i % 4],
    location: ['A 栋 3 楼', 'B 栋 1 楼', 'A 栋 2 楼', '仓库'][i % 4],
    status: ['在库', '领用中', '维修中', '已报废'][i % 4],
    purchaseDate: '2026-01-15',
    purchasePrice: 3200 + i,
    supplier: '示例供应商',
    warrantyUntil: '2029-01-15',
    notes: `备注 ${idx}`,
    lastInventoryAt: '2026-10-01T09:00:00',
    createdAt: '2026-01-01T08:00:00',
    updatedAt: `2026-10-${String((i % 28) + 1).padStart(2, '0')}T09:00:00`
  }
}

function pageOf(count: number, total: number, page = 0) {
  return {
    content: Array.from({ length: count }, (_, i) => makeRow(page * 20 + i)),
    totalElements: total,
    totalPages: Math.max(1, Math.ceil(total / 20)),
    number: page,
    size: 20
  }
}

interface Captured {
  createPayloads: Record<string, unknown>[]
  updatePayloads: Record<string, unknown>[]
  deleteCalls: string[]
}

async function mockApi(page: Page, opts: { permissions?: string[]; failCreate?: boolean; total?: number } = {}): Promise<Captured> {
  const captured: Captured = { createPayloads: [], updatePayloads: [], deleteCalls: [] }

  await page.route(
    url => url.pathname === '/api/auth/me',
    route =>
      route.fulfill({
        json: {
          employeeId: 1,
          name: 'E2E 验收账号',
          permissions: opts.permissions ?? ['officeAsset.manage'],
          dataScope: { type: 'ALL' },
          compatibilityMode: false
        }
      })
  )

  await page.route(
    url => url.pathname === '/api/office-assets',
    async route => {
      const req = route.request()
      const method = req.method()
      if (method === 'POST') {
        captured.createPayloads.push(JSON.parse(req.postData() || '{}'))
        if (opts.failCreate) return route.fulfill({ status: 500, json: { message: '模拟保存失败' } })
        return route.fulfill({ json: { ...makeRow(0), id: 9001, assetCode: 'AS-NEW' } })
      }
      if (method === 'PUT') {
        captured.updatePayloads.push(JSON.parse(req.postData() || '{}'))
        return route.fulfill({ json: { ...makeRow(0), id: 1000 } })
      }
      if (method === 'DELETE') {
        captured.deleteCalls.push(req.url())
        return route.fulfill({ json: {} })
      }
      const params = new URL(req.url()).searchParams
      const currentPage = Number(params.get('page') ?? 0)
      return route.fulfill({ json: pageOf(20, opts.total ?? 137, currentPage) })
    }
  )

  await page.route(url => url.pathname === '/api/office-assets/stats', route => route.fulfill({ json: STATS }))
  await page.route(url => url.pathname === '/api/office-assets/scan', route => route.fulfill({ json: makeRow(0) }))
  await page.route(
    url => /\/api\/office-assets\/\d+\/records$/.test(url.pathname),
    route =>
      route.fulfill({
        json: [
          {
            id: 1,
            assetId: 1000,
            action: '领用',
            operatorName: '张伟',
            department: '行政部',
            custodian: '张伟',
            location: 'A 栋 3 楼',
            notes: '扫码领用',
            createdAt: '2026-10-20T09:00:00'
          }
        ]
      })
  )

  return captured
}

async function openReady(page: Page) {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(HARNESS)
  await page.waitForSelector('.list-page-layout')
  await page.waitForSelector('.data-state--ready')
  await page.waitForSelector('.el-table__row')
}

async function shot(page: Page, name: string) {
  mkdirSync(OUT, { recursive: true })
  await page.screenshot({ path: resolve(OUT, `${name}.png`), fullPage: true })
}

const dialog = (page: Page) => page.locator('.lq-dialog__body')
const title = (page: Page) => page.locator('.lq-dialog__title-text')
const cancelBtn = (page: Page) => page.locator('.lq-dialog__foot').getByRole('button', { name: '取消' })
const saveBtn = (page: Page) => page.locator('.lq-dialog__foot').getByRole('button', { name: '保存' })

/** 新建请求绝对不允许出现的字段：全部由后端维护或属于历史对象残留 */
const FORBIDDEN_IN_CREATE = ['id', 'lastInventoryAt', 'createdAt', 'updatedAt', 'deletedAt']

function assertNoResidue(payload: Record<string, unknown>, label: string) {
  for (const key of FORBIDDEN_IN_CREATE) {
    expect(Object.keys(payload), `${label}：新建请求载荷不应包含 ${key}`).not.toContain(key)
  }
}

/** 填写新增必需字段（name / category 为必填） */
async function fillRequired(page: Page, name: string) {
  const body = dialog(page)
  await body.locator('.el-form-item', { hasText: '物品名称' }).locator('input').fill(name)
  await body.locator('.el-select').first().click()
  await page.locator('.el-select-dropdown__item:visible').first().click()
}

// ============================================================ 场景 1

test('场景 1 · 新增→关闭→再次新增：字段保持默认，无自增污染', async ({ page }) => {
  const captured = await mockApi(page)
  await openReady(page)

  await page.getByRole('button', { name: '新增物品' }).click()
  await page.waitForSelector('.lq-dialog')
  await fillRequired(page, '第一次新增')
  await saveBtn(page).click()
  await page.waitForTimeout(400)

  await page.getByRole('button', { name: '新增物品' }).click()
  await page.waitForSelector('.lq-dialog')
  await expect(title(page)).toHaveText('新增物品资产')

  const values = await page.evaluate(() =>
    Array.from(document.querySelectorAll('.lq-dialog__body input')).map(i => (i as HTMLInputElement).value)
  )
  // 除状态默认值「在库」外必须全空
  expect(values.filter(v => v && v !== '在库'), '第二次新增应回到全新空表单').toEqual([])
  expect(captured.createPayloads).toHaveLength(1)
})

// ============================================================ 场景 2（核心风险）

test('场景 2 · 编辑 A→关闭→新增→保存：请求载荷不得携带 id / lastInventoryAt', async ({ page }) => {
  const captured = await mockApi(page)
  await openReady(page)

  // 第一行编辑（夹具带完整 id / lastInventoryAt / createdAt / updatedAt）
  await page.locator('.el-table__row').first().getByRole('button', { name: '编辑' }).click()
  await page.waitForSelector('.lq-dialog')
  await expect(title(page)).toHaveText('编辑物品资产')

  await cancelBtn(page).click()
  await page.waitForTimeout(400)

  await page.getByRole('button', { name: '新增物品' }).click()
  await page.waitForSelector('.lq-dialog')
  await expect(title(page)).toHaveText('新增物品资产')

  await fillRequired(page, '编辑后新建的资产')
  await saveBtn(page).click()
  await page.waitForTimeout(600)

  expect(captured.createPayloads, '应发出一次新建请求').toHaveLength(1)
  const payload = captured.createPayloads[0]
  assertNoResidue(payload, '场景 2')
  expect(payload.name).toBe('编辑后新建的资产')
  await shot(page, '01-create-after-edit')
})

// ============================================================ 场景 3

test('场景 3 · 编辑 A→编辑 B：表单字段完全来自 B，无 A 的残留', async ({ page }) => {
  await mockApi(page)
  await openReady(page)

  const rows = page.locator('.el-table__row')
  await rows.nth(0).getByRole('button', { name: '编辑' }).click()
  await page.waitForSelector('.lq-dialog')
  const nameA = await dialog(page).locator('input').first().inputValue()
  await cancelBtn(page).click()
  await page.waitForTimeout(400)

  await rows.nth(1).getByRole('button', { name: '编辑' }).click()
  await page.waitForSelector('.lq-dialog')
  const nameB = await dialog(page).locator('input').first().inputValue()

  expect(nameA).toBe('示例资产 001')
  expect(nameB).toBe('示例资产 002')
  expect(nameB).not.toBe(nameA)
})

// ============================================================ 场景 4

test('场景 4 · 编辑→新增→保存：新建请求不落任何历史资产主键', async ({ page }) => {
  const captured = await mockApi(page)
  await openReady(page)

  await page.locator('.el-table__row').first().getByRole('button', { name: '编辑' }).click()
  await page.waitForSelector('.lq-dialog')
  await cancelBtn(page).click()
  await page.waitForTimeout(400)

  await page.getByRole('button', { name: '新增物品' }).click()
  await page.waitForSelector('.lq-dialog')
  await fillRequired(page, '场景四资产')
  await saveBtn(page).click()
  await page.waitForTimeout(600)

  expect(captured.createPayloads).toHaveLength(1)
  assertNoResidue(captured.createPayloads[0], '场景 4')
  // 编辑过的资产 id 为 1000，绝不能出现在新建载荷里
  expect(JSON.stringify(captured.createPayloads[0])).not.toContain('1000')
})

// ============================================================ 场景 5

test('场景 5 · 保存失败→关闭→重新打开：仍为干净的新增态', async ({ page }) => {
  const captured = await mockApi(page, { failCreate: true })
  await openReady(page)

  await page.getByRole('button', { name: '新增物品' }).click()
  await page.waitForSelector('.lq-dialog')
  await fillRequired(page, '触发失败的新增')
  await saveBtn(page).click()
  await page.waitForTimeout(600)

  // 失败后弹窗保持打开（不丢用户输入）
  expect(captured.createPayloads).toHaveLength(1)

  await cancelBtn(page).click()
  await page.waitForTimeout(400)

  await page.getByRole('button', { name: '新增物品' }).click()
  await page.waitForSelector('.lq-dialog')
  await expect(title(page)).toHaveText('新增物品资产')

  const values = await page.evaluate(() =>
    Array.from(document.querySelectorAll('.lq-dialog__body input')).map(i => (i as HTMLInputElement).value)
  )
  expect(values.filter(v => v && v !== '在库'), '保存失败后重开应完全复位').toEqual([])
})

// ============================================================ 场景 6

test('场景 6 · 表单校验失败→关闭→重新打开：不遗留红色错误态', async ({ page }) => {
  await mockApi(page)
  await openReady(page)

  await page.getByRole('button', { name: '新增物品' }).click()
  await page.waitForSelector('.lq-dialog')

  // 不填必填项直接保存 → 触发校验失败
  await saveBtn(page).click()
  await page.waitForTimeout(400)
  const errorCount = await page.locator('.lq-dialog__body .el-form-item__error:visible').count()
  expect(errorCount, '应触发必填校验错误').toBeGreaterThan(0)

  await cancelBtn(page).click()
  await page.waitForTimeout(400)

  await page.getByRole('button', { name: '新增物品' }).click()
  await page.waitForSelector('.lq-dialog')
  await expect(title(page)).toHaveText('新增物品资产')

  const leftover = await page.locator('.lq-dialog__body .el-form-item__error:visible').count()
  expect(leftover, '重新打开不应遗留上一次的校验错误态').toBe(0)
  await shot(page, '02-validation-reset')
})
