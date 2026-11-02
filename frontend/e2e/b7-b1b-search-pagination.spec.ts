import { expect, test, type Page } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * B7-B1b：搜索 / 筛选 / 分页联动回归。
 *
 * 契约（本阶段要钉死的行为）：
 *   1. **改变查询条件**（输入关键词、切换筛选、清除筛选）→ 用新条件**从第 1 页**重新查询；
 *   2. **仅翻页** → 保留当前查询条件，只改变页码。
 *
 * 为什么必须在**请求侧**断言：界面上的高亮页码由响应回写，
 * 而「分页栏点了但页码没变」与「页码变了但请求没带条件」在界面上都可能看不出来；
 * 只有拦截 XHR 检查查询串才能确认真正发出的请求。
 *
 * 三个夹具均直接挂载真实业务页面，全部 /api/ 请求由 page.route 拦截返回固定数据，
 * 不连接后端、不产生任何写入副作用。
 */

// 挂载真实业务页面 + Element Plus，首屏较重；放宽超时避免并行下的偶发抖动
test.setTimeout(60_000)

const OUT = resolve(process.cwd(), 'playwright-report', 'b7-b1b')
const TOTAL = 137
const SIZE = 20

const ME = {
  employeeId: 1,
  name: 'E2E 验收账号',
  permissions: ['certificate.manage', 'customer.manage', 'website.manage', 'officeAsset.manage'],
  dataScope: { type: 'ALL' },
  compatibilityMode: false
}

interface Call {
  page: string | null
  keyword: string | null
  status: string | null
}

function toCall(url: string): Call {
  const params = new URL(url).searchParams
  return { page: params.get('page'), keyword: params.get('keyword'), status: params.get('status') }
}

function shot(page: Page, name: string) {
  mkdirSync(OUT, { recursive: true })
  return page.screenshot({ path: resolve(OUT, `${name}.png`), fullPage: true })
}

/**
 * 只拦后端接口（路径以 /api 开头）。
 * 不能写成两段星号的通配路径：那样会把 Vite 服务的 /src/api/*.ts 模块请求一起拦掉，页面直接白屏。
 */
async function mockMe(page: Page) {
  await page.addInitScript(() => localStorage.setItem('lq.token', 'e2e-mock-token'))
  await page.route(url => new URL(url).pathname === '/api/auth/me', route => route.fulfill({ json: ME }))
}

async function gotoHarness(page: Page, fixture: string) {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto(`/e2e/fixtures/${fixture}.html`)
  await page.waitForSelector('.search-toolbar__search input')
  // 列表请求返回后分页信息才回写，用它作为「首屏就绪」信号（比等某一行更稳）
  await expect(page.locator('.el-pagination__total')).toContainText(String(TOTAL))
}

async function shotSafe(page: Page, name: string) {
  await shot(page, name).catch(() => undefined)
}

// ============================================================ 询盘管理（WebsiteInquiryView）
// 协议：page 参数为 **1 基**（fetchInquiries 传 pageInfo.page + 1）

