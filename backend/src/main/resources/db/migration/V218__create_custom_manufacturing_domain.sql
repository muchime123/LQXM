-- Formal local manufacturing domain. Legacy production_orders remains read-only H3 ledger.
CREATE TABLE warehouses (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  warehouse_code VARCHAR(40) NOT NULL,
  warehouse_name VARCHAR(100) NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_warehouse_code (warehouse_code),
  UNIQUE KEY uk_warehouse_name (warehouse_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式仓库主数据';

INSERT INTO warehouses(warehouse_code,warehouse_name) VALUES
 ('WENZHOU','温州仓'),('DROPSHIP','代发仓'),('ANHUI','安徽仓');

CREATE TABLE manufacturing_orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(50) NOT NULL,
  sales_order_ref VARCHAR(100) NULL,
  customer_id BIGINT NULL,
  customer_name_snapshot VARCHAR(300) NOT NULL,
  salesperson_id BIGINT NULL,
  salesperson_name_snapshot VARCHAR(100) NULL,
  product_name_snapshot VARCHAR(500) NOT NULL,
  specification_snapshot VARCHAR(1000) NULL,
  custom_requirement LONGTEXT NULL,
  planned_quantity DECIMAL(18,6) NOT NULL,
  completed_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  unit VARCHAR(40) NOT NULL,
  due_date DATE NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'RELEASED',
  remark VARCHAR(1000) NULL,
  created_by_employee_id BIGINT NULL,
  created_by_name VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_manufacturing_order_no (order_no),
  KEY idx_manufacturing_sales_ref (sales_order_ref),
  KEY idx_manufacturing_salesperson (salesperson_id),
  KEY idx_manufacturing_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地正式生产任务';

CREATE TABLE manufacturing_completion_receipts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  receipt_no VARCHAR(50) NOT NULL,
  manufacturing_order_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  completed_at DATETIME NOT NULL,
  quantity DECIMAL(18,6) NOT NULL,
  unit VARCHAR(40) NOT NULL,
  finished_goods_name VARCHAR(500) NOT NULL,
  specification_snapshot VARCHAR(1000) NULL,
  operator_employee_id BIGINT NULL,
  operator_name VARCHAR(100) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'POSTED',
  remark VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_completion_receipt_no (receipt_no),
  KEY idx_completion_order (manufacturing_order_id),
  KEY idx_completion_warehouse (warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产完工入库单';

CREATE TABLE finished_goods_lots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  lot_no VARCHAR(50) NOT NULL,
  manufacturing_order_id BIGINT NOT NULL,
  completion_receipt_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  sales_order_ref VARCHAR(100) NULL,
  customer_name_snapshot VARCHAR(300) NOT NULL,
  salesperson_id BIGINT NULL,
  salesperson_name_snapshot VARCHAR(100) NULL,
  finished_goods_name VARCHAR(500) NOT NULL,
  specification_snapshot VARCHAR(1000) NULL,
  unit VARCHAR(40) NOT NULL,
  received_quantity DECIMAL(18,6) NOT NULL,
  available_quantity DECIMAL(18,6) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_finished_goods_lot_no (lot_no),
  KEY idx_finished_goods_order (manufacturing_order_id),
  KEY idx_finished_goods_available (warehouse_id,status),
  KEY idx_finished_goods_salesperson (salesperson_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按单生产的成品批次';

CREATE TABLE finished_goods_movements (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  lot_id BIGINT NOT NULL,
  warehouse_id BIGINT NOT NULL,
  movement_type VARCHAR(30) NOT NULL,
  quantity_change DECIMAL(18,6) NOT NULL,
  source_type VARCHAR(30) NOT NULL,
  source_id BIGINT NOT NULL,
  source_no VARCHAR(50) NOT NULL,
  operator_employee_id BIGINT NULL,
  operator_name VARCHAR(100) NULL,
  occurred_at DATETIME NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_finished_goods_movement_source (source_type,source_id,lot_id),
  KEY idx_finished_goods_movement_lot (lot_id),
  KEY idx_finished_goods_movement_warehouse (warehouse_id,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='定制成品不可变库存流水';

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('manufacturing.read.self','查看本人生产任务'),
 ('manufacturing.read.department','查看本部门生产任务'),
 ('manufacturing.read.all','查看全部生产任务'),
 ('manufacturing.manage','创建正式生产任务'),
 ('manufacturing.receive','办理生产完工入库');

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS') AND p.permission_code LIKE 'manufacturing.%';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='SALES_MANAGER' AND p.permission_code IN ('manufacturing.read.department','manufacturing.manage');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='SALESPERSON' AND p.permission_code IN ('manufacturing.read.self','manufacturing.manage');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('PRODUCTION','PRODUCTION_MANAGER','WAREHOUSE','WAREHOUSE_MANAGER')
   AND p.permission_code IN ('manufacturing.read.all','manufacturing.receive');
