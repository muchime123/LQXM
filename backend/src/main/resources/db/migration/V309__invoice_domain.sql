-- P3 开票域（2026-09-17）：开票资料 + 开票申请（主表/开票内容/收款引用）。
-- 表名带 h3_ 前缀（与 h3_supplier_catalog / h3_other_stock_movements / h3_inventory_transfers 同规）。
-- ⚠️ 开票资料无业务单号（只有数据ID UUID）→ 仿 h3_supplier_catalog 用 source_object_id，无 source_no。
-- ⚠️ 开票申请 10986 单中 18 单流水号为空（实测尾部）→ source_no 允许存 external_id 兜底，仍唯一。

-- 1) 开票资料（单表，无子表；44 组同名户名是合法事实 → 唯一键只有 external_id）
CREATE TABLE IF NOT EXISTS h3_customer_invoice_profiles (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id     VARCHAR(64) NOT NULL COMMENT '数据ID UUID',
  customer_name   VARCHAR(255) NOT NULL COMMENT '户名原文',
  normalized_name VARCHAR(255) NULL COMMENT '归一化户名（SupplierNameNormalizer 口径，重名对拍用）',
  tax_no          VARCHAR(64) NULL,
  address         VARCHAR(500) NULL,
  phone           VARCHAR(64) NULL,
  bank_name       VARCHAR(255) NULL COMMENT '开户行',
  bank_account    VARCHAR(128) NULL COMMENT '银行账号',
  customer_id     BIGINT UNSIGNED NULL COMMENT '关联 customers（税号→credit_code 等值，唯一命中才绑；未命中不是错误）',
  data_status     VARCHAR(32) NULL COMMENT '已生效/草稿',
  source_object_id VARCHAR(64) NULL,
  source_title    VARCHAR(255) NULL,
  owner_name      VARCHAR(64) NULL,
  department_name VARCHAR(128) NULL,
  created_by_name VARCHAR(64) NULL,
  source_created_at DATETIME NULL,
  source_updated_at DATETIME NULL,
  source_hash     VARCHAR(64) NULL,
  last_synced_at  DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cip_external (external_source, external_id),
  KEY idx_cip_tax (tax_no),
  KEY idx_cip_name (customer_name),
  KEY idx_cip_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='氚云开票资料镜像（P3，仅参考数据集）';

-- 2) 开票申请主表
CREATE TABLE IF NOT EXISTS h3_invoice_requests (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id     VARCHAR(64) NOT NULL COMMENT '数据ID UUID（分组键：18 单缺流水号，不能按流水号分组）',
  source_no       VARCHAR(64) NOT NULL COMMENT '流水号（18 单为空 → 存 external_id 兜底）',
  invoice_type    VARCHAR(64) NULL COMMENT '增值税专用发票/普通发票（9 单空原样存）',
  redflush_ref    VARCHAR(255) NULL COMMENT '选择已开发票申请红冲（原文）',
  request_date    DATE NULL COMMENT '开票日期',
  salesperson     VARCHAR(64) NULL COMMENT '业务员',
  profile_external_id VARCHAR(64) NULL COMMENT '选择开票资料（户名文本反查 h3_customer_invoice_profiles.external_id，可空）',
  profile_id      BIGINT UNSIGNED NULL,
  customer_name   VARCHAR(255) NULL COMMENT '户名（主表直读）',
  tax_no          VARCHAR(64) NULL,
  snapshot_address VARCHAR(500) NULL COMMENT '开票时点快照（只存不校）',
  snapshot_phone  VARCHAR(64) NULL,
  snapshot_bank_name VARCHAR(255) NULL,
  snapshot_bank_account VARCHAR(128) NULL,
  request_amount  DECIMAL(18,2) NULL COMMENT '本次申请开票金额',
  received_amount DECIMAL(18,2) NULL COMMENT '已收款金额（只存，不写 receipts）',
  unreceived_amount DECIMAL(18,2) NULL COMMENT '未收款金额',
  outbound_ref_raw VARCHAR(1000) NULL COMMENT '选择出库单匹配开票内容、收款情况——自由文本无单号，只存原文不勾稽',
  remark          TEXT NULL,
  data_status     VARCHAR(32) NULL COMMENT '已生效/草稿/已取消/进行中',
  source_object_id VARCHAR(64) NULL,
  source_title    VARCHAR(255) NULL,
  owner_name      VARCHAR(64) NULL,
  department_name VARCHAR(128) NULL,
  created_by_name VARCHAR(64) NULL,
  source_created_at DATETIME NULL,
  source_updated_at DATETIME NULL,
  source_hash     VARCHAR(64) NULL,
  last_synced_at  DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_invreq_external (external_source, external_id),
  UNIQUE KEY uk_invreq_no (source_no),
  KEY idx_invreq_date (request_date),
  KEY idx_invreq_name (customer_name),
  KEY idx_invreq_profile (profile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='氚云开票申请镜像（P3，仅参考数据集）';

-- 3) 开票内容（行级幂等键 = 开票内容.数据ID）
CREATE TABLE IF NOT EXISTS h3_invoice_request_items (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  request_id   BIGINT UNSIGNED NOT NULL,
  source_item_id VARCHAR(64) NOT NULL COMMENT '开票内容.数据ID',
  line_no      INT NULL,
  product_name VARCHAR(255) NULL,
  spec_model   VARCHAR(255) NULL COMMENT '规格型号',
  unit         VARCHAR(32) NULL,
  quantity     DECIMAL(18,4) NULL,
  unit_price   DECIMAL(18,6) NULL,
  amount       DECIMAL(18,2) NULL COMMENT '金额小计',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_invitem (source_item_id),
  KEY idx_invitem_req (request_id),
  CONSTRAINT fk_invitem_req FOREIGN KEY (request_id) REFERENCES h3_invoice_requests(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 4) 收款情况引用（§2.7 决策：只建引用不写 receipts；文本无收款单号 → receipt_id 恒 NULL 预留）
CREATE TABLE IF NOT EXISTS h3_invoice_request_receipt_refs (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  request_id   BIGINT UNSIGNED NOT NULL,
  source_item_id VARCHAR(64) NOT NULL COMMENT '收款情况.数据ID',
  raw_title    VARCHAR(255) NULL COMMENT '收款情况.数据标题原文',
  receipt_date DATE NULL,
  receipt_method VARCHAR(64) NULL COMMENT '对公/承兑等',
  receipt_amount DECIMAL(18,2) NULL,
  receipt_id   BIGINT UNSIGNED NULL COMMENT '恒 NULL 预留（自由文本无从关联）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_invrecv (source_item_id),
  KEY idx_invrecv_req (request_id),
  CONSTRAINT fk_invrecv_req FOREIGN KEY (request_id) REFERENCES h3_invoice_requests(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
