-- V316: 第19类 利强收银通收单流水（校验源）
-- 源：2026年流水日记账.xlsx 「利强收银通」sheet
-- 幂等键 = 交易完成时间#交易金额#付款人识别号（实测 6088 行零重复）

CREATE TABLE IF NOT EXISTS h3_cashier_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  external_source VARCHAR(32) NOT NULL DEFAULT 'H3YUN',
  external_id VARCHAR(128) NOT NULL,
  source_no VARCHAR(128) NOT NULL,
  tx_time DATETIME NULL,
  status VARCHAR(16) NULL,
  amount DECIMAL(18, 4) NULL,
  fee DECIMAL(18, 4) NULL,
  payer_openid VARCHAR(64) NULL,
  summary VARCHAR(255) NULL,
  salesperson VARCHAR(64) NULL,
  source_hash VARCHAR(64) NULL,
  source_updated_at DATETIME NULL,
  last_synced_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_cashier_ext (external_source, external_id),
  UNIQUE KEY uk_cashier_no (source_no),
  KEY idx_cashier_time (tx_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
