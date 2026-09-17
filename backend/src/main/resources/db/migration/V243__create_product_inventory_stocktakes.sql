CREATE TABLE product_inventory_stocktakes (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,stocktake_no VARCHAR(50) NOT NULL,warehouse_id BIGINT NOT NULL,snapshot_at DATETIME NOT NULL,
 reason VARCHAR(200) NOT NULL,remark VARCHAR(500) NULL,status VARCHAR(20) NOT NULL DEFAULT 'COUNTING',created_by_employee_id BIGINT NULL,created_by_name VARCHAR(100) NULL,
 posted_at DATETIME NULL,reversed_at DATETIME NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_pis_no(stocktake_no),KEY idx_pis_status(status,snapshot_at),CONSTRAINT fk_pis_warehouse FOREIGN KEY(warehouse_id) REFERENCES warehouses(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品库存盘点单';
CREATE TABLE product_inventory_stocktake_items (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,stocktake_id BIGINT NOT NULL,line_no INT NOT NULL,sales_product_id BIGINT NOT NULL,product_name_snapshot VARCHAR(500) NOT NULL,
 specification_snapshot VARCHAR(1000) NULL,unit VARCHAR(40) NOT NULL,book_quantity DECIMAL(18,6) NOT NULL,actual_quantity DECIMAL(18,6) NULL,difference_quantity DECIMAL(18,6) NULL,remark VARCHAR(500) NULL,
 UNIQUE KEY uk_pisi_line(stocktake_id,line_no),KEY idx_pisi_product(sales_product_id),CONSTRAINT fk_pisi_stocktake FOREIGN KEY(stocktake_id) REFERENCES product_inventory_stocktakes(id),CONSTRAINT fk_pisi_product FOREIGN KEY(sales_product_id) REFERENCES sales_products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品库存盘点明细';
