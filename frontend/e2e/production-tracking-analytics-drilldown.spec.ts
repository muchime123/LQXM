import { expect, test, type Page } from '@playwright/test'

/**
 * 生产交期分析（L3）抽屉的**卡片 — 下钻 契约**：
 *
 * 1. 卡片上写着 8 单，点进去必须还是 8 单 —— 数字必须来自同一份接口数据；
 * 2. 每张卡片点下去，请求里的 bucket 都必须是后端认识的（名单外一律 400「分析指标无效」）。
 *
 * 为什么只能用真机 e2e 钉：卡片与下钻是两套 DOM，绑错 bucket 或读错字段时
 * 类型检查、单测、vue-tsc 全绿，只有点一下才知道「卡片 39、下钻 41」，或者干脆 400。
 * 后端的卡片/下钻一致性由 ProductionTrackingAnalyticsConsistencyTest 保证，这里只管前端这一半。
 *
 * 数据集与后端那份契约测试同源（同一组 16 张任务），数字刻意各不相同，方便一眼定位。
 */

/** ⚠️ 与后端 ProductionTrackingAnalyticsService.BUCKETS 保持一致，改后端名单时这里也要改。 */
const ALLOWED_BUCKETS = [
  'total', 'dueTasks', 'cancelled', 'missingExpected', 'stopped', 'matured', 'openNotDue', 'openDueToday',
  'delivered', 'comparable', 'onTime', 'overdue', 'deliveredNoBaseline', 'open', 'openOverdue', 'openDueSoon',
  'openNoBaseline', 'requestedComparable', 'requestedOnTime', 'extended', 'advanced',
  'extensionReasonMissing', 'overdueReasonMissing', 'overdue1to3', 'overdue4to7', 'overdue8plus',
  'receiptOnly', 'manualOnly', 'bothSources', 'extensionReason', 'overdueReason', 'riskCategory'
]

const TOTALS = {
  total: 16, dueTasks: 14, cancelled: 2, missingExpected: 2, stopped: 1, matured: 8,
  openNotDue: 4, openDueToday: 1, delivered: 7, comparable: 6, onTime: 2, overdue: 4,
  deliveredNoBaseline: 1, open: 7, openOverdue: 1, openDueSoon: 3, openNoBaseline: 1,
  requestedComparable: 4, requestedOnTime: 2, extended: 3, advanced: 1,
  extensionReasonMissing: 1, overdueReasonMissing: 2, overdue1to3: 2, overdue4to7: 1, overdue8plus: 1,
  receiptOnly: 5, manualOnly: 1, bothSources: 1,
  openOverdueDays: 9, overdueDays: 20, extensionDays: 80,
  completionRate: 50, onTimeAchievementRate: 14.3, completedOnTimeRate: 28.6,
  onTimeRate: 33.3, requestedOnTimeRate: 50, averageOverdueDays: 5
}

function analyticsPayload() {
  return {
    totals: TOTALS,
    missingExpected: TOTALS.missingExpected,
    workshops: [{ ...TOTALS, name: '封口机车间' }],
    months: [{ ...TOTALS, name: '2026-09' }, { ...TOTALS, name: '2026-10' }],
    extensionReasons: [{ name: '产能紧张', count: 1 }, { name: '材料未到', count: 1 }, { name: '未记录', count: 1 }],
    riskCategories: [{ name: 'CAPACITY', count: 1 }, { name: 'MATERIAL', count: 1 }, { name: '未记录', count: 1 }],
    overdueReasons: [{ name: '客户验收延迟', count: 1 }, { name: '物流延误', count: 1 }, { name: '未记录', count: 2 }],
    asOf: '2026-10-19'
  }
}

function drilldownItems() {
  return [{
    id: 1, orderNo: 'SCGD20260006047', productName: '测试产品', workshop: '封口机车间',
    submittedDate: '2026-09-01', baseline: '2026-09-25', deliveredDate: '2026-09-24',
    status: 'ON_TIME', overdueDays: null, unfinishedOverdueDays: null
  }]
}

/** 模拟后端：带原因的下钻按该原因在榜里的条数返回，其余按 totals 计。 */
function totalFor(bucket: string, reason: string | null) {
  const payload = analyticsPayload()
  if (bucket === 'extensionReason') return payload.extensionReasons.find(item => item.name === reason)?.count ?? 0
  if (bucket === 'riskCategory') return payload.riskCategories.find(item => item.name === reason)?.count ?? 0
  if (bucket === 'overdueReason') return payload.overdueReasons.find(item => item.name === reason)?.count ?? 0
  return (TOTALS as Record<string, number>)[bucket] ?? 0
}

async function stubApis(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('lq.token', 'analytics-contract-only')
    localStorage.setItem('lq.currentEmployeeId', '23')
    localStorage.setItem('lq.currentUserName', '交期分析验收')
  })
  await page.route(url => new URL(url).pathname.startsWith('/api/'), route => {
    const url = route.request().url()
    if (url.includes('/auth/me')) {
      return route.fulfill({
        json: {
          employeeId: 23, name: '交期分析验收', roles: [], dataScope: 'ALL', loginType: 'LOCAL',
          permissions: ['manufacturing.read.all']
        }
      })
    }
    if (url.includes('/manufacturing/followups/analytics/orders')) {
      const bucket = new URL(url).searchParams.get('bucket') ?? ''
      // 与后端一致：名单外的 bucket 直接 400。前端若点了后端不认的名字，这里立刻暴露。
      if (!ALLOWED_BUCKETS.includes(bucket)) {
        return route.fulfill({ status: 400, json: { message: '分析指标无效' } })
      }
      return route.fulfill({
        json: {
          items: drilldownItems(),
          total: totalFor(bucket, new URL(url).searchParams.get('reason')),
          page: 1, size: 20
        }
      })
    }
    if (url.includes('/manufacturing/followups/analytics')) return route.fulfill({ json: analyticsPayload() })
    if (url.includes('/manufacturing/followups/filter-options')) {
      return route.fulfill({ json: { workshops: [], sourceStatuses: ['进行中'], assignees: [] } })
    }
    if (url.includes('/workbook')) return route.fulfill({ json: { actionable: 0, items: [] } })
    if (url.includes('/manufacturing/followups')) {
      return route.fulfill({ json: { items: [], total: 0, page: 1, size: 30, visibility: 'ALL' } })
    }
    return route.fulfill({ json: {} })
  })
}

