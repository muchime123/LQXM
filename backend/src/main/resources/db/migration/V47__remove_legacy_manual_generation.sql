-- Remove the unusable legacy manual template/generation/versioning subsystem.
-- Product manual file assets and product_models.default_manual_file are intentionally preserved.

DROP TABLE IF EXISTS product_manual_versions;
DROP TABLE IF EXISTS manual_templates;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'manual_status');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN manual_status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'manual_generated_at');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN manual_generated_at', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'manual_template_version');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN manual_template_version', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'manual_product_version');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN manual_product_version', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'manual_variant_id');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN manual_variant_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'manual_title');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN manual_title', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'product_models' AND column_name = 'manual_markdown');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN manual_markdown', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
