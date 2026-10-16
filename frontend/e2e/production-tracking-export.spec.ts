import { expect, test } from '@playwright/test'
import fs from 'node:fs'

/**
 * 生产跟单导出的**输出契约**：勾选的格式必须与最终落盘的文件类型一致。
 *
 * 背景：导出有两条输出（网页 .html / Excel .xlsx），共用同一份数据与同一个
 * `collectExportGroups()`，只差最后「谁把二维数组写成文件」。历史上这里翻过车
 * （格式单选的变量没声明 → v-model 落空 → 无论选什么都走 Excel 分支），
 * 而单测/类型检查都发现不了「跑起来才错」的这类问题，所以用真机 e2e 钉死。
 */
const row = {
  id: 1,
  orderNo: 'SCGD20260006047',
  productNameSnapshot: '测试产品',
  // 规格写成可区分的两段：网页版产品副行只带尺寸段（productSize），「水冷板」只在独立列/xlsx 出现
  specificationSnapshot: '620*820 / 水冷板',
  moldLayout: '一出四',
  workshopType: '封口机车间',
  customerNameSnapshot: '测试客户',
  salespersonNameSnapshot: '测试业务',
  plannedQuantity: 10,
  unit: '台',
  sourceCreatedAt: '2026-10-01T09:30:00',
  sourceStatus: '进行中',
  sourceOptionalConfiguration: '氧化/喷特氟龙，木箱',
  sourceOrderRemark: '客户要求优先安排',
  remark: '跟单员已核实现场',
  trackingStatus: 'PENDING',
  applicationDate: '2026-10-01',
  dueDate: '2026-10-20',
  expectedFinishDate: '2026-10-18',
  forecastFinishDate: '2026-10-25',
  stageDoneCount: 0,
  stageTotal: 5,
  stageProgress: [
    { code: 'MATERIAL', label: '备料', status: 'DONE', completedDate: '2026-10-08', note: '和黑白机一起发货' },
    { code: 'MOLD', label: '模具生产', status: 'DONE', completedDate: '2026-10-09' },
    { code: 'OXIDATION', label: '氧化/喷特氟龙', status: 'PENDING', optional: true, applicable: false },
    { code: 'ASSEMBLY', label: '组装', status: 'DONE', completedDate: '2026-10-09' },
    { code: 'WAREHOUSE', label: '入库', status: 'PENDING' }
  ],
  // 10-15：导出要能带出「为什么逾期」——取的就是详情页交付逾期录入框的同一份核实记录。
  timeReviews: [
    { kind: 'OVERDUE', status: 'OPEN', reason: '配件原因：差打码机支架' },
    { kind: 'ACCEPTANCE', status: 'OPEN', reason: '接单时已超期' }
  ]
}

/** 只拦后端接口（路径以 /api/ 开头）——`**\/api\/**` 会连 Vite 的 /src/api/*.ts 模块一起拦掉。 */
async function stubApis(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    localStorage.setItem('lq.token', 'export-contract-only')
    localStorage.setItem('lq.currentEmployeeId', '23')
    localStorage.setItem('lq.currentUserName', '导出验收')
  })
  await page.route(url => new URL(url).pathname.startsWith('/api/'), route => {
    const url = route.request().url()
    if (url.includes('/auth/me')) {
      return route.fulfill({
        json: {
          employeeId: 23, name: '导出验收', roles: [], dataScope: 'ALL', loginType: 'LOCAL',
          permissions: ['manufacturing.read.all', 'manufacturing.write']
        }
      })
    }
    if (url.includes('/manufacturing/followups/export')) {
      return route.fulfill({ json: { items: [row], total: 1, exported: 1, truncated: false, maxRows: 5000, visibility: 'ALL' } })
    }
    if (url.includes('/manufacturing/followups/filter-options')) {
      return route.fulfill({ json: { workshops: [], sourceStatuses: ['进行中'], assignees: [] } })
    }
    if (url.includes('/manufacturing/followups/dashboard')) {
      return route.fulfill({ json: { totals: { total: 1, active: 1, overdueOpen: 0, dueSoon: 0, noBaseline: 0, onTime: 0, deliveredOverdue: 0, deliveredNoBaseline: 0, inboundBeforeReport: 0 }, workshops: [], visibility: 'ALL' } })
    }
    if (url.includes('/workbook')) return route.fulfill({ json: { actionable: 0, items: [] } })
    if (url.includes('/manufacturing/followups')) {
      return route.fulfill({ json: { items: [row], total: 1, page: 1, size: 30, visibility: 'ALL' } })
    }
    return route.fulfill({ json: {} })
  })
}

