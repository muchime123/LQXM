import { expect, test } from '@playwright/test'

/**
 * 跟单周复盘看板的**接口契约**：看板上每个数字都要真的来自后端，且下钻带对参数。
 *
 * 背景：周复盘看板是替跟单员手工周报（Word 表格）做的，一旦前端自作主张算占比、
 * 或者下钻忘了带「原因类别」，看板就会和图省事的手工表对不上 —— 而单测与类型检查
 * 都发现不了「打开页面才错」的这类问题，故用真机 e2e 钉死。
 *
 * 数字取自 10 月第二周周复盘表：需完成 65、按期 55、逾期 7、未完工 3、按期率 84.6%。
 */
const review = {
  period: { from: '2026-10-12', to: '2026-10-18', days: 7 },
  asOf: '2026-10-19',
  workshops: ['封口机车间'],
  summary: {
    dueTasks: 65, onTime: 55, overdue: 7, open: 3,
    onTimeAchievementRate: 84.6, completionRate: 89.2,
    extended: 10, extensionDays: 42,
    overdueDays: 17, averageOverdueDays: 2.4,
    openOverdue: 1, openOverdueDays: 5,
    cancelled: 0, stopped: 0, missingExpected: 4
  },
  overdueTotal: 7,
  extensionTotal: 10,
  overdueLevels: [
    { key: 'MINOR', label: '轻微逾期', range: '1-3 天', count: 7, rate: 100 },
    { key: 'MEDIUM', label: '中度逾期', range: '4-7 天', count: 0, rate: 0 },
    { key: 'SEVERE', label: '严重逾期', range: '8 天以上', count: 0, rate: 0 }
  ],
  overdueReasons: [
    { key: 'ASSEMBLY', label: '组装原因', count: 2, rate: 28.6, samples: ['没时间装'] },
    { key: 'INBOUND', label: '入库原因', count: 2, rate: 28.6, samples: ['入库延迟'] },
    { key: 'SALES', label: '业务员原因', count: 1, rate: 14.3, samples: ['送单延迟'] },
    { key: 'PARTS', label: '配件原因', count: 1, rate: 14.3, samples: ['差钣金配件'] },
    { key: 'MOLD', label: '模具原因', count: 1, rate: 14.3, samples: ['模具延迟'] }
  ],
  extensionReasons: [
    { key: 'SALES', label: '业务员原因', count: 4, rate: 40, samples: ['订单不确定'] },
    { key: 'ASSEMBLY', label: '组装原因', count: 3, rate: 30, samples: ['没时间装，档期排满'] },
    { key: 'PARTS', label: '配件原因', count: 2, rate: 20, samples: ['配件延迟'] },
    { key: 'LEAD_TIME', label: '工期原因', count: 1, rate: 10, samples: ['时间来不及'] }
  ]
}

const orders = {
  items: [{
    id: 1, orderNo: 'SCGD20260006047', productName: '测试产品', workshop: '封口机车间',
    baseline: '2026-10-18', deliveredDate: '2026-10-20',
    status: 'LATE', overdueDays: 2, unfinishedOverdueDays: null
  }],
  total: 1,
  page: 1,
  size: 30
}

/** 空列表响应：跟单页自身的接口，与本次契约无关，给个空壳让它不报错即可。 */
const emptyList = { items: [], total: 0, page: 1, size: 20 }

/** 只拦后端接口（路径以 /api/ 开头）——`**\/api\/**` 会连 Vite 的 /src/api/*.ts 模块一起拦掉。 */
async function stubApis(page: import('@playwright/test').Page) {
  await page.addInitScript(() => {
    localStorage.setItem('lq.token', 'weekly-review-contract-only')
    localStorage.setItem('lq.currentEmployeeId', '23')
    localStorage.setItem('lq.currentUserName', '周复盘验收')
  })
  await page.route(url => new URL(url).pathname.startsWith('/api/'), route => {
    const url = route.request().url()
    if (url.includes('/auth/me')) {
      return route.fulfill({
        json: {
          employeeId: 23, name: '周复盘验收', roles: [], dataScope: 'ALL', loginType: 'LOCAL',
          permissions: ['manufacturing.read.all', 'manufacturing.write']
        }
      })
    }
    if (url.includes('/manufacturing/followups/weekly-review')) return route.fulfill({ json: review })
    if (url.includes('/manufacturing/followups/analytics/orders')) return route.fulfill({ json: orders })
    if (url.includes('/manufacturing/followups/filter-options')) {
      return route.fulfill({ json: { workshops: [], sourceStatuses: [], assignees: [] } })
    }
    if (url.includes('/manufacturing/followups/dashboard')) {
      return route.fulfill({ json: { totals: {}, workshops: [], visibility: 'ALL' } })
    }
    if (url.includes('/manufacturing/followups')) return route.fulfill({ json: emptyList })
    return route.fulfill({ json: {} })
  })
}

/** 周复盘是**生产跟单页里的抽屉**（不是独立页面）：先打开跟单页，再点「周复盘」。 */
async function openReviewDrawer(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.getByRole('button', { name: '周复盘' }).click()
  await expect(page.getByRole('heading', { name: '跟单周复盘' })).toBeVisible()
}

test('周复盘看板按周报结构出数', async ({ page }) => {
  await stubApis(page)
  await openReviewDrawer(page)

  // 一、本周数据汇总：与周报表一一对应
  const cards = page.locator('.weekly-section').first()
  await expect(cards.getByText('65')).toBeVisible()
  await expect(cards.getByText('55')).toBeVisible()
  await expect(cards.getByText('7', { exact: true }).first()).toBeVisible()
  await expect(cards.getByText('按期完工率 84.6%')).toBeVisible()

  // 二、逾期复盘：天数分档 + 原因分类
  await expect(page.getByRole('heading', { name: '二、本周订单逾期问题复盘（7 单）' })).toBeVisible()
  await expect(page.getByText('轻微逾期')).toBeVisible()
  await expect(page.getByText('100.0%')).toBeVisible()
  await expect(page.getByText('28.6%')).toBeVisible()
  // 备注列是跟单员填的原文，不是类别名
  await expect(page.getByText('没时间装', { exact: true })).toBeVisible()

  // 三、延期复盘
  await expect(page.getByRole('heading', { name: '三、本周订单延期问题复盘（10 单）' })).toBeVisible()
  await expect(page.getByText('40.0%')).toBeVisible()

  // 四、下周重点跟进
  await expect(page.getByRole('heading', { name: /四、下周重点跟进/ })).toBeVisible()
  await expect(page.getByText('SCGD20260006047')).toBeVisible()

  // 五、总结由数据自动生成
  await expect(page.getByText(/本周需完成订单 65 单，按期完工率 84.6%/)).toBeVisible()
})

/** 点原因类型要按「类别」下钻 —— 而不是把类别名当原文去精确匹配（那样一条都匹配不到）。 */
test('点原因类型按类别下钻', async ({ page }) => {
  await stubApis(page)
  await openReviewDrawer(page)
  const drilldown = page.waitForRequest(request => {
    if (!new URL(request.url()).pathname.endsWith('/manufacturing/followups/analytics/orders')) return false
    const params = new URL(request.url()).searchParams
    return params.get('bucket') === 'overdueReasonCategory' && params.get('reasonCategory') === 'ASSEMBLY'
  })
  await page.getByRole('button', { name: '组装原因' }).first().click()
  await drilldown
  await expect(page.getByRole('heading', { name: '组装原因 · 对应工单 1 单' })).toBeVisible()
  await expect(page.getByText('SCGD20260006047')).toBeVisible()
})
