-- Preparation only: no inventory balances, movements, receipts or historical facts are modified.
CREATE TABLE h3_preparation_batches (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  kind VARCHAR(16) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  file_hash CHAR(64) NOT NULL,
  identity_hash CHAR(64) NOT NULL,
  snapshot_at DATETIME NULL,
  product_batch_id BIGINT NULL,
  row_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3_prep_identity (identity_hash),
  CONSTRAINT fk_h3_prep_product_batch FOREIGN KEY (product_batch_id) REFERENCES h3_preparation_batches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE h3_preparation_rows (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  batch_id BIGINT NOT NULL,
  source_row INT NOT NULL,
  source_id VARCHAR(200) NOT NULL DEFAULT '',
  product_code VARCHAR(200) NOT NULL DEFAULT '',
  product_name VARCHAR(500) NOT NULL DEFAULT '',
  specification VARCHAR(1000) NOT NULL DEFAULT '',
  unit VARCHAR(100) NOT NULL DEFAULT '',
  warehouse_name VARCHAR(200) NOT NULL DEFAULT '',
  -- Candidate quantity is exact source text: do not round small negative floating-point residues to zero.
  quantity VARCHAR(400) NULL,
  source_status VARCHAR(100) NOT NULL DEFAULT '',
  parse_issue VARCHAR(500) NOT NULL DEFAULT '',
  raw_json LONGTEXT NOT NULL,
  target_kind VARCHAR(32) NULL,
  target_id BIGINT NULL,
  confirmed_unit VARCHAR(100) NULL,
  version INT NOT NULL DEFAULT 0,
  mapped_at DATETIME NULL,
  UNIQUE KEY uk_h3_prep_source_row (batch_id, source_row),
  KEY idx_h3_prep_code (batch_id, product_code),
  CONSTRAINT fk_h3_prep_rows_batch FOREIGN KEY (batch_id) REFERENCES h3_preparation_batches(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- A name is registered only after an operator confirms the source warehouse in the preparation UI.
CREATE TABLE h3_preparation_warehouses (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3_prep_warehouse_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
