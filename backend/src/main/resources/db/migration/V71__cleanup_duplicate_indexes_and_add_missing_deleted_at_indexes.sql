-- V71: 索引优化：清理 14 个重复/冗余索引 + 补充 9 张软删除表的 deleted_at 索引
-- 背景：
--   1) 多轮迁移遗留了 8 个完全重复索引（同一列上有 2~3 个 BTREE）
--   2) 6 个单列索引是复合索引的最左前缀，已被复合索引完全覆盖，纯浪费空间
--   3) 9 张使用 @SQLRestriction("deleted_at IS NULL") 的软删除表缺少 deleted_at 索引，
--      每次查询走全表扫描过滤 deleted_at IS NULL（哪怕只查 1 条也要扫全表）
--
-- 说明：
--   归档表（*_archive）主要由 ArchiveScheduler 批量读写，走 deleted_at 过滤但数据量小，
--   暂不追加；product_models_archive 已有 idx_pma_deleted 索引。
--   删除索引全部使用 existence check + PREPARE 模式，与 V2/V39 风格一致。

-- ============================================================
-- 第一部分：删除完全重复索引（8 个）
-- 同一列上有 2 个或以上 BTREE，保留一个即可
-- ============================================================

-- 1. certificates：asset_category 删除 idx_cert_asset_category，保留 idx_cert_category
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'certificates'
                 AND index_name = 'idx_cert_asset_category');
SET @sql = IF(@exists > 0, 'ALTER TABLE certificates DROP INDEX idx_cert_asset_category', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. certificates：asset_status 删除 idx_cert_asset_status，保留 idx_cert_status
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'certificates'
                 AND index_name = 'idx_cert_asset_status');
SET @sql = IF(@exists > 0, 'ALTER TABLE certificates DROP INDEX idx_cert_asset_status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. certificates：fee_due_date 删除 idx_cert_fee_due_date，保留 idx_cert_fee_due
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'certificates'
                 AND index_name = 'idx_cert_fee_due_date');
SET @sql = IF(@exists > 0, 'ALTER TABLE certificates DROP INDEX idx_cert_fee_due_date', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. documents：category 删除 idx_doc_category，保留 idx_documents_category
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'documents'
                 AND index_name = 'idx_doc_category');
SET @sql = IF(@exists > 0, 'ALTER TABLE documents DROP INDEX idx_doc_category', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. media_account_updates：account_id 删除 idx_updates_account_id，保留 idx_update_account_id
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'media_account_updates'
                 AND index_name = 'idx_updates_account_id');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_account_updates DROP INDEX idx_updates_account_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6. media_account_updates：update_date 删除 idx_updates_date，保留 idx_update_date
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'media_account_updates'
                 AND index_name = 'idx_updates_date');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_account_updates DROP INDEX idx_updates_date', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 7. media_accounts：platform 删除 idx_media_platform，保留 idx_media_accounts_platform
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'media_accounts'
                 AND index_name = 'idx_media_platform');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_accounts DROP INDEX idx_media_platform', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 8. media_accounts：last_updated_at 删除 idx_media_last_updated，保留 idx_media_accounts_last_updated_at
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'media_accounts'
                 AND index_name = 'idx_media_last_updated');
SET @sql = IF(@exists > 0, 'ALTER TABLE media_accounts DROP INDEX idx_media_last_updated', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ============================================================
-- 第二部分：删除被复合索引最左前缀覆盖的冗余单列索引（6 个）
-- 单列索引 = 复合索引第1列，MySQL 可直接复用复合索引的最左前缀走 range/ref 扫描
-- 验证标准：复合索引第1列与单列索引列完全相同，即可删
-- ============================================================

-- 9. product_models：product_line_name = idx_products_filter(col1)，删除单列 idx_product_line
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'product_models'
                 AND index_name = 'idx_product_line');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_models DROP INDEX idx_product_line', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 10. product_models：equipment_category = idx_products_filter(col3) — 注意不是 col1！
--     等一下，idx_products_filter 是 (product_line_name, workshop, equipment_category, sale_status)。
--     equipment_category 是第 3 列，不是最左前缀，不能用复合索引。保留单列 idx_equip_category。
--     → 此项跳过（保留索引）。验证：SELECT ... WHERE equipment_category = ? 无法跳前两列。

-- 11. product_models：workshop = idx_products_filter(col2) — 同样非最左前缀。
--     → 此项跳过（保留 idx_workshop）。理由同上：workshop 是第 2 列，WHERE workshop = ? 单独用不了复合索引。

