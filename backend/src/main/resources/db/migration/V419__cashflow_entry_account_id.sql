-- P2-C account identity foundation. Historical amounts and source keys remain unchanged.
ALTER TABLE h3_cashflow_entries ADD COLUMN account_id BIGINT NULL AFTER account_name;

UPDATE h3_cashflow_entries e
JOIN h3_cash_accounts a ON a.external_source=e.external_source AND a.account_name=e.account_name
SET e.account_id=a.id;

-- Fails safely if any source row cannot be linked; no orphan is silently assigned.
ALTER TABLE h3_cashflow_entries MODIFY COLUMN account_id BIGINT NOT NULL;
ALTER TABLE h3_cashflow_entries
  ADD KEY idx_cashflow_account_id_date (account_id, entry_date),
  ADD CONSTRAINT fk_cashflow_entry_account FOREIGN KEY (account_id) REFERENCES h3_cash_accounts(id);
