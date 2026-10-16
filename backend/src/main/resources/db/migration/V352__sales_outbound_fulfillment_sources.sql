-- 发货明细保留实物来源，销售明细关联保持不变；历史出库不回填、不重新扣账。
ALTER TABLE erp_sales_outbound_items
  ADD COLUMN source_finished_goods_lot_id BIGINT NULL COMMENT '原生产任务完工入库的成品批次',
  ADD COLUMN source_purchase_item_id BIGINT NULL COMMENT '原外采采购明细的到货来源',
  ADD KEY idx_esoi_finished_lot (source_finished_goods_lot_id),
  ADD KEY idx_esoi_purchase_item (source_purchase_item_id);