test.describe('WebsiteInquiryView · 搜索 / 筛选 / 分页联动', () => {
  test('翻页只改页码，保留当前查询条件', async ({ page }) => {
    const calls: string[] = []
    await mockMe(page)
    await page.route(
      url => new URL(url).pathname === '/api/website/inquiries/admin/list',
      async route => {
        const params = new URL(route.request().url()).searchParams
        calls.push(route.request().url())
        const p = Number(params.get('page') ?? 1)
        const size = Number(params.get('size') ?? SIZE)
        const items = Array.from({ length: size }, (_, i) => ({
          id: (p - 1) * size + i + 1,
          name: `联系人 ${(p - 1) * size + i + 1}`,
          company: `示例公司 ${(p - 1) * size + i + 1}`,
          phone: '13800000000',
          email: 'demo@example.com',
          productFocus: '封口机',
          status: 'pending',
          sourcePage: 'index',
          assignee: null,
          createdAt: '2026-10-20T09:00:00',
          nextContactAt: null
        }))
        await route.fulfill({ json: { items, total: TOTAL, totalPages: Math.ceil(TOTAL / size), page: p } })
      }
    )
    await page.route(
      url => new URL(url).pathname === '/api/website/inquiries/admin/stats',
      route => route.fulfill({ json: { pending: 3, processed: 2, closed: 1, thisMonth: 5, pendingFollowUp: 2 } })
    )

    await gotoHarness(page, 'website-inquiry')

    await page.locator('.search-toolbar__search input').fill('包装机')
    await expect.poll(() => calls.length).toBe(2)
    const byKeyword = toCall(calls[1])
    expect(byKeyword.page, '首次搜索应带第 1 页').toBe('1')
    expect(byKeyword.keyword).toBe('包装机')

    await page.locator('.el-pager').getByText('3', { exact: true }).click()
    await expect.poll(() => calls.length).toBe(3)
    const paged = toCall(calls[2])
    expect(paged.page, '翻页应请求目标页码').toBe('3')
    expect(paged.keyword, '翻页必须保留关键词').toBe('包装机')
  })

  test('输入关键词后回到第 1 页（分页重置）', async ({ page }) => {
    const calls: string[] = []
    await mockMe(page)
    await page.route(
      url => new URL(url).pathname === '/api/website/inquiries/admin/list',
      async route => {
        const params = new URL(route.request().url()).searchParams
        calls.push(route.request().url())
        const p = Number(params.get('page') ?? 1)
        const size = Number(params.get('size') ?? SIZE)
        const items = Array.from({ length: size }, (_, i) => ({
          id: (p - 1) * size + i + 1,
          name: `联系人 ${(p - 1) * size + i + 1}`,
          company: null,
          phone: '13800000001',
          email: null,
          productFocus: null,
          status: 'processed',
          sourcePage: null,
          assignee: '张伟',
          createdAt: '2026-10-21T09:00:00',
          nextContactAt: null
        }))
        await route.fulfill({ json: { items, total: TOTAL, totalPages: Math.ceil(TOTAL / size), page: p } })
      }
    )
    await page.route(
      url => new URL(url).pathname === '/api/website/inquiries/admin/stats',
      route => route.fulfill({ json: { pending: 3, processed: 2, closed: 1, thisMonth: 5, pendingFollowUp: 2 } })
    )

    await gotoHarness(page, 'website-inquiry')

    await page.locator('.el-pager').getByText('3', { exact: true }).click()
    await expect.poll(() => calls.length).toBe(2)
    expect(toCall(calls[1]).page, '前置：应停在第 3 页').toBe('3')

    await page.locator('.search-toolbar__search input').fill('封口机')
    await expect.poll(() => calls.length).toBe(3)
    const reset = toCall(calls[2])
    expect(reset.page, '改关键词后必须回到第 1 页').toBe('1')
    expect(reset.keyword).toBe('封口机')
    await shotSafe(page, '01-website-inquiry-keyword-resets-page')
  })
})

// ============================================================ 知识产权（CertificateView）
// 协议：page 参数为 **0 基**（getPageParams 直接透传）

test.describe('CertificateView · 搜索 / 筛选 / 分页联动', () => {
  async function mockCertificate(page: Page, calls: string[]) {
    await mockMe(page)
    await page.route(
      url => new URL(url).pathname === '/api/certificates',
      async route => {
        const params = new URL(route.request().url()).searchParams
        calls.push(route.request().url())
        const p = Number(params.get('page') ?? 0)
        const size = Number(params.get('size') ?? SIZE)
        const content = Array.from({ length: size }, (_, i) => ({
          id: p * size + i + 1,
          assetStatus: '有效',
          assetCategory: '专利',
          patentType: '发明',
          name: `示例知识产权 ${p * size + i + 1}`,
          patentNo: `ZL2026${String(p * size + i + 1).padStart(6, '0')}`,
          applicationNo: '202610000000.0',
          applicant: '浙江利强包装',
          applyDate: '2026-01-10',
          grantDate: '2026-06-10',
          expireDate: '2036-01-10',
          daysRemaining: 3600,
          feeStatus: '已缴费',
          feeDueDate: '2027-01-10',
          lastFeeAmount: 900,
          feeQueryDate: '2026-10-20',
          specFile: '',
          certFile: '',
          invoiceStatus: '未开票',
          agencyContact: '',
          niceClass: 0,
          applicantAddress: ''
        }))
        await route.fulfill({
          json: { content, totalElements: TOTAL, totalPages: Math.ceil(TOTAL / size), number: p, size }
        })
      }
    )
    await page.route(
      url => new URL(url).pathname === '/api/certificates/stats',
      route => route.fulfill({ json: { total: TOTAL, valid: 100, expiringSoon: 5, expired: 3, dueSoonItems: [] } })
    )
    await page.route(
      url => new URL(url).pathname === '/api/certificates/filters',
      route =>
        route.fulfill({
          json: {
            assetStatuses: ['有效', '即将到期', '已过期'],
            assetCategories: ['专利', '商标', '软著'],
            patentTypes: ['发明', '实用新型', '外观设计'],
            feeStatuses: ['已缴费', '待缴费', '已逾期']
          }
        })
    )
  }

  test('翻页只改页码，保留当前查询条件', async ({ page }) => {
    const calls: string[] = []
    await mockCertificate(page, calls)
    await gotoHarness(page, 'certificate')

    await page.locator('.search-toolbar__search input').fill('专利')
    await expect.poll(() => calls.length).toBe(2)
    expect(toCall(calls[1]).page, '首次搜索应带第 1 页（0 基）').toBe('0')
    expect(toCall(calls[1]).keyword).toBe('专利')

    await page.locator('.el-pager').getByText('3', { exact: true }).click()
    await expect.poll(() => calls.length).toBe(3)
    const paged = toCall(calls[2])
    expect(paged.page, '翻页应请求目标页码（0 基）').toBe('2')
    expect(paged.keyword, '翻页必须保留关键词').toBe('专利')
    await shotSafe(page, '02-certificate-pagination-keeps-keyword')
  })

  test('清除筛选后回到第 1 页（分页重置）', async ({ page }) => {
    const calls: string[] = []
    await mockCertificate(page, calls)
    await gotoHarness(page, 'certificate')

    await page.locator('.search-toolbar__search input').fill('专利')
    await expect.poll(() => calls.length).toBe(2)

    await page.locator('.el-pager').getByText('3', { exact: true }).click()
    await expect.poll(() => calls.length).toBe(3)
    expect(toCall(calls[2]).page, '前置：应停在第 3 页（0 基为 2）').toBe('2')

    await page.locator('.search-toolbar__clear').click()
    await expect.poll(() => calls.length).toBe(4)
    const cleared = toCall(calls[3])
    expect(cleared.page, '清除筛选后必须回到第 1 页').toBe('0')
    expect(cleared.keyword, '清除筛选后不应再带关键词').toBeNull()

    await shotSafe(page, '03-certificate-clear-resets-page')
  })
})

