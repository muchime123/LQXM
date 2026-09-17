-- 正式回款域：与旧轨 receipts / receipt_allocations 完全分离，仅挂接 formal_sales_shipments。
-- 旧轨 sales_outbounds/receipts 仍由氚云导入台账与月度提成核算消费，本域不影响。
CREATE TABLE formal_receipts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  receipt_no VARCHAR(50) NOT NULL,
  customer_id BIGINT NULL,
  customer_name_snapshot VARCHAR(300) NOT NULL,
  salesperson_id BIGINT NULL,
  salesperson_name_snapshot VARCHAR(100) NULL,
  receipt_type VARCHAR(20) NOT NULL DEFAULT 'SINGLE',
  received_at DATETIME NOT NULL,
  receipt_amount DECIMAL(18,2) NOT NULL,
  allocated_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  unallocated_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  payer_name VARCHAR(200) NULL,
  payer_bank VARCHAR(200) NULL,
  our_account_no VARCHAR(100) NULL,
  external_ref VARCHAR(200) NULL,
  remark VARCHAR(1000) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'POSTED',
  reversed_at DATETIME NULL,
  reversed_by_employee_id BIGINT NULL,
  reversed_by_name VARCHAR(100) NULL,
  reversal_reason VARCHAR(500) NULL,
  created_by_employee_id BIGINT NULL,
  created_by_name VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_formal_receipt_no (receipt_no),
  KEY idx_formal_receipt_customer (customer_id),
  KEY idx_formal_receipt_salesperson (salesperson_id),
  KEY idx_formal_receipt_status (status, received_at),
  KEY idx_formal_receipt_date (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式回款主单（本地轨，SINGLE=单出库回款/MULTI=多出库结清）';

CREATE TABLE formal_receipt_allocations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  receipt_id BIGINT NOT NULL,
  shipment_id BIGINT NOT NULL,
  allocated_amount DECIMAL(18,2) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  shipment_no_snapshot VARCHAR(50) NOT NULL,
  customer_name_snapshot VARCHAR(300) NOT NULL,
  sales_amount_snapshot DECIMAL(18,2) NOT NULL DEFAULT 0,
  received_before_snapshot DECIMAL(18,2) NOT NULL DEFAULT 0,
  outstanding_before_snapshot DECIMAL(18,2) NOT NULL DEFAULT 0,
  confirmed_at DATETIME NULL,
  confirmed_by_employee_id BIGINT NULL,
  confirmed_by_name VARCHAR(100) NULL,
  reversed_at DATETIME NULL,
  reversed_by_employee_id BIGINT NULL,
  reversed_by_name VARCHAR(100) NULL,
  reversal_reason VARCHAR(500) NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_fra_receipt_shipment (receipt_id, shipment_id),
  KEY idx_fra_shipment (shipment_id),
  KEY idx_fra_status (status),
  CONSTRAINT fk_fra_receipt FOREIGN KEY (receipt_id) REFERENCES formal_receipts(id),
  CONSTRAINT fk_fra_shipment FOREIGN KEY (shipment_id) REFERENCES formal_sales_shipments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式回款→正式出库分配（PENDING待核销/CONFIRMED已核销/REVERSED已撤销）';

CREATE TABLE formal_settlement_events (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_type VARCHAR(20) NOT NULL,
  receipt_id BIGINT NULL,
  receipt_no VARCHAR(50) NULL,
  receipt_allocation_id BIGINT NULL,
  shipment_id BIGINT NULL,
  shipment_no VARCHAR(50) NULL,
  amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  shipment_received_before DECIMAL(18,2) NULL,
  shipment_received_after DECIMAL(18,2) NULL,
  shipment_settlement_status_before VARCHAR(20) NULL,
  shipment_settlement_status_after VARCHAR(20) NULL,
  operator_employee_id BIGINT NULL,
  operator_name VARCHAR(100) NULL,
  occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) NULL,
  KEY idx_fse_shipment (shipment_id),
  KEY idx_fse_receipt (receipt_id),
  KEY idx_fse_occurred (occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式出库结清事件流水（每次分配/核销/撤销追加一条）';

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('formal_receipt.read.self','查看本人正式回款'),
 ('formal_receipt.read.department','查看本部门正式回款'),
 ('formal_receipt.read.all','查看全部正式回款'),
 ('formal_receipt.manage','办理正式回款'),
 ('formal_receipt.allocate','分配与核销正式回款'),
 ('formal_receipt.reverse','红冲正式回款'),
 ('formal_settlement_event.read','查看正式出库结清流水');

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS') AND p.permission_code LIKE 'formal_receipt.%';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS') AND p.permission_code='formal_settlement_event.read';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='SALES_MANAGER'
   AND p.permission_code IN ('formal_receipt.read.department','formal_receipt.manage','formal_receipt.allocate','formal_settlement_event.read');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='SALESPERSON'
   AND p.permission_code IN ('formal_receipt.read.self','formal_receipt.manage','formal_settlement_event.read');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='FINANCE'
   AND p.permission_code IN ('formal_receipt.read.all','formal_receipt.manage','formal_receipt.allocate','formal_receipt.reverse','formal_settlement_event.read');
