import { expect, type Locator, type Page } from '@playwright/test'

/**
 * 销售订单的客户必须锚定**客户档案**（订单是唯一需求源，客户身份靠 customerId 往下游传：
 * 出库 / 提成 / 客户档案台账）。三个销售订单用例共用同一条档案 fixture，别各写一份。
 */
export const ORDER_CUSTOMER = { id: 5, customerCode: 'CUS000005', customerName: '测试客户' }

/** 客户列表接口 `GET /api/customers` 的响应体（与后端 Spring Page 同形），供客户下拉使用。 */
export const customerDirectoryPage = { content: [ORDER_CUSTOMER], totalElements: 1, totalPages: 1 }

/**
 * 从「客户档案」下拉里选客户。
 *
 * 两个必须遵守的写法（EP 2.14 实测）：
 * - **真敲字**：远程搜索型下拉没触发 `remote-method` 前候选是空的，直接点选项 = 假失败；
 * - **`:visible`**：页面上每个 el-select 的 popper 都常驻 DOM，不加过滤会点到零尺寸空壳。
 */
export async function pickOrderCustomer(page: Page, drawer: Locator, name = ORDER_CUSTOMER.customerName) {
  const item = drawer.locator('.order-head-form .el-form-item').filter({ hasText: '客户名称' }).first()
  await item.locator('.el-select__wrapper').click()
  await item.locator('input').focus()
  await page.keyboard.type(name)
  const option = page.locator('.el-select-dropdown__item:visible').filter({ hasText: name }).first()
  await expect(option).toBeVisible()
  await option.click()
  // 选中后下拉收起，选中文案渲染在 placeholder 节点里（filterable 的 input.value 会被清空，不能拿它断言）
  await expect(item).toContainText(name)
}
