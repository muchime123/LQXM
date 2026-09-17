CREATE TABLE h3_reset_runs (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reset_token CHAR(36) NOT NULL,
  mode VARCHAR(30) NOT NULL DEFAULT 'SOURCE_REBUILD',
  status VARCHAR(20) NOT NULL DEFAULT 'PREPARED',
  identity_count BIGINT NOT NULL DEFAULT 0,
  outbound_nature_count BIGINT NOT NULL DEFAULT 0,
  created_by_employee_id BIGINT NULL,
  created_by_name VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  executed_at DATETIME NULL,
  UNIQUE KEY uk_h3_reset_token(reset_token),
  KEY idx_h3_reset_status(status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云来源重建运行与保护快照批次';

CREATE TABLE h3_reset_identity_snapshots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reset_run_id BIGINT NOT NULL,
  entity_type VARCHAR(30) NOT NULL,
  source_key VARCHAR(500) NOT NULL,
  source_name VARCHAR(500) NULL,
  target_id BIGINT NULL,
  resolution_status VARCHAR(20) NOT NULL,
  confidence DECIMAL(5,4) NULL,
  resolution_note VARCHAR(1000) NULL,
  UNIQUE KEY uk_h3_reset_identity(reset_run_id,entity_type,source_key),
  KEY idx_h3_reset_identity_run(reset_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='重导前来源身份映射保护快照';

CREATE TABLE h3_reset_outbound_nature_snapshots (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  reset_run_id BIGINT NOT NULL,
  outbound_no VARCHAR(100) NOT NULL,
  confirmed_business_type VARCHAR(30) NULL,
  original_outbound_no VARCHAR(100) NULL,
  UNIQUE KEY uk_h3_reset_nature(reset_run_id,outbound_no),
  KEY idx_h3_reset_nature_run(reset_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='重导前销售出库人工业务类型保护快照';
