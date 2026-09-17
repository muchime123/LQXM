-- ============================================================================
-- V294：氚云经销商信息台账（导入中心第七类数据源）
--
-- 背景：氚云「经销商信息」表单此前没有任何落点，只有销售出库上的自由文本
--       sales_outbounds.dealer_name（97 种写法、无编号）。本表把氚云导出的
--       经销商主数据按批次增量导入，为经销商实体化提供权威主档与业务键
--       （经销商编号 JXS…）。
--
-- 与 h3_product_catalog / h3_warehouse_stock 同构：批次化增量导入，
-- 按业务键（dealer_code）逐行 diff，只新增/更新，不删除、不覆盖历史行。
--
-- 幂等：全部语句可重复执行。
-- ============================================================================

CREATE TABLE IF NOT EXISTS h3_dealer_catalog (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  import_batch_id VARCHAR(50) NOT NULL COMMENT '导入批次号',
  dealer_code VARCHAR(64) NOT NULL COMMENT '氚云经销商编号（业务键，如 JXS00000208）',
  dealer_name VARCHAR(200) COMMENT '经销商名称',
  contact VARCHAR(200) COMMENT '联系方式',
  address VARCHAR(500) COMMENT '经销商地址',
  remark VARCHAR(1000) COMMENT '备注',
  owner_salesperson VARCHAR(100) COMMENT '归属业务员',
  data_status VARCHAR(50) COMMENT '氚云数据状态（已生效等）',
  source_object_id VARCHAR(64) COMMENT '氚云数据ID（不可修改）',
  source_title VARCHAR(200) COMMENT '氚云数据标题',
  owner_name VARCHAR(100) COMMENT '拥有者',
  department_name VARCHAR(200) COMMENT '所属部门',
  created_by_name VARCHAR(100) COMMENT '创建人',
  source_created_at DATETIME COMMENT '氚云创建时间',
  source_modified_at DATETIME COMMENT '氚云修改时间',
  source_hash CHAR(64) NOT NULL COMMENT '来源行哈希（参与比对的字段摘要）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次入台账时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近一次更新',
  UNIQUE KEY uk_h3_dealer_code (dealer_code),
  KEY idx_h3dc_batch (import_batch_id),
  KEY idx_h3dc_name (dealer_name),
  KEY idx_h3dc_salesperson (owner_salesperson)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='氚云经销商信息台账';
