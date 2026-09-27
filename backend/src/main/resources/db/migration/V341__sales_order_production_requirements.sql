-- 自产需求在销售订单明细阶段确定，生产任务后续直接引用此快照。
ALTER TABLE erp_sales_order_items
  ADD COLUMN production_workshop_id BIGINT NULL COMMENT '销售确认的目标产线',
  ADD COLUMN production_config_json JSON NULL COMMENT '销售确认的产品结构化配置快照',
  ADD KEY idx_esoi_production_workshop (production_workshop_id);
