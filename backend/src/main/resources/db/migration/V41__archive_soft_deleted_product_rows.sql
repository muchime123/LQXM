-- V41: 将产品主表中大量已软删记录迁移到独立归档表
-- 背景：product_models 累计 5000+ 软删行、product_variants / product_assets 也积累了大量软删数据。
--       通过 CTAS + DELETE 方式把 2026-Q3 之前的软删历史从主表分离，降低主表体积与主键消耗。
--       任何时候都可以将 *_archive 表 JOIN 回来做历史分析，不丢失审计线索。

-- ============================================================
-- 1) 准备归档表（不带外键/索引，后续可按需补齐）
-- ============================================================

CREATE TABLE IF NOT EXISTS product_models_archive
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
AS SELECT * FROM product_models WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS product_variants_archive
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
AS SELECT * FROM product_variants WHERE 1 = 0;

CREATE TABLE IF NOT EXISTS product_assets_archive
ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
AS SELECT * FROM product_assets WHERE 1 = 0;

-- ============================================================
-- 2) 迁移：deleted_at IS NOT NULL 的记录全部写入归档表
--    使用 INSERT ... SELECT 在同一事务中保证一致性
-- ============================================================

INSERT INTO product_models_archive
SELECT * FROM product_models
WHERE deleted_at IS NOT NULL
  AND id NOT IN (SELECT id FROM product_models_archive);

INSERT INTO product_variants_archive
SELECT * FROM product_variants
WHERE deleted_at IS NOT NULL
  AND id NOT IN (SELECT id FROM product_variants_archive);

INSERT INTO product_assets_archive
SELECT * FROM product_assets
WHERE deleted_at IS NOT NULL
  AND id NOT IN (SELECT id FROM product_assets_archive);

-- ============================================================
-- 3) 物理删除已归档的软删记录（释放主键空间）
--    执行前请确保本迁移在备份后运行；生产上线前应在测试库演练
-- ============================================================

DELETE FROM product_assets
WHERE deleted_at IS NOT NULL
  AND id IN (SELECT id FROM product_assets_archive);

DELETE FROM product_variants
WHERE deleted_at IS NOT NULL
  AND id IN (SELECT id FROM product_variants_archive);

DELETE FROM product_models
WHERE deleted_at IS NOT NULL
  AND id IN (SELECT id FROM product_models_archive);

-- ============================================================
-- 4) 为归档表增加关键查询索引（方便后续审计/报告）
-- ============================================================

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models_archive'
                 AND index_name = 'idx_pma_deleted');
SET @sql = IF(@exists > 0, 'SELECT 1',
              'CREATE INDEX idx_pma_deleted ON product_models_archive(deleted_at)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_models_archive'
                 AND index_name = 'idx_pma_name');
SET @sql = IF(@exists > 0, 'SELECT 1',
              'CREATE INDEX idx_pma_name ON product_models_archive(name)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_variants_archive'
                 AND index_name = 'idx_pva_model');
SET @sql = IF(@exists > 0, 'SELECT 1',
              'CREATE INDEX idx_pva_model ON product_variants_archive(model_id)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE()
                 AND table_name = 'product_assets_archive'
                 AND index_name = 'idx_paa_model');
SET @sql = IF(@exists > 0, 'SELECT 1',
              'CREATE INDEX idx_paa_model ON product_assets_archive(model_id)');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
