import { expect, test, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * B6-1 试点页面（OperationLogView）视觉与交互验收。
 *
 * 复用仓库既有 Playwright + Edge 环境（playwright.config.ts：baseURL 4173、channel msedge），
 * 不引入新工具链。夹具挂载**真实业务页面**，后端请求全部由 page.route 拦截返回固定夹具，
 * 因此不连接生产数据库、不产生任何写入副作用。
 *
 * 截图输出目录：frontend/playwright-report/b6-1/{before|after}。
 * 刻意不用 frontend/test-results/（Playwright 每次运行都会清空该目录，改造前基线会被误删），
 * playwright-report 同样被 .gitignore 忽略且不会被清空。
 * 通过环境变量 B6_SHOT_PHASE 切换改造前 / 改造后，便于逐图对比。
 */

const HARNESS = '/e2e/fixtures/operation-log.html'
const PHASE = process.env.B6_SHOT_PHASE || 'before'
const OUT = resolve(process.cwd(), 'playwright-report', 'b6-1', PHASE)

const FACETS = {
  entityTypes: ['CUSTOMER', 'DOCUMENT', 'MOLD', 'CERTIFICATE'],
  actions: ['CREATE', 'UPDATE', 'DELETE', 'MERGE']
}

function makeRow(i: number) {
  return {
    id: 1000 + i,
    entityType: ['CUSTOMER', 'DOCUMENT', 'MOLD', 'CERTIFICATE'][i % 4],
    entityId: 500 + i,
    modelName: `示例档案对象 ${String(i + 1).padStart(3, '0')}`,
    action: ['CREATE', 'UPDATE', 'DELETE', 'MERGE'][i % 4],
    changes: JSON.stringify({ field: `旧值-${i}`, to: `新值-${i}` }),
    createdAt: `2026-10-${String((i % 28) + 1).padStart(2, '0')}T09:${String(i % 60).padStart(2, '0')}:11`
  }
}

function page20() {
  const content = Array.from({ length: 20 }, (_, i) => makeRow(i))
  return { content, totalElements: 137, totalPages: 7, number: 0, size: 20 }
}

async function shot(page: Page, name: string) {
  mkdirSync(OUT, { recursive: true })
  await page.screenshot({ path: resolve(OUT, `${name}.png`), fullPage: true })
}

async function mockApi(page: Page, opts: { mode: 'ok' | 'empty' | 'error'; delayMs?: number } = { mode: 'ok' }) {
  await page.route(url => /\/api\/audit-logs\/facets$/.test(url.pathname), route => route.fulfill({ json: FACETS }))

  await page.route(
    url => /\/api\/audit-logs$/.test(url.pathname),
    async route => {
      if (opts.delayMs) await new Promise(r => setTimeout(r, opts.delayMs))
      if (opts.mode === 'error') return route.fulfill({ status: 500, json: { message: '模拟后端异常' } })
      if (opts.mode === 'empty') {
        return route.fulfill({ json: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } })
      }
      return route.fulfill({ json: page20() })
    }
  )
}

async function openPage(page: Page, theme: 'light' | 'dark', width: number, height: number) {
  await page.setViewportSize({ width, height })
  await page.goto(HARNESS)
  await page.evaluate(t => document.documentElement.setAttribute('data-theme', t), theme)
  await page.waitForSelector('.list-page-layout')
}

test('默认态：浅色/深色 × 桌面/窄屏', async ({ page }) => {
  await mockApi(page, { mode: 'ok' })

  for (const theme of ['light', 'dark'] as const) {
    for (const vp of [
      { name: 'desktop', width: 1440, height: 900 },
      { name: 'narrow', width: 420, height: 900 }
    ]) {
      await openPage(page, theme, vp.width, vp.height)
      await expect(page.locator('.data-state--ready')).toBeVisible()
      await shot(page, `default__${vp.name}__${theme}`)
    }
  }
})

test('加载态：骨架屏（浅色/深色 × 桌面）', async ({ page }) => {
  // 刻意让列表接口挂起，以便在骨架屏停留期间截图
  await mockApi(page, { mode: 'ok', delayMs: 8000 })

  for (const theme of ['light', 'dark'] as const) {
    await openPage(page, theme, 1440, 900)
    await expect(page.locator('.data-state--loading')).toBeVisible()
    await shot(page, `loading__desktop__${theme}`)
  }
})

test('空状态与筛选无结果：文案与留白', async ({ page }) => {
  await mockApi(page, { mode: 'empty' })

  for (const theme of ['light', 'dark'] as const) {
    await openPage(page, theme, 1440, 900)
    await expect(page.locator('.data-state--empty')).toBeVisible()
    await expect(page.getByText('暂无操作日志')).toBeVisible()
    await shot(page, `empty__desktop__${theme}`)
  }
})