async function openAnalytics(page: Page) {
  await page.goto('/')
  await expect(page.getByRole('button', { name: '生产分析' })).toBeVisible({ timeout: 20_000 })
  await page.getByRole('button', { name: '生产分析' }).click()
  await expect(page.locator('main.tracking-analysis section.analysis-cards')).toBeVisible({ timeout: 20_000 })
}

/** 抽屉里所有能点的卡片按钮：核心指标卡 + 三块 facts + 车间/月份表 + 缺少预计日期。 */
function cardButtons(page: Page) {
  return page.locator('main.tracking-analysis button.analysis-link, main.tracking-analysis .analysis-facts button')
}

/** 点遍所有卡片；每张卡片点完，回调拿到的就是卡片上写的那个数。 */
async function clickEveryCard(page: Page) {
  const texts = await cardButtons(page).allInnerTexts()
  const clicked: string[] = []
  for (const raw of texts) {
    // 「N 单 ↗」或直接就是「N ↗」（车间/月份表里的计数）；名称按钮（车间名、月份名）没有数字，跳过
    // 主数字一律读「N 单」—— 天数只作附注（见最后一条用例），不参与对拍
    const shown = Number(raw.match(/(\d+)\s*单/)?.[1] ?? raw.match(/^\s*(\d+)\s*↗\s*$/)?.[1])
    if (!Number.isFinite(shown)) continue
    // 必须整串精确匹配：子串匹配会让「4 单 ↗」命中「14 单 ↗」，点错卡片还看不出来
    const label = raw.trim().replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    await cardButtons(page).filter({ hasText: new RegExp(`^${label}$`) }).first().click()
    await expect(page.locator('section.analysis-drilldown h2')).toContainText(`对应工单 ${shown} 单`)
    await page.locator('section.analysis-drilldown').getByRole('button', { name: '收起' }).click()
    clicked.push(`${raw.trim()}→${shown}`)
  }
  return clicked
}

test('每个卡片写着几单，点进去就还是几单', async ({ page }) => {
  await stubApis(page)
  await openAnalytics(page)
  const clicked = await clickEveryCard(page)
  // 覆盖到核心指标、进度核实、协商改期、逾期分桶、车间与月份下钻，别只点了两三个就走过场
  expect(clicked.length).toBeGreaterThan(10)
})

test('没有哪张卡片会点出「分析指标无效」', async ({ page }) => {
  await stubApis(page)
  const rejected: string[] = []
  page.on('response', response => {
    if (response.url().includes('/analytics/orders') && response.status() === 400) rejected.push(response.url())
  })
  await openAnalytics(page)
  await clickEveryCard(page)
  // 后端 10-19 移除过冗余 bucket completed（它与 delivered 同义且无卡片）—— 卡片若还绑着它，这里就会红
  expect(rejected).toEqual([])
})

test('核心指标卡读的是接口返回的对应字段，不是写死的文案', async ({ page }) => {
  await stubApis(page)
  await openAnalytics(page)
  await expect(page.locator('section.analysis-cards button.analysis-link.metric'))
    .toHaveText(['14 单 ↗', '7 单 ↗', '7 单 ↗', '2 单 ↗', '4 单 ↗'])
})

/**
 * 「累计延后 N 天」的量纲问题：它统计的是延后天数之和，下钻只能出单数。
 * 修法不是让它也能下钻，而是把它降级成附注 —— 卡上仍然能看到累计天数，
 * 但**能点的那个数必须是单数**，否则又变回「卡片 80、下钻 3」。
 */
test('累计延后天数是附注，能点的那个数必须是延后单数', async ({ page }) => {
  await stubApis(page)
  await openAnalytics(page)
  const card = page.locator('main.tracking-analysis .analysis-facts button', { hasText: '协商延后' })
  await expect(card).toHaveCount(1, '协商延后只应有一张卡，天数在同一张卡里作附注')
  await expect(card).toContainText('3 单')
  await expect(card).toContainText('累计延后 80 天')
  await card.click()
  await expect(page.locator('section.analysis-drilldown h2')).toContainText('对应工单 3 单')
})

test('原因榜上写着几单，点进去也是那几单', async ({ page }) => {
  await stubApis(page)
  await openAnalytics(page)
  const reasons = page.locator('main.tracking-analysis .reason-list button')
  const count = await reasons.count()
  expect(count).toBeGreaterThan(5)
  for (let i = 0; i < count; i++) {
    const shown = Number((await reasons.nth(i).locator('b').innerText()).match(/(\d+)/)?.[1])
    await reasons.nth(i).click()
    await expect(page.locator('section.analysis-drilldown h2')).toContainText(`对应工单 ${shown} 单`)
    await page.locator('section.analysis-drilldown').getByRole('button', { name: '收起' }).click()
  }
})
