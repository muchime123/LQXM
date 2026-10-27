-- V411 建表时把 sha256 定成了 CHAR(64)，而实体 WebsiteMediaAsset.sha256 声明为
-- @Column(length = 64)（VARCHAR）。ddl-auto=validate 下 Hibernate 视 CHAR 与 VARCHAR 类型不一致，
-- 应用启动直接失败：Schema-validation: wrong column type encountered in column [sha256]。
-- 历史脚本 V411 已执行不可回改，这里以增量迁移把列类型对齐实体。
-- 幂等：已经是 VARCHAR 时静默跳过，支持 Flyway 失败重跑。

SET @schema_name := DATABASE();

SET @is_char := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @schema_name AND TABLE_NAME = 'website_media_assets'
      AND COLUMN_NAME = 'sha256' AND DATA_TYPE = 'char'
);
SET @ddl := IF(@is_char > 0,
    'ALTER TABLE website_media_assets MODIFY COLUMN sha256 VARCHAR(64) NOT NULL',
    'DO 0');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
