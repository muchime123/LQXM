-- ============================================================
-- V53: 包材模具数据完整性加固
-- 1. 清理 mold_variant_links 历史重复默认（每个 variant 保留最早一条 is_default=1）
-- 2. 建立部分唯一索引：每个未软删的 variant 最多一个 default mold
--    使用 MySQL 8 函数索引：CASE 表达式返回 NULL 的行不占用索引槽，
--    仅对 is_default=1 AND deleted_at IS NULL 的行生效。
-- ============================================================

-- 1. 清理重复默认：同一 variant 存在多条未软删的 default link 时，
--    保留 id 最小（最早创建）的一条，其余将 is_default 置 0（不删数据，仅取消默认标记）
UPDATE mold_variant_links l1
INNER JOIN (
    SELECT variant_id, MIN(id) AS keep_id
    FROM mold_variant_links
    WHERE is_default = 1 AND deleted_at IS NULL
    GROUP BY variant_id
    HAVING COUNT(*) > 1
) keep ON l1.variant_id = keep.variant_id
SET l1.is_default = 0
WHERE l1.is_default = 1
  AND l1.deleted_at IS NULL
  AND l1.id <> keep.keep_id;

-- 2. 部分唯一索引：防并发下产生多个默认模具
--    MySQL 8.0.13+ 支持函数索引；CASE 返回 NULL 时该行不参与唯一性约束
CREATE UNIQUE INDEX uk_variant_default_mold ON mold_variant_links (
    (CASE WHEN is_default = 1 AND deleted_at IS NULL THEN variant_id END)
);
