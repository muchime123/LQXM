CREATE TABLE settlement_exception_reviews (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reconciliation_id BIGINT NOT NULL,
    review_conclusion VARCHAR(40) NOT NULL,
    review_status VARCHAR(20) NOT NULL DEFAULT 'RESOLVED',
    review_notes VARCHAR(1000) NULL,
    reviewed_by VARCHAR(100) NOT NULL,
    reviewed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ser_reconciliation (reconciliation_id),
    KEY idx_ser_conclusion (review_conclusion),
    KEY idx_ser_status (review_status),
    KEY idx_ser_reviewed_at (reviewed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='结清快照缺正式收款异常的财务真实性复核结论';
