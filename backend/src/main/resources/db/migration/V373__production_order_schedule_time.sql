-- 生产跟单三时间节点（氚云工单主表）：排产时间落库。
-- 氚云导出主表含「排产时间」（分钟级，如 2026-09-02 13:32），导入映射此前缺失；
-- 跟单员以排产时间确认是否开工，与预计/实际生产完成日期共同构成跟单的 deadline 口径。
-- 预计生产完成日期 / 实际生产完成日期 / 订单要求生产完成日期 已有列，不动。

-- 1) 补列（MySQL 9.7 不支持 ADD COLUMN IF NOT EXISTS，动态 SQL 先查后加，保证重跑幂等）
SET @has := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'production_orders'
               AND COLUMN_NAME = 'schedule_time');
SET @sql := IF(@has = 0,
    'ALTER TABLE production_orders ADD COLUMN schedule_time DATETIME NULL COMMENT ''氚云排产时间'' AFTER actual_completion_date',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) 从最近一次氚云生产工单导出批次回填（主表 JSON 键：排产时间）。
--    仅填空，不覆盖任何已有值；重复执行幂等（WHERE p.schedule_time IS NULL）。
UPDATE production_orders p
JOIN (
    SELECT DISTINCT s.external_id, JSON_UNQUOTE(JSON_EXTRACT(s.main_json, '$.排产时间')) AS schedule_text
    FROM h3_import_staging_rows s
    WHERE s.batch_id = (SELECT MAX(b.id) FROM h3_import_batches b WHERE b.data_type = 'PRODUCTION')
      AND JSON_UNQUOTE(JSON_EXTRACT(s.main_json, '$.排产时间')) IS NOT NULL
      AND JSON_UNQUOTE(JSON_EXTRACT(s.main_json, '$.排产时间')) <> ''
) src ON src.external_id = p.external_id
SET p.schedule_time = STR_TO_DATE(
        CONCAT(src.schedule_text, CASE WHEN CHAR_LENGTH(src.schedule_text) = 16 THEN ':00' ELSE '' END),
        '%Y-%m-%d %H:%i:%s')
WHERE p.external_source = 'H3YUN'
  AND p.schedule_time IS NULL;
