-- 氚云全字段导出的 Excel 中，订单备注/收款备注/客户地址/可选配置等字段
-- 经常超过 VARCHAR(1000) 甚至 VARCHAR(500) 的限制，导致导入时报 Data truncation 错误。
-- 统一将这些"长文本类"字段放宽为 TEXT（MySQL TEXT 最大 65535 字节，约 2 万中文）。

-- sales_outbounds
ALTER TABLE sales_outbounds MODIFY COLUMN order_remark TEXT NULL;
ALTER TABLE sales_outbounds MODIFY COLUMN collection_remark TEXT NULL;
ALTER TABLE sales_outbounds MODIFY COLUMN customer_address TEXT NULL;
ALTER TABLE sales_outbounds MODIFY COLUMN external_title TEXT NULL;

-- sales_outbound_items
ALTER TABLE sales_outbound_items MODIFY COLUMN remark TEXT NULL;
ALTER TABLE sales_outbound_items MODIFY COLUMN external_title TEXT NULL;
ALTER TABLE sales_outbound_items MODIFY COLUMN product_name TEXT NULL;
ALTER TABLE sales_outbound_items MODIFY COLUMN specification TEXT NULL;

-- receipts
ALTER TABLE receipts MODIFY COLUMN remark TEXT NULL;

-- receipt_allocations
ALTER TABLE receipt_allocations MODIFY COLUMN external_title TEXT NULL;

-- production_orders
ALTER TABLE production_orders MODIFY COLUMN optional_configuration TEXT NULL;
ALTER TABLE production_orders MODIFY COLUMN production_requirement TEXT NULL;
ALTER TABLE production_orders MODIFY COLUMN linked_sales_order_ref TEXT NULL;

-- production_order_components
ALTER TABLE production_order_components MODIFY COLUMN external_title TEXT NULL;
ALTER TABLE production_order_components MODIFY COLUMN component_name TEXT NULL;
ALTER TABLE production_order_components MODIFY COLUMN component_ref TEXT NULL;
