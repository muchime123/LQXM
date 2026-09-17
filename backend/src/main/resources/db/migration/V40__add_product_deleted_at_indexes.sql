-- V40: 给产品相关主表增加 deleted_at 索引
-- 背景：product_models / product_variants / product_assets 均使用 @SQLRestriction("deleted_at IS NULL")
--       软删除数据量较大（product_models 已累计 5000+ 已软删行），需加速回收站/归档查询。

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models'
                 AND index_name = 'idx_pm_deleted_at');
SET @sql = IF(@exists > 0, 'SELECT 1',
              'CREATE INDEX idx_pm_deleted_at ON product_models(deleted_at)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_variants'
                 AND index_name = 'idx_pv_deleted_at');
SET @sql = IF(@exists > 0, 'SELECT 1',
              'CREATE INDEX idx_pv_deleted_at ON product_variants(deleted_at)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_assets'
                 AND index_name = 'idx_pa_deleted_at');
SET @sql = IF(@exists > 0, 'SELECT 1',
              'CREATE INDEX idx_pa_deleted_at ON product_assets(deleted_at)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 配套对按模型 + 软删的常用组合加速
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_variants'
                 AND index_name = 'idx_pv_model_deleted');
SET @sql = IF(@exists > 0, 'SELECT 1',
              'CREATE INDEX idx_pv_model_deleted ON product_variants(model_id, deleted_at)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_assets'
                 AND index_name = 'idx_pa_model_deleted');
SET @sql = IF(@exists > 0, 'SELECT 1',
              'CREATE INDEX idx_pa_model_deleted ON product_assets(model_id, deleted_at)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
