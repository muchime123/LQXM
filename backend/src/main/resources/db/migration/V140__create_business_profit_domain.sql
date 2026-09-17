-- P6.4 最终业务利润：以 business_order 为核算主体，保留可审计快照与人工调整。
CREATE TABLE business_profit_adjustments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    business_order_id BIGINT NOT NULL,
    adjustment_type VARCHAR(30) NOT NULL DEFAULT 'OTHER_COST',
    amount DECIMAL(14,2) NOT NULL,
    affects_commission TINYINT(1) NOT NULL DEFAULT 1,
    description VARCHAR(500) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_bpa_business FOREIGN KEY (business_order_id) REFERENCES business_orders(id) ON DELETE CASCADE,
    CONSTRAINT chk_bpa_status CHECK (status IN ('ACTIVE','REVERSED')),
    KEY idx_bpa_business (business_order_id), KEY idx_bpa_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务利润人工调整';

CREATE TABLE business_profit_snapshots (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    business_order_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    sales_revenue DECIMAL(14,2) NOT NULL DEFAULT 0,
    direct_product_cost DECIMAL(14,2) NOT NULL DEFAULT 0,
    direct_gross_profit DECIMAL(14,2) NOT NULL DEFAULT 0,
    allocated_business_expense DECIMAL(14,2) NOT NULL DEFAULT 0,
    other_adjustments DECIMAL(14,2) NOT NULL DEFAULT 0,
    accounting_profit DECIMAL(14,2) NOT NULL DEFAULT 0,
    non_commissionable_amount DECIMAL(14,2) NOT NULL DEFAULT 0,
    commissionable_profit DECIMAL(14,2) NOT NULL DEFAULT 0,
    calculation_status VARCHAR(20) NOT NULL DEFAULT 'CALCULATED',
    calculation_note VARCHAR(500) NULL,
    calculated_by VARCHAR(100) NULL,
    calculated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_current TINYINT(1) NOT NULL DEFAULT 1,
    CONSTRAINT fk_bps_business FOREIGN KEY (business_order_id) REFERENCES business_orders(id) ON DELETE CASCADE,
    UNIQUE KEY uk_bps_version (business_order_id,version_no),
    KEY idx_bps_current (business_order_id,is_current), KEY idx_bps_status (calculation_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务利润核算历史快照';
