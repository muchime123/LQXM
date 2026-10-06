import { expect, test } from '@playwright/test'
import { ORDER_CUSTOMER, customerDirectoryPage, pickOrderCustomer } from './fixtures/salesOrderCustomer'

test('外采要求从销售订单填写并在采购需求中沿用', async ({page}) => {
  let saved: Record<string, any> | undefined
  let purchaseSaved: Record<string, any> | undefined
  let localProductCreated = false
  await page.addInitScript(() => localStorage.setItem('lq.token','buy-config-test-only'))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request=route.request(), path=new URL(request.url()).pathname
    const reply=(data:unknown)=>route.fulfill({json:{code:0,message:'ok',timestamp:new Date().toISOString(),data}})
    if(path==='/api/auth/me')return reply({employeeId:23,name:'销售员',roles:[],permissions:['sales_order.read.all','sales_order.manage','purchase_order.read.all','purchase_order.manage'],dataScope:'ALL',loginType:'LOCAL'})
    if(path==='/api/customers')return reply(customerDirectoryPage)
    if(path==='/api/erp-sales-orders' && request.method()==='GET')return reply({items:[],total:0,page:1,size:20})
    if(path==='/api/erp-sales-orders' && request.method()==='POST'){saved=request.postDataJSON();return reply(42)}
    if(path==='/api/erp-sales-orders/42')return reply({head:{id:42,orderNo:'XSDD00000042',status:'DRAFT',customerId:ORDER_CUSTOMER.id,customerName:'测试客户',orderDate:'2026-09-27'},items:saved?.lines.map((line:Record<string,unknown>)=>({...line,id:77,purchaseConfigJson:JSON.stringify(line.purchaseConfig)}))||[],approvals:[]})
    if(path==='/api/erp-purchase-orders/demands')return reply([{itemId:77,orderId:42,orderNo:'XSDD00000042',customerName:'测试客户',productName:'外采电机',specification:'380V / 2.2kW',unit:'台',quantity:2,purchaseConfigJson:JSON.stringify(saved?.lines[0].purchaseConfig)}])
    if(path==='/api/erp-purchase-orders' && request.method()==='GET')return reply({items:[],total:0,page:1,size:20})
    if(path==='/api/erp-purchase-orders' && request.method()==='POST'){purchaseSaved=request.postDataJSON();return reply(8)}
    if(path==='/api/erp-purchase-orders/products' && request.method()==='POST'){
      localProductCreated=true
      return reply({id:91,productCode:'BUY-LOCAL-91',productName:'外采电机',specification:'380V / 2.2kW',unit:'台'})
    }
    if(path==='/api/erp-purchase-orders/warehouses')return reply([])
    return reply([])
  })
  await page.goto('/#/sales-orders')
  await page.getByRole('button',{name:'新建订单'}).click()
  const order=page.getByRole('dialog',{name:'新建销售订单'})
  await pickOrderCustomer(page, order)
  const line=order.locator('.el-table__row').first()
  await line.locator('td').nth(1).locator('.el-select__wrapper').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'外采'}).first().click()
  await line.getByRole('button',{name:'配置外采需求'}).click()
  const config=page.getByRole('dialog',{name:'配置外采需求'})
  const field=(label:string)=>config.locator('.el-form-item').filter({hasText:label}).first().locator('input,textarea')
  await field('产品名称').fill('外采电机')
  await field('规格/型号要求').fill('380V / 2.2kW')
  await field('品牌要求').fill('客户指定品牌')
  await field('验收要求').fill('附出厂检验报告')
  await config.getByRole('button',{name:'保存到订单明细'}).click()
  await order.getByRole('button',{name:'保存草稿'}).click()
  await expect.poll(()=>saved?.lines[0].purchaseConfig?.brand).toBe('客户指定品牌')
  // 订单头必须带上客户档案 id（只是名称锚不上档案，下游就对不上客户）
  expect(saved).toMatchObject({customerId:ORDER_CUSTOMER.id,customerName:ORDER_CUSTOMER.customerName})
  expect(saved?.lines[0]).toMatchObject({productName:'外采电机',specification:'380V / 2.2kW',supplyMode:'BUY'})
  await page.goto('/#/purchase-orders')
  await page.getByRole('button',{name:'建采购单'}).click()
  const purchase=page.getByRole('dialog',{name:'新建采购订单'})
  await expect(purchase.getByText('附出厂检验报告')).toBeVisible()
  await expect(purchase.getByText('客户指定品牌')).toBeVisible()
  await expect(purchase.locator('.el-table__row').first().locator('td').nth(1).locator('input')).toBeDisabled()
  await purchase.locator('.el-form-item').filter({hasText:'供应商'}).first().locator('input').fill('本地供应商')
  await purchase.getByRole('button',{name:'没有匹配产品？建立本地产品身份'}).click()
  await expect.poll(()=>localProductCreated).toBeTruthy()
  await purchase.getByRole('button',{name:'保存草稿'}).click()
  await expect.poll(()=>purchaseSaved).toMatchObject({
    supplierId:null,supplierName:'本地供应商',lines:[{salesProductId:91,productName:'外采电机'}]
  })
})
