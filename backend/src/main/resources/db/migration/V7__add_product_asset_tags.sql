-- 兼容 MySQL 9.7：使用 PREPARE/EXECUTE 模式（字段可能已存在）
SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_assets' AND column_name = 'tags');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_assets ADD COLUMN tags VARCHAR(500) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
