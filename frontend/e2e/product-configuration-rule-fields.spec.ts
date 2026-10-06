import { expect, test, type Locator } from '@playwright/test'

test('规则试算沿用任务配置选择控件，按形状切换尺寸并保留可选字段', async ({ page }) => {
  const previews: Array<Record<string, unknown>> = []
  const rule = { id:101,baseProductId:49,version:1,revision:1,status:'DRAFT',
    definition:{productType:'MACHINE',requiredFields:['boxShape','moldArrangement'],options:[],includeElectrical:true},
    labels:{productName:'全自动气调机',productCode:'BP-49',optionNames:{}} }
  await page.addInitScript(() => localStorage.setItem('lq.token','rule-field-test-only'))
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    const reply = (data:unknown) => route.fulfill({json:{code:0,message:'ok',timestamp:new Date().toISOString(),data}})
    if(path === '/api/auth/me')return reply({employeeId:23,name:'配置维护员',roles:[],permissions:['manufacturing.template.manage'],dataScope:'ALL',loginType:'LOCAL'})
    if(path === '/api/manufacturing/master/task-configs')return reply([{id:49,workshopId:7,workshopName:'气调机车间',configCode:'BP-49',configName:'全自动气调机',configType:'PRODUCT_NAME',active:true,processTemplateId:8}])
    if(path === '/api/manufacturing/master/workshops')return reply([{id:7,workshopName:'气调机车间',enabled:true,defaultProcessTemplateId:8}])
    if(path === '/api/manufacturing/master/templates')return reply([{id:8,templateName:'标准制程',active:true}])
    if(path === '/api/product-configurations/products/49/rules')return reply(route.request().method()==='POST'?rule:[])
    if(path.endsWith('/preview')){previews.push(route.request().postDataJSON());return reply({namePreview:'试算名称',specificationPreview:'试算规格',completionState:'COMPLETE',fieldErrors:{},confirmationItems:[]})}
    return reply(null)
  })
  await page.goto('/#/manufacturing-task-configs')
  await page.getByRole('button',{name:'配置规则',exact:true}).click()
  const dialog = page.getByRole('dialog',{name:'产品配置与命名规则'})
  const trial = dialog.locator('.rule-section').filter({has:page.getByRole('heading',{name:'试算配置',exact:true})})
  const field = (label:string):Locator => trial.locator('.el-form-item').filter({hasText:label}).first()
  await expect(field('盒子形状').getByRole('radio')).toHaveCount(3)
  await expect(field('电压').getByRole('radio')).toHaveCount(4)
  await expect(field('品牌').getByRole('radio')).toHaveCount(3)
  // 电压不是必填，仍然可以在试算中选择。
  await field('电压').getByText('单相220V',{exact:true}).click()
  await field('插头').getByText('国标',{exact:true}).click()
  await field('品牌').getByText('利强品牌',{exact:true}).click()
  await field('盒子长').locator('input').fill('200')
  await field('盒子宽').locator('input').fill('150')
  await expect(trial.getByText('盒子直径（mm）',{exact:true})).toHaveCount(0)
  await field('模具排列').locator('.el-select__wrapper').click()
  await page.locator('.el-select-dropdown__item:visible').filter({hasText:'1出2'}).first().click()
  await trial.getByRole('button',{name:'预览已保存规则'}).click()
  await expect.poll(()=>previews.length).toBe(1)
  expect(previews[0]).toMatchObject({boxShape:'RECT',boxLengthMm:'200',boxWidthMm:'150',moldArrangement:'1出2',voltage:'单相220V',extraMold:false})
  await field('盒子形状').getByText('圆形',{exact:true}).click()
  await expect(trial.getByText('盒子长（mm）',{exact:true})).toHaveCount(0)
  await field('盒子直径').locator('input').fill('180')
  await trial.getByRole('button',{name:'预览已保存规则'}).click()
  await expect.poll(()=>previews.length).toBe(2)
  expect(previews[1]).toMatchObject({boxShape:'ROUND',boxDiameterMm:'180'})
  expect(previews[1]).not.toHaveProperty('boxLengthMm')
  await field('盒子形状').getByText('其他',{exact:true}).click()
  await expect(trial.getByText('盒子直径（mm）',{exact:true})).toHaveCount(0)
  await expect(field('盒子形状说明').locator('input')).toBeVisible()
  await expect(field('尺寸描述').locator('input')).toBeVisible()
})
