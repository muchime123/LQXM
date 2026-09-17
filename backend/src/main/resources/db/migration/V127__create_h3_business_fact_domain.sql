-- 氚云业务事实域：生产执行、销售出库、收款分配、统一业务生命周期。

CREATE TABLE production_orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    external_source VARCHAR(30) NOT NULL DEFAULT 'H3YUN', external_id VARCHAR(100) NOT NULL,
    work_order_no VARCHAR(50) NOT NULL, status VARCHAR(30) NULL, plan_type VARCHAR(50) NULL,
    application_date DATE NULL, required_completion_date DATE NULL, expected_completion_date DATE NULL,
    actual_completion_date DATE NULL, salesperson_id BIGINT NULL, salesperson_name VARCHAR(100) NULL,
    customer_id BIGINT NULL, customer_name VARCHAR(300) NULL, product_name VARCHAR(300) NULL,
    required_quantity DECIMAL(14,4) NULL, completed_quantity DECIMAL(14,4) NULL,
    warehoused_quantity DECIMAL(14,4) NULL, production_progress VARCHAR(30) NULL,
    warehousing_progress VARCHAR(30) NULL, production_completed TINYINT(1) NULL, warehousing_completed TINYINT(1) NULL,
    box_size VARCHAR(100) NULL, box_shape VARCHAR(100) NULL, box_material VARCHAR(100) NULL,
    mold_layout VARCHAR(100) NULL, voltage VARCHAR(100) NULL, plug_type VARCHAR(100) NULL,
    brand VARCHAR(100) NULL, optional_configuration VARCHAR(1000) NULL, production_requirement LONGTEXT NULL,
    process_name VARCHAR(200) NULL, production_line VARCHAR(200) NULL, order_receiver VARCHAR(100) NULL,
    warehouse_operator VARCHAR(100) NULL, linked_sales_order_no VARCHAR(100) NULL,
    linked_sales_order_ref VARCHAR(300) NULL, product_order_external_id VARCHAR(100) NULL,
    department_name VARCHAR(200) NULL, source_created_by VARCHAR(100) NULL,
    source_created_at DATETIME NULL, source_updated_at DATETIME NULL,
    source_hash CHAR(64) NULL, last_synced_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_po_external (external_source, external_id), UNIQUE KEY uk_po_work_order (work_order_no),
    KEY idx_po_customer (customer_id), KEY idx_po_salesperson (salesperson_id), KEY idx_po_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产任务执行事实';

