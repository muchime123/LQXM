-- 销售出库单继续向氚云「销售出库」表单形态对齐：补「区域」与「应收日期」两列。
-- 区域：省/市/区拼成一行存纯文本快照（避免 3 张字典表），便于快搜索与导出；
-- 应收日期：与「要求交期」分离，要求交期是客户要货，日期是销售挂账/催收节点，
-- 共同支撑财务对账（出库时即可起算账期，默认 +30 天可在前端自动提示）。

ALTER TABLE formal_sales_shipments
  ADD COLUMN region VARCHAR(100) NULL
    COMMENT '客户所在区域（省/市/区），纯文本快照便于搜索与导出' AFTER shipping_address,
  ADD COLUMN due_date DATE NULL
    COMMENT '应收日期（账期节点，仅作提示，不参与过账计算）' AFTER delivery_date;

CREATE INDEX idx_formal_shipment_due_date ON formal_sales_shipments (due_date);
CREATE INDEX idx_formal_shipment_region ON formal_sales_shipments (region(32));
