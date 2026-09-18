-- V315: 第18类 流水日记账（校验源）—— 账户维度 + 账户分录
-- 源：2026年流水日记账.xlsx 36 张账户 sheet（multiSheet，sheet名=账户）
-- 设计稿：docs/流水日记账导入设计稿-20260918.md

CREATE TABLE IF NOT EXISTS h3_cash_accounts (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(128) NOT NULL,
  source_no VARCHAR(128) NOT NULL,
  account_name VARCHAR(64) NOT NULL,
  currency VARCHAR(8) NOT NULL DEFAULT 'CNY',
  opening_balance DECIMAL(18, 4) NULL,
  source_hash VARCHAR(64) NULL,
  source_updated_at DATETIME NULL,
  last_synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cash_acct_ext (external_source, external_id),
  UNIQUE KEY uk_cash_acct_no (source_no),
  UNIQUE KEY uk_cash_acct_name (account_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS h3_cashflow_entries (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(128) NOT NULL,
  source_no VARCHAR(128) NOT NULL,
  account_name VARCHAR(64) NOT NULL,
  entry_date DATE NULL,
  month_label VARCHAR(8) NULL,
  summary VARCHAR(255) NULL,
  supplier_raw VARCHAR(255) NULL,
  counterparty_raw VARCHAR(255) NULL,
  reimbursed_by VARCHAR(64) NULL,
  invoiced VARCHAR(8) NULL,
  income DECIMAL(18, 4) NULL,
  expense DECIMAL(18, 4) NULL,
  balance DECIMAL(18, 4) NULL,
  fx_rate DECIMAL(12, 6) NULL,
  fx_cny DECIMAL(18, 4) NULL,
  settle_rate DECIMAL(12, 6) NULL,
  settle_cny DECIMAL(18, 4) NULL,
  fx_gain_loss DECIMAL(18, 4) NULL,
  source_hash VARCHAR(64) NULL,
  source_updated_at DATETIME NULL,
  last_synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cashflow_ext (external_source, external_id),
  UNIQUE KEY uk_cashflow_no (source_no),
  KEY idx_cashflow_acct_date (account_name, entry_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
