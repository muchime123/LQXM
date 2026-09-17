-- 正式出库金额结转 + 结清状态 + 正式轨利润快照。
-- 不触碰 sales_outbounds / receipts / business_profit_snapshots / business_commission_snapshots（旧轨）。

-- --------------------------------------------------------------------
-- 1. formal_sales_shipments 金额列 + 结清状态（派生维护：FormalReceiptService 核销/撤销时刷新）
-- --------------------------------------------------------------------
ALTER TABLE formal_sales_shipments
  ADD COLUMN sales_amount DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '销售总额（=SUM(shipment_items.line_amount)，出库时结转）' AFTER formal_sales_order_id,
  ADD COLUMN cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '成本总额（待成本结转，当前为0）' AFTER sales_amount,
  ADD COLUMN gross_profit DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '毛利=sales_amount-cost_amount' AFTER cost_amount,
  ADD COLUMN received_amount DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '已收（CONFIRMED 分配合计）' AFTER gross_profit,
  ADD COLUMN outstanding_amount DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '未收=sales_amount-received_amount（下限0）' AFTER received_amount,
  ADD COLUMN settlement_status VARCHAR(20) NOT NULL DEFAULT 'UNSETTLED'
    COMMENT '结清状态：UNSETTLED=未结清/PARTIAL=部分结清/SETTLED=已结清' AFTER outstanding_amount;

CREATE INDEX idx_formal_shipment_settlement ON formal_sales_shipments (settlement_status);

-- --------------------------------------------------------------------
-- 2. formal_sales_shipment_items 行级金额快照（出库时从 formal_sales_order_items.unit_price 结转）
-- --------------------------------------------------------------------
ALTER TABLE formal_sales_shipment_items
  ADD COLUMN unit_price_snapshot DECIMAL(18,4) NOT NULL DEFAULT 0
    COMMENT '出库时点单价快照（取自订单明细 unit_price，无订单关联为0）' AFTER quantity,
  ADD COLUMN line_amount DECIMAL(18,2) NOT NULL DEFAULT 0
    COMMENT '本行金额=unit_price_snapshot×quantity（4舍5入2位）' AFTER unit_price_snapshot;

-- --------------------------------------------------------------------
-- 3. formal_profit_snapshots 正式轨利润快照（按出库单维度、版本化）
--    calculation_version 单调递增；is_current=1 为当前版本。
-- --------------------------------------------------------------------
CREATE TABLE formal_profit_snapshots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  shipment_id BIGINT NOT NULL,
  shipment_no VARCHAR(50) NOT NULL,
  customer_name_snapshot VARCHAR(300) NOT NULL,
  salesperson_id BIGINT NULL,
  salesperson_name_snapshot VARCHAR(100) NULL,
  shipped_at DATETIME NOT NULL,
  sales_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  cost_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  gross_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
  expense_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  adjustment_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  net_profit DECIMAL(18,2) NOT NULL DEFAULT 0,
  received_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  settlement_status VARCHAR(20) NOT NULL DEFAULT 'UNSETTLED',
  calculation_version INT NOT NULL DEFAULT 1,
  is_current TINYINT(1) NOT NULL DEFAULT 1,
  calculated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  calculated_by_employee_id BIGINT NULL,
  calculated_by_name VARCHAR(100) NULL,
  remark VARCHAR(1000) NULL,
  UNIQUE KEY uk_fps_shipment_version (shipment_id, calculation_version),
  KEY idx_fps_current (is_current, shipped_at),
  KEY idx_fps_salesperson (salesperson_id, shipped_at),
  CONSTRAINT fk_fps_shipment FOREIGN KEY (shipment_id) REFERENCES formal_sales_shipments(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式轨利润快照（净利=sales-cost-expense+adjustment，历史版本可追溯）';

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('formal_profit.read.self','查看本人正式利润'),
 ('formal_profit.read.department','查看本部门正式利润'),
 ('formal_profit.read.all','查看全部正式利润'),
 ('formal_profit.manage','维护正式利润调整');

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS') AND p.permission_code LIKE 'formal_profit.%';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='FINANCE' AND p.permission_code LIKE 'formal_profit.%';
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='SALES_MANAGER' AND p.permission_code IN ('formal_profit.read.department');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='SALESPERSON' AND p.permission_code='formal_profit.read.self';
