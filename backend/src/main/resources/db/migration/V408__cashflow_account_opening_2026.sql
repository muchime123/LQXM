-- P2-B-1: 2026 年度期初数据基础。报表计算仍读取原账户期初列。
CREATE TABLE cashflow_account_opening (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL,
  fiscal_year SMALLINT NOT NULL,
  amount DECIMAL(18,4) NOT NULL,
  source VARCHAR(16) NOT NULL,
  active TINYINT(1) NOT NULL DEFAULT 0,
  finance_confirmed TINYINT(1) NOT NULL DEFAULT 0,
  confirmed_by BIGINT NULL,
  confirmed_at DATETIME NULL,
  basis VARCHAR(255) NULL,
  note VARCHAR(255) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_opening (account_id, fiscal_year),
  CONSTRAINT fk_opening_acct FOREIGN KEY (account_id) REFERENCES h3_cash_accounts(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO cashflow_account_opening (account_id, fiscal_year, amount, source, active, finance_confirmed)
SELECT id, 2026, opening_balance, 'H3_OPENING', 1, 0 FROM h3_cash_accounts;
