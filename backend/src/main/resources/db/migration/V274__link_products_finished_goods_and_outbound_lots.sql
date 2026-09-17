-- 生产工单和成品批次保留标准产品型号/档位快照；历史销售出库可独立追溯成品批次。
ALTER TABLE manufacturing_orders
  ADD COLUMN product_model_id BIGINT NULL,
  ADD COLUMN product_machine_tier_id BIGINT NULL,
  ADD COLUMN product_model_name_snapshot VARCHAR(200) NULL,
  ADD KEY idx_mo_product_model (product_model_id),
  ADD KEY idx_mo_machine_tier (product_machine_tier_id),
  ADD CONSTRAINT fk_mo_product_model FOREIGN KEY (product_model_id) REFERENCES product_models(id),
  ADD CONSTRAINT fk_mo_machine_tier FOREIGN KEY (product_machine_tier_id) REFERENCES product_machine_tiers(id);

ALTER TABLE finished_goods_lots
  ADD COLUMN product_model_id BIGINT NULL,
  ADD COLUMN product_machine_tier_id BIGINT NULL,
  ADD COLUMN product_model_name_snapshot VARCHAR(200) NULL,
  ADD KEY idx_fgl_product_model (product_model_id),
  ADD KEY idx_fgl_machine_tier (product_machine_tier_id),
  ADD CONSTRAINT fk_fgl_product_model FOREIGN KEY (product_model_id) REFERENCES product_models(id),
  ADD CONSTRAINT fk_fgl_machine_tier FOREIGN KEY (product_machine_tier_id) REFERENCES product_machine_tiers(id);

ALTER TABLE sales_outbound_items
  ADD COLUMN lot_trace_status VARCHAR(30) NOT NULL DEFAULT 'HISTORICAL_UNKNOWN'
    COMMENT 'HISTORICAL_UNKNOWN/UNALLOCATED/PARTIALLY_ALLOCATED/ALLOCATED';

CREATE TABLE sales_outbound_finished_goods_allocations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sales_outbound_id BIGINT NOT NULL,
  sales_outbound_item_id BIGINT NOT NULL,
  finished_goods_lot_id BIGINT NOT NULL,
  allocated_quantity DECIMAL(18,6) NOT NULL,
  allocated_by_employee_id BIGINT NULL,
  allocated_by_name VARCHAR(100) NULL,
  allocated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  remark VARCHAR(500) NULL,
  UNIQUE KEY uk_outbound_item_lot (sales_outbound_item_id, finished_goods_lot_id),
  KEY idx_outbound_lot_allocation_outbound (sales_outbound_id),
  KEY idx_outbound_lot_allocation_lot (finished_goods_lot_id),
  CONSTRAINT fk_outbound_lot_allocation_outbound FOREIGN KEY (sales_outbound_id)
    REFERENCES sales_outbounds(id) ON DELETE CASCADE,
  CONSTRAINT fk_outbound_lot_allocation_item FOREIGN KEY (sales_outbound_item_id)
    REFERENCES sales_outbound_items(id) ON DELETE CASCADE,
  CONSTRAINT fk_outbound_lot_allocation_lot FOREIGN KEY (finished_goods_lot_id)
    REFERENCES finished_goods_lots(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='Sales outbound item to finished-goods lot allocation';
