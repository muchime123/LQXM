CREATE TABLE material_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  material_code VARCHAR(50) NOT NULL,
  material_name VARCHAR(300) NOT NULL,
  specification VARCHAR(500) NULL,
  default_unit VARCHAR(40) NOT NULL,
  material_category VARCHAR(100) NULL,
  description VARCHAR(500) NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_material_code (material_code),
  KEY idx_material_name (material_name(150))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原材料与零部件主数据';

ALTER TABLE manufacturing_material_requirements
  ADD COLUMN material_id BIGINT NULL AFTER manufacturing_order_id,
  ADD KEY idx_mmr_material (material_id),
  ADD CONSTRAINT fk_mmr_material FOREIGN KEY (material_id) REFERENCES material_items(id);

ALTER TABLE material_inventory_balances
  ADD COLUMN material_id BIGINT NULL AFTER warehouse_id,
  ADD KEY idx_mib_material (material_id),
  ADD UNIQUE KEY uk_mib_warehouse_material (warehouse_id,material_id),
  ADD CONSTRAINT fk_mib_material FOREIGN KEY (material_id) REFERENCES material_items(id);

CREATE TABLE material_inventory_transactions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  transaction_no VARCHAR(50) NOT NULL,
  warehouse_id BIGINT NOT NULL,
  material_id BIGINT NULL,
  material_name_snapshot VARCHAR(300) NOT NULL,
  specification_snapshot VARCHAR(500) NULL,
  unit VARCHAR(40) NOT NULL,
  transaction_type VARCHAR(40) NOT NULL,
  quantity_change DECIMAL(18,6) NOT NULL,
  balance_after DECIMAL(18,6) NOT NULL,
  source_type VARCHAR(50) NOT NULL,
  source_id BIGINT NULL,
  operator_employee_id BIGINT NULL,
  operator_name VARCHAR(100) NULL,
  occurred_at DATETIME NOT NULL,
  reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_mit_no (transaction_no),
  KEY idx_mit_material (warehouse_id,material_id,occurred_at),
  KEY idx_mit_source (source_type,source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原材料库存不可变总流水';

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('material_inventory.read','查看原材料档案与库存'),
 ('material_inventory.manage','维护原材料档案及调整库存');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS','PRODUCTION_MANAGER','WAREHOUSE_MANAGER') AND p.permission_code IN ('material_inventory.read','material_inventory.manage');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('PRODUCTION','WAREHOUSE') AND p.permission_code='material_inventory.read';
