-- 产品跟单制程与氚云原始制程分开；发布版本和任务快照均不可随字典编辑而改写。
CREATE TABLE production_tracking_process_templates (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  product_config_id BIGINT NOT NULL,
  workshop_kind VARCHAR(20) NOT NULL,
  version_no INT NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  note VARCHAR(500) NULL,
  created_by VARCHAR(100) NOT NULL,
  published_at DATETIME NULL,
  published_by VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ptpt_product_version(product_config_id,version_no),
  KEY idx_ptpt_lookup(product_config_id,state,version_no),
  CONSTRAINT fk_ptpt_product FOREIGN KEY(product_config_id) REFERENCES manufacturing_task_config_items(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE production_tracking_process_template_nodes (
  template_id BIGINT NOT NULL,
  stage_code VARCHAR(30) NOT NULL,
  stage_label VARCHAR(50) NOT NULL,
  sort_no INT NOT NULL,
  conditional TINYINT(1) NOT NULL DEFAULT 0,
  match_keywords VARCHAR(200) NULL,
  exclude_keywords VARCHAR(200) NULL,
  PRIMARY KEY(template_id,stage_code),
  CONSTRAINT fk_ptptn_template FOREIGN KEY(template_id) REFERENCES production_tracking_process_templates(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE production_tracking_process_template_edges (
  template_id BIGINT NOT NULL,
  predecessor_code VARCHAR(30) NOT NULL,
  successor_code VARCHAR(30) NOT NULL,
  PRIMARY KEY(template_id,predecessor_code,successor_code),
  CONSTRAINT fk_ptpte_from FOREIGN KEY(template_id,predecessor_code)
    REFERENCES production_tracking_process_template_nodes(template_id,stage_code),
  CONSTRAINT fk_ptpte_to FOREIGN KEY(template_id,successor_code)
    REFERENCES production_tracking_process_template_nodes(template_id,stage_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE production_tracking_process_snapshots (
  production_order_id BIGINT PRIMARY KEY,
  template_id BIGINT NULL,
  template_version INT NULL,
  source_kind VARCHAR(20) NOT NULL COMMENT 'PUBLISHED / LEGACY / MANUAL',
  created_by VARCHAR(100) NOT NULL COMMENT 'H3_IMPORT 或操作人姓名',
  revision_no INT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_ptps_order FOREIGN KEY(production_order_id) REFERENCES production_orders(id),
  CONSTRAINT fk_ptps_template FOREIGN KEY(template_id) REFERENCES production_tracking_process_templates(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE production_tracking_process_snapshot_nodes (
  production_order_id BIGINT NOT NULL,
  stage_code VARCHAR(30) NOT NULL,
  stage_label VARCHAR(50) NOT NULL,
  sort_no INT NOT NULL,
  conditional TINYINT(1) NOT NULL DEFAULT 0,
  applicability VARCHAR(20) NOT NULL COMMENT 'APPLICABLE / NOT_APPLICABLE / PENDING_REVIEW',
  applicability_reason VARCHAR(500) NULL,
  PRIMARY KEY(production_order_id,stage_code),
  CONSTRAINT fk_ptpsn_snapshot FOREIGN KEY(production_order_id)
    REFERENCES production_tracking_process_snapshots(production_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE production_tracking_process_snapshot_edges (
  production_order_id BIGINT NOT NULL,
  predecessor_code VARCHAR(30) NOT NULL,
  successor_code VARCHAR(30) NOT NULL,
  PRIMARY KEY(production_order_id,predecessor_code,successor_code),
  CONSTRAINT fk_ptpse_from FOREIGN KEY(production_order_id,predecessor_code)
    REFERENCES production_tracking_process_snapshot_nodes(production_order_id,stage_code),
  CONSTRAINT fk_ptpse_to FOREIGN KEY(production_order_id,successor_code)
    REFERENCES production_tracking_process_snapshot_nodes(production_order_id,stage_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE production_tracking_process_changes (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  production_order_id BIGINT NOT NULL,
  revision_no INT NOT NULL,
  before_json JSON NULL,
  after_json JSON NOT NULL,
  reason VARCHAR(500) NOT NULL,
  changed_by VARCHAR(100) NOT NULL,
  changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ptpc_order(production_order_id,revision_no),
  CONSTRAINT fk_ptpc_order FOREIGN KEY(production_order_id) REFERENCES production_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
