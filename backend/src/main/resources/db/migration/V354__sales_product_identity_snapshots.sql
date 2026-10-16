-- 销售订单配置 → 生产产出：把“制程品类”“规范显示名”“SKU 身份键”拆开。
ALTER TABLE erp_sales_order_items
  ADD COLUMN process_category_snapshot VARCHAR(500) NULL COMMENT '制程解析用基础品类，不等于最终产品显示名' AFTER product_name_snapshot,
  ADD COLUMN sku_key_snapshot VARCHAR(500) NULL COMMENT '配置确认时生成的稳定产品身份键' AFTER process_category_snapshot,
  ADD KEY idx_esoi_sku_key (sku_key_snapshot);

ALTER TABLE manufacturing_orders
  ADD COLUMN process_category_snapshot VARCHAR(500) NULL COMMENT '制程解析用基础品类' AFTER product_name_snapshot,
  ADD COLUMN sku_key_snapshot VARCHAR(500) NULL COMMENT '主产出的配置身份键' AFTER process_category_snapshot,
  ADD KEY idx_mo_sku_key (sku_key_snapshot);

ALTER TABLE manufacturing_order_outputs
  ADD COLUMN sku_key VARCHAR(500) NULL COMMENT '规格化产出的配置身份键；非标工装为空' AFTER product_name_snapshot,
  ADD COLUMN identity_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_PRODUCT'
    COMMENT 'RESOLVED/PENDING_PRODUCT/NON_STANDARD' AFTER sku_key,
  ADD KEY idx_moo_sku_key (sku_key);

-- 存量名称只能确定为历史显示快照，不能反推出配置身份键。
UPDATE erp_sales_order_items
   SET process_category_snapshot=product_name_snapshot
 WHERE process_category_snapshot IS NULL;
UPDATE manufacturing_orders
   SET process_category_snapshot=product_name_snapshot
 WHERE process_category_snapshot IS NULL;
UPDATE manufacturing_order_outputs
   SET identity_status=CASE WHEN sales_product_id IS NOT NULL THEN 'RESOLVED' ELSE 'PENDING_PRODUCT' END;
