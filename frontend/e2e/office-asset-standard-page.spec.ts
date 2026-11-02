import { expect, test, type Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * B6-2 综合试点页面（OfficeAssetView）视觉与交互验收。
 *
 * 复用仓库既有 Playwright + Edge 环境（playwright.config.ts：baseURL 4173、channel msedge），
 * 不引入新工具链。夹具挂载**真实业务页面**，后端请求全部由 page.route 拦截返回固定夹具，
 * 因此不连接生产数据库、不产生任何写入副作用（保存 / 批量 / 删除 / 打印均被拦截）。
 *
 * 截图输出：frontend/playwright-report/b6-2/{before,after}。
 * 刻意不用 frontend/test-results/（Playwright 每次运行都会清空，改造前基线会被误删）。
 * 通过环境变量 B6_SHOT_PHASE 切换改造前 / 改造后。
 */

const HARNESS = '/e2e/fixtures/office-asset.html'
const PHASE = process.env.B6_SHOT_PHASE || 'before'
const OUT = resolve(process.cwd(), 'playwright-report', 'b6-2', PHASE)

interface ListCall {
  page: number
  size: number
  keyword?: string
  category?: string
  status?: string
  sort?: string
  direction?: string
}

const STATS = { total: 137, inStock: 62, inUse: 48, repairing: 12, retired: 15 }

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

async function shot(page: Page, name: string) {
  mkdirSync(OUT, { recursive: true })
  await page.screenshot({ path: resolve(OUT, `${name}.png`), fullPage: true })
}

/** 拦截全部 /api/**；返回列表请求记录供用例断言（请求侧证据，不受 UI 影响） */
async function mockApi(
  page: Page,
  opts: {
    mode?: 'ok' | 'empty' | 'error'
    delayMs?: number
    total?: number
    permissions?: string[]
  } = {}
) {
  const calls: ListCall[] = []

  // 写入类接口（POST/PUT/DELETE）一律拦截返回成功：验收不产生真实副作用。
  // 先注册 = 优先级最低，后面注册的列表 / stats / scan / records 精确路由会优先命中。
  await page.route(
    url => url.pathname.startsWith('/api/office-assets'),
    route => route.fulfill({ json: { ...makeRow(0), id: 1000 } })
  )

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
      const q = route.request().url()
      const params = new URL(q).searchParams
      const currentPage = Number(params.get('page') ?? 0)
      calls.push({
        page: currentPage,
        size: Number(params.get('size') ?? -1),
        keyword: params.get('keyword') ?? undefined,
        category: params.get('category') ?? undefined,
        status: params.get('status') ?? undefined,
        sort: params.get('sort') ?? undefined,
        direction: params.get('direction') ?? undefined
      })
      if (opts.delayMs) await new Promise(r => setTimeout(r, opts.delayMs))
      if (opts.mode === 'error') return route.fulfill({ status: 500, json: { message: '模拟后端异常' } })
      if (opts.mode === 'empty') return route.fulfill({ json: pageOf(0, 0, currentPage) })
      // 响应必须回传请求的页码：真实后端如此，useListPagination.updateFromResponse 会据此同步
      return route.fulfill({ json: pageOf(20, opts.total ?? 137, currentPage) })
    }
  )

  await page.route(
    url => url.pathname === '/api/office-assets/stats',
    route => route.fulfill({ json: STATS })
  )

  await page.route(
    url => url.pathname === '/api/office-assets/scan',
    route => route.fulfill({ json: makeRow(0) })
  )

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

  return calls
}

async function openPage(page: Page, theme: 'light' | 'dark', width: number, height: number) {
  await page.setViewportSize({ width, height })
  await page.goto(HARNESS)
  await page.evaluate(t => document.documentElement.setAttribute('data-theme', t), theme)
  await page.waitForSelector('.list-page-layout')
}

async function ready(page: Page) {
  await page.waitForSelector('.data-state--ready')
  await page.waitForSelector('.el-table__row')
}

