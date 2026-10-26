-- =====================================================================
-- V409: 解除「知识产权 / 资产合规」与官网的耦合
-- ---------------------------------------------------------------------
-- 背景：官网「企业资质」「专利与知识产权」两块不再由后台档案驱动，
-- 官网展示内容由官网侧自行维护。后端随之整体下线：
--   - /api/website/certifications、/certification-categories、/certifications/{id}/image
--   - /api/website/patents、/patent-types、/patent-categories、/patents/{id}/image
--   - /api/website/home 聚合中的 certifications / patentCount / patents
--   - 官网工作台的「已发布资质 / 临期资质 / 已发布专利 / 精选专利」统计
--
-- 本迁移删除两张主表上的官网发布字段（内部管理字段与到期提醒不受影响）：
--   company_certifications: website_enabled / website_featured / website_sort_order / public_description
--   certificates:           website_enabled / website_featured / website_sort_order
-- 连同 V80 建的官网查询索引 idx_ccert_website 一并删除。
--
-- 幂等：列 / 索引不存在时直接跳过。
-- =====================================================================

DROP PROCEDURE IF EXISTS drop_column_if_exists;
DROP PROCEDURE IF EXISTS drop_index_if_exists;
DELIMITER //
CREATE PROCEDURE drop_column_if_exists(IN tbl VARCHAR(128), IN col VARCHAR(128))
BEGIN
    DECLARE col_exists INT;
    SELECT COUNT(*) INTO col_exists
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND COLUMN_NAME = col;
    IF col_exists > 0 THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` DROP COLUMN `', col, '`');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //

CREATE PROCEDURE drop_index_if_exists(IN tbl VARCHAR(128), IN idx VARCHAR(128))
BEGIN
    DECLARE idx_exists INT;
    SELECT COUNT(*) INTO idx_exists
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx;
    IF idx_exists > 0 THEN
        SET @sql = CONCAT('ALTER TABLE `', tbl, '` DROP INDEX `', idx, '`');
        PREPARE stmt FROM @sql;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

-- 1) 公司资质与合规文件（company_certifications）
CALL drop_index_if_exists('company_certifications', 'idx_ccert_website');
CALL drop_column_if_exists('company_certifications', 'website_enabled');
CALL drop_column_if_exists('company_certifications', 'website_featured');
CALL drop_column_if_exists('company_certifications', 'website_sort_order');
CALL drop_column_if_exists('company_certifications', 'public_description');

-- 2) 知识产权 / 证书专利（certificates）
CALL drop_index_if_exists('certificates', 'idx_certificate_website');
CALL drop_column_if_exists('certificates', 'website_enabled');
CALL drop_column_if_exists('certificates', 'website_featured');
CALL drop_column_if_exists('certificates', 'website_sort_order');

DROP PROCEDURE IF EXISTS drop_column_if_exists;
DROP PROCEDURE IF EXISTS drop_index_if_exists;
