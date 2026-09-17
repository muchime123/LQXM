CREATE TABLE sales_outbound_acceptance_reviews (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sales_outbound_id BIGINT NOT NULL,
    outbound_no_snapshot VARCHAR(100) NOT NULL,
    customer_name_snapshot VARCHAR(300) NULL,
    sales_amount_snapshot DECIMAL(14,2) NOT NULL DEFAULT 0,
    purchase_amount_snapshot DECIMAL(14,2) NOT NULL DEFAULT 0,
    received_amount_snapshot DECIMAL(14,2) NOT NULL DEFAULT 0,
    outstanding_amount_snapshot DECIMAL(14,2) NOT NULL DEFAULT 0,
    expense_amount_snapshot DECIMAL(14,2) NOT NULL DEFAULT 0,
    accounting_profit_snapshot DECIMAL(14,2) NOT NULL DEFAULT 0,
    theoretical_commission_snapshot DECIMAL(14,2) NOT NULL DEFAULT 0,
    sales_checked TINYINT(1) NULL,
    purchase_checked TINYINT(1) NULL,
    receipt_checked TINYINT(1) NULL,
    expense_checked TINYINT(1) NULL,
    profit_checked TINYINT(1) NULL,
    commission_checked TINYINT(1) NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PASSED/DIFFERENCE',
    difference_note VARCHAR(1000) NULL,
    reviewed_by VARCHAR(100) NULL,
    reviewed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_soar_outbound (sales_outbound_id),
    KEY idx_soar_status (review_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='销售出库利润财务验收记录';

INSERT IGNORE INTO system_permissions(permission_code, permission_name)
VALUES ('order_acceptance.manage', '管理销售订单财务验收');

INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM system_roles r CROSS JOIN system_permissions p
WHERE r.role_code IN ('ADMIN','BOSS','FINANCE') AND p.permission_code='order_acceptance.manage';