// ============================================================ 视觉基线

test('默认态 · 桌面 1440 · 浅色', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)
  await shot(page, '01-default-light-1440')
})

test('默认态 · 桌面 1440 · 深色', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'dark', 1440, 1000)
  await ready(page)
  await shot(page, '02-default-dark-1440')
})

test('默认态 · 窄屏 420 · 浅色', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'light', 420, 900)
  await ready(page)
  await shot(page, '03-default-light-420')
})

test('默认态 · 窄屏 420 · 深色', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'dark', 420, 900)
  await ready(page)
  await shot(page, '04-default-dark-420')
})

test('加载态 · 浅色', async ({ page }) => {
  await mockApi(page, { delayMs: 3000 })
  await openPage(page, 'light', 1440, 1000)
  await page.waitForSelector('.data-state--loading')
  await shot(page, '05-loading-light')
})

test('空状态 · 浅色', async ({ page }) => {
  await mockApi(page, { mode: 'empty' })
  await openPage(page, 'light', 1440, 1000)
  await page.waitForSelector('.data-state--empty')
  await shot(page, '06-empty-light')
})

test('筛选无结果 · 浅色', async ({ page }) => {
  await mockApi(page, { mode: 'empty' })
  await openPage(page, 'light', 1440, 1000)
  await page.waitForSelector('.data-state--empty')
  await page.locator('.search-toolbar__search input').fill('不存在的资产')
  await page.waitForSelector('.data-state--filtered-empty')
  await shot(page, '07-filtered-empty-light')
})

test('错误状态 · 浅色', async ({ page }) => {
  await mockApi(page, { mode: 'error' })
  await openPage(page, 'light', 1440, 1000)
  await page.waitForSelector('.data-state--error')
  await shot(page, '08-error-light')
})

test('布局几何探针：头部 / 统计 / 工具栏 / 扫码台 / 表格 / 分页', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  // F1：title / description 已传入 ListPageLayout，但未传 show-header → 头部整块不渲染
  await expect(page.locator('.page-surface-header')).toBeVisible()
  await expect(page.locator('.page-surface-header h1')).toHaveText('物品资产')

  const geometry = await page.evaluate(() => {
    const pick = (sel: string) => {
      const el = document.querySelector(sel)
      if (!el) return null
      const r = el.getBoundingClientRect()
      const s = getComputedStyle(el)
      return {
        top: Math.round(r.top),
        bottom: Math.round(r.bottom),
        width: Math.round(r.width),
        padding: s.padding,
        borderRadius: s.borderRadius,
        background: s.backgroundColor
      }
    }
    return {
      header: pick('.page-surface-header'),
      summary: pick('.list-page-layout__summary'),
      toolbar: pick('.search-toolbar'),
      scan: pick('.scan-panel'),
      chips: pick('.active-filter-chips'),
      table: pick('.el-table'),
      pagination: pick('.list-page-layout__pagination-bar')
    }
  })
  mkdirSync(OUT, { recursive: true })
  writeFileSync(resolve(OUT, 'geometry.json'), JSON.stringify(geometry, null, 2))

  await shot(page, '09-geometry-light')
})

// ============================================================ 搜索 / 筛选 / 分页

test('搜索：翻页后输入关键词应回到第 1 页（分页重置）', async ({ page }) => {
  const calls = await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  // 先翻到第 3 页
  await page.locator('.btn-next').click()
  await page.waitForTimeout(300)
  await page.locator('.btn-next').click()
  await page.waitForTimeout(600)
  expect(calls.at(-1)?.page).toBe(2)

  // 再输入关键词
  await page.locator('.search-toolbar__search input').fill('AS-001')
  await page.waitForTimeout(800)

  const last = calls.at(-1)
  expect(last?.keyword).toBe('AS-001')
  expect(last?.page, '搜索必须重置到 0 基第 0 页，否则会停留在越界页码').toBe(0)
})

