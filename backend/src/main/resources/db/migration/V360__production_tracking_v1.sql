-- Production tracking is a local planning and communication layer over manufacturing_orders.
-- It does not update source facts, reports, receipts or inventory.
CREATE TABLE production_tracking_state (
  manufacturing_order_id BIGINT NOT NULL PRIMARY KEY,
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
  CONSTRAINT fk_pts_order FOREIGN KEY (manufacturing_order_id) REFERENCES manufacturing_orders(id),
  KEY idx_pts_next (next_followup_date),
  KEY idx_pts_assignee (assignee_employee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产跟单当前计划与风险';

CREATE TABLE production_tracking_entries (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  manufacturing_order_id BIGINT NOT NULL,
  version BIGINT NOT NULL,
  content VARCHAR(1000) NOT NULL,
  before_json JSON NOT NULL,
  after_json JSON NOT NULL,
  created_by_employee_id BIGINT NOT NULL,
  created_by_name VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_pte_order FOREIGN KEY (manufacturing_order_id) REFERENCES manufacturing_orders(id),
  UNIQUE KEY uk_pte_version (manufacturing_order_id, version),
  KEY idx_pte_time (manufacturing_order_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产跟单逐次记录';

INSERT IGNORE INTO system_permissions(permission_code, permission_name)
VALUES ('manufacturing.followup', '记录生产跟单计划与风险');
INSERT IGNORE INTO system_feature_actions(feature_code,action_kind,permission_code,sort_order)
VALUES ('MANUFACTURING','UPDATE','manufacturing.followup',90);
INSERT IGNORE INTO system_role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
WHERE r.role_code IN ('ADMIN','BOSS','PRODUCTION_MANAGER','PRODUCTION_TRACER')
  AND p.permission_code='manufacturing.followup';
UPDATE system_role_feature_grants g JOIN system_roles r ON r.id=g.role_id
SET g.selected_actions=CONCAT_WS(',',NULLIF(g.selected_actions,''),'manufacturing.followup')
WHERE g.feature_code='MANUFACTURING'
  AND r.role_code IN ('ADMIN','BOSS','PRODUCTION_MANAGER','PRODUCTION_TRACER')
  AND FIND_IN_SET('manufacturing.followup',g.selected_actions)=0;
