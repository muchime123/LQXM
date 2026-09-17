-- 氚云生产工单接入本地正式生产主链
-- 1) 正式生产任务记录氚云来源（唯一索引防止重复接入，NULL 不冲突）
ALTER TABLE manufacturing_orders
  ADD COLUMN external_source VARCHAR(30) NULL AFTER task_type,
  ADD COLUMN external_id VARCHAR(100) NULL AFTER external_source,
  ADD COLUMN h3_work_order_no VARCHAR(50) NULL AFTER external_id,
  ADD UNIQUE KEY uk_manufacturing_external (external_source, external_id);

-- 2) 氚云生产工单记录已接入的正式生产任务（台账可见关联）
ALTER TABLE production_orders
  ADD COLUMN linked_manufacturing_order_id BIGINT NULL AFTER product_order_external_id,
  ADD COLUMN linked_manufacturing_order_no VARCHAR(50) NULL AFTER linked_manufacturing_order_id,
  ADD KEY idx_po_linked_manufacturing (linked_manufacturing_order_id);
