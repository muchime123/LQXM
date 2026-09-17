-- 生产执行第二阶段：排产、工序分派和生产异常闭环。
ALTER TABLE manufacturing_orders
  ADD COLUMN priority VARCHAR(20) NOT NULL DEFAULT 'NORMAL' AFTER workshop_type,
  ADD COLUMN planned_start_date DATE NULL AFTER priority,
  ADD COLUMN planned_finish_date DATE NULL AFTER planned_start_date,
  ADD COLUMN production_team_name VARCHAR(100) NULL AFTER planned_finish_date,
  ADD COLUMN responsible_employee_id BIGINT NULL AFTER production_team_name,
  ADD COLUMN responsible_employee_name VARCHAR(100) NULL AFTER responsible_employee_id,
  ADD KEY idx_manufacturing_schedule (priority,planned_finish_date),
  ADD KEY idx_manufacturing_responsible (responsible_employee_id);

ALTER TABLE manufacturing_order_operations
  ADD COLUMN assigned_team_name VARCHAR(100) NULL AFTER allow_parallel,
  ADD COLUMN assigned_employee_id BIGINT NULL AFTER assigned_team_name,
  ADD COLUMN assigned_employee_name VARCHAR(100) NULL AFTER assigned_employee_id,
  ADD KEY idx_order_operation_assignee (assigned_employee_id,status);

CREATE TABLE manufacturing_issues (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  issue_no VARCHAR(50) NOT NULL,
  manufacturing_order_id BIGINT NOT NULL,
  manufacturing_order_operation_id BIGINT NULL,
  issue_type VARCHAR(30) NOT NULL,
  severity VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
  description VARCHAR(1000) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  reporter_employee_id BIGINT NULL,
  reporter_name_snapshot VARCHAR(100) NULL,
  reported_at DATETIME NOT NULL,
  resolver_employee_id BIGINT NULL,
  resolver_name_snapshot VARCHAR(100) NULL,
  resolved_at DATETIME NULL,
  resolution VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_manufacturing_issue_no (issue_no),
  KEY idx_manufacturing_issue_order (manufacturing_order_id,status),
  KEY idx_manufacturing_issue_operation (manufacturing_order_operation_id,status),
  CONSTRAINT fk_manufacturing_issue_order FOREIGN KEY (manufacturing_order_id) REFERENCES manufacturing_orders(id),
  CONSTRAINT fk_manufacturing_issue_operation FOREIGN KEY (manufacturing_order_operation_id) REFERENCES manufacturing_order_operations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产异常处理流水';

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('manufacturing.schedule','生产排产与工序分派'),
 ('manufacturing.issue.report','上报生产异常'),
 ('manufacturing.issue.manage','处理生产异常');

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS','PRODUCTION_MANAGER') AND p.permission_code IN ('manufacturing.schedule','manufacturing.issue.report','manufacturing.issue.manage');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='PRODUCTION' AND p.permission_code='manufacturing.issue.report';
