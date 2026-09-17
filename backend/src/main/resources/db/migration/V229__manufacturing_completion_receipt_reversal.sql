-- 完工入库单冲正（反入库）：抵消入库数量、回退生产任务状态、保留审计轨迹，不删除原入库单。
-- 设计原则与报工冲正一致：错误入库通过冲正抵消，不允许直接删除历史。

ALTER TABLE manufacturing_completion_receipts
  ADD COLUMN reversed_at DATETIME NULL,
  ADD COLUMN reversed_by_employee_id BIGINT NULL,
  ADD COLUMN reversed_by_name VARCHAR(100) NULL,
  ADD COLUMN reversal_reason VARCHAR(500) NULL,
  ADD COLUMN reversal_movement_id BIGINT NULL,
  ADD KEY idx_completion_reversal (reversed_at);

ALTER TABLE finished_goods_lots
  ADD COLUMN reversed_at DATETIME NULL,
  ADD COLUMN reversal_reason VARCHAR(500) NULL,
  ADD COLUMN reversal_receipt_id BIGINT NULL,
  ADD KEY idx_finished_goods_reversal (reversed_at);

-- 扩展流水类型：PRODUCTION_RECEIPT_REVERSAL，不新增约束列，由 source_type='COMPLETION_RECEIPT_REVERSAL' 区分。
INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('manufacturing.receive.reverse','反入库（完工入库冲正）');

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS') AND p.permission_code='manufacturing.receive.reverse';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('PRODUCTION_MANAGER','WAREHOUSE_MANAGER') AND p.permission_code='manufacturing.receive.reverse';
