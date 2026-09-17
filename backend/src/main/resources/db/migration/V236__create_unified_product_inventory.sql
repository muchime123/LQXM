ALTER TABLE formal_sales_order_items ADD COLUMN sales_product_id BIGINT NULL AFTER line_no, ADD KEY idx_fsoi_sales_product(sales_product_id);
ALTER TABLE manufacturing_orders ADD COLUMN sales_product_id BIGINT NULL AFTER product_name_snapshot, ADD KEY idx_mo_sales_product(sales_product_id);
ALTER TABLE finished_goods_lots ADD COLUMN sales_product_id BIGINT NULL AFTER formal_sales_order_item_id, ADD KEY idx_fgl_sales_product(sales_product_id);

INSERT INTO sales_products(product_code,product_name,specification,unit,source,status,remark)
SELECT CONCAT('MFG-',m.order_no),m.product_name_snapshot,m.specification_snapshot,m.unit,'MANUFACTURING','PENDING_REVIEW','由已有正式生产任务生成的交易产品身份'
FROM manufacturing_orders m LEFT JOIN sales_products p ON p.product_code=CONCAT('MFG-',m.order_no)
WHERE p.id IS NULL;

UPDATE manufacturing_orders m JOIN sales_products p ON p.product_code=CONCAT('MFG-',m.order_no)
SET m.sales_product_id=p.id WHERE m.sales_product_id IS NULL;
UPDATE finished_goods_lots l JOIN manufacturing_orders m ON m.id=l.manufacturing_order_id
SET l.sales_product_id=m.sales_product_id WHERE l.sales_product_id IS NULL;
UPDATE formal_sales_order_items i JOIN manufacturing_orders m ON m.formal_sales_order_item_id=i.id
SET i.sales_product_id=m.sales_product_id WHERE i.sales_product_id IS NULL;

CREATE TABLE product_inventory_balances (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  warehouse_id BIGINT NOT NULL,
  sales_product_id BIGINT NOT NULL,
  unit VARCHAR(40) NOT NULL,
  quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_pib_warehouse_product_unit(warehouse_id,sales_product_id,unit),
  KEY idx_pib_product(sales_product_id),
  CONSTRAINT fk_pib_warehouse FOREIGN KEY(warehouse_id) REFERENCES warehouses(id),
  CONSTRAINT fk_pib_product FOREIGN KEY(sales_product_id) REFERENCES sales_products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一产品库存余额缓存';

CREATE TABLE product_inventory_transactions (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  transaction_no VARCHAR(50) NOT NULL,
  warehouse_id BIGINT NOT NULL,
  sales_product_id BIGINT NOT NULL,
  finished_goods_lot_id BIGINT NULL,
  product_name_snapshot VARCHAR(500) NOT NULL,
  specification_snapshot VARCHAR(1000) NULL,
  unit VARCHAR(40) NOT NULL,
  transaction_type VARCHAR(40) NOT NULL,
  quantity_change DECIMAL(18,6) NOT NULL,
  balance_after DECIMAL(18,6) NOT NULL,
  source_type VARCHAR(50) NOT NULL,
  source_id BIGINT NOT NULL,
  source_no VARCHAR(50) NOT NULL,
  operator_employee_id BIGINT NULL,
  operator_name VARCHAR(100) NULL,
  occurred_at DATETIME NOT NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_pit_no(transaction_no),
  UNIQUE KEY uk_pit_source(source_type,source_id,finished_goods_lot_id),
  KEY idx_pit_product(warehouse_id,sales_product_id,occurred_at),
  KEY idx_pit_lot(finished_goods_lot_id),
  CONSTRAINT fk_pit_warehouse FOREIGN KEY(warehouse_id) REFERENCES warehouses(id),
  CONSTRAINT fk_pit_product FOREIGN KEY(sales_product_id) REFERENCES sales_products(id),
  CONSTRAINT fk_pit_lot FOREIGN KEY(finished_goods_lot_id) REFERENCES finished_goods_lots(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一产品库存不可变流水';

INSERT INTO product_inventory_balances(warehouse_id,sales_product_id,unit,quantity)
SELECT warehouse_id,sales_product_id,unit,SUM(available_quantity) FROM finished_goods_lots
WHERE sales_product_id IS NOT NULL AND status<>'REVERSED'
GROUP BY warehouse_id,sales_product_id,unit;

INSERT INTO product_inventory_transactions(transaction_no,warehouse_id,sales_product_id,finished_goods_lot_id,product_name_snapshot,
 specification_snapshot,unit,transaction_type,quantity_change,balance_after,source_type,source_id,source_no,occurred_at,remark)
SELECT CONCAT('PIM',LPAD(l.id,8,'0')),l.warehouse_id,l.sales_product_id,l.id,l.finished_goods_name,l.specification_snapshot,l.unit,
 'MIGRATION_OPENING',l.available_quantity,l.available_quantity,'FINISHED_GOODS_MIGRATION',l.id,l.lot_no,l.created_at,'V236按现有成品批次可用数量建立统一库存基线'
FROM finished_goods_lots l WHERE l.sales_product_id IS NOT NULL AND l.status<>'REVERSED' AND l.available_quantity<>0;

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('product_inventory.read','查看统一产品库存'),('product_inventory.manage','维护产品库存业务');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS','PRODUCTION_MANAGER','WAREHOUSE_MANAGER') AND p.permission_code IN ('product_inventory.read','product_inventory.manage');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('PRODUCTION','WAREHOUSE','SALESPERSON','SALES_MANAGER') AND p.permission_code='product_inventory.read';
