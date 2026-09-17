-- 氚云生产主数据底座：制程、产品零部件制程、车间人员、任务单配置项。
-- 已下达生产任务仍使用工序快照，不随主数据导入发生变化。

ALTER TABLE manufacturing_operations
  ADD COLUMN external_source VARCHAR(20) NULL AFTER id,
  ADD COLUMN external_id VARCHAR(100) NULL AFTER external_source,
  ADD COLUMN process_image LONGTEXT NULL AFTER description,
  ADD COLUMN source_inventory_quantity DECIMAL(18,6) NULL AFTER process_image,
  ADD COLUMN reporting_qr LONGTEXT NULL AFTER source_inventory_quantity,
  ADD COLUMN source_status VARCHAR(30) NULL AFTER reporting_qr,
  ADD UNIQUE KEY uk_manufacturing_operation_external(external_source,external_id);

ALTER TABLE manufacturing_process_templates
  ADD COLUMN external_source VARCHAR(20) NULL AFTER id,
  ADD COLUMN external_id VARCHAR(100) NULL AFTER external_source,
  ADD COLUMN assembly_reference_image LONGTEXT NULL AFTER description,
  ADD COLUMN source_status VARCHAR(30) NULL AFTER assembly_reference_image,
  ADD UNIQUE KEY uk_manufacturing_template_external(external_source,external_id);

ALTER TABLE manufacturing_process_template_operations
  ADD COLUMN external_source VARCHAR(20) NULL AFTER id,
  ADD COLUMN external_id VARCHAR(100) NULL AFTER external_source,
  ADD COLUMN component_name VARCHAR(200) NULL AFTER operation_id,
  ADD COLUMN component_form VARCHAR(200) NULL AFTER component_name,
  ADD COLUMN reporting_qr LONGTEXT NULL AFTER remark,
  ADD COLUMN component_image LONGTEXT NULL AFTER reporting_qr,
  ADD UNIQUE KEY uk_manufacturing_template_operation_external(external_source,external_id);

CREATE TABLE manufacturing_workshops (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  external_source VARCHAR(20) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(100) NOT NULL,
  workshop_name VARCHAR(200) NOT NULL,
  department_id BIGINT NULL COMMENT '人工映射到统一部门主数据；允许待映射',
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  order_receiver_name VARCHAR(100) NULL,
  order_receiver_employee_id BIGINT NULL,
  warehouse_clerk_name VARCHAR(100) NULL,
  warehouse_clerk_employee_id BIGINT NULL,
  source_owner_name VARCHAR(100) NULL,
  source_department VARCHAR(200) NULL,
  source_status VARCHAR(30) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_manufacturing_workshop_external(external_source,external_id),
  UNIQUE KEY uk_manufacturing_workshop_name(workshop_name),
  KEY idx_manufacturing_workshop_department(department_id),
  CONSTRAINT fk_manufacturing_workshop_department FOREIGN KEY(department_id) REFERENCES departments(id) ON DELETE SET NULL,
  CONSTRAINT fk_manufacturing_workshop_receiver FOREIGN KEY(order_receiver_employee_id) REFERENCES employees(id) ON DELETE SET NULL,
  CONSTRAINT fk_manufacturing_workshop_clerk FOREIGN KEY(warehouse_clerk_employee_id) REFERENCES employees(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产车间及接单/入库负责人主数据';

CREATE TABLE manufacturing_task_config_items (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  external_source VARCHAR(20) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(100) NOT NULL,
  config_name VARCHAR(200) NOT NULL,
  workshop_name VARCHAR(200) NULL,
  workshop_id BIGINT NULL,
  config_type VARCHAR(30) NOT NULL COMMENT 'PRODUCT_NAME/OPTIONAL_CONFIG',
  duplicate_detection_key VARCHAR(500) NULL,
  source_owner_name VARCHAR(100) NULL,
  source_status VARCHAR(30) NULL,
  active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_manufacturing_task_config_external(external_source,external_id),
  KEY idx_manufacturing_task_config_workshop(workshop_id,config_type,active),
  CONSTRAINT fk_manufacturing_task_config_workshop FOREIGN KEY(workshop_id) REFERENCES manufacturing_workshops(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产任务单产品名称与可选配置主数据';

CREATE TABLE h3_manufacturing_master_import_batches (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  imported_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  imported_by VARCHAR(100) NULL,
  process_file_name VARCHAR(500) NULL,
  routing_file_name VARCHAR(500) NULL,
  workshop_file_name VARCHAR(500) NULL,
  config_file_name VARCHAR(500) NULL,
  process_count INT NOT NULL DEFAULT 0,
  template_count INT NOT NULL DEFAULT 0,
  template_operation_count INT NOT NULL DEFAULT 0,
  workshop_count INT NOT NULL DEFAULT 0,
  config_count INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS',
  error_message VARCHAR(1000) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云生产主数据导入批次';

CREATE TABLE h3_manufacturing_master_import_rows (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  data_type VARCHAR(30) NOT NULL,
  external_id VARCHAR(100) NULL,
  row_no INT NOT NULL,
  raw_json LONGTEXT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_h3_mfg_master_rows_batch(batch_id,data_type),
  CONSTRAINT fk_h3_mfg_master_rows_batch FOREIGN KEY(batch_id)
    REFERENCES h3_manufacturing_master_import_batches(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云生产主数据原始行快照';
