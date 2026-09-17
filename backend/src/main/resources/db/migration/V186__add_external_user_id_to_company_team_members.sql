-- V186 员工外部身份导入映射支撑
-- 目标：company_team_members 增加 external_user_id 列，存储氚云成员ID（ObjectId），
--      使氚云侧身份从「内部 id 桥接」升级为「真实外部 ID 精确映射」（与钉钉 UserID 对齐）。
-- 幂等设计：列/索引均通过 information_schema 判断后创建，可安全重复执行。
-- 加列统一追加在表末尾（INSTANT 算法），不指定 AFTER 以免触发全表重建。

-- 1. company_team_members 增加 external_user_id（氚云成员ID）
SET @has_col = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='company_team_members' AND COLUMN_NAME='external_user_id');
SET @ddl = IF(@has_col=0,
  'ALTER TABLE company_team_members ADD COLUMN external_user_id VARCHAR(100) NULL COMMENT ''氚云成员ID(ObjectId)''',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. 唯一索引（同一外部ID只能对应一个团队成员）
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='company_team_members' AND INDEX_NAME='uk_ctm_external_user_id');
SET @ddl = IF(@has_idx=0,
  'ALTER TABLE company_team_members ADD UNIQUE INDEX uk_ctm_external_user_id (external_user_id)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. name 索引（按姓名桥接/去重）
SET @has_idx = (SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='product_archive' AND TABLE_NAME='company_team_members' AND INDEX_NAME='idx_ctm_name');
SET @ddl = IF(@has_idx=0,
  'ALTER TABLE company_team_members ADD INDEX idx_ctm_name (name)',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
