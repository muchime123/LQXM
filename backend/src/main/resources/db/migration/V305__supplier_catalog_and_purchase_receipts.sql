-- V305 (2026-09-17): P0 供应商主数据 + 采购入库事实层
-- ⚠️ 版本号由 V302 改为 V305：V302 已被 V302__h3_inventory_issue_dispositions.sql（09-16）占用，
--    Flyway 重复版本号会导致应用启动直接失败。本迁移此前从未执行（无 flyway 记录），改号安全。
-- 设计依据：docs/氚云新增7表ERP适用性梳理与导入设计-20260917.md §2.2/§2.3
-- 决策（LQ 2026-09-17 拍板）：
--   1) 采购入库只落事实层 + 采购价权威口径，不动 product_inventory_balances / 库存流水；
--   2) 供应商无氚云编号，主键 = 数据ID(UUID)，归一化名称唯一索引 + 别名表兜历史引用文本；
--   3) 草稿单导入并标 data_status='草稿'，不进正式口径（标记比丢弃安全）。
-- 系统列与 A 域事实表同款（external_source/external_id/source_hash/source_updated_at/last_synced_at），
-- 供 H3FactImportService 统一管线（loadExistingFacts/unchanged/touch）直接复用。

-- ============ 供应商主数据 ============
CREATE TABLE IF NOT EXISTS h3_supplier_catalog (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(64) NOT NULL COMMENT '氚云数据ID(UUID)，供应商唯一稳定键',
  name VARCHAR(190) NOT NULL COMMENT '供应商名称（原文）',
  normalized_name VARCHAR(190) NOT NULL COMMENT '归一化名称（剥状态后缀+去空白+小写），自愈匹配键',
  supplier_status VARCHAR(32) NULL COMMENT '合作中/合作终止（Hidden_Options_1）',
  is_internal TINYINT(1) NOT NULL DEFAULT 0 COMMENT '内部车间/产线误入值标记',
  is_dirty_value TINYINT(1) NOT NULL DEFAULT 0 COMMENT '串名脏值标记（如单据类型串入）',
  private_bank_name VARCHAR(190) NULL,
  private_bank_account VARCHAR(190) NULL,
  private_bank_branch VARCHAR(190) NULL,
  public_bank_name VARCHAR(190) NULL,
  public_bank_account VARCHAR(190) NULL,
  public_bank_branch VARCHAR(190) NULL,
  contact_name VARCHAR(190) NULL,
  contact_phone VARCHAR(190) NULL,
  address VARCHAR(500) NULL,
  remark VARCHAR(1000) NULL,
  data_status VARCHAR(32) NULL,
  source_object_id VARCHAR(64) NULL,
  source_title VARCHAR(255) NULL,
  owner_name VARCHAR(190) NULL,
  department_name VARCHAR(190) NULL,
  created_by_name VARCHAR(190) NULL,
  source_created_at DATETIME NULL,
  source_updated_at DATETIME NULL,
  source_hash VARCHAR(64) NULL,
  last_synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3sup_external (external_source, external_id),
  UNIQUE KEY uk_h3sup_normalized (normalized_name),
  KEY idx_h3sup_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='氚云供应商主数据（无编号，名称即业务语义键）';

-- 别名表：采购入库等单据引用的是「名称+状态」数据标题文本，状态变更后历史文本失配，
-- 解析命中后自动落别名（自愈），避免每次都走归一化匹配。
CREATE TABLE IF NOT EXISTS h3_supplier_aliases (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  supplier_id BIGINT NOT NULL,
  raw_text VARCHAR(255) NOT NULL COMMENT '单据上出现的原始引用文本',
  source VARCHAR(64) NULL COMMENT '引用来源（PURCHASE_IN 等）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3sup_alias_raw (raw_text),
  KEY idx_h3sup_alias_supplier (supplier_id),
  CONSTRAINT fk_h3sup_alias_supplier FOREIGN KEY (supplier_id) REFERENCES h3_supplier_catalog (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='供应商引用别名（历史文本 → 主档自愈映射）';

-- ============ 采购入库主表 ============
CREATE TABLE IF NOT EXISTS purchase_receipts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(64) NOT NULL COMMENT '采购入库编号（主表数据ID列在子表列之后会被表头首命中吞掉，external_id 统一取单号）',
  source_no VARCHAR(64) NOT NULL COMMENT '采购入库编号 RK…',
  purchase_date DATE NULL,
  warehouse_id BIGINT NULL COMMENT '关联 warehouses（同名唯一才绑）',
  warehouse_name VARCHAR(64) NULL,
  owner_company VARCHAR(190) NULL COMMENT '所属部门=采购主体（多主体：浙江利强/安徽盒多多/温州永铭…）',
  supplier_id BIGINT NULL COMMENT '解析后的供应商主档ID；解析失败为 NULL（不静默新建）',
  supplier_name_raw VARCHAR(255) NULL COMMENT '单据上的供应商引用原文',
  contact_name VARCHAR(190) NULL,
  contact_phone VARCHAR(190) NULL,
  supplier_address VARCHAR(500) NULL COMMENT '空壳JSON({"adcode":..}全空)一律视为 NULL',
  total_amount DECIMAL(18, 2) NULL COMMENT '主表合计采购金额',
  total_quantity DECIMAL(18, 4) NULL COMMENT '主表合计采购数量',
  expected_arrival_date DATE NULL,
  keeper VARCHAR(64) NULL COMMENT '仓管员',
  unpaid_amount DECIMAL(18, 2) NULL COMMENT '未付款金额=应付口径（导出件已付款恒0/已收票全空，唯一有意义的应付值）',
  uninvoiced_amount DECIMAL(18, 2) NULL,
  paid_amount DECIMAL(18, 2) NULL COMMENT '已付款金额（实测恒0，保留字段仅为完整透传）',
  payment_status VARCHAR(32) NULL COMMENT '付款给供应商状态',
  order_remark VARCHAR(1000) NULL,
  payment_remark VARCHAR(500) NULL,
  data_status VARCHAR(32) NULL COMMENT '已生效/草稿（草稿不进正式口径）',
  source_title VARCHAR(255) NULL,
  owner_name VARCHAR(190) NULL,
  department_name VARCHAR(190) NULL,
  created_by_name VARCHAR(190) NULL,
  source_created_at DATETIME NULL,
  source_updated_at DATETIME NULL,
  source_hash VARCHAR(64) NULL,
  last_synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_pr_external (external_source, external_id),
  UNIQUE KEY uk_pr_no (source_no),
  KEY idx_pr_supplier (supplier_id),
  KEY idx_pr_date (purchase_date),
  KEY idx_pr_warehouse (warehouse_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='氚云采购入库（事实层，2026-09-17 决策：不动库存余额）';

-- ============ 采购入库明细 ============
CREATE TABLE IF NOT EXISTS purchase_receipt_items (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  purchase_receipt_id BIGINT NOT NULL,
  source_item_id VARCHAR(64) NULL COMMENT '产品明细.数据ID(UUID)，行级幂等键；空值行存 NULL',
  line_no INT NULL,
  product_code VARCHAR(64) NULL,
  product_name VARCHAR(255) NULL,
  category VARCHAR(190) NULL,
  spec VARCHAR(255) NULL,
  unit VARCHAR(32) NULL,
  quantity DECIMAL(18, 4) NULL COMMENT '入库数量',
  purchase_price DECIMAL(18, 4) NULL COMMENT '采购价格（带日期/供应商/单据的采购价权威来源）',
  amount DECIMAL(18, 2) NULL COMMENT '采购金额小计',
  stock_quantity DECIMAL(18, 4) NULL COMMENT '库存数量快照',
  is_mold TINYINT(1) NULL,
  has_process VARCHAR(8) NULL COMMENT '是否有制程（是/否）',
  task_salesperson VARCHAR(64) NULL,
  production_order_no VARCHAR(64) NULL COMMENT '选择生产任务单首段编号；关联不上记原文语义不阻断',
  production_order_raw VARCHAR(255) NULL COMMENT '选择生产任务单原文',
  remark VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_pri_item (source_item_id),
  KEY idx_pri_receipt (purchase_receipt_id),
  KEY idx_pri_code (product_code),
  KEY idx_pri_pono (production_order_no),
  CONSTRAINT fk_pri_receipt FOREIGN KEY (purchase_receipt_id) REFERENCES purchase_receipts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='氚云采购入库产品明细（按单据整单删除重插，行级 UUID 幂等）';
