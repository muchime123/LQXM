-- 保留执行方复评时的下游事实；审批前再次核对，避免旧意见覆盖新的执行情况。
ALTER TABLE erp_sales_order_changes
  ADD COLUMN review_downstream_snapshot LONGTEXT NULL COMMENT '复评通过时的下游单据 JSON 快照';
