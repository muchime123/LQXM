-- 以销售出库为核心的确定性关联：仓库稳定外键与来源名称映射。
-- 员工来源映射继续复用 employee_external_mappings(H3YUN)，不新增重复表。

ALTER TABLE sales_outbounds
  ADD COLUMN warehouse_id BIGINT NULL AFTER warehouse,
  ADD KEY idx_so_warehouse_id (warehouse_id),
  ADD CONSTRAINT fk_so_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(id);

CREATE TABLE h3_warehouse_name_mappings (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  source_name VARCHAR(150) NOT NULL,
  warehouse_id BIGINT NOT NULL,
  match_method VARCHAR(30) NOT NULL DEFAULT 'EXACT_NAME',
  confirmed_by VARCHAR(100) NULL,
  confirmed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3_warehouse_source_name (source_name),
  KEY idx_h3_warehouse_target (warehouse_id),
  CONSTRAINT fk_h3_warehouse_mapping_target FOREIGN KEY (warehouse_id) REFERENCES warehouses(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云来源仓库名称到正式仓库的持久映射';
