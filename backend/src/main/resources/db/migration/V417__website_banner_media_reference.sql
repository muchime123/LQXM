-- Add an optional website-owned media reference to Banner without changing legacy URLs.
-- Existing published banners continue to use image_url until explicitly replaced.
SET @schema_name := DATABASE();
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'website_banners'
      AND COLUMN_NAME = 'website_media_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE website_banners ADD COLUMN website_media_id BIGINT NULL AFTER image_asset_id',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = @schema_name AND TABLE_NAME = 'website_banners'
      AND CONSTRAINT_NAME = 'fk_website_banner_media'
);
SET @ddl := IF(@fk_exists = 0,
    'ALTER TABLE website_banners ADD CONSTRAINT fk_website_banner_media FOREIGN KEY (website_media_id) REFERENCES website_media_assets (id)',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
