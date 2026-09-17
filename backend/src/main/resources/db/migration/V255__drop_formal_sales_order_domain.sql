-- 彻底下线「正式销售订单」域：销售出库单升级为唯一开单单据（客户/仓库/产品行/单价/费用全自持）。
-- 背景：库里历史销售订单均为测试数据，直接删除；出库单/批次/生产任务上的订单关联列为历史冗余一并清理。
-- 财务链（出库单金额、内嵌收款 formal_receipts、利润快照 formal_profit_snapshots）全部挂 formal_sales_shipments，
-- 本迁移不触碰它们，只移除对订单域的引用。

-- 1. 删除正式销售订单域三张表（无外键约束，直接 DROP）
DROP TABLE IF EXISTS formal_sales_order_fees;
DROP TABLE IF EXISTS formal_sales_order_items;
DROP TABLE IF EXISTS formal_sales_orders;

-- 2. 移除各业务表对订单的关联列（对应单列索引随 DROP COLUMN 自动删除）
ALTER TABLE formal_sales_shipments
  DROP COLUMN formal_sales_order_id;
ALTER TABLE formal_sales_shipment_items
  DROP COLUMN formal_sales_order_item_id;
ALTER TABLE finished_goods_lots
  DROP COLUMN formal_sales_order_id,
  DROP COLUMN formal_sales_order_item_id;
ALTER TABLE manufacturing_orders
  DROP COLUMN formal_sales_order_id,
  DROP COLUMN formal_sales_order_item_id;

-- 3. 出库单补客户档案关联（可选，快照 customer_name_snapshot 仍是权威展示；无档案的历史单允许 NULL）
ALTER TABLE formal_sales_shipments
  ADD COLUMN customer_id BIGINT NULL COMMENT '客户档案 id（出库开单时选择/快速建档写入；可空，展示以快照为准）' AFTER shipment_no,
  ADD KEY idx_formal_shipment_customer (customer_id);

-- 4. 清理订单域权限码（旧订单接口 403 依赖注册表；页面/菜单下线后无引用）
DELETE rp FROM system_role_permissions rp
  JOIN system_permissions p ON p.id = rp.permission_id
 WHERE p.permission_code LIKE 'formal_sales_order.%';
DELETE FROM system_permissions WHERE permission_code LIKE 'formal_sales_order.%';

-- 5. 清理 V249 功能授权目录中的「正式销售订单」功能（配置页不再展示已下线功能）
DELETE FROM system_feature_actions WHERE feature_code='FORMAL_SALES_ORDER';
DELETE FROM system_role_feature_grants WHERE feature_code='FORMAL_SALES_ORDER';
DELETE FROM system_features WHERE feature_code='FORMAL_SALES_ORDER';
