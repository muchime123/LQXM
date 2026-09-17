-- 四类事实关联质量审计表
-- 用于定时巡检生产任务↔销售出库、收款↔出库分配的关联质量，发现缺口、不一致和重复

CREATE TABLE business_relation_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    audit_batch_no VARCHAR(40) NOT NULL,
    audit_dimension VARCHAR(60) NOT NULL COMMENT '审计维度（PRODUCTION_OUTBOUND_LINK / RECEIPT_OUTBOUND_LINK / RECEIPT_AMOUNT_CONSISTENCY / BUSINESS_MATCH_STATUS / DUPLICATE_RELATION / ORPHAN_BUSINESS）',
    metric_key VARCHAR(80) NOT NULL COMMENT '指标键（如 production_total / outbound_total / linked_count / amount_mismatch_count）',
    metric_value BIGINT NOT NULL DEFAULT 0 COMMENT '指标数值',
    metric_detail TEXT NULL COMMENT '补充说明（如样本编号、缺失字段等）',
    severity VARCHAR(20) NOT NULL DEFAULT 'INFO' COMMENT '严重程度：INFO/WARN/ERROR',
    audited_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_bra_batch (audit_batch_no),
    KEY idx_bra_dimension (audit_dimension),
    KEY idx_bra_severity (severity)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='四类事实关联质量审计结果';

CREATE TABLE business_relation_audit_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    audit_batch_no VARCHAR(40) NOT NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/FAILED',
    total_metrics INT NOT NULL DEFAULT 0,
    error_count INT NOT NULL DEFAULT 0,
    warn_count INT NOT NULL DEFAULT 0,
    triggered_by VARCHAR(100) NULL COMMENT '触发方（SCHEDULER/MANUAL/{user}）',
    error_message TEXT NULL,
    UNIQUE KEY uk_brar_batch (audit_batch_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计任务运行记录';
