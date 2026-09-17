CREATE TABLE customer_split_runs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_customer_id BIGINT NOT NULL,
    target_customer_id BIGINT NULL,
    cluster_key VARCHAR(64) NOT NULL,
    action VARCHAR(20) NOT NULL COMMENT 'CREATE_NEW/MAP_EXISTING/KEEP_CURRENT/DEFER',
    status VARCHAR(20) NOT NULL DEFAULT 'APPLIED' COMMENT 'APPLIED/UNDONE/RECORDED',
    source_customer_name VARCHAR(200) NOT NULL,
    target_customer_name VARCHAR(200) NULL,
    cluster_address TEXT NULL,
    cluster_phone TEXT NULL,
    reason VARCHAR(500) NULL,
    created_customer TINYINT(1) NOT NULL DEFAULT 0,
    outbound_count INT NOT NULL DEFAULT 0,
    created_by_employee_id BIGINT NULL,
    created_by_name VARCHAR(100) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    undone_by_employee_id BIGINT NULL,
    undone_by_name VARCHAR(100) NULL,
    undone_at DATETIME NULL,
    KEY idx_customer_split_source (source_customer_id,created_at),
    KEY idx_customer_split_target (target_customer_id),
    KEY idx_customer_split_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户身份簇拆分决策与撤销记录';

CREATE TABLE customer_split_run_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id BIGINT NOT NULL,
    sales_outbound_id BIGINT NOT NULL,
    previous_customer_id BIGINT NULL,
    applied_customer_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_customer_split_run_outbound (run_id,sales_outbound_id),
    KEY idx_customer_split_item_outbound (sales_outbound_id),
    CONSTRAINT fk_customer_split_item_run FOREIGN KEY (run_id) REFERENCES customer_split_runs(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='客户拆分涉及的销售出库明细';

CREATE TABLE customer_source_cluster_mappings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    source_system VARCHAR(30) NOT NULL DEFAULT 'H3YUN',
    normalized_source_name VARCHAR(220) NOT NULL,
    cluster_key VARCHAR(64) NOT NULL,
    customer_id BIGINT NOT NULL,
    split_run_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_customer_source_cluster (source_system,normalized_source_name,cluster_key),
    KEY idx_customer_source_cluster_customer (customer_id),
    KEY idx_customer_source_cluster_run (split_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云客户名称加地址电话身份簇映射';
