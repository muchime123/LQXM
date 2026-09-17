CREATE TABLE manufacturing_material_requirements (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  manufacturing_order_id BIGINT NOT NULL,
  material_name VARCHAR(300) NOT NULL,
  specification VARCHAR(500) NULL,
  planned_quantity DECIMAL(18,6) NOT NULL,
  unit VARCHAR(40) NOT NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_mmr_order (manufacturing_order_id),
  CONSTRAINT fk_mmr_order FOREIGN KEY (manufacturing_order_id) REFERENCES manufacturing_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产任务计划用料';

CREATE TABLE material_inventory_balances (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  warehouse_id BIGINT NOT NULL,
  material_name VARCHAR(300) NOT NULL,
  specification_key VARCHAR(500) NOT NULL DEFAULT '',
  unit VARCHAR(40) NOT NULL,
  quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_material_balance (warehouse_id,material_name(150),specification_key(150),unit),
  KEY idx_material_balance_name (material_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原材料仓库账面余额';

CREATE TABLE manufacturing_material_movements (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  movement_no VARCHAR(50) NOT NULL,
  manufacturing_order_id BIGINT NOT NULL,
  requirement_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  movement_type VARCHAR(20) NOT NULL,
  quantity DECIMAL(18,6) NOT NULL,
  quantity_change DECIMAL(18,6) NOT NULL,
  operator_employee_id BIGINT NULL,
  operator_name VARCHAR(100) NULL,
  occurred_at DATETIME NOT NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_mmm_no (movement_no),
  KEY idx_mmm_order (manufacturing_order_id,occurred_at),
  KEY idx_mmm_requirement (requirement_id),
  CONSTRAINT fk_mmm_order FOREIGN KEY (manufacturing_order_id) REFERENCES manufacturing_orders(id),
  CONSTRAINT fk_mmm_requirement FOREIGN KEY (requirement_id) REFERENCES manufacturing_material_requirements(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产领料与退料不可变流水';

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('manufacturing.material.manage','维护生产用料及办理领退料');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS','PRODUCTION_MANAGER','WAREHOUSE_MANAGER') AND p.permission_code='manufacturing.material.manage';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('PRODUCTION','WAREHOUSE') AND p.permission_code='manufacturing.material.manage';
