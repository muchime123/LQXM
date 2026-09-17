-- 销售出库退换货治理：系统推断与人工确认分离。
-- 氚云重导只刷新 inferred_*；confirmed_* 与原单关系由人工维护，不得被覆盖。
ALTER TABLE sales_outbounds
  ADD COLUMN inferred_business_type VARCHAR(30) NOT NULL DEFAULT 'SALE' COMMENT '系统推断：SALE/RETURN/EXCHANGE/RETURN_OR_EXCHANGE',
  ADD COLUMN business_type_confidence INT NOT NULL DEFAULT 100 COMMENT '系统推断置信度 0-100',
  ADD COLUMN business_type_reason VARCHAR(500) NULL COMMENT '系统推断依据',
  ADD COLUMN business_type_confirmed TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否人工确认业务类型',
  ADD COLUMN confirmed_business_type VARCHAR(30) NULL COMMENT '人工确认：SALE/RETURN/EXCHANGE',
  ADD COLUMN original_outbound_id BIGINT NULL COMMENT '退换货关联的原销售出库ID',
  ADD COLUMN business_type_confirmed_by VARCHAR(100) NULL,
  ADD COLUMN business_type_confirmed_at DATETIME NULL,
  ADD INDEX idx_so_inferred_business_type (inferred_business_type),
  ADD INDEX idx_so_original_outbound (original_outbound_id);

-- 为存量数据建立可解释的初始推断；混合正负产品行优先视为疑似换货。
UPDATE sales_outbounds o
SET inferred_business_type = CASE
      WHEN EXISTS (SELECT 1 FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.quantity<0)
       AND EXISTS (SELECT 1 FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.quantity>0) THEN 'EXCHANGE'
      WHEN COALESCE(o.sales_amount,0)<0
        OR EXISTS (SELECT 1 FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.quantity<0) THEN 'RETURN'
      WHEN COALESCE(o.shipping_method,'') LIKE '%退换货%'
        OR COALESCE(o.order_remark,'') REGEXP '退货|换货|补发|重发' THEN 'RETURN_OR_EXCHANGE'
      ELSE 'SALE' END,
    business_type_confidence = CASE
      WHEN (EXISTS (SELECT 1 FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.quantity<0)
        AND EXISTS (SELECT 1 FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.quantity>0)) THEN 90
      WHEN COALESCE(o.sales_amount,0)<0
        OR EXISTS (SELECT 1 FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.quantity<0) THEN 95
      WHEN COALESCE(o.shipping_method,'') LIKE '%退换货%'
        OR COALESCE(o.order_remark,'') REGEXP '退货|换货|补发|重发' THEN 60
      ELSE 100 END,
    business_type_reason = CASE
      WHEN EXISTS (SELECT 1 FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.quantity<0)
       AND EXISTS (SELECT 1 FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.quantity>0) THEN '同时存在正数与负数产品明细，疑似换货'
      WHEN COALESCE(o.sales_amount,0)<0 THEN '销售金额为负数，疑似退货'
      WHEN EXISTS (SELECT 1 FROM sales_outbound_items i WHERE i.sales_outbound_id=o.id AND i.quantity<0) THEN '存在负数出库产品明细，疑似退货'
      WHEN COALESCE(o.shipping_method,'') LIKE '%退换货%' THEN '运输方式包含退换货，需人工确认'
      WHEN COALESCE(o.order_remark,'') REGEXP '退货|换货|补发|重发' THEN '订单备注包含退换货相关关键词，需人工确认'
      ELSE '未发现退换货特征' END;
