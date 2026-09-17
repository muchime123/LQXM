-- 兼容 MySQL 9.7：使用 PREPARE/EXECUTE 模式（字段可能已存在）
SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'applicable_scope');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN applicable_scope LONGTEXT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'equipment_advantages');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN equipment_advantages LONGTEXT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
