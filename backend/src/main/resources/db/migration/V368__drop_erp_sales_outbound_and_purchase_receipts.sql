-- V368: 删除 ERP 自建销售出库与外采到货死表（erp_sales_outbounds / erp_purchase_receipts 及子表）
-- 背景：V321 创建的 erp_sales_outbounds / erp_sales_outbound_items 是 ERP 自建出库单的尝试，
--       但系统已全面转向氚云导入源数据（sales_outbounds 台账），erp_ 前缀出库表从未写入、无 Java 引用。
--       V345 创建的 erp_purchase_receipts / erp_purchase_receipt_items 是外采到货入库链，
--       父表 erp_purchase_orders 已在 V364 删除，FK fk_epr_po 已摘除，两表 0 行、无 Java 引用。
--       erp_sales_outbounds.fulfillment_source（V345 代发标记）随主表一起 DROP。

-- 按依赖反序：先子表后父表
DROP TABLE IF EXISTS erp_purchase_receipt_items;
DROP TABLE IF EXISTS erp_purchase_receipts;
DROP TABLE IF EXISTS erp_sales_outbound_items;
DROP TABLE IF EXISTS erp_sales_outbounds;
