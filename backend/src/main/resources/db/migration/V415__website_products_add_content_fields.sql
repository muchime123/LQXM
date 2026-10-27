-- V413（官网产品独立内容模型建表）已于 2026-10-26 在本地库执行成功
-- （flyway_schema_history: version=413, success=1）。已执行的历史脚本不可再改，
-- 直接改 V413 会触发 Flyway checksum 校验失败导致应用无法启动，因此字段增补另起本迁移。
-- 补齐 website_products 的容器标签 / 包装工艺标签 / 产能分级 / 卡片主图四个字段。
-- 幂等：列与外键均已存在时静默跳过，支持 Flyway 失败重跑。

SET @schema_name := DATABASE();

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'website_products' AND COLUMN_NAME = 'container_tags'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE website_products ADD COLUMN container_tags JSON NULL AFTER application_tags',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'website_products' AND COLUMN_NAME = 'process_tags'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE website_products ADD COLUMN process_tags JSON NULL AFTER container_tags',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'website_products' AND COLUMN_NAME = 'capacity_level'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE website_products ADD COLUMN capacity_level VARCHAR(200) NULL AFTER process_tags',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'website_products' AND COLUMN_NAME = 'card_media_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE website_products ADD COLUMN card_media_id BIGINT NULL AFTER seo_description',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = @schema_name AND TABLE_NAME = 'website_products'
      AND CONSTRAINT_NAME = 'fk_website_product_card_media'
);
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE website_products ADD CONSTRAINT fk_website_product_card_media FOREIGN KEY (card_media_id) REFERENCES website_media_assets (id)',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
