-- Time gap investigations are append-only follow-up facts. They never rewrite imported H3 dates.
CREATE TABLE production_tracking_time_reviews (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  production_order_id BIGINT NOT NULL,
  kind VARCHAR(24) NOT NULL COMMENT 'ACCEPTANCE / COMMITMENT / REPORTING / WAREHOUSING',
  status VARCHAR(24) NOT NULL COMMENT 'OPEN / RESOLVED / ON_TIME / NEGOTIATED',
  reason VARCHAR(500) NOT NULL,
  contact_name VARCHAR(100) NULL,
  agreed_date DATE NULL,
  next_followup_date DATE NULL,
  source_due_date DATE NULL,
  source_expected_date DATE NULL,
  created_by_employee_id BIGINT NOT NULL,
  created_by_name VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_pttr_order FOREIGN KEY (production_order_id) REFERENCES production_orders(id),
  KEY idx_pttr_latest (production_order_id,kind,id),
  KEY idx_pttr_followup (next_followup_date,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产跟单时间间隔核实与交期协商记录';
