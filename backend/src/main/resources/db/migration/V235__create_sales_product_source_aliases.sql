CREATE TABLE sales_product_source_aliases (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sales_product_id BIGINT NULL,
  source_batch_id BIGINT NOT NULL,
  source_system VARCHAR(30) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(200) NOT NULL,
  source_row INT NOT NULL,
  source_product_code VARCHAR(200) NULL,
  source_name VARCHAR(500) NULL,
  source_specification VARCHAR(1000) NULL,
  source_unit VARCHAR(100) NULL,
  source_category VARCHAR(200) NULL,
  source_status VARCHAR(100) NULL,
  source_hash CHAR(64) NOT NULL,
  resolution_status VARCHAR(30) NOT NULL DEFAULT 'REVIEW',
  issue_code VARCHAR(50) NULL,
  resolution_note VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_spsa_source (source_system,external_id),
  KEY idx_spsa_batch_status (source_batch_id,resolution_status),
  KEY idx_spsa_code (source_system,source_product_code),
  KEY idx_spsa_product (sales_product_id),
  CONSTRAINT fk_spsa_product FOREIGN KEY (sales_product_id) REFERENCES sales_products(id),
  CONSTRAINT fk_spsa_batch FOREIGN KEY (source_batch_id) REFERENCES h3_preparation_batches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易产品外部来源身份与解析结果';

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('sales_product_source.resolve','解析氚云交易产品来源');
INSERT IGNORE INTO system_role_permissions
SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS') AND p.permission_code='sales_product_source.resolve';
