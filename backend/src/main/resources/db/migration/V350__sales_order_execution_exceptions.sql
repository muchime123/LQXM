-- 已确认订单的执行异常与受控变更；旧订单明细不直接回退草稿。
ALTER TABLE erp_sales_orders
  ADD COLUMN revision_no INT NOT NULL DEFAULT 1 COMMENT '已确认需求版本';

CREATE TABLE erp_sales_order_exceptions (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / RESOLVED',
  reason VARCHAR(1000) NOT NULL,
  proposed_action VARCHAR(1000) NULL,
  reported_by_employee_id BIGINT NOT NULL,
  reported_by_name VARCHAR(100) NOT NULL,
  reported_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  resolution_note VARCHAR(1000) NULL,
  resolved_by_employee_id BIGINT NULL,
  resolved_by_name VARCHAR(100) NULL,
  resolved_at DATETIME NULL,
  KEY idx_esoe_order (order_id, item_id, status),
  CONSTRAINT fk_esoe_order FOREIGN KEY (order_id) REFERENCES erp_sales_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='已确认销售订单履约异常';

CREATE TABLE erp_sales_order_changes (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  exception_id BIGINT NOT NULL,
  order_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW' COMMENT 'PENDING_REVIEW/PENDING_APPROVAL/APPROVED/REJECTED',
  original_quantity DECIMAL(18,6) NOT NULL,
  proposed_quantity DECIMAL(18,6) NOT NULL,
  original_delivery_date DATE NULL,
  proposed_delivery_date DATE NULL,
  customer_confirmation_ref VARCHAR(500) NOT NULL,
  review_comment VARCHAR(1000) NULL,
  reviewed_by_employee_id BIGINT NULL,
  reviewed_by_name VARCHAR(100) NULL,
  reviewed_at DATETIME NULL,
  approval_comment VARCHAR(1000) NULL,
  decided_by_employee_id BIGINT NULL,
  decided_by_name VARCHAR(100) NULL,
  decided_at DATETIME NULL,
  created_by_employee_id BIGINT NOT NULL,
  created_by_name VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_esoc_order (order_id, item_id, id),
  KEY idx_esoc_exception (exception_id, status),
  CONSTRAINT fk_esoc_exception FOREIGN KEY (exception_id) REFERENCES erp_sales_order_exceptions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售订单明细数量或交期的变更审批历史';
