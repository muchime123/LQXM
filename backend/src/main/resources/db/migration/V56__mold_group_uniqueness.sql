-- ============================================================
-- V55: 模具规格族 (model_id, name) 唯一性加固
-- 1. 清理同一系列下重复的规格族（保留 id 最小者，将重复组的模具迁移到保留者后软删重复组）
-- 2. 建立部分唯一索引：model_id + name + (deleted_at IS NULL)
--    利用 MySQL 函数索引，仅对未软删行生效，允许已删除行与新行同名。
-- ============================================================

-- 1. 识别并合并同一系列下的重复规格族（仅处理未软删的冲突组）
--    先把重复组下的模具 group_id 指向保留组 (id 最小者)，再软删重复组
UPDATE packaging_molds m
INNER JOIN (
    SELECT g1.id AS dup_id, keep.keep_id
    FROM packaging_mold_groups g1
    INNER JOIN (
        SELECT model_id, name, MIN(id) AS keep_id, COUNT(*) AS cnt
        FROM packaging_mold_groups
        WHERE deleted_at IS NULL
        GROUP BY model_id, name
        HAVING COUNT(*) > 1
    ) keep ON g1.model_id = keep.model_id AND g1.name = keep.name AND g1.id <> keep.keep_id
    WHERE g1.deleted_at IS NULL
) merge_map ON m.group_id = merge_map.dup_id
SET m.group_id = merge_map.keep_id;

-- 软删已被合并的重复规格族
UPDATE packaging_mold_groups g
INNER JOIN (
    SELECT g1.id AS dup_id
    FROM packaging_mold_groups g1
    INNER JOIN (
        SELECT model_id, name, MIN(id) AS keep_id, COUNT(*) AS cnt
        FROM packaging_mold_groups
        WHERE deleted_at IS NULL
        GROUP BY model_id, name
        HAVING COUNT(*) > 1
    ) keep ON g1.model_id = keep.model_id AND g1.name = keep.name AND g1.id <> keep.keep_id
    WHERE g1.deleted_at IS NULL
) merge_map ON g.id = merge_map.dup_id
SET g.deleted_at = NOW();

-- 2. 部分唯一索引：仅 deleted_at IS NULL 的未软删行参与唯一性约束
--    MySQL 8.0.13+ 支持函数索引；CASE 返回 NULL 时该行不占用索引槽。
CREATE UNIQUE INDEX uk_pkg_mold_group_model_name ON packaging_mold_groups (
    model_id,
    name,
    (CASE WHEN deleted_at IS NULL THEN 0 END)
);
