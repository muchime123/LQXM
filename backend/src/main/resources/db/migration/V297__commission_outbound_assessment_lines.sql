-- 核算时逐单冻结氚云销售出库的未收款考核依据；补充核算仍记录当次完整期间快照。
CREATE TABLE commission_outbound_assessment_lines (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    version_id BIGINT NOT NULL,
    outbound_id BIGINT NOT NULL,
    outbound_no VARCHAR(100) NULL,
    customer_name VARCHAR(255) NULL,
    employee_id BIGINT NOT NULL,
    employee_name VARCHAR(100) NOT NULL,
    attribution_date DATE NOT NULL,
    sales_amount DECIMAL(16,2) NOT NULL DEFAULT 0,
    received_amount DECIMAL(16,2) NOT NULL DEFAULT 0,
    outstanding_amount DECIMAL(16,2) NOT NULL DEFAULT 0,
    payment_status VARCHAR(100) NULL,
    channel_type VARCHAR(20) NOT NULL,
    captured_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_coal_version_outbound (version_id, outbound_id),
    KEY idx_coal_version_employee (version_id, employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='提成核算版本的逐张销售出库未收款考核快照';
