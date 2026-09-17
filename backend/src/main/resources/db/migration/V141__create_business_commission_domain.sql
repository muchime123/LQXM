-- P6.5 最终业务提成：BusinessProfit 快照 × 回款比例 × Employee 规则。
CREATE TABLE business_commission_rules (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, rule_name VARCHAR(100) NOT NULL,
    employee_id BIGINT NULL COMMENT 'NULL=全体业务员默认规则',
    commission_rate DECIMAL(7,4) NOT NULL, receipt_threshold_pct DECIMAL(7,4) NOT NULL DEFAULT 100,
    effective_from DATE NOT NULL, effective_to DATE NULL, priority INT NOT NULL DEFAULT 100,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', description VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_bcr_employee FOREIGN KEY(employee_id) REFERENCES employees(id) ON DELETE SET NULL,
    KEY idx_bcr_employee(employee_id), KEY idx_bcr_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务提成规则 V2';

CREATE TABLE business_commission_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, business_order_id BIGINT NOT NULL,
    business_profit_snapshot_id BIGINT NOT NULL, employee_id BIGINT NULL,
    salesperson_name_snapshot VARCHAR(100) NULL, rule_id BIGINT NULL, rule_name_snapshot VARCHAR(100) NULL,
    commissionable_profit DECIMAL(14,2) NOT NULL DEFAULT 0, sales_revenue DECIMAL(14,2) NOT NULL DEFAULT 0,
    received_amount DECIMAL(14,2) NOT NULL DEFAULT 0, receipt_ratio_pct DECIMAL(9,4) NOT NULL DEFAULT 0,
    commission_rate DECIMAL(7,4) NOT NULL DEFAULT 0, receipt_threshold_pct DECIMAL(7,4) NOT NULL DEFAULT 100,
    estimated_commission DECIMAL(14,2) NOT NULL DEFAULT 0, available_commission DECIMAL(14,2) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ESTIMATED', settlement_id BIGINT NULL,
    calculated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, settled_at DATETIME NULL, is_current TINYINT(1) NOT NULL DEFAULT 1,
    CONSTRAINT fk_bcs_business FOREIGN KEY(business_order_id) REFERENCES business_orders(id) ON DELETE CASCADE,
    CONSTRAINT fk_bcs_profit FOREIGN KEY(business_profit_snapshot_id) REFERENCES business_profit_snapshots(id),
    CONSTRAINT fk_bcs_employee FOREIGN KEY(employee_id) REFERENCES employees(id) ON DELETE SET NULL,
    KEY idx_bcs_current(business_order_id,is_current), KEY idx_bcs_employee(employee_id), KEY idx_bcs_status(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务提成计算快照 V2';

CREATE TABLE business_commission_settlements (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, settlement_no VARCHAR(40) NOT NULL, period VARCHAR(7) NOT NULL,
    employee_id BIGINT NOT NULL, employee_name_snapshot VARCHAR(100) NOT NULL,
    business_count INT NOT NULL DEFAULT 0, total_commissionable_profit DECIMAL(14,2) NOT NULL DEFAULT 0,
    total_commission DECIMAL(14,2) NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    confirmed_by VARCHAR(100) NULL, confirmed_at DATETIME NULL, paid_by VARCHAR(100) NULL, paid_at DATETIME NULL,
    payment_note VARCHAR(500) NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bcst_no(settlement_no), KEY idx_bcst_period(period), KEY idx_bcst_employee(employee_id),
    CONSTRAINT fk_bcst_employee FOREIGN KEY(employee_id) REFERENCES employees(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务提成结算单 V2';

INSERT INTO business_commission_rules(rule_name,employee_id,commission_rate,receipt_threshold_pct,effective_from,priority,status,description)
VALUES('全员默认提成规则',NULL,10.0000,100.0000,'2026-01-01',999,'ACTIVE','默认按可计提利润的10%计算；回款达到100%后可结算。');
