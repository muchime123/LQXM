-- ============================================================
-- V52: 清理之前错误导入产生的独立系列和规格
-- 旧 ImportService 把 Excel 产品名创建为 PackagingModel，
-- 把高度创建为 PackagingVariant，但没有创建 MoldVariantLink。
-- 这些数据与原系统平行，需要清理。
-- ============================================================

-- 1. 标记由模具导入创建的 PackagingModel（有 source_file 且名称不在原系列列表中）
--    先软删除这些系列的规格
UPDATE packaging_variants v
INNER JOIN packaging_models m ON v.model_id = m.id
SET v.deleted_at = NOW()
WHERE m.source_file IS NOT NULL
  AND m.source_file LIKE '%模具%'
  AND v.deleted_at IS NULL;

-- 2. 软删除由模具导入创建的 PackagingModel
UPDATE packaging_models
SET deleted_at = NOW()
WHERE source_file IS NOT NULL
  AND source_file LIKE '%模具%'
  AND deleted_at IS NULL;

-- 3. 清除模具的旧 model_id 引用（保留 mold 本身，group_id 会在新导入时设置）
--    不需要清除，因为 mold 表的 model_id 在新导入时会被正确设置
