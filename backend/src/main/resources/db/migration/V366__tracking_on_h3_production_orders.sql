-- Tracking belongs to the imported H3 production task, not the retired local order.
-- Keep the old tables as archives so installations with earlier manual entries retain them.
RENAME TABLE production_tracking_state TO production_tracking_state_legacy_20261008;
RENAME TABLE production_tracking_entries TO production_tracking_entries_legacy_20261008;

CREATE TABLE production_tracking_state (
  production_order_id BIGINT NOT NULL PRIMARY KEY,
  planned_start_date DATE NULL,
  forecast_finish_date DATE NULL,
  risk_category VARCHAR(30) NULL,
  delay_reason VARCHAR(300) NULL,
  next_followup_date DATE NULL,
  assignee_employee_id BIGINT NULL,
  assignee_name VARCHAR(100) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  last_followup_at DATETIME NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_pts_source_production FOREIGN KEY (production_order_id) REFERENCES production_orders(id),
  KEY idx_pts_source_next (next_followup_date),
  KEY idx_pts_source_assignee (assignee_employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云生产任务跟单状态';

CREATE TABLE production_tracking_entries (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  production_order_id BIGINT NOT NULL,
  version BIGINT NOT NULL,
  content VARCHAR(1000) NOT NULL,
  before_json JSON NOT NULL,
  after_json JSON NOT NULL,
  created_by_employee_id BIGINT NOT NULL,
  created_by_name VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_pte_source_production FOREIGN KEY (production_order_id) REFERENCES production_orders(id),
  UNIQUE KEY uk_pte_source_version (production_order_id, version),
  KEY idx_pte_source_time (production_order_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云生产任务跟单历史';

-- Earlier tracking records, if present, follow the matching H3 external ID.
-- Records without an H3 source remain in the legacy archive for manual review.
INSERT INTO production_tracking_state (
  production_order_id, planned_start_date, forecast_finish_date, risk_category,
  delay_reason, next_followup_date, assignee_employee_id, assignee_name,
  version, last_followup_at, updated_at
)
SELECT p.id, t.planned_start_date, t.forecast_finish_date, t.risk_category,
       t.delay_reason, t.next_followup_date, t.assignee_employee_id, t.assignee_name,
       t.version, t.last_followup_at, t.updated_at
FROM production_tracking_state_legacy_20261008 t
JOIN manufacturing_orders m ON m.id = t.manufacturing_order_id AND m.external_source = 'H3YUN'
JOIN production_orders p ON p.external_source = 'H3YUN' AND BINARY p.external_id = BINARY m.external_id;

INSERT INTO production_tracking_entries (
  id, production_order_id, version, content, before_json, after_json,
  created_by_employee_id, created_by_name, created_at
)
SELECT e.id, p.id, e.version, e.content, e.before_json, e.after_json,
       e.created_by_employee_id, e.created_by_name, e.created_at
FROM production_tracking_entries_legacy_20261008 e
JOIN manufacturing_orders m ON m.id = e.manufacturing_order_id AND m.external_source = 'H3YUN'
JOIN production_orders p ON p.external_source = 'H3YUN' AND BINARY p.external_id = BINARY m.external_id;

-- Avoid reusing an archived entry ID in row_change_log when old entries lacked a source match.
SET @tracking_next_entry_id = (
  SELECT COALESCE(MAX(id), 0) + 1 FROM production_tracking_entries_legacy_20261008
);
SET @tracking_auto_increment_sql = CONCAT(
  'ALTER TABLE production_tracking_entries AUTO_INCREMENT = ', @tracking_next_entry_id
);
PREPARE tracking_auto_increment_stmt FROM @tracking_auto_increment_sql;
EXECUTE tracking_auto_increment_stmt;
DEALLOCATE PREPARE tracking_auto_increment_stmt;
