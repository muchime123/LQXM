-- B-3.2: annual opening finance confirmation. Candidate only until the migration queue is reviewed.
-- evidence_ref is an external voucher number; Finance retains the source document.
CREATE TABLE cashflow_opening_confirmation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  opening_id BIGINT NOT NULL,
  opening_amount_snapshot DECIMAL(18,4) NOT NULL,
  evidence_type VARCHAR(32) NOT NULL,
  balance_as_of_date DATE NOT NULL,
  verified_balance DECIMAL(18,4) NOT NULL,
  evidence_ref VARCHAR(255) NOT NULL,
  note VARCHAR(255) NOT NULL,
  confirmed_by BIGINT NOT NULL,
  confirmed_by_name VARCHAR(100) NOT NULL,
  confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  request_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
  UNIQUE KEY uk_cash_opening_confirm_request (request_key),
  KEY idx_cash_opening_confirm_opening (opening_id, confirmed_at),
  CONSTRAINT fk_cash_opening_confirm_opening FOREIGN KEY (opening_id)
    REFERENCES cashflow_account_opening(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO system_permissions(permission_code, permission_name)
VALUES ('cashflow.opening.confirm', '核对确认资金账户年度期初');

INSERT IGNORE INTO system_feature_actions(feature_code, action_kind, permission_code, variant, sort_order)
VALUES ('CASHFLOW', 'EXECUTE', 'cashflow.opening.confirm', NULL, 3);

-- Grant only to the explicitly designated FINANCE role; cashflow.manage does not imply confirmation.
UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
   SET g.selected_actions=CONCAT_WS(',', NULLIF(g.selected_actions,''), 'cashflow.opening.confirm')
 WHERE r.role_code='FINANCE' AND g.feature_code='CASHFLOW'
   AND FIND_IN_SET('cashflow.opening.confirm',g.selected_actions)=0;

INSERT IGNORE INTO system_role_permissions(role_id, permission_id)
SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='FINANCE' AND p.permission_code='cashflow.opening.confirm';
