-- 正式销售订单「现货 / 待生产」供货方式改造（数据层）。
-- 设计依据：docs/formal-sales-order-stock-sales-redesign-handoff.md
--
-- 1) 订单行区分供货方式：
--    STOCK = 现货（必须选销售 SKU，确认时占用现货池可售数，走订单内现货出库）
--    MTO   = 按单生产（保持现有机型/档位/定制链路，生产下单流程不变）
--    历史行统一按 MTO 兼容，行为与改造前完全一致。
-- 2) 出库明细记录成本来源，支撑现货出库"成本取自批次原订单行"的财务追溯口径。

-- --------------------------------------------------------------------
-- 1. formal_sales_order_items 供货方式
-- --------------------------------------------------------------------
ALTER TABLE formal_sales_order_items
  ADD COLUMN supply_type VARCHAR(20) NOT NULL DEFAULT 'MTO'
    COMMENT '供货方式：STOCK=现货（占用现货库存）/ MTO=按单生产' AFTER line_no;

-- 现货预留量统计：按 SKU 汇总未交付的现货行
CREATE INDEX idx_fsoi_supply_lookup ON formal_sales_order_items (sales_product_id, supply_type);

-- --------------------------------------------------------------------
-- 2. 现货池候选批次查询：SKU × 状态 × 可用量
-- --------------------------------------------------------------------
CREATE INDEX idx_fgl_pool ON finished_goods_lots (sales_product_id, status, available_quantity);

-- --------------------------------------------------------------------
-- 3. 出库明细成本来源（财务追溯）
--    LOT_ORIGIN  成本取自批次原订单行 unit_cost（现货出库主口径，对应该批次真实生产成本）
--    ORDER_ITEM  成本取自本单售卖行 unit_cost（原订单行无成本时的回退，MTO 出库恒为此值）
--    ZERO        两侧均无成本，按 0 结转
-- --------------------------------------------------------------------
ALTER TABLE formal_sales_shipment_items
  ADD COLUMN unit_cost_source VARCHAR(30) NOT NULL DEFAULT 'ORDER_ITEM'
    COMMENT '出库成本来源：LOT_ORIGIN=批次原订单行 / ORDER_ITEM=本单订单行 / ZERO=无成本' AFTER unit_cost_snapshot;
