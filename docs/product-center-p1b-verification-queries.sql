-- 产品中心 P1-B 复核 SQL（只读，2026-10-26）
-- 目的：把 P1-A 报告中三项结论做成「可复现 + 可导出」的精确查询：
--   A. 23 条有效包材素材指向软删除包材规格（P1-A 专项缺口）
--   B. 26 条有效产品素材指向旧规格（ProductVariant 迁移对象）
--   C. 退役影响面（product_documents、*_archive 归档表、默认图派生口径）
--   D. 空适配表与有数据关联表的现状复核
--
-- 执行方式（本机，账号 root / 本地授权账号）：
--   推荐：在 cmd 中用重定向（PowerShell 的管道会给字节流加 BOM，导致 ERROR 1064）
--     mysql -uroot -p product_archive --table < docs\product-center-p1b-verification-queries.sql
--   或在 MySQL 客户端内逐段执行：START TRANSACTION READ ONLY; ... COMMIT;（只读事务，不含任何 DML/DDL）

START TRANSACTION READ ONLY;

-- =====================================================================
-- A. 23 条异常包材素材：deleted_at IS NULL 的素材 指向 deleted_at IS NOT NULL 的规格
-- =====================================================================

-- A1. 总数（应等于 23；与 P1-A 报告一致）
SELECT COUNT(*) AS anomaly_asset_count
FROM packaging_assets a
JOIN packaging_variants v ON v.id = a.variant_id
WHERE a.deleted_at IS NULL
  AND v.deleted_at IS NOT NULL;

-- A2. 明细（官方网站/台账核对用：素材 ID、所属系列、失效规格、文件名、是否默认图）
SELECT a.id                        AS asset_id,
       a.model_id                  AS asset_series_id,
       am.name                     AS asset_series_name,
       a.variant_id                AS variant_id,
       v.variant_name              AS variant_name,
       v.deleted_at                AS variant_deleted_at,
       (v.model_id = a.model_id)   AS same_series,
       a.category                  AS asset_category,
       a.file_name                 AS file_name,
       a.default_asset             AS is_default,
       a.source_file               AS source_file,
       a.created_at                AS asset_created_at
FROM packaging_assets a
JOIN packaging_variants v ON v.id = a.variant_id
LEFT JOIN packaging_models am ON am.id = a.model_id
WHERE a.deleted_at IS NULL
  AND v.deleted_at IS NOT NULL
ORDER BY v.id, a.id;

-- A3. 按失效规格分组统计（便于逐条决策时按「规格」而非「图片」推进）
SELECT v.id                        AS variant_id,
       v.variant_name              AS variant_name,
       v.deleted_at                AS variant_deleted_at,
       vm.name                     AS variant_series_name,
       COUNT(a.id)                 AS orphan_asset_count,
       SUM(a.default_asset = 1)    AS default_asset_count
FROM packaging_assets a
JOIN packaging_variants v ON v.id = a.variant_id
LEFT JOIN packaging_models vm ON vm.id = v.model_id
WHERE a.deleted_at IS NULL
  AND v.deleted_at IS NOT NULL
GROUP BY v.id, v.variant_name, v.deleted_at, vm.name
ORDER BY v.id;

-- A4. 同一系列是否存在「有效规格」可承接（迁移/重定向候选，不自动执行）
SELECT a.id AS asset_id,
       a.variant_id AS dead_variant_id,
       v.variant_name AS dead_variant_name,
       (SELECT GROUP_CONCAT(cv.id, ':', cv.variant_name SEPARATOR ' | ')
          FROM packaging_variants cv
         WHERE cv.model_id = v.model_id
           AND cv.deleted_at IS NULL) AS alive_variants_in_same_series
FROM packaging_assets a
JOIN packaging_variants v ON v.id = a.variant_id
WHERE a.deleted_at IS NULL
  AND v.deleted_at IS NOT NULL
ORDER BY v.model_id, a.id;

-- =====================================================================
-- B. 旧规格 ProductVariant：本体 4 条 + 26 条素材引用
-- =====================================================================

-- B1. 旧规格本体（含机型归属，用于判断能否用机型承接）
SELECT v.id                AS variant_id,
       v.variant_name      AS variant_name,
       v.spec_key          AS spec_key,
       v.baseline          AS baseline,
       v.sort_order        AS sort_order,
       v.machine_tier_id   AS machine_tier_id,
       t.tier_code         AS tier_code,
       t.tier_label        AS tier_label,
       m.id                AS model_id,
       m.name              AS model_name,
       m.website_enabled   AS model_website_enabled,
       v.remark            AS remark
FROM product_variants v
LEFT JOIN product_models m ON m.id = v.model_id
LEFT JOIN product_machine_tiers t ON t.id = v.machine_tier_id
WHERE v.deleted_at IS NULL
ORDER BY v.id;

-- B2. 26 条引用旧规格的有效素材（迁移对象全集）
SELECT a.id                  AS asset_id,
       v.id                  AS variant_id,
       v.variant_name        AS variant_name,
       a.model_id            AS model_id,
       m.name                AS model_name,
       a.category            AS category,
       a.file_name           AS file_name,
       a.default_asset       AS is_default,
       a.website_visible     AS website_visible,
       a.sort_order          AS sort_order,
       a.source_file         AS source_file,
       a.source_row_number   AS source_row,
       a.created_at          AS created_at
