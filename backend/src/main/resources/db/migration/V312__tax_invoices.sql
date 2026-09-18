-- V312 (2026-09-18): 第 16 类「发票台账」（G2 实际开票权威源，税务平台全量导出）
-- 数据源：财务提供的《2026年发票明细.xlsx》（原 WPS 加密，LQ 另存 + 解密；LQ 2026-09-18 提供），
--   4 个 sheet：进项发票 2 382 行 / 销项发票 4 686 行 / 对公流水 2 555 行（属流水日记账域，留给第 18 类）/ Sheet3（空）。
-- LQ 拍板（2026-09-18 §9.3）：
--   ① 实开口径 = 发票明细（权威）；氚云开票申请降级为申请侧意向；
--   ② 收款权威仍 = 销售出库 → 本表收款字段只作「校验源」，不写 receipts、不回写 received_amount。
-- 幂等键 = 数电发票号码（实测进/销项各自唯一、进销 0 交集；非氚云导出件，无数据ID列）。
-- 销项 sheet 实为主子结构：4 174 主行（有数电票号）+ 512 收款子行（无票号，多次收款登记，日期早至 2025）→
--   挂靠前一行发票，解析层按「无数电发票号码」判子行（复用氚云主子分组管线）。
-- ⚠️ 销项有两个同名「备注」列：第 17 列=人工收款备注（11 行）/第 24 列=平台红冲备注（271 行，
--   含「被红冲蓝字数电发票号码：X 红字发票信息确认单编号：Y」红冲链，0 行同时非空）→ 解析层把第二列改名「备注(平台)」。
-- 不落的空壳列（2026-09-18 实测）：发票代码 / 发票号码（进销项均 100% 全空）。
-- 红冲口径：负数红字票 216 张（是否正数发票=否，全带红冲引用）；已红冲-全额 138 / 已红冲-部分 2（正数票）。
-- 开票日期混型（datetime 单元格与文本字符串混存）→ 解析层 dateTime() 兼容 ISO 'T' 分隔。

