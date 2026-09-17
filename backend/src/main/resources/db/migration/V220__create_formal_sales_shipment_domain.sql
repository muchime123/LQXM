-- Formal outbound documents for custom finished-goods lots. H3 sales_outbounds remains an imported financial fact ledger.
CREATE TABLE formal_sales_shipments (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  shipment_no VARCHAR(50) NOT NULL,
  sales_order_ref VARCHAR(100) NULL,
  customer_name_snapshot VARCHAR(300) NOT NULL,
  salesperson_id BIGINT NULL,
  salesperson_name_snapshot VARCHAR(100) NULL,
  warehouse_id BIGINT NOT NULL,
  shipped_at DATETIME NOT NULL,
  receiver_name VARCHAR(100) NULL,
  receiver_phone VARCHAR(100) NULL,
  shipping_address VARCHAR(1000) NULL,
  shipping_method VARCHAR(100) NULL,
  tracking_no VARCHAR(200) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'POSTED',
  remark VARCHAR(1000) NULL,
  created_by_employee_id BIGINT NULL,
  created_by_name VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_formal_shipment_no (shipment_no),
  KEY idx_formal_shipment_order (sales_order_ref),
  KEY idx_formal_shipment_salesperson (salesperson_id),
  KEY idx_formal_shipment_date (shipped_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地正式销售出库单';

CREATE TABLE formal_sales_shipment_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  shipment_id BIGINT NOT NULL,
  finished_goods_lot_id BIGINT NOT NULL,
  lot_no_snapshot VARCHAR(50) NOT NULL,
  finished_goods_name_snapshot VARCHAR(500) NOT NULL,
  specification_snapshot VARCHAR(1000) NULL,
  quantity DECIMAL(18,6) NOT NULL,
  unit VARCHAR(40) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_formal_shipment_lot (shipment_id,finished_goods_lot_id),
  KEY idx_formal_shipment_item_lot (finished_goods_lot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式销售出库成品批次分配';

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('sales_shipment.read.self','查看本人正式销售出库'),
 ('sales_shipment.read.department','查看本部门正式销售出库'),
 ('sales_shipment.read.all','查看全部正式销售出库'),
 ('sales_shipment.manage','办理正式销售出库');

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS') AND p.permission_code LIKE 'sales_shipment.%';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='SALES_MANAGER' AND p.permission_code IN ('sales_shipment.read.department','sales_shipment.manage');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='SALESPERSON' AND p.permission_code IN ('sales_shipment.read.self','sales_shipment.manage');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('WAREHOUSE','WAREHOUSE_MANAGER')
   AND p.permission_code IN ('sales_shipment.read.all','sales_shipment.manage');
