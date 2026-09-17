CREATE TABLE product_inventory_transfer_documents (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,transfer_no VARCHAR(50) NOT NULL,source_warehouse_id BIGINT NOT NULL,target_warehouse_id BIGINT NOT NULL,
 occurred_at DATETIME NOT NULL,reason VARCHAR(200) NOT NULL,remark VARCHAR(500) NULL,status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
 created_by_employee_id BIGINT NULL,created_by_name VARCHAR(100) NULL,posted_at DATETIME NULL,reversed_at DATETIME NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_pitd_no(transfer_no),KEY idx_pitd_status(status,occurred_at),
 CONSTRAINT fk_pitd_source_wh FOREIGN KEY(source_warehouse_id) REFERENCES warehouses(id),CONSTRAINT fk_pitd_target_wh FOREIGN KEY(target_warehouse_id) REFERENCES warehouses(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品库存调拨单';
CREATE TABLE product_inventory_transfer_items (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,transfer_id BIGINT NOT NULL,line_no INT NOT NULL,sales_product_id BIGINT NOT NULL,
 product_name_snapshot VARCHAR(500) NOT NULL,specification_snapshot VARCHAR(1000) NULL,unit VARCHAR(40) NOT NULL,quantity DECIMAL(18,6) NOT NULL,remark VARCHAR(500) NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_piti_line(transfer_id,line_no),KEY idx_piti_product(sales_product_id),
 CONSTRAINT fk_piti_transfer FOREIGN KEY(transfer_id) REFERENCES product_inventory_transfer_documents(id),CONSTRAINT fk_piti_product FOREIGN KEY(sales_product_id) REFERENCES sales_products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品库存调拨明细';
