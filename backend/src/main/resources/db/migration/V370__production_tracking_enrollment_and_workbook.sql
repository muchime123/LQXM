-- Follow-up is an explicit work queue, not every imported H3 production task.
CREATE TABLE IF NOT EXISTS production_tracking_enrollments (
  production_order_id BIGINT NOT NULL PRIMARY KEY,
  origin VARCHAR(20) NOT NULL,
  enrolled_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_pte_enrolled_order FOREIGN KEY (production_order_id) REFERENCES production_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产跟单纳入范围';

-- Retain any task already followed before introducing the explicit queue.
INSERT IGNORE INTO production_tracking_enrollments(production_order_id,origin)
SELECT production_order_id,'MANUAL' FROM production_tracking_state;

CREATE TABLE IF NOT EXISTS production_tracking_workbook_rows (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  sheet_name VARCHAR(100) NOT NULL,
  sheet_row INT NOT NULL,
  source_order_no VARCHAR(100) NULL,
  product_name VARCHAR(300) NULL,
  required_completion_date DATE NULL,
  payload_json JSON NOT NULL,
  match_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  production_order_id BIGINT NULL,
  match_note VARCHAR(500) NULL,
  imported_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_ptwr_sheet_row (sheet_name,sheet_row),
  KEY idx_ptwr_status (match_status,id),
  CONSTRAINT fk_ptwr_production_order FOREIGN KEY (production_order_id) REFERENCES production_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='车间进度表逐行核对与导入记录';
