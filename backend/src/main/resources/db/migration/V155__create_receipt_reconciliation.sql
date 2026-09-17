-- 收款金额源数据对账结果表
-- 存储 receipts.receipt_amount 与 h3_import_rows.raw_data 中氚云原始金额的比对结果

CREATE TABLE IF NOT EXISTS receipt_reconciliation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_id      BIGINT       NOT NULL COMMENT '收款主单ID',
    receipt_no      VARCHAR(100)          COMMENT '收款单号（冗余便于查询）',
    receipt_type    VARCHAR(30)  NOT NULL COMMENT '收款类型：RECEIPT/SETTLEMENT',
    receipt_amount  DECIMAL(18,2)          COMMENT '系统中存储的收款金额',
    source_amount   DECIMAL(18,2)          COMMENT '氚云原始数据中的金额',
    diff_type       VARCHAR(50)  NOT NULL COMMENT '差异类型：AMOUNT_MISMATCH/MISSING_SOURCE/MISSING_FIELD',
    diff_detail     VARCHAR(500)           COMMENT '差异详情描述',
    severity        VARCHAR(20)  NOT NULL COMMENT '严重级别：ERROR/WARN',
    reconciled_at   DATETIME     NOT NULL COMMENT '对账时间',
    INDEX idx_rr_receipt (receipt_id),
    INDEX idx_rr_severity (severity),
    INDEX idx_rr_reconciled (reconciled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款金额源数据对账结果';
