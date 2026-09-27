-- 氚云任务的本地质检退回与返工；来源事实不修改。
CREATE TABLE production_execution_reworks (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_external_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 work_order_no_snapshot VARCHAR(100) NOT NULL,
 component_external_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 component_name_snapshot VARCHAR(300) NOT NULL,
 target_quantity DECIMAL(14,4) NOT NULL,
 reported_quantity DECIMAL(14,4) NOT NULL DEFAULT 0,
 status VARCHAR(24) NOT NULL DEFAULT 'REWORKING',
 failure_reason VARCHAR(1000) NOT NULL,
 inspected_by_employee_id BIGINT NOT NULL,
 inspected_by_name VARCHAR(100) NOT NULL,
 inspected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 closed_at DATETIME NULL,
 active_order_external_id VARCHAR(100) COLLATE utf8mb4_bin
   GENERATED ALWAYS AS (IF(status IN ('REWORKING','PENDING_REINSPECTION'),order_external_id,NULL)) STORED,
 UNIQUE KEY uk_perw_active_order (active_order_external_id),
 KEY idx_perw_order (order_external_id,inspected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云任务本地检验退回及返工轮次';

CREATE TABLE production_execution_rework_reports (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 rework_id BIGINT NOT NULL,
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
 KEY idx_perwr_rework (rework_id,status,reported_at),
 CONSTRAINT fk_perwr_rework FOREIGN KEY(rework_id) REFERENCES production_execution_reworks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云任务本地返工报工及冲正';