async function openExportDialog(page: import('@playwright/test').Page) {
  await page.goto('/')
  const toolbarExport = page.getByRole('button', { name: '导出', exact: true })
  await expect(toolbarExport).toBeVisible({ timeout: 20_000 })
  await toolbarExport.click()
  await expect(page.getByText('网页（.html）')).toBeVisible()
}

test('默认（网页）格式导出的是一份自包含 .html', async ({ page }) => {
  await stubApis(page)
  await openExportDialog(page)

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30_000 }),
    page.getByRole('button', { name: '导出网页' }).click()
  ])
  const content = fs.readFileSync(await download.path(), 'utf8')

  expect(download.suggestedFilename()).toMatch(/\.html$/)
  expect(content.startsWith('<!DOCTYPE html>')).toBe(true)
  expect(content).toContain('<style>')          // 样式内联 = 离线可看、可打印
  expect(content).not.toMatch(/https?:\/\//)    // 零外链（自包含）
  expect(content).toContain('<section class="sheet">')
  expect(content).toContain('生产任务单提交时间')
  expect(content).toContain('10-01') // 网页版日期按打印口径省去年份；Excel 保留完整时间
  expect(content).toContain('生产任务单可选配置')
  expect(content).toContain('氧化/喷特氟龙，木箱')
  expect(content).toContain('生产任务单备注')
  expect(content).toContain('客户要求优先安排')
  expect(content).toContain('跟单员已核实现场')
  expect(content).toContain('10-25') // 已协商延期与列表当前交期一致
})

test('导出带出逾期原因与节点备注（10-15 LQ：线下要能分析数据）', async ({ page }) => {
  await stubApis(page)
  await openExportDialog(page)

  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30_000 }),
    page.getByRole('button', { name: '导出网页' }).click()
  ])
  const content = fs.readFileSync(await download.path(), 'utf8')

  expect(content).toContain('逾期原因')
  expect(content).toContain('配件原因：差打码机支架')
  expect(content).toContain('节点备注')
  expect(content).toContain('备料：和黑白机一起发货')
  // 其他核实原因带类目前缀；逾期那条只出现在「逾期原因」列，不在这列重复一遍
  expect(content).toContain('接单等待：接单时已超期')
  expect(content.match(/配件原因：差打码机支架/g)).toHaveLength(1)
})

test('旧版勾选记忆（纯数组）打开导出弹窗后会补上本次新增的列', async ({ page }) => {
  await stubApis(page)
  // v1 记忆：LQ 浏览器里存的形态，里面必然没有 10-15 新增的三列
  await page.addInitScript(() => localStorage.setItem('lq.trackingExportColumns', JSON.stringify(['orderNo', 'product'])))
  await openExportDialog(page)

  await expect(page.getByRole('checkbox', { name: '逾期原因', exact: true })).toBeChecked()
  await expect(page.getByRole('checkbox', { name: '节点备注', exact: true })).toBeChecked()
  await expect(page.getByRole('checkbox', { name: '其他时间核实原因', exact: true })).toBeChecked()
})

test('网页导出取消附属列后不会把其内容藏在主列中', async ({ page }) => {
  await stubApis(page)
  await openExportDialog(page)
  await page.locator('.export-columns').getByText('业务员', { exact: true }).click()
  await page.locator('.export-columns').getByText('生产倒计时', { exact: true }).click()
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: '导出网页' }).click()
  ])
  const content = fs.readFileSync(await download.path(), 'utf8')
  expect(content).not.toContain('测试业务')
  expect(content).not.toContain('剩余 ')
  expect(content).toContain('车间预计完成')
})