CREATE TABLE production_order_components (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, production_order_id BIGINT NOT NULL,
    external_id VARCHAR(100) NULL, sequence_no INT NULL, external_title VARCHAR(300) NULL,
    component_name VARCHAR(300) NULL, component_ref VARCHAR(300) NULL, component_serial_no VARCHAR(100) NULL,
    required_quantity DECIMAL(14,4) NULL, requirement_factor DECIMAL(14,4) NULL,
    stock_quantity DECIMAL(14,4) NULL, stock_used_quantity DECIMAL(14,4) NULL,
    completed_quantity DECIMAL(14,4) NULL, completion_ratio VARCHAR(30) NULL,
    completed_at DATETIME NULL, final_process TINYINT(1) NULL, completed TINYINT(1) NULL,
    process_drawing LONGTEXT NULL, reporting_qr LONGTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_poc_order (production_order_id), KEY idx_poc_external (external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产任务零部件制程';

CREATE TABLE sales_outbounds (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    external_source VARCHAR(30) NOT NULL DEFAULT 'H3YUN', external_id VARCHAR(100) NOT NULL,
    outbound_no VARCHAR(50) NOT NULL, status VARCHAR(30) NULL, ship_date DATE NULL, business_date DATE NULL,
    customer_id BIGINT NULL, customer_name VARCHAR(300) NULL, dealer_name VARCHAR(300) NULL,
    salesperson_id BIGINT NULL, salesperson_name VARCHAR(100) NULL, business_owner VARCHAR(100) NULL,
    region VARCHAR(100) NULL, contact_phone VARCHAR(100) NULL, customer_address VARCHAR(500) NULL,
    shipping_method VARCHAR(100) NULL, free_shipping TINYINT(1) NULL, freight DECIMAL(14,2) NULL,
    warehouse VARCHAR(100) NULL, warehouse_keeper VARCHAR(100) NULL,
    payment_status VARCHAR(50) NULL, order_remark VARCHAR(1000) NULL, collection_remark VARCHAR(1000) NULL,
    purchase_amount DECIMAL(14,2) NULL, sales_amount DECIMAL(14,2) NULL,
    source_gross_profit DECIMAL(14,2) NULL, received_amount DECIMAL(14,2) NULL,
    outstanding_amount DECIMAL(14,2) NULL, invoiced_amount DECIMAL(14,2) NULL, uninvoiced_amount DECIMAL(14,2) NULL,
    invoicer VARCHAR(100) NULL, external_title VARCHAR(300) NULL, department_name VARCHAR(200) NULL,
    source_created_by VARCHAR(100) NULL, source_created_at DATETIME NULL, source_updated_at DATETIME NULL,
    attachment_info LONGTEXT NULL, source_hash CHAR(64) NULL, last_synced_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_so_external (external_source, external_id), UNIQUE KEY uk_so_no (outbound_no),
    KEY idx_so_customer (customer_id), KEY idx_so_salesperson (salesperson_id), KEY idx_so_ship_date (ship_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售出库财务事实';

CREATE TABLE sales_outbound_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, sales_outbound_id BIGINT NOT NULL,
    external_id VARCHAR(100) NULL, sequence_no INT NULL, external_title VARCHAR(300) NULL,
    line_type VARCHAR(20) NOT NULL DEFAULT 'PRODUCT', category VARCHAR(100) NULL,
    product_code VARCHAR(100) NULL, product_name VARCHAR(300) NULL, specification VARCHAR(300) NULL, unit VARCHAR(30) NULL,
    quantity DECIMAL(14,4) NULL, sales_unit_price DECIMAL(14,4) NULL, sales_total DECIMAL(14,4) NULL,
    purchase_unit_price DECIMAL(14,4) NULL, purchase_total DECIMAL(14,4) NULL,
    source_profit DECIMAL(14,4) NULL, stock_quantity DECIMAL(14,4) NULL,
    remark VARCHAR(1000) NULL, image_ref LONGTEXT NULL, attachment_info LONGTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_soi_outbound (sales_outbound_id), KEY idx_soi_external (external_id), KEY idx_soi_product_code (product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='销售出库产品明细快照';

CREATE TABLE receipts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    external_source VARCHAR(30) NOT NULL DEFAULT 'H3YUN', external_id VARCHAR(100) NOT NULL,
    receipt_no VARCHAR(50) NOT NULL, receipt_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NULL, receipt_date DATE NULL, customer_id BIGINT NULL,
    customer_name VARCHAR(300) NULL, dealer_name VARCHAR(300) NULL,
    salesperson_id BIGINT NULL, salesperson_name VARCHAR(100) NULL,
    payment_method VARCHAR(100) NULL, receipt_amount DECIMAL(14,2) NULL,
    receivable_amount DECIMAL(14,2) NULL, allocated_amount DECIMAL(14,2) NULL,
    unallocated_amount DECIMAL(14,2) NULL, remark VARCHAR(1000) NULL, attachment_info LONGTEXT NULL,
    department_name VARCHAR(200) NULL, source_created_by VARCHAR(100) NULL,
    source_created_at DATETIME NULL, source_updated_at DATETIME NULL,
    source_hash CHAR(64) NULL, last_synced_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_receipt_external (external_source, external_id), UNIQUE KEY uk_receipt_no (receipt_no),
    KEY idx_receipt_date (receipt_date), KEY idx_receipt_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='收款主单（单订单与多订单结清）';

CREATE TABLE receipt_allocations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, receipt_id BIGINT NOT NULL, sales_outbound_id BIGINT NULL,
    external_id VARCHAR(100) NULL, sequence_no INT NULL, external_title VARCHAR(300) NULL,
    outbound_no_snapshot VARCHAR(50) NULL, sales_date_snapshot DATE NULL,
    sales_amount_snapshot DECIMAL(14,2) NULL, received_before_snapshot DECIMAL(14,2) NULL,
    outstanding_before_snapshot DECIMAL(14,2) NULL, allocated_amount DECIMAL(14,2) NULL,
    allocation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_ra_receipt (receipt_id), KEY idx_ra_outbound (sales_outbound_id), KEY idx_ra_outbound_no (outbound_no_snapshot)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回款到销售出库的分配';

CREATE TABLE business_orders (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, business_no VARCHAR(30) NOT NULL,
    customer_id BIGINT NULL, salesperson_id BIGINT NULL,
    customer_name_snapshot VARCHAR(300) NULL, salesperson_name_snapshot VARCHAR(100) NULL,
    business_type VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN', lifecycle_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    contract_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN', match_status VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED',
    first_activity_date DATE NULL, last_activity_date DATE NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bo_no (business_no), KEY idx_bo_customer (customer_id), KEY idx_bo_salesperson (salesperson_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一业务生命周期';

CREATE TABLE business_order_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, business_order_id BIGINT NOT NULL,
    object_type VARCHAR(30) NOT NULL, object_id BIGINT NOT NULL,
    link_method VARCHAR(20) NOT NULL, confidence INT NOT NULL DEFAULT 100,
    match_reason VARCHAR(500) NULL, confirmed_by VARCHAR(100) NULL, confirmed_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bol_object (object_type, object_id), KEY idx_bol_business (business_order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统一业务与事实对象关联';

CREATE TABLE business_match_candidates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT, production_order_id BIGINT NOT NULL, sales_outbound_id BIGINT NOT NULL,
    confidence INT NOT NULL, match_reason VARCHAR(500) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_bmc_pair (production_order_id, sales_outbound_id), KEY idx_bmc_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生产任务与销售出库模糊匹配候选';
