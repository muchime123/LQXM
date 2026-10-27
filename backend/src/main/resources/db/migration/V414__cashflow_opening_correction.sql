-- B-3.3 candidate. Do not deploy before the pending Flyway queue is reviewed.
-- A correction request retains both amount snapshots; applying it never changes confirmation history.
CREATE TABLE cashflow_opening_correction (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  opening_id BIGINT NOT NULL,
  old_amount DECIMAL(18,4) NOT NULL,
  new_amount DECIMAL(18,4) NOT NULL,
  original_finance_confirmed TINYINT(1) NOT NULL,
  original_confirmation_id BIGINT NULL,
  reason VARCHAR(255) NOT NULL,
  evidence_ref VARCHAR(255) NOT NULL,
  requested_by BIGINT NOT NULL,
  requested_by_name VARCHAR(100) NOT NULL,
  requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  applied_by BIGINT NULL,
  applied_by_name VARCHAR(100) NULL,
  applied_at DATETIME NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  request_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  apply_request_key VARCHAR(100) COLLATE utf8mb4_bin NULL,
  pending_opening_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='PENDING' THEN opening_id ELSE NULL END) STORED,
  UNIQUE KEY uk_cash_opening_correction_request (request_key),
  UNIQUE KEY uk_cash_opening_correction_apply (apply_request_key),
  UNIQUE KEY uk_cash_opening_correction_pending (pending_opening_id),
  KEY idx_cash_opening_correction_history (opening_id, id),
  CONSTRAINT fk_cash_opening_correction_opening FOREIGN KEY (opening_id)
    REFERENCES cashflow_account_opening(id),
  CONSTRAINT fk_cash_opening_correction_confirmation FOREIGN KEY (original_confirmation_id)
    REFERENCES cashflow_opening_confirmation(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO system_permissions(permission_code, permission_name)
VALUES ('cashflow.opening.correct', '申请及应用资金账户年度期初更正');

INSERT IGNORE INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order)
VALUES ('CASHFLOW', 'EXECUTE', 'cashflow.opening.correct', NULL, 4);

-- No role grant here: project administrators must explicitly assign the independent correction permission.
