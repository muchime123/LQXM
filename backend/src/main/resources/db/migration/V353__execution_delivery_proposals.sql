-- 原异常保留暂停语义；新记录可明确选择风险提醒，并记录执行方可行交期。
ALTER TABLE erp_sales_order_exceptions
  ADD COLUMN pause_followup BOOLEAN NOT NULL DEFAULT TRUE,
  ADD COLUMN proposed_delivery_date DATE NULL,
  ADD COLUMN proposal_original_quantity DECIMAL(18,6) NULL,
  ADD COLUMN proposal_original_delivery_date DATE NULL,
  ADD COLUMN proposal_downstream_snapshot TEXT NULL;

ALTER TABLE manufacturing_orders
  ADD COLUMN approval_basis VARCHAR(30) NOT NULL DEFAULT 'INDEPENDENT';
