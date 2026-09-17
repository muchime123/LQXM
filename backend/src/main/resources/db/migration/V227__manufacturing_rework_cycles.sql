-- 生产返工轮次：返工报工与原始报工分开累计，可追踪退回工序和返工要求。
ALTER TABLE manufacturing_order_operations
  ADD COLUMN execution_round INT NOT NULL DEFAULT 0 AFTER reported_quantity;

ALTER TABLE manufacturing_work_reports
  ADD COLUMN execution_round INT NOT NULL DEFAULT 0 AFTER manufacturing_order_operation_id,
  ADD KEY idx_work_report_round (manufacturing_order_operation_id,execution_round,status);

CREATE TABLE manufacturing_reworks (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  rework_no VARCHAR(50) NOT NULL,
  manufacturing_order_id BIGINT NOT NULL,
  inspection_id BIGINT NOT NULL,
  return_operation_id BIGINT NOT NULL,
  rework_round INT NOT NULL,
  requirement VARCHAR(1000) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS',
  created_by_employee_id BIGINT NULL,
  created_by_name VARCHAR(100) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME NULL,
  UNIQUE KEY uk_manufacturing_rework_no (rework_no),
  KEY idx_rework_order (manufacturing_order_id,status),
  CONSTRAINT fk_rework_order FOREIGN KEY (manufacturing_order_id) REFERENCES manufacturing_orders(id),
  CONSTRAINT fk_rework_inspection FOREIGN KEY (inspection_id) REFERENCES manufacturing_inspections(id),
  CONSTRAINT fk_rework_operation FOREIGN KEY (return_operation_id) REFERENCES manufacturing_order_operations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产返工轮次';
