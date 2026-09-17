-- 兼容 MySQL 9.7：函数表达式索引（LOWER(...)）不能用 PREPARE，改为先 DROP（PREPARE）再直接 CREATE
-- 先删除已存在的索引
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'product_models' AND index_name = 'idx_products_filter');
SET @sql = IF(@exists > 0, 'DROP INDEX idx_products_filter ON product_models', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'product_models' AND index_name = 'idx_products_sort_name');
SET @sql = IF(@exists > 0, 'DROP INDEX idx_products_sort_name ON product_models', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'product_models' AND index_name = 'idx_products_keyword_name');
SET @sql = IF(@exists > 0, 'DROP INDEX idx_products_keyword_name ON product_models', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'product_models' AND index_name = 'idx_products_keyword_line');
SET @sql = IF(@exists > 0, 'DROP INDEX idx_products_keyword_line ON product_models', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'product_models' AND index_name = 'idx_products_keyword_category');
SET @sql = IF(@exists > 0, 'DROP INDEX idx_products_keyword_category ON product_models', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'product_models' AND index_name = 'idx_products_keyword_workshop');
SET @sql = IF(@exists > 0, 'DROP INDEX idx_products_keyword_workshop ON product_models', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 直接创建索引（函数表达式索引不能用 PREPARE）
CREATE INDEX idx_products_filter ON product_models (product_line_name, workshop, equipment_category, sale_status);
CREATE INDEX idx_products_sort_name ON product_models (name);
CREATE INDEX idx_products_keyword_name ON product_models ((LOWER(name)));
CREATE INDEX idx_products_keyword_line ON product_models ((LOWER(product_line_name)));
CREATE INDEX idx_products_keyword_category ON product_models ((LOWER(equipment_category)));
CREATE INDEX idx_products_keyword_workshop ON product_models ((LOWER(workshop)));
