CREATE TABLE settlement_receipt_candidates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reconciliation_id BIGINT NOT NULL,
    receipt_id BIGINT NOT NULL,
    sales_outbound_id BIGINT NOT NULL,
    suggested_amount DECIMAL(18,2) NOT NULL,
    confidence INT NOT NULL DEFAULT 0,
    match_reason VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    decided_by VARCHAR(100) NULL,
    decided_at DATETIME NULL,
    feedback_notes VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_src_reconciliation_receipt (reconciliation_id,receipt_id),
    KEY idx_src_status (status),
    KEY idx_src_receipt (receipt_id),
    KEY idx_src_outbound (sales_outbound_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结清快照缺正式回款时的未分配收款推荐候选';
