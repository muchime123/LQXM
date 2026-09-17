-- 当前生产模块已整体撤回等待重建：氚云生产工单仅保留为来源历史台账，
-- 不再提供“接入正式生产任务”的旧设计。
ALTER TABLE production_orders DROP INDEX idx_po_linked_manufacturing;
ALTER TABLE production_orders
    DROP COLUMN linked_manufacturing_order_no,
    DROP COLUMN linked_manufacturing_order_id;

-- 月度提成尚未投入使用：保留比例配置、历史比例和核算代码，清理全部测试核算结果。
DELETE FROM commission_calculation_source_lines;
DELETE FROM commission_calculation_items;
DELETE FROM commission_calculation_versions;
DELETE FROM commission_outbound_changes;
DELETE FROM commission_outbound_baselines;

-- 销售出库利润公式与重算能力继续保留，只清理现有测试计算结果与人工测试调整。
DELETE FROM sales_outbound_commissions;
DELETE FROM sales_outbound_profit_adjustments;
DELETE FROM sales_outbound_profits;

-- 统一库存尚未正式投入使用：保留表结构、库存引擎和台账合并能力，清理测试余额与流水。
DELETE FROM product_inventory_transactions;
DELETE FROM product_inventory_balances;

-- 销售与生产业务关联设计已放弃，清理旧关联数据；表暂保留供下一轮清除旧业务单链时安全拆除。
DELETE FROM business_order_links;
