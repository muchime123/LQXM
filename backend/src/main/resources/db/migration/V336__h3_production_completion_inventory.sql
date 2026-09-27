-- 氚云来源工单只读；质检合格与正式库存入账作为同一笔本地事务。
CREATE TABLE production_execution_completions (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 order_external_id VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 work_order_no_snapshot VARCHAR(100) NOT NULL,
 warehouse_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'POSTED',
 remark VARCHAR(1000) NULL,
 inspected_by_employee_id BIGINT NOT NULL,
 inspected_by_name VARCHAR(100) NOT NULL,
 inspected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 reversed_by_employee_id BIGINT NULL,
 reversed_by_name VARCHAR(100) NULL,
 reversed_at DATETIME NULL,
 reversal_reason VARCHAR(1000) NULL,
 active_order_external_id VARCHAR(100) COLLATE utf8mb4_bin
   GENERATED ALWAYS AS (IF(status='POSTED',order_external_id,NULL)) STORED,
 UNIQUE KEY uk_pec_active_order (active_order_external_id),
 KEY idx_pec_order (order_external_id,inspected_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云生产任务本地质检合格和完工入库';

CREATE TABLE production_execution_completion_items (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 completion_id BIGINT NOT NULL,
 line_no INT NOT NULL,
 is_primary TINYINT(1) NOT NULL,
 sales_product_id BIGINT NOT NULL,
 canonical_product_id BIGINT NOT NULL,
 product_code_snapshot VARCHAR(100) NULL,
 product_name_snapshot VARCHAR(500) NOT NULL,
 specification_snapshot VARCHAR(1000) NULL,
 unit VARCHAR(40) NOT NULL,
 quantity DECIMAL(18,6) NOT NULL,
 UNIQUE KEY uk_peci_line (completion_id,line_no),
 KEY idx_peci_product (sales_product_id),
 CONSTRAINT fk_peci_completion FOREIGN KEY(completion_id) REFERENCES production_execution_completions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云任务完工入库的实际产出 SKU 明细';
