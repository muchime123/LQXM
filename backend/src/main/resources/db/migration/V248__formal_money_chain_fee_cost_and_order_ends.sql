-- 正式域金额闭环补齐（财务口径确认后的落地）：
--  1) 订单明细记录「采购单价 unit_cost」：出库结转行成本，单产品利润 = 销售单价 - 采购单价；
--  2) 出库单结转「随单分摊的订单费用净额 fee_amount」：费用计入应收，
--     应收回笼口径 = 商品销售金额(sales_amount) + fee_amount（收款上限/未收/结清均按此口径）；
--     分批出库按「本次出库数量/订单商品总数」分摊，最后一张出库单带清余量避免尾差；
--  3) 出库明细快照 unit_cost_snapshot / line_cost，出库单累计 cost_amount / gross_profit；
--  4) 利润快照新增 fee_amount（订单杂费单列展示，不计入商品毛利，便于追溯与人工调整）；
--  5) 销售订单新增取消/关闭终态记录字段（状态值 CANCELLED / CLOSED 由业务代码维护）。

-- 1. 订单明细：采购单价（出库结转成本的来源）
ALTER TABLE formal_sales_order_items
  ADD COLUMN unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0
    COMMENT '采购单价（下单时录入；出库结转行成本，单产品利润=unit_price-unit_cost）' AFTER unit_price;

-- 2. 出库单：随单分摊的订单费用净额
ALTER TABLE formal_sales_shipments
  ADD COLUMN fee_amount DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '随本出库分摊的订单费用净额（运费/税点/优惠等，计入应收；应收回笼口径=商品销售金额+fee_amount）' AFTER sales_amount;

-- 3. 出库单金额口径注释修正（商品金额/成本/未收）
ALTER TABLE formal_sales_shipments
  MODIFY COLUMN sales_amount DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '商品销售金额（=SUM(shipment_items.line_amount)，出库时结转，不含订单费用）',
  MODIFY COLUMN cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '成本总额（=SUM(shipment_items.line_cost)=采购价快照×数量，随出库结转）',
  MODIFY COLUMN outstanding_amount DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '未收=MAX(0, sales_amount+fee_amount-received_amount)';

-- 4. 出库明细：采购价与行成本快照
ALTER TABLE formal_sales_shipment_items
  ADD COLUMN unit_cost_snapshot DECIMAL(18,4) NOT NULL DEFAULT 0
    COMMENT '出库时点采购价快照（取自订单明细 unit_cost）' AFTER unit_price_snapshot,
  ADD COLUMN line_cost DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '本行成本=unit_cost_snapshot×quantity（四舍五入2位）' AFTER line_amount;

-- 5. 利润快照：订单费用单列（不并入商品毛利；净利口径保持 sales-cost-expense+adjustment）
ALTER TABLE formal_profit_snapshots
  ADD COLUMN fee_amount DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '随本出库结转的订单费用净额（单列展示，不计入毛利/净利）' AFTER sales_amount;

-- 6. 销售订单：取消/关闭终态审计字段
ALTER TABLE formal_sales_orders
  ADD COLUMN ended_at DATETIME NULL COMMENT '取消/关闭时间' AFTER confirmed_at,
  ADD COLUMN ended_by_name VARCHAR(100) NULL COMMENT '取消/关闭操作人' AFTER ended_at,
  ADD COLUMN end_reason VARCHAR(500) NULL COMMENT '取消/关闭原因' AFTER ended_by_name;
