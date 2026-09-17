-- V87: 给证书/专利表（certificates）增加官网展示控制字段
-- 与 company_certifications 表（V80）保持一致的 website_* 三件套：
-- website_enabled: 是否在官网公开
-- website_featured: 是否首页重点展示（仅用于"资质与创新"预览区精选）
-- website_sort_order: 官网排序（升序）

DROP PROCEDURE IF EXISTS add_column_if_missing;
DELIMITER //
CREATE PROCEDURE add_column_if_missing(
    IN tbl VARCHAR(128),
    IN col VARCHAR(128),
    IN col_def VARCHAR(512)
)
BEGIN
    DECLARE col_exists INT;
    SELECT COUNT(*) INTO col_exists
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = tbl
      AND COLUMN_NAME = col;
    IF col_exists = 0 THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` ADD COLUMN `', col, '` ', col_def);
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

-- website_enabled: 是否在官网公开（默认 false，需手动开启）
CALL add_column_if_missing('certificates', 'website_enabled',
    'TINYINT(1) NOT NULL DEFAULT 0 COMMENT "是否在官网公开" AFTER nice_class_description');

-- website_featured: 是否首页/重点区精选
CALL add_column_if_missing('certificates', 'website_featured',
    'TINYINT(1) NOT NULL DEFAULT 0 COMMENT "是否官网重点展示" AFTER website_enabled');

-- website_sort_order: 官网排序（升序，数字越小越靠前）
CALL add_column_if_missing('certificates', 'website_sort_order',
    'INT NOT NULL DEFAULT 0 COMMENT "官网排序（升序）" AFTER website_featured');

DROP PROCEDURE IF EXISTS add_column_if_missing;
