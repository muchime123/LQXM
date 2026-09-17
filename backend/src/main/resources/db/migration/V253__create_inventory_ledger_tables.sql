-- V253: 氚云产品信息台账 + 仓库明细台账
-- 用于"先导入台账→再合并出非零库存"的两阶段流程

CREATE TABLE IF NOT EXISTS h3_product_catalog (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  import_batch_id VARCHAR(50) NOT NULL COMMENT '导入批次号',
  product_category VARCHAR(100) COMMENT '产品类目',
  product_code VARCHAR(200) NOT NULL COMMENT '产品编号',
  product_name VARCHAR(500) COMMENT '产品名称',
  specification VARCHAR(1000) COMMENT '规格',
  unit VARCHAR(100) COMMENT '单位',
  purchase_price DECIMAL(18,4) COMMENT '采购价格',
  source_hash CHAR(64) NOT NULL COMMENT '来源哈希',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3pc_code (product_code),
  KEY idx_h3pc_batch (import_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云产品信息台账';

CREATE TABLE IF NOT EXISTS h3_warehouse_stock (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  import_batch_id VARCHAR(50) NOT NULL COMMENT '导入批次号',
  warehouse VARCHAR(100) COMMENT '仓库',
  product_category VARCHAR(100) COMMENT '产品类目',
  product_code VARCHAR(200) NOT NULL COMMENT '产品编号',
  product_name VARCHAR(500) COMMENT '产品名称',
  specification VARCHAR(1000) COMMENT '规格',
  unit VARCHAR(100) COMMENT '单位',
  quantity DECIMAL(18,4) COMMENT '库存数量',
  purchase_price DECIMAL(18,4) COMMENT '采购价格',
  source_hash CHAR(64) NOT NULL COMMENT '来源哈希',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_h3ws_batch (import_batch_id),
  KEY idx_h3ws_code (product_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云仓库明细台账';
