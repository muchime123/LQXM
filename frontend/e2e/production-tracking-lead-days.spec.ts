import { expect, test } from '@playwright/test'

/**
 * 工期列「分不清是哪个工期」的输出契约（2026-10-15 LQ 提）。
 *
 * 一条工单同时存在要求 / 计划 / 实际三个工期，且**起算日各不相同**
 * （要求从申请时间算，车间计划与实际都从任务单提交时间算），列里却只放得下一个主数值；
 * 只显示一个数时跟单员无从判断这个数是按哪段算的，「延期之后工期跟着变」也就看不出来。
 * 故列内必须同时给出：① 主数值 ② 当前取用的是哪个真身（徽章，有延期时是「计划·延」）
 * ③ 其余真身的紧凑小字；悬停再给出带**起止日期**的完整明细。
 *
 * 这几样全是「跑起来才成立」的（el-tooltip 的 raw-content 换行、两行单元格布局、
 * 徽章不被 `.tracking-table small` 刷成统一灰），单测与类型检查都够不着，故用真机钉死。
 */
const base = {
  id: 1,
  orderNo: 'SCGD20260006047',
  productNameSnapshot: '测试产品',
  specificationSnapshot: 'A / B / C',
  workshopType: '封口机车间',
  customerNameSnapshot: '测试客户',
  salespersonNameSnapshot: '测试业务',
  plannedQuantity: 10,
  unit: '台',
  sourceCreatedAt: '2026-10-03T09:30:00',
  sourceStatus: '进行中',
  sourceOptionalConfiguration: '氧化/喷特氟龙，木箱',
  sourceOrderRemark: '客户要求优先安排',
  remark: '跟单员已核实现场',
  trackingStatus: 'PENDING',
  applicationDate: '2026-10-01',
  dueDate: '2026-10-11',
  expectedFinishDate: '2026-10-16',
  stageDoneCount: 0,
  stageTotal: 5,
  stageProgress: []
}

/** 三真身齐全：要求 10 天（10-01→10-11）/ 计划 17 天（10-03→10-20，含延期）/ 实际 22 天（10-03→10-25）。 */
const finishedRow = { ...base, id: 1, forecastFinishDate: '2026-10-20', deliveredDate: '2026-10-25' }
/** 已延期但未入库。 */
const delayedRow = { ...base, id: 2, orderNo: 'SCGD20260006048', forecastFinishDate: '2026-10-20' }
/** 未延期未入库。 */
const plannedRow = { ...base, id: 3, orderNo: 'SCGD20260006049' }
/** 车间没给预计完成日，只剩业务员要求。 */
const requiredRow = { ...base, id: 4, orderNo: 'SCGD20260006050', expectedFinishDate: null }

async function stubApis(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    localStorage.setItem('lq.token', 'lead-days-contract-only')
    localStorage.setItem('lq.currentEmployeeId', '23')
    localStorage.setItem('lq.currentUserName', '工期验收')
  })
  await page.route(url => new URL(url).pathname.startsWith('/api/'), route => {
    const url = route.request().url()
    if (url.includes('/auth/me')) {
      return route.fulfill({
        json: {
          employeeId: 23, name: '工期验收', roles: [], dataScope: 'ALL', loginType: 'LOCAL',
          permissions: ['manufacturing.read.all', 'manufacturing.write']
        }
      })
    }
    if (url.includes('/manufacturing/followups/dashboard')) {
      return route.fulfill({ json: { totals: { total: 4, active: 4 }, workshops: [], visibility: 'ALL' } })
    }
    if (url.includes('/manufacturing/followups/filter-options')) {
      return route.fulfill({ json: { workshops: [], sourceStatuses: ['进行中'], assignees: [] } })
    }
    if (url.includes('/workbook')) return route.fulfill({ json: { actionable: 0, items: [] } })
    if (url.includes('/manufacturing/followups')) {
      return route.fulfill({ json: { items: [finishedRow, delayedRow, plannedRow, requiredRow], total: 4, page: 1, size: 30, visibility: 'ALL' } })
    }
    return route.fulfill({ json: {} })
  })
}

test('工期列同时给出主数值、当前真身徽章与其余真身小字', async ({ page }) => {
  await stubApis(page)
  await page.goto('/')

  const cells = page.locator('.lead-cell')
  await expect(cells).toHaveCount(4, { timeout: 20_000 })

  // 已入库：主数值取既成事实（实际 22 天），徽章「实际」，其余两个真身挂在小字里
  await expect(cells.nth(0)).toContainText('22 天')
  await expect(cells.nth(0).locator('small.lead-kind')).toHaveText('实际')
  await expect(cells.nth(0).locator('small.lead-others')).toHaveText('要求 10 天 · 计划·延 17 天')

  // 已延期未入库：主数值是含延期的计划工期，徽章必须带「·延」——这是延期唯一的落点
  await expect(cells.nth(1)).toContainText('17 天')
  await expect(cells.nth(1).locator('small.lead-kind')).toHaveText('计划·延')
  await expect(cells.nth(1).locator('small.lead-others')).toHaveText('要求 10 天')

  // 未延期：徽章不带延
  await expect(cells.nth(2)).toContainText('13 天')
  await expect(cells.nth(2).locator('small.lead-kind')).toHaveText('计划')

  // 只剩业务员要求：回落成要求工期，没有其它真身可列
  await expect(cells.nth(3)).toContainText('10 天')
  await expect(cells.nth(3).locator('small.lead-kind')).toHaveText('要求')
  await expect(cells.nth(3).locator('small.lead-others')).toHaveCount(0)
})

test('悬停给出带起止日期的三真身明细，起算日不同也要看得出来', async ({ page }) => {
  await stubApis(page)
  await page.goto('/')

  const first = page.locator('.lead-cell').first()
  await expect(first).toBeVisible({ timeout: 20_000 })
  await first.hover()

  const tip = page.locator('.el-popper').filter({ hasText: '实际工期' }).last()
  await expect(tip).toBeVisible({ timeout: 5_000 })
  await expect(tip).toContainText('业务员要求工期 10 天（10-01 → 10-11）')
  await expect(tip).toContainText('车间计划工期 17 天（10-03 → 10-20），含延期')
  await expect(tip).toContainText('当前 实际工期 22 天（10-03 → 10-25）')
  await expect(tip).toContainText('比要求晚 14 天')
})
