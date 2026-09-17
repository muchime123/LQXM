-- 旧“统一业务单”链已放弃：销售出库直接作为销售、客户、回款和提成事实，
-- 生产工单只保留独立历史台账，二者不再通过 business_order 关联。

-- 旧费用迁移行尚未投入正式使用，移除其 BUSINESS_ORDER 目标和迁移追踪列。
DELETE FROM expense_allocations WHERE target_type='BUSINESS_ORDER';
ALTER TABLE expense_allocations DROP INDEX uk_ea_legacy_source_target;
ALTER TABLE expense_allocations DROP COLUMN legacy_source_allocation_id;
ALTER TABLE expense_allocations DROP CHECK chk_ea_target;
ALTER TABLE expense_allocations
  ADD CONSTRAINT chk_ea_target
  CHECK (target_type IN ('DEPARTMENT','COMPANY','CORP_PAYMENT','SALES_OUTBOUND'));

DROP TABLE IF EXISTS business_order_links;
DROP TABLE IF EXISTS business_orders;
