-- 销售确认前的履约评审：评审记录绑定明细内容快照，旧意见保留但不再满足提交门槛。
ALTER TABLE erp_sales_orders
  ADD COLUMN fulfillment_review_requested_at DATETIME NULL COMMENT '最近一次发起履约评审的时间';

CREATE TABLE erp_sales_order_fulfillment_reviews (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  supply_mode VARCHAR(10) NOT NULL COMMENT 'MAKE / BUY',
  content_hash CHAR(64) NOT NULL COMMENT '销售需求与交期的 SHA-256 快照',
  decision VARCHAR(20) NOT NULL COMMENT 'ACCEPTED / CHANGE_REQUIRED',
  comment VARCHAR(1000) NULL,
  proposed_delivery_date DATE NULL,
  reviewer_employee_id BIGINT NOT NULL,
  reviewer_name VARCHAR(100) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_esofr_order_item (order_id, item_id, id),
  KEY idx_esofr_queue (supply_mode, created_at),
  CONSTRAINT fk_esofr_order FOREIGN KEY (order_id) REFERENCES erp_sales_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售订单履约评审历史';