// ============================================================ 客户档案（CustomerView）
// 协议：page 参数为 **0 基**

test.describe('CustomerView · 搜索 / 筛选 / 分页联动', () => {
  async function mockCustomer(page: Page, calls: string[]) {
    await mockMe(page)
    await page.route(
      url => new URL(url).pathname === '/api/customers',
      async route => {
        const params = new URL(route.request().url()).searchParams
        calls.push(route.request().url())
        const p = Number(params.get('page') ?? 0)
        const size = Number(params.get('size') ?? SIZE)
        const content = Array.from({ length: size }, (_, i) => ({
          id: p * size + i + 1,
          customerCode: `KH${String(p * size + i + 1).padStart(4, '0')}`,
          customerName: `示例客户 ${p * size + i + 1}`,
          shortName: `示例${p * size + i + 1}`,
          customerType: '直销客户',
          industry: '食品包装',
          primarySalesperson: '张伟',
          contactName: '李娜',
          contactMobile: '13900000000',
          province: '浙江省',
          city: '温州市',
          district: '鹿城区',
          relationStatus: '合作中',
          status: '正常',
          createdAt: '2026-10-01T09:00:00'
        }))
        await route.fulfill({
          json: { content, totalElements: TOTAL, totalPages: Math.ceil(TOTAL / size), number: p, size }
        })
      }
    )
    await page.route(
      url => new URL(url).pathname === '/api/customers/filters',
      route =>
        route.fulfill({
          json: {
            customerTypes: ['直销客户', '经销商'],
            industries: ['食品包装', '医药包装'],
            provinces: ['浙江省', '广东省'],
            relationStatuses: ['合作中', '已中断'],
            statuses: ['正常', '停用'],
            salespersons: ['张伟', '李娜']
          }
        })
    )
  }

  test('翻页只改页码，保留当前查询条件', async ({ page }) => {
    const calls: string[] = []
    await mockCustomer(page, calls)
    await gotoHarness(page, 'customer')

    await page.locator('.search-toolbar__search input').fill('温州')
    await expect.poll(() => calls.length).toBe(2)
    expect(toCall(calls[1]).page, '首次搜索应带第 1 页（0 基）').toBe('0')
    expect(toCall(calls[1]).keyword).toBe('温州')

    await page.locator('.el-pager').getByText('3', { exact: true }).click()
    await expect.poll(() => calls.length).toBe(3)
    const paged = toCall(calls[2])
    expect(paged.page, '翻页应请求目标页码（0 基）').toBe('2')
    expect(paged.keyword, '翻页必须保留关键词').toBe('温州')
    await shotSafe(page, '04-customer-pagination-keeps-keyword')
  })

  test('清除筛选后回到第 1 页（分页重置）', async ({ page }) => {
    const calls: string[] = []
    await mockCustomer(page, calls)
    await gotoHarness(page, 'customer')

    await page.locator('.search-toolbar__search input').fill('温州')
    await expect.poll(() => calls.length).toBe(2)

    await page.locator('.el-pager').getByText('3', { exact: true }).click()
    await expect.poll(() => calls.length).toBe(3)
    expect(toCall(calls[2]).page, '前置：应停在第 3 页（0 基为 2）').toBe('2')

    await page.locator('.search-toolbar__clear').click()
    await expect.poll(() => calls.length).toBe(4)
    const cleared = toCall(calls[3])
    expect(cleared.page, '清除筛选后必须回到第 1 页').toBe('0')
    expect(cleared.keyword, '清除筛选后不应再带关键词').toBeNull()
    await shotSafe(page, '05-customer-clear-resets-page')
  })
})