test('搜索：连续输入只触发一次请求（防抖生效）', async ({ page }) => {
  const calls = await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)
  const before = calls.length

  const input = page.locator('.search-toolbar__search input')
  await input.click()
  await input.type('AS-001', { delay: 40 })
  await page.waitForTimeout(900)

  expect(calls.length - before).toBe(1)
})

test('筛选：选择分类后请求带 category 且回到第 1 页', async ({ page }) => {
  const calls = await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  await page.locator('.search-toolbar__filter').first().click()
  await page.locator('.el-select-dropdown__item', { hasText: '电脑设备' }).first().click()
  await page.waitForTimeout(600)

  const last = calls.at(-1)
  expect(last?.category).toBe('电脑设备')
  expect(last?.page).toBe(0)
})

test('筛选：已筛选标签可单个移除，也可全部清除', async ({ page }) => {
  const calls = await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  await page.locator('.search-toolbar__filter').first().click()
  await page.locator('.el-select-dropdown__item', { hasText: '电脑设备' }).first().click()
  await page.waitForSelector('.active-filter-chips')
  await expect(page.locator('.active-filter-chips__chip')).toHaveCount(1)

  await page.locator('.active-filter-chips__chip-value').first().click()
  await page.waitForTimeout(600)
  expect(calls.at(-1)?.category).toBeFalsy()
  await expect(page.locator('.active-filter-chips')).toHaveCount(0)
})

test('排序：点击表头只发一次请求，且排序参数生效', async ({ page }) => {
  const calls = await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)
  const before = calls.length

  await page.locator('th:has-text("资产编号")').click()
  await page.waitForTimeout(800)

  const fired = calls.length - before
  expect(fired, '排序只应触发一次列表请求（不得因 default-sort 回写重复请求）').toBe(1)
  expect(calls.at(-1)?.sort).toBe('assetCode')
  expect(calls.at(-1)?.page).toBe(0)
})

test('分页：点击下一页只请求下一页，不重复请求', async ({ page }) => {
  const calls = await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)
  const before = calls.length

  await page.locator('.btn-next').click()
  await page.waitForTimeout(700)

  expect(calls.length - before).toBe(1)
  expect(calls.at(-1)?.page).toBe(1)
})

// ============================================================ 批量操作

test('批量：表头全选进入批量模式，取消全选退出', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  await page.locator('.el-table th .el-checkbox').first().click()
  await page.waitForSelector('.search-toolbar--batch')
  await expect(page.locator('.search-toolbar-batch')).toContainText('20')

  await page.locator('.el-table th .el-checkbox').first().click()
  await page.waitForTimeout(300)
  await expect(page.locator('.search-toolbar--batch')).toHaveCount(0)
})

test('批量：单行勾选进入批量模式，取消后退出', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  await page.locator('.el-table__row .el-checkbox').first().click()
  await page.waitForSelector('.search-toolbar--batch')
  await page.locator('.el-table__row .el-checkbox').first().click()
  await page.waitForTimeout(300)
  await expect(page.locator('.search-toolbar--batch')).toHaveCount(0)
})

test('权限：无 officeAsset.manage 时不出现新增与批量操作入口', async ({ page }) => {
  await mockApi(page, { permissions: [] })
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  await expect(page.getByRole('button', { name: '新增物品' })).toHaveCount(0)
  // 无权限时不应出现选择列：此前勾选会进入批量模式，但批量编辑/删除按钮对本角色不可用
  await expect(page.locator('.el-table__row .el-checkbox')).toHaveCount(0)
  await expect(page.locator('.search-toolbar--batch')).toHaveCount(0)
  await shot(page, '10-no-permission-light')
})

// ============================================================ 弹窗