FROM product_assets a
JOIN product_variants v ON v.id = a.variant_id
LEFT JOIN product_models m ON m.id = a.model_id
WHERE a.deleted_at IS NULL
ORDER BY v.id, a.id;

-- B3. 按规格 / 类目分组计数（确认「方模/圆模、一出一/一出二」语义是否还能从规格名或类目恢复）
SELECT v.id            AS variant_id,
       v.variant_name  AS variant_name,
       a.category      AS category,
       COUNT(*)        AS asset_count,
       SUM(a.default_asset = 1)     AS default_count,
       SUM(a.website_visible = 1)   AS website_visible_count
FROM product_assets a
JOIN product_variants v ON v.id = a.variant_id
WHERE a.deleted_at IS NULL
GROUP BY v.id, v.variant_name, a.category
ORDER BY v.id, a.category;

-- B4. 这些素材是否同时有「机型集合」冗余（product_asset_machine_tier_links 26 行对照）
SELECT a.id            AS asset_id,
       a.variant_id    AS variant_id,
       a.machine_tier_id AS single_tier_id,
       (SELECT GROUP_CONCAT(l.machine_tier_id ORDER BY l.machine_tier_id)
          FROM product_asset_machine_tier_links l
         WHERE l.asset_id = a.id) AS tier_link_ids,
       a.machine_scope_confirmed AS scope_confirmed
FROM product_assets a
WHERE a.deleted_at IS NULL
  AND a.variant_id IS NOT NULL
ORDER BY a.id;

-- =====================================================================
-- C. 退役影响面核查（决定 product_variants 能否真正 DROP）
-- =====================================================================

-- C1. product_documents：P1-A 为 0 行，但仍持有 variant_id 外键
SELECT COUNT(*)                AS total_rows,
       SUM(variant_id IS NULL) AS null_variant_rows,
       SUM(variant_id IS NOT NULL) AS non_null_variant_rows
FROM product_documents;

-- C2. 归档表（ArchiveScheduler 会把超过保留期的软删记录搬到 *_archive 后物理删除）
SELECT table_name, table_rows
FROM information_schema.tables
WHERE table_schema = 'product_archive'
  AND table_name IN ('product_variants_archive', 'product_models_archive',
                     'product_assets_archive', 'packaging_variants_archive')
ORDER BY table_name;

-- C3. 默认图派生口径：product_models 不存 owner_type，
--     default_image_asset_id 指向旧规格素材时，默认图显示为 product_variant
SELECT m.id            AS model_id,
       m.name          AS model_name,
       m.default_image_asset_id AS default_asset_id,
       a.variant_id    AS default_asset_variant_id,
       v.variant_name  AS variant_name,
       a.category      AS category,
       a.file_name     AS file_name
FROM product_models m
JOIN product_assets a ON a.id = m.default_image_asset_id
LEFT JOIN product_variants v ON v.id = a.variant_id
WHERE m.deleted_at IS NULL
  AND a.variant_id IS NOT NULL
ORDER BY m.id;

-- C4. 旧规格素材在各类目的分布（判断哪些类目还需要「归属到规格」这一层）
SELECT a.category, COUNT(*) AS variant_owned_assets
FROM product_assets a
WHERE a.deleted_at IS NULL
  AND a.variant_id IS NOT NULL
GROUP BY a.category
ORDER BY variant_owned_assets DESC;

-- =====================================================================
-- D. 空适配表与有数据关联表现状复核
-- =====================================================================

SELECT 'product_model_packaging_links'          AS relation_table, COUNT(*) AS total_rows FROM product_model_packaging_links
UNION ALL SELECT 'product_machine_tier_packaging_links', COUNT(*) FROM product_machine_tier_packaging_links
UNION ALL SELECT 'product_variant_packaging_links',      COUNT(*) FROM product_variant_packaging_links
UNION ALL SELECT 'product_asset_machine_tier_links',     COUNT(*) FROM product_asset_machine_tier_links
UNION ALL SELECT 'mold_variant_links',                   COUNT(*) FROM mold_variant_links
UNION ALL SELECT 'mold_variant_links(alive)',            COUNT(*) FROM mold_variant_links WHERE deleted_at IS NULL
UNION ALL SELECT 'product_models',                       COUNT(*) FROM product_models WHERE deleted_at IS NULL
UNION ALL SELECT 'product_machine_tiers',                COUNT(*) FROM product_machine_tiers WHERE deleted_at IS NULL
UNION ALL SELECT 'product_variants',                     COUNT(*) FROM product_variants WHERE deleted_at IS NULL
UNION ALL SELECT 'product_assets',                       COUNT(*) FROM product_assets WHERE deleted_at IS NULL
UNION ALL SELECT 'product_assets(variant-owned)',        COUNT(*) FROM product_assets WHERE deleted_at IS NULL AND variant_id IS NOT NULL
UNION ALL SELECT 'packaging_models',                     COUNT(*) FROM packaging_models WHERE deleted_at IS NULL
UNION ALL SELECT 'packaging_variants',                   COUNT(*) FROM packaging_variants WHERE deleted_at IS NULL
UNION ALL SELECT 'packaging_assets',                     COUNT(*) FROM packaging_assets WHERE deleted_at IS NULL;

COMMIT;
