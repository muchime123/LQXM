-- 兼容 MySQL 9.7：使用 PREPARE/EXECUTE 模式（字段可能已存在）
SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'voltage');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN voltage VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'frequency');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN frequency VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'power');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN power VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'packaging_speed');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN packaging_speed VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'device_weight_kg');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN device_weight_kg VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'device_size');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN device_size VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'max_film_width');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN max_film_width VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'roll_film_diameter');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN roll_film_diameter VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'air_pressure_requirement');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN air_pressure_requirement VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'gas_replacement_rate');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN gas_replacement_rate VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'residual_oxygen');
SET @sql = IF(@exists = 0, 'ALTER TABLE product_models ADD COLUMN residual_oxygen VARCHAR(100) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