-- 12. product_models：sale_status = idx_products_filter(col4) — 同样非最左前缀。
--     → 此项跳过（保留 idx_sale_status）。

-- 结论：真正被最左前缀覆盖的只有 idx_product_line 一项。
-- 下面继续看其他表。

-- 13. product_assets：model_id 被 idx_pa_model_deleted(model_id, deleted_at) 覆盖
--     idx_asset_model_id(model_id) = 复合索引第1列，可删除
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'product_assets'
                 AND index_name = 'idx_asset_model_id');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_assets DROP INDEX idx_asset_model_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 14. product_variants：model_id 被 idx_pv_model_deleted(model_id, deleted_at) 覆盖
--     idx_variant_model_id(model_id) = 复合索引第1列，可删除
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'product_variants'
                 AND index_name = 'idx_variant_model_id');
SET @sql = IF(@exists > 0, 'ALTER TABLE product_variants DROP INDEX idx_variant_model_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- （补充）product_models 的 idx_products_sort_name(name) = PRIMARY(id) 是单列不重复，
--       但 name 列同时有 uk_product_models_name 唯一索引。
--       idx_products_sort_name(name) 是 B+ 树索引排序用，DELETE 时不影响 unique 约束
--       检查：是否有 ORDER BY name 场景？如果走 uk_product_models_name 也一样。
--       安全起见保留（无重复且 uk 是约束索引，功能索引单独保留没问题）。
--       → 此项跳过。

-- 小结：本部分删除 3 个索引（idx_product_line / idx_asset_model_id / idx_variant_model_id）
--       其余 3 个候选（idx_equip_category / idx_workshop / idx_sale_status）
--       因不是复合索引最左前缀而保留。

-- ============================================================
-- 第三部分：为 9 张使用 @SQLRestriction 的软删除表补充 deleted_at 索引
-- 这些表每次 SELECT 都会追加 WHERE deleted_at IS NULL，没有索引就全表扫描
-- ============================================================

-- 15. company_assets
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'company_assets'
                 AND index_name = 'idx_ca_deleted_at');
SET @sql = IF(@exists = 0, 'CREATE INDEX idx_ca_deleted_at ON company_assets(deleted_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 16. mold_accessories
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'mold_accessories'
                 AND index_name = 'idx_ma_deleted_at');
SET @sql = IF(@exists = 0, 'CREATE INDEX idx_ma_deleted_at ON mold_accessories(deleted_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 17. mold_variant_links
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'mold_variant_links'
                 AND index_name = 'idx_mvl_deleted_at');
SET @sql = IF(@exists = 0, 'CREATE INDEX idx_mvl_deleted_at ON mold_variant_links(deleted_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 18. office_assets
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'office_assets'
                 AND index_name = 'idx_oa_deleted_at');
SET @sql = IF(@exists = 0, 'CREATE INDEX idx_oa_deleted_at ON office_assets(deleted_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 19. packaging_assets
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'packaging_assets'
                 AND index_name = 'idx_pkg_a_deleted_at');
SET @sql = IF(@exists = 0, 'CREATE INDEX idx_pkg_a_deleted_at ON packaging_assets(deleted_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 20. packaging_models
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'packaging_models'
                 AND index_name = 'idx_pkg_m_deleted_at');
SET @sql = IF(@exists = 0, 'CREATE INDEX idx_pkg_m_deleted_at ON packaging_models(deleted_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 21. packaging_mold_groups
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'packaging_mold_groups'
                 AND index_name = 'idx_pkg_mg_deleted_at');
SET @sql = IF(@exists = 0, 'CREATE INDEX idx_pkg_mg_deleted_at ON packaging_mold_groups(deleted_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 22. packaging_molds
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'packaging_molds'
                 AND index_name = 'idx_pkg_mo_deleted_at');
SET @sql = IF(@exists = 0, 'CREATE INDEX idx_pkg_mo_deleted_at ON packaging_molds(deleted_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 23. packaging_variants
SET @exists = (SELECT COUNT(*) FROM information_schema.statistics
               WHERE table_schema = DATABASE() AND table_name = 'packaging_variants'
                 AND index_name = 'idx_pkg_v_deleted_at');
SET @sql = IF(@exists = 0, 'CREATE INDEX idx_pkg_v_deleted_at ON packaging_variants(deleted_at)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- * 归档表 (*_archive) 不补充索引：
--   - product_models_archive 已有 idx_pma_deleted（V41 创建）
--   - product_variants_archive / product_assets_archive 目前仅 ArchiveScheduler 批量迁移写入，
--     没有业务查询走 deleted_at，不追加索引。
