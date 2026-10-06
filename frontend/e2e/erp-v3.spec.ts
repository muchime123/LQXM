import { expect, test, type Page } from '@playwright/test'

async function setup(page: Page, permissions: string[], dark = false) {
  const requests: string[] = []
  await page.addInitScript(({ dark }) => {
    localStorage.setItem('lq.token', 'erp-v3-fixture')
    localStorage.setItem('theme', dark ? 'dark' : 'light')
  }, { dark })
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const url = new URL(route.request().url()), path = url.pathname
    requests.push(path + url.search)
    const reply = (data: unknown) => route.fulfill({json:{code:0,message:'ok',timestamp:new Date().toISOString(),data}})
    if(path === '/api/auth/me') return reply({employeeId:23,name:'ERP验收',roles:[],permissions,dataScope:'ALL',loginType:'LOCAL'})
    if(path === '/api/erp-sales-orders') return reply({items:[],total:7,page:1,size:20})
    if(path === '/api/erp-sales-orders/fulfillment-reviews/purchase') return reply([{itemId:78,orderId:42,orderNo:'SO42',lineNo:2,customerName:'星禾食品',productName:'包装盒',quantity:2000,unit:'个'}])
    if(path === '/api/erp-purchase-orders') return reply({items:[],total:3,page:1,size:20})
    if(path === '/api/manufacturing/orders') return reply({items:[],total:5,page:1,size:20})
    if(path === '/api/sales-outbounds') return reply({items:[],total:2,page:1,size:20})
    if(path === '/api/manufacturing/inspections/pending') return reply({items:[],total:0,page:1,size:20})
    return reply([])
  })
  return requests
}
const permissions = ['sales_order.read.all','manufacturing.read.all','manufacturing.manage','manufacturing.inspect','purchase_order.read.all','purchase_order.manage','manufacturing.template.manage','system.employee.manage']

test('ERP 导航聚焦已接入业务域，主数据页签可进入真实配置页面', async ({page}, info) => {
  await setup(page, permissions)
  await page.goto('/#/erp-master')
  const menu = page.locator('.nav-submenu').filter({has:page.getByRole('link',{name:'工作台',exact:true})})
  await expect(menu.getByRole('link')).toHaveText(['工作台','销售管理','生产管理','采购管理','库存与交付','主数据'])
  await expect(page.getByRole('heading',{name:'基础资料',exact:true})).toBeVisible()
  await page.getByRole('button',{name:'员工与权限',exact:true}).click()
  await expect(page).toHaveURL(/erp-master\?tab=4/)
  await expect(page.getByRole('button',{name:'进入角色权限 →'})).toBeVisible()
  await page.getByRole('button',{name:'产品与配置',exact:true}).click()
  await expect(page.getByRole('button',{name:'进入任务单产品与配置 →'})).toBeVisible()
  await page.screenshot({path:info.outputPath('erp-v3-master.png'),fullPage:true})
})

test('旧销售入口进入销售订单，运行数量可追溯来源', async ({page}) => {
  const requests = await setup(page, permissions)
  await page.goto('/#/erp-sales')
  await expect(page).toHaveURL(/sales-orders/)
  await page.goto('/#/sales-orders?status=DRAFT')
  await expect.poll(()=>requests.some(url=>url.startsWith('/api/erp-sales-orders?') && url.includes('status=DRAFT'))).toBeTruthy()
  await page.goto('/#/erp-overview')
  await expect(page.getByRole('heading',{name:'业务运行概览'})).toBeVisible()
  await expect(page.locator('.erp-panel').filter({has:page.getByRole('heading',{name:'生产中任务'})})).toContainText('5')
  await page.locator('.erp-panel').filter({has:page.getByRole('heading',{name:'生产中任务'})}).getByRole('button',{name:'查看来源队列 →'}).click()
  await expect(page).toHaveURL(/status=IN_PRODUCTION/)
  await expect.poll(()=>requests.some(url=>url.startsWith('/api/manufacturing/orders?') && url.includes('status=IN_PRODUCTION'))).toBeTruthy()
})

test('收付款明确未启用，无业务权限时不读取经营与配置数据', async ({page}) => {
  const requests = await setup(page, [])
  await page.goto('/#/erp-finance')
  await expect(page.getByText('建设方案 · 尚未启用正式 ERP 收付款账。', {exact:false})).toBeVisible()
  await page.getByRole('button',{name:'资金流水与核销',exact:true}).click()
  await expect(page.getByRole('heading',{name:'资金流水认领',exact:true})).toBeVisible()
  await expect(page.locator('.erp-metric-value')).toHaveCount(0)
  await page.goto('/#/erp-master?tab=4')
  await expect(page.getByRole('button',{name:'进入角色权限 →'})).toHaveCount(0)
  await page.goto('/#/erp-overview')
  await expect(page.getByText('当前账号没有可读取的经营业务队列。')).toBeVisible()
  expect(requests.some(url=>url.includes('erp-sales-orders') || url.includes('erp-purchase-orders') || url.includes('manufacturing/orders'))).toBeFalsy()
})

test('从采购业务域进入外采评审保持正确模式和侧栏归属', async ({page}) => {
  await setup(page, permissions)
  await page.goto('/#/erp-purchase')
  await page.getByRole('navigation',{name:'业务工作区导航'}).getByRole('button',{name:'需求评审',exact:true}).click()
  await expect(page).toHaveURL(/sales-fulfillment-reviews\?area=purchase/)
  await expect(page.getByRole('tab',{name:'采购 · 外采'})).toHaveAttribute('aria-selected','true')
  await expect(page.getByRole('navigation',{name:'主导航'}).getByRole('link',{name:'采购管理',exact:true})).toHaveClass(/active/)
  await expect(page.getByRole('navigation',{name:'业务工作区导航'}).getByRole('button',{name:'采购工作台',exact:true})).toBeVisible()
  await page.getByRole('tab',{name:'车间 · 自产'}).click()
  await expect(page).toHaveURL(/area=production/)
  await expect(page.getByRole('navigation',{name:'主导航'}).getByRole('link',{name:'生产管理',exact:true})).toHaveClass(/active/)
})

test('我的工作中外采评审事项直达正确明细', async ({page}) => {
  await setup(page, ['purchase_order.manage','purchase_order.read.all'])
  await page.goto('/#/erp-workbench')
  await page.locator('[data-queue="review-purchase"]').getByRole('button',{name:/包装盒/}).click()
  await expect(page).toHaveURL(/sales-fulfillment-reviews\?area=purchase&itemId=78/)
  await expect(page.getByRole('dialog',{name:'SO42 · 第 2 项履约评审'})).toBeVisible()
})

test('v3 新工作区在手机暗色下无横向溢出与运行异常', async ({page}, info) => {
  const errors: string[] = []
  page.on('pageerror', error=>errors.push(error.message))
  await page.setViewportSize({width:390,height:844})
  await setup(page, permissions, true)
  for(const path of ['/erp-master?tab=2','/erp-finance?tab=3','/erp-overview','/erp-production','/erp-purchase','/erp-warehouse','/erp-workbench']) {
    await page.goto('/#'+path)
    await expect(page.locator('.shell')).toBeVisible()
    await expect(page.locator('h1')).toBeVisible()
    await expect.poll(()=>page.evaluate(()=>document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy()
  }
  await expect(page.locator('html')).toHaveAttribute('data-theme','dark')
  await page.screenshot({path:info.outputPath('erp-v3-mobile-dark.png'),fullPage:true})
  expect(errors).toEqual([])
})
