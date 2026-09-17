CREATE TABLE formal_sales_orders (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  order_no VARCHAR(50) NOT NULL,
  customer_id BIGINT NOT NULL,
  customer_name_snapshot VARCHAR(300) NOT NULL,
  salesperson_id BIGINT NULL,
  salesperson_name_snapshot VARCHAR(100) NULL,
  order_date DATE NOT NULL,
  delivery_date DATE NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
  currency VARCHAR(10) NOT NULL DEFAULT 'CNY',
  total_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  receiver_name VARCHAR(100) NULL,
  receiver_phone VARCHAR(100) NULL,
  shipping_address VARCHAR(1000) NULL,
  remark VARCHAR(1000) NULL,
  created_by_employee_id BIGINT NULL,
  created_by_name VARCHAR(100) NULL,
  confirmed_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_formal_sales_order_no (order_no),
  KEY idx_formal_sales_order_customer (customer_id),
  KEY idx_formal_sales_order_salesperson (salesperson_id),
  KEY idx_formal_sales_order_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地正式销售订单';

CREATE TABLE formal_sales_order_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sales_order_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  product_name VARCHAR(500) NOT NULL,
  specification VARCHAR(1000) NULL,
  custom_requirement LONGTEXT NULL,
  quantity DECIMAL(18,6) NOT NULL,
  unit VARCHAR(40) NOT NULL,
  unit_price DECIMAL(18,4) NOT NULL DEFAULT 0,
  line_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  production_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  shipped_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  remark VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_formal_sales_order_line (sales_order_id,line_no),
  KEY idx_formal_sales_order_item_order (sales_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式销售订单非标明细';

ALTER TABLE manufacturing_orders
  ADD COLUMN formal_sales_order_id BIGINT NULL AFTER sales_order_ref,
  ADD COLUMN formal_sales_order_item_id BIGINT NULL AFTER formal_sales_order_id,
  ADD KEY idx_manufacturing_formal_order (formal_sales_order_id),
  ADD KEY idx_manufacturing_formal_item (formal_sales_order_item_id);
ALTER TABLE finished_goods_lots
  ADD COLUMN formal_sales_order_id BIGINT NULL AFTER sales_order_ref,
  ADD COLUMN formal_sales_order_item_id BIGINT NULL AFTER formal_sales_order_id,
  ADD KEY idx_finished_goods_formal_order (formal_sales_order_id),
  ADD KEY idx_finished_goods_formal_item (formal_sales_order_item_id);
ALTER TABLE formal_sales_shipments
  ADD COLUMN formal_sales_order_id BIGINT NULL AFTER sales_order_ref,
  ADD KEY idx_formal_shipment_formal_order (formal_sales_order_id);
ALTER TABLE formal_sales_shipment_items
  ADD COLUMN formal_sales_order_item_id BIGINT NULL AFTER finished_goods_lot_id,
  ADD KEY idx_formal_shipment_formal_item (formal_sales_order_item_id);

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('formal_sales_order.read.self','查看本人正式销售订单'),
 ('formal_sales_order.read.department','查看本部门正式销售订单'),
 ('formal_sales_order.read.all','查看全部正式销售订单'),
 ('formal_sales_order.manage','创建和确认正式销售订单');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS') AND p.permission_code LIKE 'formal_sales_order.%';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='SALES_MANAGER' AND p.permission_code IN ('formal_sales_order.read.department','formal_sales_order.manage');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='SALESPERSON' AND p.permission_code IN ('formal_sales_order.read.self','formal_sales_order.manage');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('PRODUCTION','PRODUCTION_MANAGER','WAREHOUSE','WAREHOUSE_MANAGER')
   AND p.permission_code='formal_sales_order.read.all';
