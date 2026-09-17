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

CALL add_column_if_missing('product_models', 'model_code', 'VARCHAR(80) NULL COMMENT "Product model code" AFTER name_formula');

UPDATE product_models
SET model_code = machine_type,
    machine_type = NULL
WHERE (model_code IS NULL OR model_code = '')
  AND machine_type IS NOT NULL
  AND TRIM(machine_type) <> ''
  AND machine_type NOT IN ('小型', '台式', '立式', '流水线')
  AND machine_type REGEXP '[A-Za-z]';

DROP PROCEDURE IF EXISTS add_column_if_missing;