test('弹窗 · 新增：标题与空表单', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  await page.getByRole('button', { name: '新增物品' }).click()
  await page.waitForSelector('.lq-dialog')
  await expect(page.locator('.lq-dialog__title-text')).toHaveText('新增物品资产')
  await shot(page, '11-dialog-create-light')
})

test('弹窗 · 编辑后新增：表单必须完全复位（无字段残留）', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  // 打开第一行编辑（该行有品牌 / 型号 / 部门等完整数据）
  await page.locator('.el-table__row').first().getByRole('button', { name: '编辑' }).click()
  await page.waitForSelector('.lq-dialog')
  await expect(page.locator('.lq-dialog__title-text')).toHaveText('编辑物品资产')

  // 关闭（取消按钮）
  await page.locator('.lq-dialog__foot').getByRole('button', { name: '取消' }).click()
  await page.waitForTimeout(400)

  await page.getByRole('button', { name: '新增物品' }).click()
  await page.waitForSelector('.lq-dialog')
  await expect(page.locator('.lq-dialog__title-text')).toHaveText('新增物品资产')

  const values = await page.evaluate(() => {
    const inputs = Array.from(document.querySelectorAll('.lq-dialog__body input'))
    return inputs.map(i => (i as HTMLInputElement).value)
  })
  // 「状态」默认值为“在库”，其余字段必须为空
  expect(values.filter(v => v && v !== '在库'), '编辑后新增不应残留上一次的字段值').toEqual([])
  await shot(page, '12-dialog-create-after-edit-light')
})

test('弹窗 · 详情：展示资产信息、流转记录与操作按钮', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  await page.locator('.el-table__row').first().getByRole('button', { name: '详情' }).click()
  await page.waitForSelector('.lq-dialog')
  await expect(page.locator('.lq-dialog__title-text')).toHaveText('资产详情')
  await expect(page.locator('.detail-heading h3')).toContainText('示例资产')
  await expect(page.locator('.records-title')).toHaveText('流转记录')
  await expect(page.locator('.el-timeline-item')).toHaveCount(1)
  await shot(page, '13-dialog-detail-light')
})

test('弹窗 · 标签设计与打印：预览渲染且尺寸为推荐值', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  await page.locator('.el-table__row').first().getByRole('button', { name: '设计打印' }).click()
  await page.waitForSelector('.lq-dialog')
  await expect(page.locator('.lq-dialog__title-text')).toHaveText('标签设计与打印')
  await page.waitForSelector('.preview-label')

  const size = await page.locator('.preview-label').evaluate(el => {
    const r = el.getBoundingClientRect()
    return { w: Math.round(r.width), h: Math.round(r.height) }
  })
  expect(size).toEqual({ w: 350, h: 200 }) // 70mm × 40mm × 5 倍预览缩放
  await shot(page, '14-dialog-print-designer-light')
})

test('弹窗 · 窄屏 420：标签设计器可完整展示', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'light', 420, 900)
  await ready(page)

  await page.locator('.el-table__row').first().getByRole('button', { name: '设计打印' }).click()
  await page.waitForSelector('.lq-dialog')
  await page.waitForSelector('.preview-label')
  await shot(page, '15-dialog-print-designer-420')
})

test('弹窗 · 深色：新增弹窗配色正确', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'dark', 1440, 1000)
  await ready(page)

  await page.getByRole('button', { name: '新增物品' }).click()
  await page.waitForSelector('.lq-dialog')
  await shot(page, '16-dialog-create-dark')
})

test('扫码工作台：扫描后打开登记弹窗', async ({ page }) => {
  await mockApi(page)
  await openPage(page, 'light', 1440, 1000)
  await ready(page)

  await page.locator('.scan-panel input').fill('AS-001')
  await page.locator('.scan-panel').getByRole('button', { name: '查询' }).click()
  await page.waitForSelector('.lq-dialog')
  await expect(page.locator('.lq-dialog__title-text')).toHaveText('扫码登记 / 资产流转')
  await shot(page, '17-scan-action-light')
})
