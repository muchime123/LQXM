-- Manual follow-up milestones and notes for H3 production tasks. Source rows remain untouched.
ALTER TABLE production_tracking_state
  ADD COLUMN delivery_result VARCHAR(100) NULL,
  ADD COLUMN shipment_status VARCHAR(100) NULL,
  ADD COLUMN remark VARCHAR(1000) NULL;

CREATE TABLE production_tracking_milestones (
  production_order_id BIGINT NOT NULL,
  stage_code VARCHAR(30) NOT NULL,
  stage_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  completed_date DATE NULL,
  note VARCHAR(500) NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (production_order_id, stage_code),
  CONSTRAINT fk_ptm_production_order FOREIGN KEY (production_order_id) REFERENCES production_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云生产任务本地跟单节点';
