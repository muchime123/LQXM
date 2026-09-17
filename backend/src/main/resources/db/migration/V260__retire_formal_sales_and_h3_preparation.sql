-- V260: 下线「正式销售」旧本地体系（出库/收款/利润链）与 H3Preparation / 期初库存旧入口（2026-09-04）。
-- 现行口径：
--   1) 销售财务事实 = 氚云导入台账 sales_outbounds（利润/提成/回款核销全部消费该账，无其他销售出库入口）；
--   2) 统一产品库存 = h3_product_catalog + h3_warehouse_stock 台账合并入库（ProductInventoryMergeService，现行 B2）；
--   3) 成品批次 finished_goods_lots / 流水 finished_goods_movements 由生产（Manufacturing）共用，保留。
-- 本次下线对象均无前端入口、属测试期遗留，行数核验：formal_* 4 表全空（0 行）；
-- h3_preparation_batches(2)/rows(31335)/warehouses(3) 为旧期初解析原始行，product_inventory_opening_* 为其中间候选；
-- sales_product_source_aliases.source_batch_id 自 V252 起写入恒为 NULL（1551 行非空 0 行），随外键一并清理（表本身保留）。
-- 注意：sales_product_source.resolve 是 PRODUCT_SOURCE 功能目录下的动作（非三类旧功能），本迁移一并摘除其动作引用后删码。

-- 1. sales_product_source_aliases 解除对 h3_preparation_batches 的外键并删除批次列（表本身保留）
--    DDL 幂等守卫：MySQL 不支持 DROP FOREIGN KEY / DROP COLUMN IF EXISTS，用 information_schema 动态生成。
SET @fk_name := (
  SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_product_source_aliases'
     AND REFERENCED_TABLE_NAME = 'h3_preparation_batches' LIMIT 1);
SET @drop_fk := IF(@fk_name IS NULL, 'SELECT 1',
  CONCAT('ALTER TABLE sales_product_source_aliases DROP FOREIGN KEY `', @fk_name, '`'));
PREPARE stmt_drop_fk FROM @drop_fk; EXECUTE stmt_drop_fk; DEALLOCATE PREPARE stmt_drop_fk;

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sales_product_source_aliases'
     AND COLUMN_NAME = 'source_batch_id');
SET @drop_col := IF(@col_exists = 0, 'SELECT 1',
  'ALTER TABLE sales_product_source_aliases DROP COLUMN `source_batch_id`');
PREPARE stmt_drop_col FROM @drop_col; EXECUTE stmt_drop_col; DEALLOCATE PREPARE stmt_drop_col;

-- 2. formal_* 正式销售链 4 表（自引用方向删除：先删引用出库单的快照/收款表）
DROP TABLE IF EXISTS formal_profit_snapshots;
DROP TABLE IF EXISTS formal_receipts;
DROP TABLE IF EXISTS formal_sales_shipment_items;
DROP TABLE IF EXISTS formal_sales_shipments;

-- 3. 期初库存旧入口候选表（items 先于 batches）
DROP TABLE IF EXISTS product_inventory_opening_items;
DROP TABLE IF EXISTS product_inventory_opening_batches;

-- 4. H3Preparation 旧期初准备表（rows/warehouses 先于 batches）
DROP TABLE IF EXISTS h3_preparation_rows;
DROP TABLE IF EXISTS h3_preparation_warehouses;
DROP TABLE IF EXISTS h3_preparation_batches;

-- 5. V249 功能授权目录清理（先于权限删除：system_feature_actions.permission_code 外键依赖 system_permissions.permission_code）
--    覆盖：三类旧功能（SALES_SHIPMENT/FORMAL_RECEIPT/FORMAL_PROFIT）+ 挂在 PRODUCT_SOURCE 下的 resolve 动作。
DELETE FROM system_feature_actions
 WHERE feature_code IN ('SALES_SHIPMENT','FORMAL_RECEIPT','FORMAL_PROFIT')
    OR permission_code LIKE 'sales_shipment.%'
    OR permission_code LIKE 'formal_receipt.%'
    OR permission_code LIKE 'formal_profit.%'
    OR permission_code = 'sales_product_source.resolve';
DELETE FROM system_role_feature_grants WHERE feature_code IN ('SALES_SHIPMENT','FORMAL_RECEIPT','FORMAL_PROFIT');
DELETE FROM system_features WHERE feature_code IN ('SALES_SHIPMENT','FORMAL_RECEIPT','FORMAL_PROFIT');

-- 6. 清理下线域权限码（旧接口 403 依赖注册表；页面/菜单下线后无引用，口径同 V255）
DELETE FROM system_role_permissions
 WHERE permission_id IN (
   SELECT id FROM system_permissions
    WHERE permission_code LIKE 'sales_shipment.%'
       OR permission_code LIKE 'formal_receipt.%'
       OR permission_code LIKE 'formal_profit.%'
       OR permission_code = 'sales_product_source.resolve');
DELETE FROM system_permissions
 WHERE permission_code LIKE 'sales_shipment.%'
    OR permission_code LIKE 'formal_receipt.%'
    OR permission_code LIKE 'formal_profit.%'
    OR permission_code = 'sales_product_source.resolve';
