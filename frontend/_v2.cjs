const { chromium } = require('@playwright/test')
;(async () => {
  const browser = await chromium.launch({ channel: 'msedge', headless: true })
  const page = await (await browser.newContext({ viewport: { width: 1500, height: 950 } })).newPage()
  const errs = []
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text().slice(0, 180)) })
  page.on('pageerror', e => errs.push('PAGEERR: ' + e.message.slice(0, 180)))

  await page.goto('http://localhost:5173/', { waitUntil: 'domcontentloaded' })
  await page.evaluate(() => localStorage.setItem('lq.currentEmployeeId', '23'))
  await page.goto('http://localhost:5173/#/manufacturing-task-configs', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(2800)

  console.log('WS_ITEMS=' + (await page.locator('.ws-item').count()))
  for (let i = 0; i < await page.locator('.ws-item').count(); i++) {
    console.log('  [' + i + '] ' + (await page.locator('.ws-item').nth(i).innerText()).replace(/\n/g, ' / '))
  }
  console.log('DEFAULT_SELECTED=' + (await page.locator('.ws-item.active .ws-name').innerText().catch(() => 'none')))
  console.log('DETAIL_TITLE=' + (await page.locator('.detail-title h2').innerText().catch(() => 'none')))
  await page.screenshot({ path: '_v2_default.png', fullPage: true })

  // 切到封口机车间
  await page.locator('.ws-item', { hasText: '封口机车间' }).first().click()
  await page.waitForTimeout(1200)
  console.log('AFTER_SWITCH_TITLE=' + (await page.locator('.detail-title h2').innerText()))
  console.log('AFTER_SWITCH_STATS=' + (await page.locator('.detail-stats').innerText()).replace(/\n/g, ' '))
  console.log('BLOCK_TITLES=' + JSON.stringify(await page.locator('.block-head h3').allInnerTexts()))
  console.log('PRODUCT_ROWS=' + (await page.locator('.block').nth(0).locator('.el-table__row').count()))
  console.log('CONFIG_ROWS=' + (await page.locator('.block').nth(1).locator('.el-table__row').count()))
  await page.screenshot({ path: '_v2_sealer.png', fullPage: true })

  console.log('ERRORS=' + JSON.stringify(errs.slice(0, 6)))
  await browser.close(); process.exit(0)
})().catch(e => { console.error('FATAL', e); process.exit(1) })
