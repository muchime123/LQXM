-- V390: 知识产权「查询号（纯数字）」补建 + 存量回填 + 检索索引
-- 背景：专利局/年费缴费网站用纯数字申请号检索（ZL202411238193.4 → 2024112381934），
--       certificates.query_no 存的就是这个纯数字，但此前只在手工填写时才有值，
--       列表搜索也没用它，导致搜索栏输入纯数字编号查不到档案。
-- 本脚本：1) 幂等补建 query_no 列（JPA 实体已声明，ddl-auto=validate 环境已存在）
--        2) 存量回填：优先取申请号数字部分，其次专利号数字部分
--        3) 加索引，供列表关键词检索使用

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

CALL add_column_if_missing('certificates', 'query_no', 'VARCHAR(100) COMMENT "查询用申请号（纯数字），如 2024112381934"');
DROP PROCEDURE add_column_if_missing;

-- 存量回填（含软删除行）：申请号优先，其次专利号/证书号
UPDATE certificates
SET query_no = NULLIF(
  REGEXP_REPLACE(
    COALESCE(NULLIF(TRIM(application_no), ''), NULLIF(TRIM(patent_no), '')),
    '[^0-9]', ''
  ),
  ''
)
WHERE (query_no IS NULL OR query_no = '')
  AND COALESCE(NULLIF(TRIM(application_no), ''), NULLIF(TRIM(patent_no), '')) IS NOT NULL;

DROP PROCEDURE IF EXISTS add_index_if_missing;
DELIMITER //
CREATE PROCEDURE add_index_if_missing(
  IN tbl VARCHAR(128), IN idx VARCHAR(128), IN idx_def VARCHAR(512)
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = tbl AND INDEX_NAME = idx
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', tbl, ' ADD INDEX ', idx, ' ', idx_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_index_if_missing('certificates', 'idx_cert_query_no', '(query_no)');
DROP PROCEDURE add_index_if_missing;
