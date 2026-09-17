-- 正式回款收敛为「出库单内嵌收款流水」。
-- 业务实际（与氚云同构）：定金/尾款直接记录在销售出库单上，收钱日期可早于出库日；
-- 已收 = Σ该出库 POSTED 收款，未收自动派生，结清状态自动收口，登记即生效。
-- 下线「独立回款主单 + 分配 + 核销 + 结清事件」模型（相关表均为空表，无存量数据）。

DROP TABLE IF EXISTS formal_receipt_allocations;
DROP TABLE IF EXISTS formal_settlement_events;
DROP TABLE IF EXISTS formal_receipts;

CREATE TABLE formal_receipts (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  receipt_no VARCHAR(50) NOT NULL,
  shipment_id BIGINT NOT NULL,
  shipment_no_snapshot VARCHAR(50) NOT NULL,
  customer_id BIGINT NULL,
  customer_name_snapshot VARCHAR(300) NOT NULL,
  salesperson_id BIGINT NULL,
  salesperson_name_snapshot VARCHAR(100) NULL,
  payment_type VARCHAR(20) NOT NULL DEFAULT 'DEPOSIT',
  received_at DATETIME NOT NULL,
  receipt_amount DECIMAL(18,2) NOT NULL,
  payment_method VARCHAR(100) NULL,
  payer_name VARCHAR(200) NULL,
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
  KEY idx_fr_shipment (shipment_id),
  KEY idx_fr_status_date (status, received_at),
  KEY idx_fr_customer (customer_id),
  KEY idx_fr_salesperson (salesperson_id),
  CONSTRAINT fk_fr_shipment FOREIGN KEY (shipment_id) REFERENCES formal_sales_shipments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式出库单内嵌收款流水（DEPOSIT定金/BALANCE尾款/OTHER其他，登记即生效）';

-- 分配/核销、结清流水权限下线（无代码引用，清理注册表避免误导）
DELETE rp FROM system_role_permissions rp
  JOIN system_permissions p ON p.id = rp.permission_id
 WHERE p.permission_code IN ('formal_receipt.allocate','formal_settlement_event.read');
DELETE FROM system_permissions WHERE permission_code IN ('formal_receipt.allocate','formal_settlement_event.read');

-- 权限名称随模型收敛更新
UPDATE system_permissions SET permission_name='查看本人正式收款'
 WHERE permission_code='formal_receipt.read.self';
UPDATE system_permissions SET permission_name='查看本部门正式收款'
 WHERE permission_code='formal_receipt.read.department';
UPDATE system_permissions SET permission_name='查看全部正式收款'
 WHERE permission_code='formal_receipt.read.all';
UPDATE system_permissions SET permission_name='办理出库收款登记'
 WHERE permission_code='formal_receipt.manage';
UPDATE system_permissions SET permission_name='红冲正式收款'
 WHERE permission_code='formal_receipt.reverse';
