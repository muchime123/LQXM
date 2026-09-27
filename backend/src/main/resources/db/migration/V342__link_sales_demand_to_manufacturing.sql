-- 车间接受销售需求后，生产任务直接追溯到订单明细行。
ALTER TABLE manufacturing_orders
  ADD COLUMN sales_order_item_id BIGINT NULL,
  ADD UNIQUE KEY uk_mo_sales_order_item (sales_order_item_id),
  ADD CONSTRAINT fk_mo_sales_order_item FOREIGN KEY (sales_order_item_id)
    REFERENCES erp_sales_order_items(id);
