-- V184: 为利润/提成快照表加复合索引，加速业务档案列表的标量子查询
-- 业务档案列表查询每次都会按 (business_order_id, is_current=1) 查快照，加复合索引避免全表扫描

CREATE INDEX idx_profit_snapshot_biz_current
    ON business_profit_snapshots (business_order_id, is_current);

CREATE INDEX idx_commission_snapshot_biz_current
    ON business_commission_snapshots (business_order_id, is_current);

-- business_order_links 已有 idx_bol_business(business_order_id)，但列表查询常按 object_type 过滤
-- 加复合索引让 COUNT(*) 和 SUM() 走索引覆盖
CREATE INDEX idx_bol_biz_type
    ON business_order_links (business_order_id, object_type);
