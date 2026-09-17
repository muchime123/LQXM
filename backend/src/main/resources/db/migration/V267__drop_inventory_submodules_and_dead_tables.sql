-- V267: 下线孤儿代码配套数据表 + 库存子模块死表 + 孤儿材料库存权限（2026-09-07）
-- 背景（孤儿代码扫描，前端/Service/Controller 均无引用）：
--   1) 库存子模块 transfer/stocktake/other 三组：Controller+Service 已随批次清理下线，6 张表经 COUNT 核验全 0 行；
--   2) 一次性/备份/探针死表：main Java 全量零引用（探针表为 V174 历史补档过程产物、*_bak/_v13x/软删容器为迁移期备份），
--      行数据已导出 CSV 留档于 workspace 根 db_cleanup_20260907/；
--   3) expense_allocation_rules/runs：旧「业务中心费用自动归集」引擎（B3 已下线），rules 0 行、runs 1 行（已留档）；
--   4) RBAC：material_inventory.read/manage 唯一注解引用方 MaterialInventoryController 已删除
--      （Service 保留给制造领料 ManufacturingMaterialService），权限码与 MATERIAL_INVENTORY 功能授权成为孤儿。
-- 保留：sales_products / sales_product_source_aliases（现行 ProductInventoryMergeService 使用）、
--       product_inventory_* 主链（balances/stage/merge 现行端点）不受影响。

-- ===== 1. 库存子模块死表（6 张，均 0 行；先子后父） =====
DROP TABLE IF EXISTS product_inventory_transfer_items;
DROP TABLE IF EXISTS product_inventory_transfer_documents;
DROP TABLE IF EXISTS product_inventory_stocktake_items;
DROP TABLE IF EXISTS product_inventory_stocktakes;
DROP TABLE IF EXISTS product_inventory_other_items;
DROP TABLE IF EXISTS product_inventory_other_documents;

-- ===== 2. 费用自动归集旧引擎表（已 B3 下线，run 记录留档） =====
DROP TABLE IF EXISTS expense_allocation_runs;
DROP TABLE IF EXISTS expense_allocation_rules;

-- ===== 3. V2 迁移期软删容器 =====
DROP TABLE IF EXISTS product_variants_v2_soft_deleted;

-- ===== 4. V174 历史补档探查探针表（一次性产物） =====
DROP TABLE IF EXISTS probe_h3_matched_detail;
DROP TABLE IF EXISTS probe_h3_extracted_outbounds;
DROP TABLE IF EXISTS probe_missing_outbounds;
DROP TABLE IF EXISTS probe_related_receipts;

-- ===== 5. 备份表（原表仍在；子表先于父表） =====
DROP TABLE IF EXISTS expense_items_bak_20260826;
DROP TABLE IF EXISTS expense_claims_bak_20260826;
DROP TABLE IF EXISTS backup_sales_contract_items_v133;
DROP TABLE IF EXISTS backup_sales_contracts_v133;
DROP TABLE IF EXISTS backup_sales_contract_items_v132;
DROP TABLE IF EXISTS backup_sales_contracts_v132;
DROP TABLE IF EXISTS business_orders_bak_v204;
DROP TABLE IF EXISTS sales_outbounds_bak_v204;

-- ===== 6. 孤儿 RBAC 清理（Controller 已删，幂等，口径同 V255/V260） =====
DELETE FROM system_feature_actions
 WHERE feature_code = 'MATERIAL_INVENTORY'
    OR permission_code IN ('material_inventory.read','material_inventory.manage');
DELETE FROM system_role_feature_grants WHERE feature_code = 'MATERIAL_INVENTORY';
DELETE FROM system_features WHERE feature_code = 'MATERIAL_INVENTORY';
DELETE FROM system_role_permissions
 WHERE permission_id IN (
   SELECT id FROM system_permissions
    WHERE permission_code IN ('material_inventory.read','material_inventory.manage'));
DELETE FROM system_permissions
 WHERE permission_code IN ('material_inventory.read','material_inventory.manage');
