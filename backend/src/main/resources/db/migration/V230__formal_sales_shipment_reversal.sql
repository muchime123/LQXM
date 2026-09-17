-- 销售出库单冲正（反出库）：抵消本次出库数量、恢复批次可用库存、回退订单已发数量，保留审计轨迹，不删除原出库单。
-- 设计原则与报工冲正 / 反入库一致：错误出库通过冲正抵消，不允许直接删除历史。

ALTER TABLE formal_sales_shipments
  ADD COLUMN reversed_at DATETIME NULL,
  ADD COLUMN reversed_by_employee_id BIGINT NULL,
  ADD COLUMN reversed_by_name VARCHAR(100) NULL,
  ADD COLUMN reversal_reason VARCHAR(500) NULL,
  ADD COLUMN reversal_movement_id BIGINT NULL,
  ADD KEY idx_formal_shipment_reversal (reversed_at);

-- 扩展流水类型：SALES_SHIPMENT_REVERSAL，由 source_type='FORMAL_SALES_SHIPMENT_REVERSAL' 区分。
INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('sales_shipment.reverse','反出库（销售出库冲正）');

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS') AND p.permission_code='sales_shipment.reverse';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('SALES_MANAGER','WAREHOUSE_MANAGER') AND p.permission_code='sales_shipment.reverse';