CREATE TABLE IF NOT EXISTS h3_input_invoices (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(64) NOT NULL COMMENT '数电发票号码（幂等键，非氚云数据ID）',
  source_no VARCHAR(64) NOT NULL COMMENT '= 数电发票号码',
  seller_tax_no VARCHAR(32) NULL COMMENT '销方识别号（供应商税号，825 家）',
  seller_name VARCHAR(200) NULL COMMENT '销方名称',
  buyer_tax_no VARCHAR(32) NULL COMMENT '购方识别号',
  buyer_name VARCHAR(200) NULL COMMENT '购买方名称（含多主体：浙江利强/华睿/强子/机器公司）',
  invoice_date DATETIME NULL COMMENT '开票日期',
  amount DECIMAL(18, 2) NULL COMMENT '金额（不含税，可为负=红字）',
  tax_amount DECIMAL(18, 2) NULL COMMENT '税额',
  total_amount DECIMAL(18, 2) NULL COMMENT '价税合计',
  invoice_source VARCHAR(64) NULL COMMENT '发票来源（全「电子发票服务平台」）',
  invoice_kind VARCHAR(64) NULL COMMENT '发票票种（普通/通行费/专票/铁路电子客票）',
  invoice_status VARCHAR(32) NULL COMMENT '发票状态（正常/已红冲-全额）',
  is_positive VARCHAR(8) NULL COMMENT '是否正数发票',
  risk_level VARCHAR(32) NULL COMMENT '发票风险等级（全「正常」）',
  drawer VARCHAR(64) NULL COMMENT '开票人',
  remark VARCHAR(500) NULL COMMENT '备注（进项：最长 193 字符，含不动产地址等）',
  source_hash VARCHAR(64) NULL,
  source_updated_at DATETIME NULL,
  last_synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3iin_external (external_source, external_id),
  UNIQUE KEY uk_h3iin_no (source_no),
  KEY idx_h3iin_seller (seller_name),
  KEY idx_h3iin_date (invoice_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='税务平台进项发票（G2 实开口径权威源·进项侧，2026-09-18 第 16 类）';

CREATE TABLE IF NOT EXISTS h3_sales_invoices (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(64) NOT NULL COMMENT '数电发票号码（幂等键，非氚云数据ID）',
  source_no VARCHAR(64) NOT NULL COMMENT '= 数电发票号码',
  seller_tax_no VARCHAR(32) NULL COMMENT '销方识别号（全 9133030207622289XP 浙江利强）',
  seller_name VARCHAR(200) NULL COMMENT '销方名称（全「浙江利强包装科技有限公司」）',
  buyer_tax_no VARCHAR(32) NULL COMMENT '购方识别号（1 309 家有税号，个人购买方为空）',
  buyer_name VARCHAR(200) NULL COMMENT '购买方名称（1 484 家）',
  invoice_date DATETIME NULL COMMENT '开票日期',
  amount DECIMAL(18, 2) NULL COMMENT '金额（不含税，可为负=红字）',
  tax_amount DECIMAL(18, 2) NULL COMMENT '税额',
  total_amount DECIMAL(18, 2) NULL COMMENT '价税合计',
  receipt_date DATE NULL COMMENT '收款日期（校验源，收款权威=销售出库）',
  payment_method VARCHAR(64) NULL COMMENT '付款方式=收款渠道（27 值，与氚云「收款方式」字典同空间）',
  receipt_amount DECIMAL(18, 2) NULL COMMENT '收款金额（主行登记值）',
  unreceived_amount DECIMAL(18, 2) NULL COMMENT '未收款金额',
  salesman VARCHAR(32) NULL COMMENT '业务员（15 人）',
  remark VARCHAR(500) NULL COMMENT '备注（业务收款备注，最长 68 字符）',
  remark_platform VARCHAR(500) NULL COMMENT '备注(平台)（源列同名第二列，解析层改名；含红冲链引用）',
  invoice_source VARCHAR(64) NULL COMMENT '发票来源',
  invoice_kind VARCHAR(64) NULL COMMENT '发票票种（专票/普通）',
  invoice_status VARCHAR(32) NULL COMMENT '发票状态（正常/已红冲-全额/已红冲-部分）',
  is_positive VARCHAR(8) NULL COMMENT '是否正数发票（否=负数红字票 216 张）',
  risk_level VARCHAR(32) NULL COMMENT '发票风险等级（全「正常」）',
  drawer VARCHAR(64) NULL COMMENT '开票人',
  source_hash VARCHAR(64) NULL,
  source_updated_at DATETIME NULL,
  last_synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3sin_external (external_source, external_id),
  UNIQUE KEY uk_h3sin_no (source_no),
  KEY idx_h3sin_buyer (buyer_name),
  KEY idx_h3sin_date (invoice_date),
  KEY idx_h3sin_pm (payment_method)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='税务平台销项发票（G2 实开口径权威源·销项侧，2026-09-18 第 16 类）';

CREATE TABLE IF NOT EXISTS h3_sales_invoice_receipts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_id BIGINT NOT NULL COMMENT '→ h3_sales_invoices.id',
  source_item_id VARCHAR(64) NOT NULL COMMENT '行级唯一键（=发票号#组内序号）',
  receipt_date DATE NULL COMMENT '收款日期（512 行中 510 行非空，早至 2025）',
  payment_method VARCHAR(64) NULL COMMENT '付款方式=收款渠道',
  receipt_amount DECIMAL(18, 2) NULL COMMENT '收款金额（可为负=红冲退款）',
  salesman VARCHAR(32) NULL COMMENT '业务员（少量子行携带）',
  remark VARCHAR(500) NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3sir_item (source_item_id),
  KEY idx_h3sir_invoice (invoice_id),
  CONSTRAINT fk_h3sir_invoice FOREIGN KEY (invoice_id) REFERENCES h3_sales_invoices (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='销项发票多次收款登记（512 行无票号子行，收款权威=销售出库，此处仅校验源）';
