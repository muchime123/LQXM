-- 跟单参考工期的产品分组，与氚云报工制程及现行跟单节点状态分开存储。
CREATE TABLE production_tracking_duration_groups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    group_name VARCHAR(100) NOT NULL,
    workshop_kind VARCHAR(20) NOT NULL COMMENT 'SEALER 或 MACHINE，与现有节点序列一致',
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tracking_duration_group_name (group_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产跟单产品工期分组';

ALTER TABLE manufacturing_task_config_items
    ADD COLUMN duration_group_id BIGINT NULL COMMENT '本地跟单工期分组，不改氚云产品身份',
    ADD KEY idx_task_config_duration_group (duration_group_id);

CREATE INDEX idx_production_order_workshop_product ON production_orders(production_line,product_name);

CREATE TABLE production_tracking_product_benchmarks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    duration_group_id BIGINT NOT NULL,
    stage_code VARCHAR(30) NOT NULL,
    typical_days DECIMAL(5,1) NULL COMMENT '人工审核发布值；空值回退车间基准',
    sample_count INT NOT NULL DEFAULT 0,
    p50_days DECIMAL(5,1) NULL,
    p75_days DECIMAL(5,1) NULL,
    excluded_count INT NOT NULL DEFAULT 0,
    baseline_note VARCHAR(200) NOT NULL,
    computed_at DATE NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tracking_group_stage (duration_group_id, stage_code),
    CONSTRAINT fk_tracking_benchmark_group FOREIGN KEY (duration_group_id)
        REFERENCES production_tracking_duration_groups(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品分组跟单节点参考间隔；仅审核发布值参与参考日期计算';
