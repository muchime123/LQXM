-- V11: expand machine component rows for product-level machine configuration.
-- The app can keep many component items without adding more columns to product_models.

CREATE TABLE IF NOT EXISTS product_machine_components (
  id BIGINT NOT NULL AUTO_INCREMENT,
  model_id BIGINT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  component_key VARCHAR(80) NULL,
  category VARCHAR(80) NULL,
  name VARCHAR(100) NULL,
  name_en VARCHAR(150) NULL,
  brand VARCHAR(300) NULL,
  brand_en VARCHAR(300) NULL,
  spec_value VARCHAR(300) NULL,
  remark VARCHAR(500) NULL,
  standard_item BIT NOT NULL DEFAULT 0,
  enabled BIT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NULL,
  updated_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  INDEX idx_product_machine_components_model_sort (model_id, sort_order),
  INDEX idx_product_machine_components_key (component_key),
  CONSTRAINT fk_product_machine_components_model
    FOREIGN KEY (model_id) REFERENCES product_models (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER //
CREATE PROCEDURE add_column_if_missing(
  IN tbl VARCHAR(128), IN col VARCHAR(128), IN col_def VARCHAR(512)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', tbl, ' ADD COLUMN ', col, ' ', col_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_column_if_missing('product_machine_components', 'component_key', 'VARCHAR(80) NULL');
CALL add_column_if_missing('product_machine_components', 'category', 'VARCHAR(80) NULL');
CALL add_column_if_missing('product_machine_components', 'spec_value', 'VARCHAR(300) NULL');
CALL add_column_if_missing('product_machine_components', 'remark', 'VARCHAR(500) NULL');
CALL add_column_if_missing('product_machine_components', 'standard_item', 'BIT NOT NULL DEFAULT 0');
CALL add_column_if_missing('product_machine_components', 'enabled', 'BIT NOT NULL DEFAULT 1');

DROP PROCEDURE IF EXISTS add_column_if_missing;
