-- 销售订单费用明细：运费/加工费/税点/样品费/模具费/优惠/加急费等非产品费用
-- 这些费用不再混入仓库库存，在订单层级独立管理，不影响库存和生产
ALTER TABLE formal_sales_orders
  ADD COLUMN fees_total DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER total_amount,
  ADD COLUMN grand_total DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER fees_total;

CREATE TABLE formal_sales_order_fees (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  sales_order_id BIGINT NOT NULL,
  line_no INT NOT NULL,
  fee_type VARCHAR(40) NOT NULL,
  fee_name VARCHAR(200) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  remark VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_fsof_line (sales_order_id, line_no),
  KEY idx_fsof_order (sales_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='正式销售订单费用明细';
