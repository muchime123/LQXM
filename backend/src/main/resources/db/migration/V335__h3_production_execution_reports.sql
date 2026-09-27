-- 氚云工单仍为来源台账；本地报工按来源工单/制程外部 ID 锚定，避免重导替换子表行 ID 后失联。
CREATE TABLE production_execution_component_states (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_external_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 component_external_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 baseline_source_quantity DECIMAL(14,4) NOT NULL DEFAULT 0,
 net_local_quantity DECIMAL(14,4) NOT NULL DEFAULT 0,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_pecs_source (order_external_id,component_external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云制程的本地报工基线和净数量';

CREATE TABLE production_execution_reports (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_external_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 component_external_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 work_order_no_snapshot VARCHAR(100) NOT NULL,
 component_name_snapshot VARCHAR(300) NOT NULL,
 quantity DECIMAL(14,4) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'VALID',
 remark VARCHAR(1000) NULL,
 reported_by_employee_id BIGINT NOT NULL,
 reported_by_name VARCHAR(100) NOT NULL,
 reported_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 reversed_by_employee_id BIGINT NULL,
 reversed_by_name VARCHAR(100) NULL,
 reversed_at DATETIME NULL,
 reversal_reason VARCHAR(1000) NULL,
 KEY idx_per_order (order_external_id,reported_at),
 KEY idx_per_component (order_external_id,component_external_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云生产任务的本地报工及冲正记录';
