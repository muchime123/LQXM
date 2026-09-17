CREATE TABLE product_inventory_other_documents (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, document_no VARCHAR(50) NOT NULL, movement_type VARCHAR(20) NOT NULL,
 warehouse_id BIGINT NOT NULL, occurred_at DATETIME NOT NULL, reason VARCHAR(200) NOT NULL, remark VARCHAR(500) NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'DRAFT', created_by_employee_id BIGINT NULL, created_by_name VARCHAR(100) NULL,
 posted_at DATETIME NULL, reversed_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_piod_no(document_no), KEY idx_piod_status(status,occurred_at),
 CONSTRAINT fk_piod_warehouse FOREIGN KEY(warehouse_id) REFERENCES warehouses(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品其他入库出库单';
CREATE TABLE product_inventory_other_items (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, document_id BIGINT NOT NULL, line_no INT NOT NULL, sales_product_id BIGINT NOT NULL,
 product_name_snapshot VARCHAR(500) NOT NULL, specification_snapshot VARCHAR(1000) NULL, unit VARCHAR(40) NOT NULL,
 quantity DECIMAL(18,6) NOT NULL, remark VARCHAR(500) NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_pioi_doc_line(document_id,line_no), KEY idx_pioi_product(sales_product_id),
 CONSTRAINT fk_pi_other_item_doc FOREIGN KEY(document_id) REFERENCES product_inventory_other_documents(id),
 CONSTRAINT fk_pi_other_item_product FOREIGN KEY(sales_product_id) REFERENCES sales_products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品其他入库出库明细';
