-- 正式生产执行：工序字典、制程模板、任务工序快照、报工流水与检验。
CREATE TABLE manufacturing_operations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  operation_code VARCHAR(50) NOT NULL,
  operation_name VARCHAR(100) NOT NULL,
  operation_category VARCHAR(30) NOT NULL DEFAULT 'OTHER',
  description VARCHAR(500) NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  sort_order INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_manufacturing_operation_code (operation_code),
  UNIQUE KEY uk_manufacturing_operation_name (operation_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产工序主数据';

CREATE TABLE manufacturing_process_templates (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_code VARCHAR(50) NOT NULL,
  template_name VARCHAR(100) NOT NULL,
  workshop_type VARCHAR(100) NULL,
  description VARCHAR(500) NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  version INT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_manufacturing_template_code (template_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产制程模板';

CREATE TABLE manufacturing_process_template_operations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  template_id BIGINT NOT NULL,
  operation_id BIGINT NOT NULL,
  sequence_no INT NOT NULL,
  requirement_coefficient DECIMAL(18,6) NOT NULL DEFAULT 1,
  required TINYINT(1) NOT NULL DEFAULT 1,
  is_final TINYINT(1) NOT NULL DEFAULT 0,
  allow_parallel TINYINT(1) NOT NULL DEFAULT 0,
  remark VARCHAR(500) NULL,
  UNIQUE KEY uk_template_operation_sequence (template_id,sequence_no),
  KEY idx_template_operation_operation (operation_id),
  CONSTRAINT fk_template_operation_template FOREIGN KEY (template_id) REFERENCES manufacturing_process_templates(id),
  CONSTRAINT fk_template_operation_operation FOREIGN KEY (operation_id) REFERENCES manufacturing_operations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='制程模板工序';

ALTER TABLE manufacturing_orders
  ADD COLUMN task_type VARCHAR(30) NOT NULL DEFAULT 'SALES_ORDER' AFTER order_no,
  ADD COLUMN process_template_id BIGINT NULL AFTER task_type,
  ADD COLUMN process_template_name_snapshot VARCHAR(100) NULL AFTER process_template_id,
  ADD COLUMN workshop_type VARCHAR(100) NULL AFTER process_template_name_snapshot,
  ADD COLUMN production_completed_at DATETIME NULL AFTER due_date,
  ADD COLUMN inspection_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED' AFTER production_completed_at,
  ADD COLUMN inspection_at DATETIME NULL AFTER inspection_status,
  ADD COLUMN inspection_by_employee_id BIGINT NULL AFTER inspection_at,
  ADD COLUMN inspection_by_name VARCHAR(100) NULL AFTER inspection_by_employee_id,
  ADD COLUMN inspection_remark VARCHAR(1000) NULL AFTER inspection_by_name,
  ADD KEY idx_manufacturing_template (process_template_id),
  ADD KEY idx_manufacturing_inspection (inspection_status);

CREATE TABLE manufacturing_order_operations (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  manufacturing_order_id BIGINT NOT NULL,
  source_template_operation_id BIGINT NULL,
  operation_id BIGINT NOT NULL,
  sequence_no INT NOT NULL,
  operation_name_snapshot VARCHAR(100) NOT NULL,
  requirement_coefficient DECIMAL(18,6) NOT NULL DEFAULT 1,
  required_quantity DECIMAL(18,6) NOT NULL,
  reported_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  required TINYINT(1) NOT NULL DEFAULT 1,
  is_final TINYINT(1) NOT NULL DEFAULT 0,
  allow_parallel TINYINT(1) NOT NULL DEFAULT 0,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  started_at DATETIME NULL,
  completed_at DATETIME NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_order_operation_sequence (manufacturing_order_id,sequence_no),
  KEY idx_order_operation_status (status),
  KEY idx_order_operation_operation (operation_id),
  CONSTRAINT fk_order_operation_order FOREIGN KEY (manufacturing_order_id) REFERENCES manufacturing_orders(id),
  CONSTRAINT fk_order_operation_operation FOREIGN KEY (operation_id) REFERENCES manufacturing_operations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产任务工序快照';

CREATE TABLE manufacturing_work_reports (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  report_no VARCHAR(50) NOT NULL,
  manufacturing_order_id BIGINT NOT NULL,
  manufacturing_order_operation_id BIGINT NOT NULL,
  reported_quantity DECIMAL(18,6) NOT NULL,
  reported_at DATETIME NOT NULL,
  reporter_employee_id BIGINT NULL,
  reporter_name_snapshot VARCHAR(100) NULL,
  department_name_snapshot VARCHAR(100) NULL,
  overproduction_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  overproduction_reason VARCHAR(500) NULL,
  remark VARCHAR(1000) NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'VALID',
  reversed_report_id BIGINT NULL,
  reversal_reason VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_manufacturing_report_no (report_no),
  KEY idx_work_report_order (manufacturing_order_id),
  KEY idx_work_report_operation (manufacturing_order_operation_id),
  KEY idx_work_report_reporter (reporter_employee_id,reported_at),
  CONSTRAINT fk_work_report_order FOREIGN KEY (manufacturing_order_id) REFERENCES manufacturing_orders(id),
  CONSTRAINT fk_work_report_operation FOREIGN KEY (manufacturing_order_operation_id) REFERENCES manufacturing_order_operations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产报工不可覆盖流水';

CREATE TABLE manufacturing_inspections (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  inspection_no VARCHAR(50) NOT NULL,
  manufacturing_order_id BIGINT NOT NULL,
  inspection_result VARCHAR(30) NOT NULL,
  qualified_quantity DECIMAL(18,6) NOT NULL,
  unqualified_quantity DECIMAL(18,6) NOT NULL DEFAULT 0,
  inspector_employee_id BIGINT NULL,
  inspector_name_snapshot VARCHAR(100) NULL,
  inspected_at DATETIME NOT NULL,
  issue_description VARCHAR(1000) NULL,
  remark VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_manufacturing_inspection_no (inspection_no),
  KEY idx_inspection_order (manufacturing_order_id),
  CONSTRAINT fk_inspection_order FOREIGN KEY (manufacturing_order_id) REFERENCES manufacturing_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产完工检验记录';

INSERT INTO manufacturing_operations(operation_code,operation_name,operation_category,sort_order) VALUES
 ('DESIGN_CONFIRM','设计确认','DESIGN',10),
 ('PRODUCTION_PROCESS','生产加工','FABRICATION',20),
 ('MOLD_PROCESS','模具加工','TOOLING',30),
 ('MACHINE_ASSEMBLY','整机装配','ASSEMBLY',40),
 ('INSTALL_ADAPT','安装适配','ASSEMBLY',45),
 ('PURCHASE_MATERIAL','采购/下料','PURCHASE',50),
 ('SHELL_PROCESS','机壳加工','FABRICATION',60),
 ('SURFACE_PROCESS','表面处理','SURFACE',70),
 ('COMPONENT_ASSEMBLY','部件组装','ASSEMBLY',80),
 ('ELECTRIC_AIR_INSTALL','电气与气路安装','ELECTRICAL',90),
 ('ASSEMBLY_DEBUG','组装调试','DEBUGGING',100),
 ('DEBUG_INSPECTION','调试检验','INSPECTION',110),
 ('MOLD_TRIAL_INSPECTION','试模检验','INSPECTION',120),
 ('COMPLETION_CONFIRM','完成确认','INSPECTION',130);

INSERT INTO manufacturing_process_templates(template_code,template_name,workshop_type,description) VALUES
 ('STANDARD_SEALER','普通封口机','封口机车间','生产加工、整机装配、调试检验'),
 ('CUSTOM_SEALER','订做封口机','封口机车间','设计确认、模具加工、整机装配、调试检验'),
 ('RETURN_MOLD','返单/增加互换模具','封口机车间','模具加工、安装适配、试模检验'),
 ('MOLD_ONLY','单独模具','封口机车间','设计确认、模具加工、表面处理、试模检验'),
 ('SEMI_AUTO_GAS','半自动气调机','半自动气调机车间','气调设备完整制程'),
 ('FULL_AUTO_GAS','全自动气调机','全自动气调机车间','气调设备完整制程'),
 ('ACCESSORY','配件','通用','生产或采购、完成确认');

INSERT INTO manufacturing_process_template_operations(template_id,operation_id,sequence_no,is_final)
SELECT t.id,o.id,x.seq,x.final_flag FROM (
 SELECT 'STANDARD_SEALER' code,'PRODUCTION_PROCESS' op,1 seq,0 final_flag UNION ALL
 SELECT 'STANDARD_SEALER','MACHINE_ASSEMBLY',2,0 UNION ALL SELECT 'STANDARD_SEALER','DEBUG_INSPECTION',3,1 UNION ALL
 SELECT 'CUSTOM_SEALER','DESIGN_CONFIRM',1,0 UNION ALL SELECT 'CUSTOM_SEALER','MOLD_PROCESS',2,0 UNION ALL SELECT 'CUSTOM_SEALER','MACHINE_ASSEMBLY',3,0 UNION ALL SELECT 'CUSTOM_SEALER','DEBUG_INSPECTION',4,1 UNION ALL
 SELECT 'RETURN_MOLD','MOLD_PROCESS',1,0 UNION ALL SELECT 'RETURN_MOLD','INSTALL_ADAPT',2,0 UNION ALL SELECT 'RETURN_MOLD','MOLD_TRIAL_INSPECTION',3,1 UNION ALL
 SELECT 'MOLD_ONLY','DESIGN_CONFIRM',1,0 UNION ALL SELECT 'MOLD_ONLY','MOLD_PROCESS',2,0 UNION ALL SELECT 'MOLD_ONLY','SURFACE_PROCESS',3,0 UNION ALL SELECT 'MOLD_ONLY','MOLD_TRIAL_INSPECTION',4,1 UNION ALL
 SELECT 'SEMI_AUTO_GAS','DESIGN_CONFIRM',1,0 UNION ALL SELECT 'SEMI_AUTO_GAS','PURCHASE_MATERIAL',2,0 UNION ALL SELECT 'SEMI_AUTO_GAS','SHELL_PROCESS',3,0 UNION ALL SELECT 'SEMI_AUTO_GAS','MOLD_PROCESS',4,0 UNION ALL SELECT 'SEMI_AUTO_GAS','SURFACE_PROCESS',5,0 UNION ALL SELECT 'SEMI_AUTO_GAS','COMPONENT_ASSEMBLY',6,0 UNION ALL SELECT 'SEMI_AUTO_GAS','ELECTRIC_AIR_INSTALL',7,0 UNION ALL SELECT 'SEMI_AUTO_GAS','ASSEMBLY_DEBUG',8,0 UNION ALL SELECT 'SEMI_AUTO_GAS','DEBUG_INSPECTION',9,1 UNION ALL
 SELECT 'FULL_AUTO_GAS','DESIGN_CONFIRM',1,0 UNION ALL SELECT 'FULL_AUTO_GAS','PURCHASE_MATERIAL',2,0 UNION ALL SELECT 'FULL_AUTO_GAS','SHELL_PROCESS',3,0 UNION ALL SELECT 'FULL_AUTO_GAS','MOLD_PROCESS',4,0 UNION ALL SELECT 'FULL_AUTO_GAS','SURFACE_PROCESS',5,0 UNION ALL SELECT 'FULL_AUTO_GAS','COMPONENT_ASSEMBLY',6,0 UNION ALL SELECT 'FULL_AUTO_GAS','ELECTRIC_AIR_INSTALL',7,0 UNION ALL SELECT 'FULL_AUTO_GAS','ASSEMBLY_DEBUG',8,0 UNION ALL SELECT 'FULL_AUTO_GAS','DEBUG_INSPECTION',9,1 UNION ALL
 SELECT 'ACCESSORY','PRODUCTION_PROCESS',1,0 UNION ALL SELECT 'ACCESSORY','COMPLETION_CONFIRM',2,1
) x JOIN manufacturing_process_templates t ON t.template_code=x.code JOIN manufacturing_operations o ON o.operation_code=x.op;

INSERT IGNORE INTO system_permissions(permission_code,permission_name) VALUES
 ('manufacturing.template.manage','维护生产工序与制程模板'),
 ('manufacturing.report','提交生产报工'),
 ('manufacturing.report.override','超额报工与报工冲正'),
 ('manufacturing.inspect','办理生产检验');

INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('ADMIN','BOSS') AND p.permission_code IN ('manufacturing.template.manage','manufacturing.report','manufacturing.report.override','manufacturing.inspect');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code IN ('PRODUCTION','PRODUCTION_MANAGER') AND p.permission_code IN ('manufacturing.report','manufacturing.inspect');
INSERT IGNORE INTO system_role_permissions SELECT r.id,p.id FROM system_roles r CROSS JOIN system_permissions p
 WHERE r.role_code='PRODUCTION_MANAGER' AND p.permission_code IN ('manufacturing.template.manage','manufacturing.report.override');
