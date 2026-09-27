-- 销售需求源头；与已只读的 sales_contracts 历史台账隔离。
CREATE TABLE erp_sales_orders (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_no VARCHAR(50) NOT NULL UNIQUE,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  customer_id BIGINT NULL,
  customer_name_snapshot VARCHAR(300) NOT NULL,
  salesperson_id BIGINT NOT NULL,
  salesperson_name_snapshot VARCHAR(100) NOT NULL,
  order_date DATE NOT NULL,
  requested_delivery_date DATE NULL,
  remark VARCHAR(1000) NULL,
  total_amount DECIMAL(18,4) NULL,
  confirmed_at DATETIME NULL,
  confirmed_by_employee_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_eso_customer (customer_id),
  KEY idx_eso_salesperson (salesperson_id),
  KEY idx_eso_status_date (status, order_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE erp_sales_order_items (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  order_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  product_name_snapshot VARCHAR(500) NOT NULL,
  specification_snapshot VARCHAR(1000) NULL,
  sales_product_id BIGINT NULL COMMENT '定制品接单时允许为空，执行中确定身份',
  supply_mode VARCHAR(20) NOT NULL COMMENT 'STOCK/MAKE/BUY/UNDECIDED',
  unit VARCHAR(40) NOT NULL,
  quantity DECIMAL(18,6) NOT NULL,
  unit_price DECIMAL(18,4) NULL,
  amount DECIMAL(18,4) NULL,
  requested_delivery_date DATE NULL,
  remark VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_esoi_order_line (order_id, line_no),
  KEY idx_esoi_product (sales_product_id),
  CONSTRAINT fk_esoi_order FOREIGN KEY (order_id) REFERENCES erp_sales_orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 出库关联到具体需求行；原自由文本订单号保留兼容历史数据。
ALTER TABLE erp_sales_outbounds ADD COLUMN sales_order_id BIGINT NULL,
  ADD KEY idx_eso_sales_order (sales_order_id),
  ADD CONSTRAINT fk_eso_sales_order FOREIGN KEY (sales_order_id) REFERENCES erp_sales_orders(id);
ALTER TABLE erp_sales_outbound_items ADD COLUMN sales_order_item_id BIGINT NULL,
  ADD KEY idx_esoi_sales_order_item (sales_order_item_id),
  ADD CONSTRAINT fk_esoi_sales_order_item FOREIGN KEY (sales_order_item_id) REFERENCES erp_sales_order_items(id);
