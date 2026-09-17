-- ====================================================================
-- V202 清理已废弃的匹配候选/评分特征设施，以及生产任务遗留的批量编辑列
--
-- 背景：
--   1. 生产任务不再与销售出库做逐单关联（V201 已清除存量关联），
--      配套的候选匹配、评分特征、客户产品记忆三类设施在代码中已无任何读写。
--   2. 生产执行看板改为只读（状态由车间人员在氚云推进，本地不写），
--      V178 为该看板批量编辑功能补建的两列已无用途。
--
-- 说明：
--   production_orders 的 planned_start_date / remark 并非氚云同步字段——
--   导入逻辑（H3FactImportService）的 INSERT/UPDATE 字段列表从不包含它们，
--   删除不会影响氚云数据同步。
--   V178 同时为 sales_outbounds / business_orders 补建的列仍服务于其它页面的
--   批量编辑，本脚本不处理。
-- ====================================================================

-- 1. 已停写的匹配相关表（V201 已清空 business_match_candidates 数据）
DROP TABLE IF EXISTS business_match_candidates;
DROP TABLE IF EXISTS h3_match_feature;
DROP TABLE IF EXISTS h3_customer_product_hints;

-- 2. 生产任务表上由 V178 批量编辑引入、现已无引用的列
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'production_orders' AND COLUMN_NAME = 'planned_start_date') > 0,
    'ALTER TABLE production_orders DROP COLUMN planned_start_date',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'production_orders' AND COLUMN_NAME = 'remark') > 0,
    'ALTER TABLE production_orders DROP COLUMN remark',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
