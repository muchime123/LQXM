-- 成品批次增加「实际单位成本」：车间生产完工入库时录入本批成品的实际单位生产成本。
-- 出库结转成本取数优先级（V254 起）：批次实际成本(LOT_COST) > 批次原订单行成本(LOT_ORIGIN) > 本单订单行成本(ORDER_ITEM) > 0(ZERO)。
-- 现货商品若成本来自采购，则仍由产品成本快照/订单行承载，本列为 0 时按原口径回退。

ALTER TABLE finished_goods_lots
  ADD COLUMN unit_cost DECIMAL(18,4) NOT NULL DEFAULT 0
    COMMENT '实际单位成本（完工入库时录入；销售出库结转成本优先取该值）' AFTER available_quantity;
