-- V364: 删除 ERP 自建业务流死表（销售订单/采购订单/履约审核/执行异常/审批流水）
-- 背景：系统已全面转向氚云导入源数据处理，不再自建销售订单、采购订单等业务单据。
--       这些表无任何 Java 代码引用（全仓 grep 空），前端无对应页面，直接 DROP。
--
-- ⚠️ 2026-10-08 修正（阿简）：本迁移首跑两次都失败（errno 3730「Cannot drop table ... referenced by
-- a foreign key constraint」）。原因是 MySQL 不允许删除仍被外键引用的父表，而原脚本
--   ① 漏了「未删表」上的两条外键；
--   ② 这 8 张表之间本身就有 6 条外键，原删除顺序是子表在前/父表在后混着来的。
-- 现改为：先解除两张**保留表**上的外键（幂等，避免重跑报 1091），再按依赖拓扑序删表。
-- 保留表上的两条外键：
--   manufacturing_orders.fk_mo_sales_order_item → erp_sales_order_items
--   erp_purchase_receipts.fk_epr_po            → erp_purchase_orders
-- 两列均无任何代码引用，manufacturing_orders 当前 0 行；erp_purchase_receipts 保留（0 行）。
--
-- 表间外键（供核对删除顺序）：
--   erp_purchase_order_items.fk_epoi_po        → erp_purchase_orders
--   erp_purchase_orders.fk_epo_sales_order_item→ erp_sales_order_items
--   erp_sales_order_changes.fk_esoc_exception  → erp_sales_order_exceptions
--   erp_sales_order_exceptions.fk_esoe_order   → erp_sales_orders
--   erp_sales_order_fulfillment_reviews.fk_esofr_order → erp_sales_orders
--   erp_sales_order_items.fk_esoi_order        → erp_sales_orders

-- 1) 解除保留表上的外键引用（幂等：不存在则执行 DO 0）
SET @fk_sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'manufacturing_orders'
      AND CONSTRAINT_NAME = 'fk_mo_sales_order_item') > 0,
  'ALTER TABLE manufacturing_orders DROP FOREIGN KEY fk_mo_sales_order_item',
  'DO 0');
PREPARE fk_stmt FROM @fk_sql;
EXECUTE fk_stmt;
DEALLOCATE PREPARE fk_stmt;

SET @fk_sql := IF(
  (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_purchase_receipts'
      AND CONSTRAINT_NAME = 'fk_epr_po') > 0,
  'ALTER TABLE erp_purchase_receipts DROP FOREIGN KEY fk_epr_po',
  'DO 0');
PREPARE fk_stmt FROM @fk_sql;
EXECUTE fk_stmt;
DEALLOCATE PREPARE fk_stmt;

-- 2) 按依赖拓扑序删表：子表（引用方）在前，父表（被引用方）在后
DROP TABLE IF EXISTS erp_sales_order_changes;             -- → erp_sales_order_exceptions
DROP TABLE IF EXISTS erp_sales_order_fulfillment_reviews; -- → erp_sales_orders
DROP TABLE IF EXISTS erp_sales_order_exceptions;          -- → erp_sales_orders
DROP TABLE IF EXISTS erp_purchase_order_items;            -- → erp_purchase_orders
DROP TABLE IF EXISTS erp_purchase_orders;                 -- → erp_sales_order_items
DROP TABLE IF EXISTS erp_sales_order_items;               -- → erp_sales_orders
DROP TABLE IF EXISTS erp_sales_orders;
DROP TABLE IF EXISTS erp_approvals;
