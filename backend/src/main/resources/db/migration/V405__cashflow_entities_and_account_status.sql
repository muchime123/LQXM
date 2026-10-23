-- A-1：资金归属主体字典 + 账户主体/状态列（2026-10-23，P2-A）
--
-- 设计：docs/资金日记账P2设计-A账户管理与B年度期初-20261023.md §2
--   · cashflow_entities = 资金归属主体字典（不一定是企业法人：企业/个人/其他，
--     LQ 修订 1）；entity_type 归类标签，程序不做法人推断。
--   · h3_cash_accounts 补 entity_id（可空=待认领）与 status（ACTIVE/INACTIVE）。
--     停用只影响未来操作，绝不改变历史统计（LQ 修订 2）。
--   · D1：不预置任何主体——15 组前缀仅作界面建议（GET /api/cashflow/entities/suggest），
--     正式主体由财务人工创建并认领。
--   · 导入保护：H3FactImportService.upsertCashAccount / ensureCashAccountExists 均为
--     显式列清单 upsert，新增列天然不在 ON DUPLICATE KEY UPDATE 中，导入不会覆盖
--     entity_id/status（无需改导入代码；此处注释即为该结论的落地记录）。
--
-- ⚠️ MySQL DDL 不随事务回滚（LQ 对 D5 的附加条件）：本迁移执行前须备份
--   h3_cash_accounts（35 行）：CREATE TABLE _bak_h3_cash_accounts_v405 AS SELECT * FROM h3_cash_accounts;
-- 回滚（确认无认领数据后）：
--   ALTER TABLE h3_cash_accounts DROP FOREIGN KEY fk_cash_acct_entity, DROP COLUMN entity_id, DROP COLUMN status;
--   DROP TABLE cashflow_entities;
--   DROP TABLE IF EXISTS _bak_h3_cash_accounts_v405;

CREATE TABLE cashflow_entities (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  name        VARCHAR(64) NOT NULL,
  entity_type VARCHAR(16) NOT NULL DEFAULT 'OTHER'
              COMMENT 'COMPANY=企业 / INDIVIDUAL=个人 / OTHER=其他资金归属',
  status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / INACTIVE',
  note        VARCHAR(255) NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_entity_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='资金归属主体字典（企业/个人/其他；财务人工维护）';

ALTER TABLE h3_cash_accounts
  ADD COLUMN entity_id BIGINT NULL COMMENT '所属资金归属主体；NULL=待认领',
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    COMMENT 'ACTIVE / INACTIVE；停用只影响未来操作，不影响历史统计',
  ADD CONSTRAINT fk_cash_acct_entity FOREIGN KEY (entity_id)
    REFERENCES cashflow_entities (id);
