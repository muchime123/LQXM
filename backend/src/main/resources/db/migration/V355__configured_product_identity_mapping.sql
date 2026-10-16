-- 配置产品身份映射：同一 sku_key 跨销售订单、生产任务和库存产品只允许一条正式映射。
ALTER TABLE sales_products
  ADD COLUMN sku_key VARCHAR(500) NULL COMMENT '结构化产品配置生成的稳定身份键；历史氚云产品可为空' AFTER product_code,
  ADD UNIQUE KEY uk_sp_sku_key (sku_key);

-- V354 已绑定正式产品的产出直接视为已解析；待审核档案由后续新链路标记 PENDING_REVIEW。
UPDATE manufacturing_order_outputs
   SET identity_status='RESOLVED'
 WHERE sales_product_id IS NOT NULL;