test('网页格式的字段选择不含数量/规格/模具排布，切到 Excel 才出现（10-15 LQ）', async ({ page }) => {
  await stubApis(page)
  await openExportDialog(page)
  // 网页版：这三项是产品格的组成部分，不作为可选列出现
  await expect(page.locator('.export-columns').getByText('数量', { exact: true })).toHaveCount(0)
  await expect(page.locator('.export-columns').getByText('规格', { exact: true })).toHaveCount(0)
  await expect(page.locator('.export-columns').getByText('模具排布（一出几）', { exact: true })).toHaveCount(0)

  // Excel：产品名称/数量/规格/模具排布各自成列，可单独勾选
  await page.getByText('Excel（.xlsx）').click()
  await expect(page.locator('.export-columns').getByText('数量', { exact: true })).toBeVisible()
  await expect(page.locator('.export-columns').getByText('规格', { exact: true })).toBeVisible()
  await expect(page.locator('.export-columns').getByText('模具排布（一出几）', { exact: true })).toBeVisible()
})

test('网页导出的产品是一整格：名称+数量+规格+模具，表头固定单名「产品」（10-15 LQ）', async ({ page }) => {
  await stubApis(page)
  await openExportDialog(page)
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: '导出网页' }).click()
  ])
  const content = fs.readFileSync(await download.path(), 'utf8')
  expect(content).toContain('>产品<span class="col-resize"') // 表头就叫「产品」，不把四部分写进表头
  expect(content).not.toContain('产品 / 数量')
  expect(content).toContain('10台')      // 数量徽标
  expect(content).toContain('620*820')   // 规格尺寸段（副行）
  expect(content).toContain('一出四')     // 模具排布（副行）
})

test('网页导出只勾产品时，附属信息仍照常携带、表头仍是单名「产品」', async ({ page }) => {
  await stubApis(page)
  await page.addInitScript(() => localStorage.setItem('lq.trackingExportColumns',
    JSON.stringify({ version: 2, keys: ['product'] })))
  await openExportDialog(page)
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: '导出网页' }).click()
  ])
  const content = fs.readFileSync(await download.path(), 'utf8')
  expect(content).toContain('>产品<span class="col-resize"')
  expect(content).not.toContain('产品 / 数量')
  expect(content).toContain('10台')
  expect(content).toContain('620*820')
  expect(content).toContain('一出四')
})

test('只勾了数量（没勾产品）时网页导出仍把它并成产品列，不单独成列', async ({ page }) => {
  await stubApis(page)
  await page.addInitScript(() => localStorage.setItem('lq.trackingExportColumns',
    JSON.stringify({ version: 2, keys: ['quantity'] })))
  await openExportDialog(page)
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: '导出网页' }).click()
  ])
  const content = fs.readFileSync(await download.path(), 'utf8')
  expect(content).toContain('>产品<span class="col-resize"')
  expect(content).toContain('10台')
  expect(content).not.toContain('>数量<span class="col-resize"') // 没有独立的「数量」列
})

test('导出字段记忆尊重已取消的列', async ({ page }) => {
  await stubApis(page)
  await page.addInitScript(() => localStorage.setItem('lq.trackingExportColumns', JSON.stringify(['orderNo', 'product'])))
  await openExportDialog(page)
  await expect(page.getByRole('checkbox', { name: '延期原因', exact: true })).not.toBeChecked()
  await expect(page.getByRole('checkbox', { name: '任务号', exact: true })).toBeChecked()
})

test('切到 Excel 格式导出的是 .xlsx（两种输出不串味）', async ({ page }) => {
  await stubApis(page)
  await openExportDialog(page)

  await page.getByText('Excel（.xlsx）').click()
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 30_000 }),
    page.getByRole('button', { name: '导出 Excel' }).click()
  ])

  expect(download.suggestedFilename()).toMatch(/\.xlsx$/)
})

