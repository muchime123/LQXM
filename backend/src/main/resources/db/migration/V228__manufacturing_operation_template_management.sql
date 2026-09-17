-- 制程管理补充字段。已下达任务使用 manufacturing_order_operations 快照，不随此主数据变化。
ALTER TABLE manufacturing_operations
  ADD COLUMN default_workshop VARCHAR(100) NULL AFTER operation_category,
  ADD COLUMN standard_hours DECIMAL(10,2) NULL AFTER default_workshop,
  ADD COLUMN reporting_unit VARCHAR(40) NULL AFTER standard_hours,
  ADD COLUMN requires_inspection TINYINT(1) NOT NULL DEFAULT 0 AFTER reporting_unit;

ALTER TABLE manufacturing_process_templates
  ADD COLUMN applicable_product_keyword VARCHAR(500) NULL AFTER workshop_type;
