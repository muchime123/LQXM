-- V313: 供应商应付台账（2026-09-18 第 17 类，G1 应付侧权威补缺）
-- 源：《2026年各供应商明细.xlsx》60 sheet（55+ 张标准 8 列模板 + 汇总/封口机等异构被 matchesTemplate 排除）。
-- 口径（LQ 09-18 拍板）：应付侧事实权威 = 本文件（库内 purchase_receipts.paid_amount 全 0）；折让=独立一等概念。
-- 幂等键 = 供应商 sheet 名 # 月份（文件为人工月度台账，每 sheet 12 个月度行 + 尾部 RK/费用明细块）。
-- ⚠️ RK 明细行与人工费用行（代发运费/加工费）不入库：RK 已由 purchase_receipts 事实层覆盖
--    （1064/1064 命中 source_no，勿重复建事实）；费用行是人工脏结构。
-- 管线新能力：本类是首个 multiSheet kind —— 55+ 张同模板 sheet 全部聚合导入，sheet 名注入「供应商」列。
CREATE TABLE IF NOT EXISTS h3_supplier_payables (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(128) NOT NULL COMMENT '幂等键 = 供应商sheet名#月份原值（如 耀星#1）',
  source_no VARCHAR(128) NULL COMMENT '= external_id（无业务单号，人工台账）',
  supplier_name VARCHAR(100) NOT NULL COMMENT '供应商（sheet 名，内部简称；正式身份走 RK 反查，见 resolved_*）',
  month_no INT NULL COMMENT '月份 1-12（解析失败为 NULL 并告警）',
  month_raw VARCHAR(20) NULL COMMENT '月份单元格原值',
  payable_amount DECIMAL(18, 2) NULL COMMENT '当月应付货款',
  settled_amount DECIMAL(18, 2) NULL COMMENT '当月结算货款',
  allowance DECIMAL(18, 2) NULL COMMENT '折让（独立一等业务概念，勿并入调整）',
  pay_date DATE NULL COMMENT '付款日期',
  pay_method VARCHAR(64) NULL COMMENT '付款方式',
  pay_amount DECIMAL(18, 2) NULL COMMENT '付款金额',
  remaining_amount DECIMAL(18, 2) NULL COMMENT '剩余货款',
  resolved_supplier_id BIGINT NULL COMMENT '→ suppliers.id，由 RK 反查身份的映射结果（导入后脚本回填）',
  resolved_supplier_name VARCHAR(200) NULL COMMENT 'RK 反查到的正式供应商名称',
  source_hash CHAR(64) NULL,
  last_synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_h3sp_ext (external_source, external_id),
  KEY idx_h3sp_supplier (supplier_name),
  KEY idx_h3sp_month (month_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='供应商应付月度台账（第 17 类；multiSheet 聚合，人工台账口径）';