test('快捷筛选、已选条件和重置共用列表查询', async ({ page }) => {
  await stubApis(page)
  await page.goto('/')
  await expect(page.getByRole('button', { name: '待跟单', exact: true })).toBeVisible()
  const pendingRequest = page.waitForRequest(request => request.url().includes('/api/manufacturing/followups?') && new URL(request.url()).searchParams.get('trackingStatus') === 'pending')
  await page.getByRole('button', { name: '待跟单', exact: true }).click()
  await pendingRequest
  await expect(page.getByText('跟单状态：待跟单')).toBeVisible()

  const resetRequest = page.waitForRequest(request => request.url().includes('/api/manufacturing/followups?') && new URL(request.url()).searchParams.get('trackingStatus') === 'all')
  await page.getByRole('button', { name: '重置', exact: true }).click()
  await resetRequest
  await expect(page.getByText('跟单状态：待跟单')).toHaveCount(0)
})

test('常用区创建时间筛选同时用于列表和导出', async ({ page }) => {
  await stubApis(page)
  await page.goto('/')
  const creationDate = page.locator('.tracking-common-date')
  const listRequest = page.waitForRequest(request => {
    if (!new URL(request.url()).pathname.endsWith('/manufacturing/followups')) return false
    const params = new URL(request.url()).searchParams
    return params.get('createdFrom') === '2026-10-01' && !params.has('createdMissing')
  })
  await creationDate.getByPlaceholder('提交时间起').fill('2026-10-01')
  await creationDate.getByPlaceholder('提交时间起').press('Tab')
  await listRequest
  await expect(page.getByText('生产任务单提交时间：2026-10-01～不限')).toBeVisible()

  await page.getByRole('button', { name: '导出', exact: true }).click()
  const exportRequest = page.waitForRequest(request => new URL(request.url()).pathname.endsWith('/manufacturing/followups/export')
    && new URL(request.url()).searchParams.get('createdFrom') === '2026-10-01')
  await page.getByRole('button', { name: '导出网页' }).click()
  await exportRequest
})

test('任务单状态与跟单状态分开筛选，导出沿用任务单状态条件', async ({ page }) => {
  await stubApis(page)
  await page.goto('/')
  await page.getByRole('button', { name: /更多筛选/ }).click()
  const listRequest = page.waitForRequest(request => new URL(request.url()).pathname.endsWith('/manufacturing/followups')
    && new URL(request.url()).searchParams.get('sourceStatus') === '进行中')
  await page.getByText('任务单状态（全部）').click()
  await page.getByRole('option', { name: '进行中' }).click()
  await listRequest
  await expect(page.getByText('任务单状态：进行中')).toBeVisible()
  const exportRequest = page.waitForRequest(request => new URL(request.url()).pathname.endsWith('/manufacturing/followups/export')
    && new URL(request.url()).searchParams.get('sourceStatus') === '进行中')
  await page.getByRole('button', { name: '导出', exact: true }).click()
  await page.getByRole('button', { name: '导出网页' }).click()
  await exportRequest
})

test('仅看加急位于常用区，移除的筛选不再出现', async ({ page }) => {
  await stubApis(page)
  await page.goto('/')
  const commonFilters = page.locator('.tracking-filter-fields')
  const urgentRequest = page.waitForRequest(request => new URL(request.url()).pathname.endsWith('/manufacturing/followups')
    && new URL(request.url()).searchParams.get('urgent') === 'true')
  await commonFilters.getByText('仅看加急').click()
  await urgentRequest
  await page.getByRole('button', { name: /更多筛选/ }).click()
  const advanced = page.locator('.tracking-extra-filters')
  await expect(advanced.getByText('模具排布', { exact: true })).toHaveCount(0)
  await expect(advanced.getByText('申请时间', { exact: true })).toHaveCount(0)
  await expect(advanced.getByText('排产时间', { exact: true })).toHaveCount(0)
  await expect(advanced.getByText('预计完成', { exact: true })).toHaveCount(0)
  await expect(advanced.getByText('创建时间未填写', { exact: true })).toHaveCount(0)
})
