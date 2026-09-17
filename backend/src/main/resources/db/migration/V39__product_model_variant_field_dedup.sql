-- V39: ProductModel / ProductVariant 字段去重
-- 背景：ProductModel 与 ProductVariant 存在大量重复字段。经前端引用核查，
--       以下 11 个 Model 字段在前端为"幻影字段"（无类型定义、无表单绑定、无属性读取），
--       且与 Variant 完全重复，可安全删除。
--       ProductVariant 的 dealer_price_usd / retail_price_usd 为半废弃字段
--      （applyVariantPayload / createVariant 均不写入，仅 CompletenessService 读取），
--       一并清理。
-- 安全措施：先备份到 product_models_field_dedup_backup，再 DROP COLUMN。

-- ============================================================
-- 1) 备份待删字段（仅备份当前有效行）
-- ============================================================
CREATE TABLE IF NOT EXISTS product_models_field_dedup_backup AS
SELECT id,
       vacuum_supported, plc_control_system, touch_screen_config,
       vacuum_pump, cylinder, air_shaft,
       air_pressure_gauge, oil_water_separator,
       output_count, supplement, equipment_size_spec
FROM product_models WHERE deleted_at IS NULL;

-- ============================================================
-- 2) ProductModel：删除 11 个与 Variant 重复且前端未使用的字段
-- ============================================================
SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND column_name = 'vacuum_supported');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN vacuum_supported', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND column_name = 'plc_control_system');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN plc_control_system', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND column_name = 'touch_screen_config');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN touch_screen_config', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND column_name = 'vacuum_pump');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN vacuum_pump', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND column_name = 'cylinder');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN cylinder', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND column_name = 'air_shaft');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN air_shaft', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND column_name = 'air_pressure_gauge');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN air_pressure_gauge', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND column_name = 'oil_water_separator');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN oil_water_separator', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND column_name = 'output_count');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN output_count', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND column_name = 'supplement');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN supplement', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND column_name = 'equipment_size_spec');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP COLUMN equipment_size_spec', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- 3) ProductVariant：删除 2 个半废弃价格字段
--    dealer_price_usd / retail_price_usd 在 applyVariantPayload 和 createVariant 中均不写入，
--    仅 CompletenessService 读取做"是否填价"判断，V35 价格表已替代。
-- ============================================================
SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_variants'
                 AND column_name = 'dealer_price_usd');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_variants DROP COLUMN dealer_price_usd', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.columns
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_variants'
                 AND column_name = 'retail_price_usd');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_variants DROP COLUMN retail_price_usd', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