test('筛选无结果：区分于普通空状态', async ({ page }) => {
  await mockApi(page, { mode: 'empty' })
  await openPage(page, 'light', 1440, 900)

  // search-mode="submit"，需点击「查询」才会触发搜索
  await page.getByPlaceholder('对象名称 / 变更摘要').fill('不存在的对象')
  await page.getByRole('button', { name: '查询' }).click()

  await expect(page.locator('.data-state--filtered-empty')).toBeVisible()
  await expect(page.getByText('没有符合条件的日志')).toBeVisible()
  await shot(page, 'filtered-empty__desktop__light')
})

test('错误态：提示与重试入口', async ({ page }) => {
  await mockApi(page, { mode: 'error' })

  for (const theme of ['light', 'dark'] as const) {
    await openPage(page, theme, 1440, 900)
    await expect(page.locator('.data-state--error')).toBeVisible()
    await expect(page.getByText('操作日志加载失败')).toBeVisible()
    await shot(page, `error__desktop__${theme}`)
  }
})

test('搜索触发且只请求一次（submit 模式不发防抖重复请求）', async ({ page }) => {
  const calls: string[] = []
  await page.route(url => /\/api\/audit-logs$/.test(url.pathname), route => {
    calls.push(route.request().url())
    return route.fulfill({ json: page20() })
  })
  await page.route(url => /\/api\/audit-logs\/facets$/.test(url.pathname), route => route.fulfill({ json: FACETS }))

  await openPage(page, 'light', 1440, 900)
  await expect(page.locator('.data-state--ready')).toBeVisible()
  const afterLoad = calls.length

  await page.getByPlaceholder('对象名称 / 变更摘要').fill('档案')
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.locator('.data-state--ready')).toBeVisible()

  // 输入期间不应触发请求；点击查询后只新增一次
  expect(calls.length - afterLoad).toBe(1)
  expect(calls[calls.length - 1]).toContain('keyword=')
})

test('翻页使用 0 基页码，且切换后数据重新加载', async ({ page }) => {
  const urls: string[] = []
  await page.route(url => /\/api\/audit-logs$/.test(url.pathname), route => {
    urls.push(route.request().url())
    return route.fulfill({ json: page20() })
  })
  await page.route(url => /\/api\/audit-logs\/facets$/.test(url.pathname), route => route.fulfill({ json: FACETS }))

  await openPage(page, 'light', 1440, 900)
  await expect(page.locator('.data-state--ready')).toBeVisible()
  expect(urls[0]).toContain('page=0')

  await page.locator('.el-pager').getByText('2', { exact: true }).click()
  await expect(page.locator('.data-state--ready')).toBeVisible()

  expect(urls.length).toBe(2)
  expect(urls[1]).toContain('page=1')
})

test('仅设置日期区间时「清除筛选」可见，点击后一并清除（B6-1 修复项）', async ({ page }) => {
  await mockApi(page, { mode: 'ok' })
  await openPage(page, 'light', 1440, 900)
  await expect(page.locator('.data-state--ready')).toBeVisible()

  // 修复前：SearchToolbar 只感知 keyword + fields，不含外部日期区间 → 清除按钮不出现
  expect(page.locator('.search-toolbar__clear')).toHaveCount(0)

  const dateInputs = page.locator('.el-date-editor input')
  await dateInputs.nth(0).fill('2026-10-01')
  await dateInputs.nth(0).press('Enter')
  await dateInputs.nth(1).fill('2026-10-31')
  await dateInputs.nth(1).press('Enter')

  const clear = page.locator('.search-toolbar__clear')
  await expect(clear).toBeVisible()

  await clear.click()
  await expect(clear).toHaveCount(0)
  await expect(page.locator('.data-state--ready')).toBeVisible()
})

test('布局几何探针：表格与分页条的衔接关系', async ({ page }) => {
  await mockApi(page, { mode: 'ok' })
  await openPage(page, 'light', 1440, 900)
  await expect(page.locator('.data-state--ready')).toBeVisible()

  const probe = await page.evaluate(() => {
    const pick = (sel: string) => {
      const el = document.querySelector(sel)
      if (!el) return null
      const r = el.getBoundingClientRect()
      const cs = getComputedStyle(el)
      return {
        top: Math.round(r.top),
        bottom: Math.round(r.bottom),
        width: Math.round(r.width),
        borderTop: cs.borderTopWidth,
        border: `${cs.borderTopWidth} ${cs.borderRightWidth} ${cs.borderBottomWidth} ${cs.borderLeftWidth}`,
        radius: cs.borderTopLeftRadius,
        background: cs.backgroundColor,
        padding: cs.padding
      }
    }
    return {
      header: pick('.page-surface-header'),
      toolbar: pick('.search-toolbar'),
      listContainer: pick('.list-page-layout__list-container'),
      table: pick('.el-table'),
      paginationBar: pick('.list-page-layout__pagination-bar')
    }
  })

  // 探针结果写入报告用；此处只保证关键节点存在且顺序正确
  expect(probe.header).not.toBeNull()
  expect(probe.toolbar).not.toBeNull()
  expect(probe.table).not.toBeNull()
  expect(probe.paginationBar).not.toBeNull()
  expect(probe.paginationBar!.top).toBeGreaterThanOrEqual(probe.table!.top)

  console.log('[B6-1 PROBE]', JSON.stringify(probe, null, 2))
})
