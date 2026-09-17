-- 氚云销售订单导入可追溯化：来源标识、内容指纹、同步时间及导入批次/原始快照。

ALTER TABLE sales_contracts
    ADD COLUMN external_source VARCHAR(30) NOT NULL DEFAULT 'MANUAL' COMMENT '外部来源：H3YUN/MANUAL' AFTER order_type,
    ADD COLUMN external_id VARCHAR(100) NULL COMMENT '外部业务唯一标识；Excel 阶段使用订单编号' AFTER external_source,
    ADD COLUMN source_hash CHAR(64) NULL COMMENT '标准化导入内容 SHA-256，用于判断是否变化' AFTER external_id,
    ADD COLUMN last_synced_at DATETIME NULL COMMENT '最后一次从外部数据源同步时间' AFTER source_hash,
    ADD KEY idx_sc_external_identity (external_source, external_id),
    ADD KEY idx_sc_last_synced_at (last_synced_at);

CREATE TABLE h3_import_batches (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    file_name       VARCHAR(255) NOT NULL,
    data_type       VARCHAR(30) NOT NULL COMMENT 'CONTRACT/NORMAL',
    status          VARCHAR(20) NOT NULL COMMENT 'PROCESSING/COMPLETED/FAILED',
    total_count     INT NOT NULL DEFAULT 0,
    created_count   INT NOT NULL DEFAULT 0,
    updated_count   INT NOT NULL DEFAULT 0,
    unchanged_count INT NOT NULL DEFAULT 0,
    failed_count    INT NOT NULL DEFAULT 0,
    error_message   VARCHAR(1000) NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    DATETIME NULL,
    KEY idx_h3ib_created_at (created_at),
    KEY idx_h3ib_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云数据导入批次';

CREATE TABLE h3_import_rows (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    batch_id        BIGINT NOT NULL,
    external_id     VARCHAR(100) NULL,
    source_row_number INT NULL COMMENT '订单在 Excel 中的首行行号',
    raw_data        LONGTEXT NOT NULL COMMENT '解析后的原始订单及明细 JSON 快照',
    source_hash     CHAR(64) NULL,
    action          VARCHAR(20) NOT NULL COMMENT 'CREATE/UPDATE/UNCHANGED/FAILED',
    business_id     BIGINT NULL COMMENT '落库后的 sales_contracts.id',
    error_message   VARCHAR(1000) NULL,
    processed_at    DATETIME NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_h3ir_batch (batch_id),
    KEY idx_h3ir_external (external_id),
    KEY idx_h3ir_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云导入订单原始快照与处理结果';
